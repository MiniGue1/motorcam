"""Grafy a tabulky se složením datasetů (do kapitoly „Data“ v práci).

Čte přímo převedené datasety v data/processed a ukládá:
    results/figures/data_rdd2022.png      – počet objektů podle země a třídy
    results/figures/data_<cls>.png        – počet snímků podle třídy povrchu a části
    results/tables/data_<název>.csv       – stejná čísla jako tabulka

Použití:
    python -m src.dataset.dataset_stats
"""

from __future__ import annotations

import csv
from collections import Counter
from pathlib import Path

import matplotlib.pyplot as plt

from src.dataset.rdd_to_yolo import country_of
from src.utils.config import load_config, repo_path
from src.utils.plotting import apply_style, grouped_barh, save

SPLITS = ["train", "val", "test"]


def yolo_counts(root: Path, class_names: list[str]) -> dict[str, Counter]:
    """Počty objektů {země: Counter(třída)} z YOLO labelů (země podle názvu souboru)."""
    counts: dict[str, Counter] = {}
    for txt in root.glob("labels/*/*.txt"):
        c = counts.setdefault(country_of(txt.name), Counter())
        for ln in txt.read_text().splitlines():
            if ln.strip():
                c[class_names[int(ln.split()[0])]] += 1
    return counts


def folder_counts(root: Path, classes: list[str]) -> dict[str, list[int]]:
    """Počty snímků {část: [počet pro každou třídu]} z datasetu ve formátu složka = třída."""
    return {
        s: [sum(1 for _ in (root / s / c).glob("*")) if (root / s / c).exists() else 0 for c in classes]
        for s in SPLITS if (root / s).exists()
    }


def write_table(path: Path, header: list[str], rows: list[list]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(header)
        w.writerows(rows)


def main() -> None:
    cfg = load_config()
    apply_style()
    processed = repo_path(cfg["paths"]["processed"])
    figs = repo_path(cfg["paths"]["results"]) / "figures"
    tables = repo_path(cfg["paths"]["results"]) / "tables"
    det_classes = cfg["detector"]["classes"]
    det_cz = [cfg["detector"]["labels_cz"][c] for c in det_classes]
    surf_classes = cfg["surface"]["classes"]
    surf_cz = [cfg["surface"]["labels_cz"][c] for c in surf_classes]

    rdd = processed / "rdd2022_yolo"
    if rdd.exists():
        counts = yolo_counts(rdd, det_classes)
        countries = sorted(counts, key=lambda k: -sum(counts[k].values()))
        fig, ax = plt.subplots(figsize=(7, 0.6 * len(countries) + 1.2))
        grouped_barh(ax, countries, {cz: [counts[k][c] for k in countries] for c, cz in zip(det_classes, det_cz)})
        ax.set_title("RDD2022 – počet anotovaných objektů podle země")
        ax.set_xlabel("počet objektů")
        print("[✓]", save(fig, figs / "data_rdd2022"))
        write_table(tables / "data_rdd2022.csv", ["zeme", *det_classes],
                    [[k, *[counts[k][c] for c in det_classes]] for k in countries])

    for name, title in [("rscd_cls", "RSCD (převedeno na 5 tříd)"), ("custom_cls", "Vlastní záběry")]:
        root = processed / name
        if not root.exists():
            continue
        counts = folder_counts(root, surf_classes)
        fig, ax = plt.subplots(figsize=(7, 3.4))
        grouped_barh(ax, surf_cz, counts)
        ax.set_title(f"{title} – počet snímků podle povrchu")
        ax.set_xlabel("počet snímků")
        print("[✓]", save(fig, figs / f"data_{name}"))
        write_table(tables / f"data_{name}.csv", ["trida", *counts],
                    [[c, *[counts[s][i] for s in counts]] for i, c in enumerate(surf_classes)])

    if not any((processed / n).exists() for n in ("rdd2022_yolo", "rscd_cls", "custom_cls")):
        print("V data/processed zatím nic není – nejdřív spusť převodní skripty.")


if __name__ == "__main__":
    main()
