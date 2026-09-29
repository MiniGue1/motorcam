"""Trénink a vyhodnocení modelu povrchu (fáze 2).

Příklady (Colab, z kořene repozitáře):
    # jen veřejná data (RSCD)
    python -m src.models.train_surface --data data/processed/rscd_cls --name surface_public
    # doladění na vlastních záběrech (fáze 6) – start z modelu na veřejných datech
    python -m src.models.train_surface --data data/processed/custom_cls --init models/surface_public/best.pt \
        --epochs 15 --lr 3e-4 --name surface_finetuned
    # jen vyhodnocení
    python -m src.models.train_surface --eval-only models/surface_public/best.pt --data data/processed/custom_cls

Výstupy:
    models/<name>/best.pt                      – nejlepší váhy (podle F1 na validaci)
    results/tables/<name>_history.csv          – průběh učení
    results/tables/<name>_<split>_metriky.csv  – precision/recall/F1 po třídách
    results/tables/<name>_<split>_predikce.csv – predikce po snímcích (analýza chyb)
    results/figures/<name>_uceni.png, <name>_<split>_confusion.png
"""

from __future__ import annotations

import argparse
import json
import time
from pathlib import Path

import numpy as np
import pandas as pd
import torch
from torch import nn
from torch.utils.data import DataLoader, WeightedRandomSampler

from src.models.evaluation import classification_metrics, plot_confusion, plot_history
from src.models.surface import SurfaceDataset, SurfaceNet, list_images, load_checkpoint, save_checkpoint
from src.utils.config import load_config, repo_path


def worker_init(worker_id: int) -> None:
    """Každý worker DataLoaderu musí mít jiná náhodná čísla (jinak stejné augmentace)."""
    seed = torch.initial_seed() % 2**32
    np.random.seed(seed)
    ds = torch.utils.data.get_worker_info().dataset
    if ds.aug is not None:
        ds.aug.rng = np.random.default_rng(seed)


@torch.no_grad()
def run_eval(model, loader, device, criterion=None):
    model.eval()
    probs, ys, loss_sum = [], [], 0.0
    for x, y in loader:
        x, y = x.to(device), y.to(device)
        out = model(x)
        if criterion is not None:
            loss_sum += criterion(out, y).item() * len(y)
        probs.append(torch.softmax(out, 1).cpu().numpy())
        ys.append(y.cpu().numpy())
    probs = np.concatenate(probs) if probs else np.zeros((0, 1))
    ys = np.concatenate(ys) if ys else np.zeros(0, int)
    return probs, ys, loss_sum / max(1, len(ys))


def evaluate(model, items, classes, name, split, img_size, device, batch=64, workers=2) -> dict:
    """Metriky + tabulky + confusion matrix pro jednu část dat."""
    cfg = load_config()
    res = repo_path(cfg["paths"]["results"])
    ds = SurfaceDataset(items, img_size, train=False)
    probs, ys, _ = run_eval(model, DataLoader(ds, batch, num_workers=workers), device)
    pred = probs.argmax(1)
    labels_cz = [cfg["surface"]["labels_cz"].get(c, c) for c in classes]
    summary, table, cm = classification_metrics(ys, pred, labels_cz)
    (res / "tables").mkdir(parents=True, exist_ok=True)
    table.to_csv(res / "tables" / f"{name}_{split}_metriky.csv", index=False, float_format="%.4f")
    pd.DataFrame({
        "soubor": [str(p) for p, _, _ in items], "skutecnost": [classes[i] for i in ys],
        "predikce": [classes[i] for i in pred], "jistota": probs.max(1),
    }).to_csv(res / "tables" / f"{name}_{split}_predikce.csv", index=False, float_format="%.4f")
    plot_confusion(cm, labels_cz, res / "figures" / f"{name}_{split}_confusion",
                   f"Povrch – {name} ({split}), accuracy {summary['accuracy'] * 100:.1f} %")
    (res / "tables" / f"{name}_{split}_souhrn.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print(f"[{split}] accuracy {summary['accuracy']:.3f}  F1 makro {summary['f1_macro']:.3f}  (n={summary['pocet']})")
    print(table.to_string(index=False, float_format=lambda v: f"{v:.3f}"))
    return summary


def train(args) -> Path:
    cfg = load_config()
    classes = cfg["surface"]["classes"]
    device = "cuda" if torch.cuda.is_available() else "cpu"
    torch.manual_seed(cfg["seed"])
    roots = [Path(d) for d in args.data]
    tr, va = list_images(roots, "train", classes), list_images(roots, "val", classes)
    if not tr or not va:
        raise SystemExit("Chybí trénovací nebo validační data (<data>/train|val/<třída>/).")
    print(f"Trénink: {len(tr)} snímků, validace: {len(va)}, zařízení: {device}")

    if args.init:
        model, _ = load_checkpoint(args.init, device)
        model.train()
    else:
        model = SurfaceNet(args.arch, len(classes), pretrained=not args.no_pretrained).to(device)

    # vyvážení tříd: vzácnější třídy se losují častěji
    counts = np.bincount([c for _, c, _ in tr], minlength=len(classes))
    weights = [1.0 / max(1, counts[c]) for _, c, _ in tr]
    sampler = WeightedRandomSampler(weights, num_samples=len(tr), replacement=True)
    dl_tr = DataLoader(SurfaceDataset(tr, args.img_size, train=True), args.batch, sampler=sampler,
                       num_workers=args.workers, worker_init_fn=worker_init, drop_last=len(tr) > args.batch)
    dl_va = DataLoader(SurfaceDataset(va, args.img_size), args.batch, num_workers=args.workers)

    criterion = nn.CrossEntropyLoss(label_smoothing=0.1)
    opt = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=1e-4)
    sched = torch.optim.lr_scheduler.OneCycleLR(opt, max_lr=args.lr, total_steps=args.epochs * max(1, len(dl_tr)))
    scaler = torch.amp.GradScaler(enabled=device == "cuda")

    out = repo_path(cfg["paths"]["models"]) / args.name
    hist, best_f1, bad = [], -1.0, 0
    for epoch in range(1, args.epochs + 1):
        # zmrazit páteř má smysl jen u předtrénovaných vah (náhodná páteř by se nic nenaučila)
        model.freeze_backbone(epoch <= args.freeze_epochs and not args.init and not args.no_pretrained)
        model.train()
        t0, loss_sum, n = time.time(), 0.0, 0
        for x, y in dl_tr:
            x, y = x.to(device), y.to(device)
            opt.zero_grad(set_to_none=True)
            with torch.autocast(device_type=device, enabled=device == "cuda"):
                loss = criterion(model(x), y)
            scaler.scale(loss).backward()
            scaler.step(opt)
            scaler.update()
            sched.step()
            loss_sum += loss.item() * len(y)
            n += len(y)
        probs, ys, val_loss = run_eval(model, dl_va, device, criterion)
        summary, _, _ = classification_metrics(ys, probs.argmax(1), classes)
        hist.append({"epoch": epoch, "train_loss": loss_sum / max(1, n), "val_loss": val_loss,
                     "val_acc": summary["accuracy"], "val_f1": summary["f1_macro"], "cas_s": time.time() - t0})
        print(f"epocha {epoch:3d}  loss {hist[-1]['train_loss']:.3f}/{val_loss:.3f}  "
              f"acc {summary['accuracy']:.3f}  F1 {summary['f1_macro']:.3f}  ({hist[-1]['cas_s']:.0f} s)")
        if summary["f1_macro"] > best_f1:
            best_f1, bad = summary["f1_macro"], 0
            save_checkpoint(model, out / "best.pt", classes, args.img_size, {"val_f1": best_f1, "epoch": epoch})
        else:
            bad += 1
            if bad >= args.patience:
                print(f"Early stopping – {args.patience} epoch bez zlepšení.")
                break

    res = repo_path(cfg["paths"]["results"])
    h = pd.DataFrame(hist)
    (res / "tables").mkdir(parents=True, exist_ok=True)
    h.to_csv(res / "tables" / f"{args.name}_history.csv", index=False, float_format="%.4f")
    plot_history(h, res / "figures" / f"{args.name}_uceni", f"Učení modelu povrchu ({args.name})")

    model, _ = load_checkpoint(out / "best.pt", device)
    for split in ("val", "test"):
        items = list_images(roots, split, classes)
        if items:
            evaluate(model, items, classes, args.name, split, args.img_size, device, args.batch, args.workers)
    return out / "best.pt"


def main(argv=None):
    p = argparse.ArgumentParser(description="Trénink modelu povrchu")
    p.add_argument("--data", nargs="+", default=["data/processed/rscd_cls"])
    p.add_argument("--arch", default="mobilenet_v3_small", choices=["mobilenet_v3_small", "mobilenet_v3_large",
                                                                   "efficientnet_b0"])
    p.add_argument("--name", default="surface_public")
    p.add_argument("--epochs", type=int, default=25)
    p.add_argument("--freeze-epochs", type=int, default=2, help="prvních N epoch trénovat jen hlavu")
    p.add_argument("--batch", type=int, default=64)
    p.add_argument("--lr", type=float, default=1e-3)
    p.add_argument("--img-size", type=int, default=224)
    p.add_argument("--patience", type=int, default=6)
    p.add_argument("--workers", type=int, default=2)
    p.add_argument("--init", help="checkpoint pro doladění")
    p.add_argument("--no-pretrained", action="store_true", help="bez vah z ImageNetu (jen pro testy)")
    p.add_argument("--eval-only", metavar="CHECKPOINT")
    args = p.parse_args(argv)

    if args.eval_only:
        device = "cuda" if torch.cuda.is_available() else "cpu"
        model, ck = load_checkpoint(args.eval_only, device)
        roots = [Path(d) for d in args.data]
        for split in ("val", "test"):
            items = list_images(roots, split, ck["classes"])
            if items:
                evaluate(model, items, ck["classes"], args.name, split, ck["img_size"], device, args.batch,
                         args.workers)
        return
    train(args)


if __name__ == "__main__":
    main()
