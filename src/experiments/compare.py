"""Experiment 1: model jen na veřejných datech vs. model doladěný na vlastních (českých) záběrech.

Povrch:
    python -m src.experiments.compare surface \
        --model verejna=models/surface_public/best.pt --model doladeny=models/surface_finetuned/best.pt \
        --test RSCD=data/processed/rscd_cls --test ceske=data/processed/custom_cls
Detektor:
    python -m src.experiments.compare detector \
        --model verejna=models/det_public/weights/best.pt --model doladeny=models/det_finetuned/weights/best.pt \
        --test RDD2022=models/det_public/dataset/data.yaml:val --test ceske=data/processed/custom_yolo/data.yaml:test

Výstupy: results/tables/porovnani_<úloha>.csv, results/figures/porovnani_<úloha>.png
(+ F1 po třídách pro povrch: porovnani_povrch_tridy.png)
"""

from __future__ import annotations

import argparse
from pathlib import Path

import matplotlib.pyplot as plt
import pandas as pd

from src.utils.config import load_config, repo_path
from src.utils.plotting import apply_style, grouped_barh, save


def _pairs(values: list[str]) -> list[tuple[str, str]]:
    out = []
    for v in values:
        if "=" not in v:
            raise SystemExit(f"Očekávám název=cesta, dostal jsem: {v}")
        out.append(tuple(v.split("=", 1)))
    return out


def compare_surface(models: list[tuple[str, str]], tests: list[tuple[str, str]], device: str = "cpu") -> pd.DataFrame:
    import torch

    from src.models.evaluation import plot_per_class_f1
    from src.models.surface import list_images, load_checkpoint
    from src.models.train_surface import evaluate

    cfg = load_config()
    res = repo_path(cfg["paths"]["results"])
    rows, per_class = [], {}
    device = device if torch.cuda.is_available() or device == "cpu" else "cpu"
    for mname, ckpt in models:
        model, ck = load_checkpoint(ckpt, device)
        for tname, root in tests:
            items = list_images([Path(root)], "test", ck["classes"])
            if not items:
                print(f"[!] {root} nemá test/ – přeskočeno")
                continue
            s = evaluate(model, items, ck["classes"], f"povrch_{mname}_{tname}", "test", ck["img_size"], device)
            rows.append({"model": mname, "test": tname, "accuracy": s["accuracy"], "f1_makro": s["f1_macro"],
                         "pocet": s["pocet"]})
            per_class[(mname, tname)] = pd.read_csv(res / "tables" / f"povrch_{mname}_{tname}_test_metriky.csv")
    df = pd.DataFrame(rows)
    df.to_csv(res / "tables" / "porovnani_povrch.csv", index=False, float_format="%.4f")
    _plot(df, "f1_makro", "Povrch – F1 (makro) na testovacích datech", res / "figures" / "porovnani_povrch", "F1 [%]")
    last_test = tests[-1][0]    # typicky české záběry
    tabs = {m: per_class[(m, last_test)] for m, _ in models if (m, last_test) in per_class}
    if tabs:
        plot_per_class_f1(tabs, res / "figures" / "porovnani_povrch_tridy", f"Povrch – F1 po třídách ({last_test})")
    print(df.to_string(index=False, float_format=lambda v: f"{v:.3f}"))
    return df


def compare_detector(models: list[tuple[str, str]], tests: list[tuple[str, str]], device=None) -> pd.DataFrame:
    from src.models.detector import evaluate

    res = repo_path(load_config()["paths"]["results"])
    rows = []
    for mname, weights in models:
        for tname, spec in tests:
            data, _, split = spec.partition(":")
            s = evaluate(weights, data, split or "val", f"detektor_{mname}_{tname}", device=device)
            rows.append({"model": mname, "test": tname, **s})
    df = pd.DataFrame(rows)
    df.to_csv(res / "tables" / "porovnani_detektor.csv", index=False, float_format="%.4f")
    _plot(df, "mAP50", "Detektor – mAP50 na testovacích datech", res / "figures" / "porovnani_detektor", "mAP50 [%]")
    print(df.to_string(index=False, float_format=lambda v: f"{v:.3f}"))
    return df


def _plot(df: pd.DataFrame, metric: str, title: str, path: Path, xlabel: str) -> Path:
    apply_style()
    tests = list(dict.fromkeys(df.test))
    models = list(dict.fromkeys(df.model))
    series = {m: [float(df[(df.model == m) & (df.test == t)][metric].iloc[0] * 100) if len(
        df[(df.model == m) & (df.test == t)]) else 0 for t in tests] for m in models}
    fig, ax = plt.subplots(figsize=(7, 0.7 * len(tests) * len(models) + 1.3))
    grouped_barh(ax, tests, series)
    ax.set_xlim(0, 110)
    ax.set_xlabel(xlabel)
    ax.set_title(title)
    return save(fig, path)


def main(argv=None):
    p = argparse.ArgumentParser(description="Porovnání modelů (fáze 6)")
    p.add_argument("task", choices=["surface", "detector"])
    p.add_argument("--model", action="append", required=True, help="název=cesta k vahám")
    p.add_argument("--test", action="append", required=True,
                   help="povrch: název=kořen datasetu; detektor: název=data.yaml[:split]")
    p.add_argument("--device", default="cpu")
    a = p.parse_args(argv)
    if a.task == "surface":
        compare_surface(_pairs(a.model), _pairs(a.test), a.device)
    else:
        compare_detector(_pairs(a.model), _pairs(a.test), a.device)


if __name__ == "__main__":
    main()
