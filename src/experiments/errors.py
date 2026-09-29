"""Experiment 2: analýza chyb podle podmínek (stín, noc, mokro, rozmazání, …).

Podmínky pochází z anotace v Label Studiu (pole „podminky“ -> data/processed/custom_meta.csv).
„Mokro“ je navíc třída povrchu. Výstupy:

    results/tables/chyby_podminky.csv   – přesnost modelu povrchu po podmínkách
    results/figures/chyby_podminky.png  – totéž jako graf
    results/figures/chyby_ukazky.png    – mřížka chybně klasifikovaných snímků (po podmínkách)
    results/tables/chyby_detektor_podminky.csv – mAP50 detektoru po podmínkách (volitelně)

    python -m src.experiments.errors \
        --pred results/tables/surface_finetuned_test_predikce.csv --meta data/processed/custom_meta.csv \
        [--det models/det_finetuned/weights/best.pt --det-data data/processed/custom_yolo]
"""

from __future__ import annotations

import argparse
from pathlib import Path

import cv2
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd
import yaml

from src.utils.config import load_config, repo_path
from src.utils.plotting import SERIES, apply_style, save

CONDITIONS = {"stin": "stín", "noc": "noc", "rozmazane": "rozmazané", "protisvetlo": "protisvětlo", "dest": "déšť"}


def attach_conditions(pred: pd.DataFrame, meta: pd.DataFrame) -> pd.DataFrame:
    """Ke každé predikci přidá sloupce podmínek (True/False) podle názvu souboru."""
    meta = meta.copy()
    meta["podminky"] = meta["podminky"].fillna("")
    cond = {name: set(meta[meta.podminky.str.contains(k)].soubor) for k, name in CONDITIONS.items()}
    pred = pred.copy()
    pred["jmeno"] = pred.soubor.map(lambda s: Path(s).name)
    for name, files in cond.items():
        pred[name] = pred.jmeno.isin(files)
    pred["mokro (povrch)"] = pred.skutecnost == "mokro"
    pred["bez zvláštních podmínek"] = ~pred[list(CONDITIONS.values())].any(axis=1) & ~pred["mokro (povrch)"]
    pred["spravne"] = pred.skutecnost == pred.predikce
    return pred


def accuracy_by_condition(pred: pd.DataFrame) -> pd.DataFrame:
    groups = ["bez zvláštních podmínek", *CONDITIONS.values(), "mokro (povrch)"]
    rows = [{"podminka": g, "pocet": int(pred[g].sum()),
             "presnost": float(pred[pred[g]].spravne.mean()) if pred[g].any() else np.nan} for g in groups]
    rows.append({"podminka": "vše", "pocet": len(pred), "presnost": float(pred.spravne.mean())})
    return pd.DataFrame(rows)


def plot_accuracy(table: pd.DataFrame, path: Path) -> Path:
    apply_style()
    t = table[table.pocet > 0]
    fig, ax = plt.subplots(figsize=(7, 0.45 * len(t) + 1.3))
    colors = [SERIES[0] if p != "vše" else "#52514e" for p in t.podminka]
    bars = ax.barh(range(len(t)), t.presnost * 100, color=colors, height=0.6, edgecolor="white", linewidth=1.5)
    ax.bar_label(bars, labels=[f"{v * 100:.0f} %  (n={n})" for v, n in zip(t.presnost, t.pocet)], padding=3,
                 fontsize=8, color="#52514e")
    ax.set_yticks(range(len(t)), t.podminka)
    ax.invert_yaxis()
    ax.set_xlim(0, 115)
    ax.grid(axis="y", visible=False)
    ax.set_xlabel("přesnost modelu povrchu [%]")
    ax.set_title("Přesnost podle podmínek (české testovací jízdy)")
    return save(fig, path)


def plot_examples(pred: pd.DataFrame, path: Path, per_row: int = 4) -> Path | None:
    """Mřížka chyb: řádek = podmínka, v každém až per_row chybných snímků (výřez silnice)."""
    from src.models.surface import road_crop

    cz = load_config()["surface"]["labels_cz"]
    rows = []
    for g in ["bez zvláštních podmínek", *CONDITIONS.values(), "mokro (povrch)"]:
        wrong = pred[pred[g] & ~pred.spravne]
        if len(wrong):
            rows.append((g, wrong.head(per_row)))
    if not rows:
        return None
    apply_style()
    fig, axes = plt.subplots(len(rows), per_row, figsize=(3 * per_row, 2.4 * len(rows)), squeeze=False)
    for r, (g, df) in enumerate(rows):
        for c in range(per_row):
            ax = axes[r, c]
            ax.axis("off")
            if c >= len(df):
                continue
            row = df.iloc[c]
            img = cv2.imread(str(row.soubor))
            if img is None:
                continue
            img = cv2.cvtColor(img, cv2.COLOR_BGR2RGB)
            if "custom" in str(row.soubor):
                img = road_crop(img)
            ax.imshow(img)
            ax.set_title(f"{cz.get(row.skutecnost, row.skutecnost)} → {cz.get(row.predikce, row.predikce)}",
                         fontsize=9, loc="center")
        axes[r, 0].text(-0.08, 0.5, g, transform=axes[r, 0].transAxes, rotation=90, va="center", ha="right",
                        fontsize=10, fontweight="bold")
    fig.suptitle("Chybně klasifikované snímky (skutečnost → predikce)", fontweight="bold", x=0.01, ha="left")
    fig.tight_layout()
    return save(fig, path)


def detector_by_condition(weights: str, det_root: Path, meta: pd.DataFrame, split: str = "test") -> pd.DataFrame:
    """mAP50 detektoru zvlášť na snímcích s danou podmínkou (podmnožiny testu)."""
    from src.models.detector import evaluate

    meta = meta.copy()
    meta["podminky"] = meta["podminky"].fillna("")
    meta = meta[meta.cast == split]
    names = yaml.safe_load((det_root / "data.yaml").read_text(encoding="utf-8"))["names"]
    out_dir = repo_path(load_config()["paths"]["models"]) / "val" / "podminky"
    out_dir.mkdir(parents=True, exist_ok=True)
    rows = []
    groups = {"vše": meta, "bez zvláštních podmínek": meta[meta.podminky == ""]}
    groups |= {name: meta[meta.podminky.str.contains(k)] for k, name in CONDITIONS.items()}
    for g, df in groups.items():
        files = [str((det_root / "images" / split / f).resolve()) for f in df.soubor]
        if len(files) < 3:
            continue
        key = "".join(ch for ch in g if ch.isalnum())
        (out_dir / f"{key}.txt").write_text("\n".join(files) + "\n", encoding="utf-8")
        y = out_dir / f"{key}.yaml"
        y.write_text(yaml.safe_dump({"path": str(out_dir), "train": f"{key}.txt", "val": f"{key}.txt",
                                     "names": names}, allow_unicode=True), encoding="utf-8")
        s = evaluate(weights, str(y), "val", f"podminka_{key}", plots=False)
        rows.append({"podminka": g, "snimku": len(files), **s})
    return pd.DataFrame(rows)


def main(argv=None):
    p = argparse.ArgumentParser(description="Analýza chyb podle podmínek")
    p.add_argument("--pred", required=True, help="*_test_predikce.csv z train_surface / compare")
    p.add_argument("--meta", default="data/processed/custom_meta.csv")
    p.add_argument("--det", help="váhy detektoru (volitelné)")
    p.add_argument("--det-data", default="data/processed/custom_yolo")
    a = p.parse_args(argv)
    res = repo_path(load_config()["paths"]["results"])
    meta = pd.read_csv(a.meta)
    pred = attach_conditions(pd.read_csv(a.pred), meta)
    table = accuracy_by_condition(pred)
    (res / "tables").mkdir(parents=True, exist_ok=True)
    table.to_csv(res / "tables" / "chyby_podminky.csv", index=False, float_format="%.4f")
    print(table.to_string(index=False, float_format=lambda v: f"{v:.3f}"))
    plot_accuracy(table, res / "figures" / "chyby_podminky")
    plot_examples(pred, res / "figures" / "chyby_ukazky")
    if a.det:
        d = detector_by_condition(a.det, Path(a.det_data), meta)
        d.to_csv(res / "tables" / "chyby_detektor_podminky.csv", index=False, float_format="%.4f")
        print(d.to_string(index=False, float_format=lambda v: f"{v:.3f}"))


if __name__ == "__main__":
    main()
