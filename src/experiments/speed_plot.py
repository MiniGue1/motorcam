"""Experiment 3: rychlost v čase, doporučená rychlost pro zatáčky a okamžiky varování.

Vstupem je *_timeline.csv z dema (src/demo/render.py) nebo CSV z aplikace (řádky „stav“
+ GPS; tam doporučená rychlost není, jen kontrolka).

    python -m src.experiments.speed_plot results/demo/jizda1_demo_timeline.csv
    -> results/figures/rychlost_<název>.png, results/tables/varovani_<název>.csv
"""

from __future__ import annotations

import argparse
from pathlib import Path

import matplotlib.pyplot as plt
import numpy as np
import pandas as pd

from src.utils.config import load_config, repo_path
from src.utils.plotting import SERIES, TEXT_MUTED, apply_style, save

WARN_COLORS = {1: "#f59e0b", 2: "#ef4444"}   # stejné barvy jako kontrolka v aplikaci


def warning_intervals(t: np.ndarray, level: np.ndarray) -> list[tuple[float, float, int]]:
    """Souvislé úseky se stejným stupněm varování > 0: (začátek, konec, stupeň)."""
    out, start, cur = [], None, 0
    for ti, lv in zip(t, level):
        if lv != cur:
            if cur > 0:
                out.append((start, ti, cur))
            start, cur = ti, lv
    if cur > 0:
        out.append((start, t[-1], cur))
    return out


def plot_speed(tl: pd.DataFrame, path: Path, title: str) -> Path:
    apply_style()
    fig, (ax, strip) = plt.subplots(2, 1, figsize=(11, 4.6), sharex=True, gridspec_kw={"height_ratios": [5, 0.6]})
    t = tl.cas_s.to_numpy()
    for a, b, lv in warning_intervals(t, tl.varovani.fillna(0).astype(int).to_numpy()):
        ax.axvspan(a, b, color=WARN_COLORS[lv], alpha=0.18, lw=0)
    ax.plot(t, tl.rychlost_kmh, color=SERIES[0], lw=2, label="rychlost (GPS)")
    ax.plot(t, tl.doporucena_kmh, color=SERIES[1], lw=2, ls="--", label="doporučená rychlost (nejbližší zatáčka)")
    # přímé popisky zatáček v minimu doporučené rychlosti
    rec = tl.doporucena_kmh.to_numpy()
    for a, b, lv in warning_intervals(t, tl.varovani.fillna(0).astype(int).to_numpy()):
        seg = (t >= a) & (t <= b)
        if seg.any() and np.isfinite(rec[seg]).any():
            ax.annotate(f"{np.nanmin(rec[seg]):.0f}", (t[seg][np.nanargmin(rec[seg])], np.nanmin(rec[seg])),
                        textcoords="offset points", xytext=(0, -14), ha="center", fontsize=8, color=TEXT_MUTED)
    ax.set_ylabel("km/h")
    ax.set_ylim(0, max(10, np.nanmax(tl.rychlost_kmh.to_numpy(dtype=float)) * 1.2 if tl.rychlost_kmh.notna().any() else 10))
    handles, labels = ax.get_legend_handles_labels()
    for lv, name in [(1, "varování: zpomal mírně"), (2, "varování: zpomal hodně")]:
        handles.append(plt.Rectangle((0, 0), 1, 1, color=WARN_COLORS[lv], alpha=0.35))
        labels.append(name)
    ax.legend(handles, labels, loc="lower left", bbox_to_anchor=(0, 1.0), ncol=4, fontsize=8)
    ax.set_title(title)
    # pás s barvou kontrolky
    lamp_colors = np.array([[34, 197, 94], [245, 158, 11], [239, 68, 68]]) / 255
    strip.imshow(lamp_colors[tl.kontrolka.fillna(0).astype(int).to_numpy()][None], aspect="auto",
                 extent=(t[0], t[-1], 0, 1))
    strip.set_yticks([0.5], ["kontrolka"])
    strip.grid(False)
    strip.set_xlabel("čas [s]")
    fig.tight_layout()
    return save(fig, path)


def main(argv=None):
    p = argparse.ArgumentParser()
    p.add_argument("timeline")
    p.add_argument("--name", default=None)
    a = p.parse_args(argv)
    tl = pd.read_csv(a.timeline)
    name = a.name or Path(a.timeline).stem.replace("_timeline", "")
    res = repo_path(load_config()["paths"]["results"])
    out = plot_speed(tl, res / "figures" / f"rychlost_{name}", f"Rychlost a varování před zatáčkami – {name}")
    iv = warning_intervals(tl.cas_s.to_numpy(), tl.varovani.fillna(0).astype(int).to_numpy())
    rows = []
    for a0, b0, lv in iv:
        seg = tl[(tl.cas_s >= a0) & (tl.cas_s <= b0)]
        rows.append({"zacatek_s": a0, "konec_s": b0, "stupen": lv, "max_rychlost_kmh": seg.rychlost_kmh.max(),
                     "doporucena_kmh": seg.doporucena_kmh.min(), "polomer_m": seg.polomer_m.min()})
    (res / "tables").mkdir(parents=True, exist_ok=True)
    pd.DataFrame(rows).to_csv(res / "tables" / f"varovani_{name}.csv", index=False, float_format="%.1f")
    print(f"[✓] {out} ({len(rows)} varování)")


if __name__ == "__main__":
    main()
