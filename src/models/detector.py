"""Detektor děr a trhlin – YOLO (ultralytics, nejmenší varianta „n“) – fáze 3.

Příklady (Colab s GPU):
    # jen veřejná data RDD2022
    python -m src.models.detector train --sources data/processed/rdd2022_yolo --name det_public --epochs 60
    # veřejná + vlastní data (vlastní snímky 3× častěji – je jich málo, ale jsou nejdůležitější)
    python -m src.models.detector train --sources data/processed/rdd2022_yolo data/processed/custom_yolo \
        --custom-repeat 3 --name det_finetuned --init models/det_public/weights/best.pt --epochs 30
    # vyhodnocení (mAP50, mAP50-95, precision, recall) na testu z vlastních jízd
    python -m src.models.detector eval models/det_finetuned/weights/best.pt \
        --data data/processed/custom_yolo/data.yaml --split test --name det_finetuned

Výstupy: models/<name>/weights/best.pt, results/tables/<name>_<split>_metriky.csv,
results/figures/<name>_<split>_PR.png (+ confusion matrix od ultralytics).
"""

from __future__ import annotations

import argparse
import json
import shutil
from pathlib import Path

import pandas as pd
import yaml

from src.utils.config import load_config, repo_path

IMAGE_EXT = {".jpg", ".jpeg", ".png"}


def _images(d: Path) -> list[str]:
    return sorted(str(p.resolve()) for p in d.glob("*") if p.suffix.lower() in IMAGE_EXT) if d.exists() else []


def build_data_yaml(sources: list[Path], out_dir: Path, names: list[str], custom_repeat: int = 1) -> Path:
    """Spojí víc YOLO datasetů do jednoho (seznamy snímků v .txt).
    Datasety s „custom“ v názvu se v tréninku opakují `custom_repeat`×."""
    out_dir.mkdir(parents=True, exist_ok=True)
    lists: dict[str, list[str]] = {"train": [], "val": [], "test": []}
    for src in sources:
        rep = custom_repeat if "custom" in src.name else 1
        lists["train"] += _images(src / "images" / "train") * rep
        lists["val"] += _images(src / "images" / "val")
        lists["test"] += _images(src / "images" / "test")
    if not lists["train"] or not lists["val"]:
        raise SystemExit("Chybí trénovací nebo validační snímky v zadaných datasetech.")
    for split, files in lists.items():
        (out_dir / f"{split}.txt").write_text("\n".join(files) + "\n", encoding="utf-8")
    data = {"path": str(out_dir.resolve()), "train": "train.txt", "val": "val.txt",
            "names": {i: n for i, n in enumerate(names)}}
    if lists["test"]:
        data["test"] = "test.txt"
    y = out_dir / "data.yaml"
    y.write_text(yaml.safe_dump(data, allow_unicode=True, sort_keys=False), encoding="utf-8")
    print(f"Dataset: train {len(lists['train'])}, val {len(lists['val'])}, test {len(lists['test'])} snímků -> {y}")
    return y


def train(args) -> Path:
    from ultralytics import YOLO

    cfg = load_config()
    names = cfg["detector"]["classes"]
    models_dir = repo_path(cfg["paths"]["models"])
    data = build_data_yaml([Path(s) for s in args.sources], models_dir / args.name / "dataset", names,
                           args.custom_repeat)
    model = YOLO(args.init or args.model)
    model.train(
        data=str(data), epochs=args.epochs, imgsz=args.imgsz, batch=args.batch, patience=args.patience,
        project=str(models_dir), name=args.name, exist_ok=True, seed=cfg["seed"], workers=args.workers,
        device=args.device, plots=not args.no_plots,
        # augmentace navíc k výchozím (mozaika, HSV, překlopení): mírné natočení = náklon motorky
        degrees=5.0, translate=0.1, scale=0.5, fliplr=0.5, close_mosaic=10,
    )
    best = models_dir / args.name / "weights" / "best.pt"
    print(f"[✓] Nejlepší váhy: {best}")
    return best


def evaluate(weights: str, data: str, split: str, name: str, imgsz: int = 640, device=None,
             plots: bool = True) -> dict:
    """Vyhodnocení: mAP50, mAP50-95, precision, recall (celkem i po třídách)."""
    from ultralytics import YOLO

    cfg = load_config()
    res = repo_path(cfg["paths"]["results"])
    model = YOLO(weights)
    m = model.val(data=data, split=split, imgsz=imgsz, device=device, plots=plots,
                  project=str(repo_path(cfg["paths"]["models"]) / "val"), name=f"{name}_{split}", exist_ok=True)
    box = m.box
    summary = {"precision": float(box.mp), "recall": float(box.mr), "mAP50": float(box.map50),
               "mAP50-95": float(box.map)}
    labels_cz = cfg["detector"]["labels_cz"]
    rows = []
    for k, ci in enumerate(box.ap_class_index):
        n = model.names[int(ci)]
        p, r, ap50, ap = box.class_result(k)
        rows.append({"trida": labels_cz.get(n, n), "precision": p, "recall": r, "mAP50": ap50, "mAP50-95": ap})
    rows.append({"trida": "celkem", **summary})
    (res / "tables").mkdir(parents=True, exist_ok=True)
    pd.DataFrame(rows).to_csv(res / "tables" / f"{name}_{split}_metriky.csv", index=False, float_format="%.4f")
    (res / "tables" / f"{name}_{split}_souhrn.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    # grafy od ultralytics zkopírujeme do results/figures
    save_dir = Path(m.save_dir)
    for src, dst in [("BoxPR_curve.png", "PR"), ("PR_curve.png", "PR"), ("confusion_matrix_normalized.png", "confusion")]:
        if (save_dir / src).exists():
            (res / "figures").mkdir(parents=True, exist_ok=True)
            shutil.copy(save_dir / src, res / "figures" / f"{name}_{split}_{dst}.png")
    print(pd.DataFrame(rows).to_string(index=False, float_format=lambda v: f"{v:.3f}"))
    return summary


def main(argv=None):
    p = argparse.ArgumentParser(description="YOLO detektor děr (ultralytics)")
    sub = p.add_subparsers(dest="cmd", required=True)
    t = sub.add_parser("train")
    t.add_argument("--sources", nargs="+", default=["data/processed/rdd2022_yolo"])
    t.add_argument("--name", default="det_public")
    t.add_argument("--model", default="yolo11n.pt", help="nejmenší předtrénovaný YOLO (COCO)")
    t.add_argument("--init", help="vlastní váhy pro doladění")
    t.add_argument("--epochs", type=int, default=60)
    t.add_argument("--imgsz", type=int, default=640)
    t.add_argument("--batch", type=int, default=32)
    t.add_argument("--patience", type=int, default=15)
    t.add_argument("--workers", type=int, default=2)
    t.add_argument("--custom-repeat", type=int, default=3)
    t.add_argument("--device", default=None)
    t.add_argument("--no-plots", action="store_true")
    e = sub.add_parser("eval")
    e.add_argument("weights")
    e.add_argument("--data", required=True)
    e.add_argument("--split", default="val", choices=["val", "test"])
    e.add_argument("--name", default="det")
    e.add_argument("--imgsz", type=int, default=640)
    e.add_argument("--device", default=None)
    args = p.parse_args(argv)
    if args.cmd == "train":
        best = train(args)
        cfg = load_config()
        data = repo_path(cfg["paths"]["models"]) / args.name / "dataset" / "data.yaml"
        for split in ("val", "test"):
            if split == "val" or "test" in yaml.safe_load(data.read_text(encoding="utf-8")):
                evaluate(str(best), str(data), split, args.name, args.imgsz, args.device, not args.no_plots)
    else:
        evaluate(args.weights, args.data, args.split, args.name, args.imgsz, args.device)


if __name__ == "__main__":
    main()
