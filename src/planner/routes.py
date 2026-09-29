"""Analýza motorkářských tras (stejný výpočet zatáčkovitosti jako aplikace, Curviness.java).

    # přehled předpřipravených tras (body, délka vzdušnou čarou)
    python -m src.planner.routes list
    # rozbor tras exportovaných z aplikace (tlačítko GPX) -> tabulka + grafy do práce
    python -m src.planner.routes analyze Stažené/MotorCam/*.gpx

Výstupy: results/tables/trasy_zatacky.csv, results/figures/trasy_zatackovitost.png,
results/figures/trasa_<název>_zatacky.png (poloměr zatáček podél trasy).
"""

from __future__ import annotations

import argparse
import json
import math
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path

import matplotlib.pyplot as plt
import numpy as np
import pandas as pd

from src.fusion.curves import circle_radius, smooth
from src.fusion.geo import LocalProjection, haversine
from src.utils.config import REPO_ROOT, load_config, repo_path
from src.utils.plotting import SERIES, apply_style, grouped_barh, save

LIBRARY = REPO_ROOT / "android" / "assets" / "trasy.json"
CURVE_R = 300.0
CLASSES = [("vracečky", 25), ("ostré", 60), ("střední", 150), ("plynulé", 300)]
WEIGHTS = [4, 3, 2, 1]


@dataclass
class CurvinessStats:
    length_m: float
    counts: list[int]                 # vracečky, ostré, střední, plynulé
    curvy_m: float
    score: float


def resample(lat: np.ndarray, lon: np.ndarray, step: float = 5.0) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    proj = LocalProjection(float(lat[0]), float(lon[0]))
    x, y = proj.xy(np.asarray(lat), np.asarray(lon))
    cum = np.concatenate([[0], np.cumsum(np.hypot(np.diff(x), np.diff(y)))])
    s = np.arange(0, cum[-1] + 1e-9, step)
    return s, np.interp(s, cum, x), np.interp(s, cum, y)


def radii_along(lat, lon, step: float = 5.0) -> tuple[np.ndarray, np.ndarray]:
    """(vzdálenost podél trasy, poloměr se znaménkem) – vyhlazení ±15 m, body ±20 m."""
    s, x, y = resample(lat, lon, step)
    xs, ys = smooth(x, 3), smooth(y, 3)
    k = 4
    r = np.full(len(s), np.inf)
    if len(s) > 2 * k:
        r[k:-k] = circle_radius(xs[:-2 * k], ys[:-2 * k], xs[k:-k], ys[k:-k], xs[2 * k:], ys[2 * k:])
    return s, r


def analyze(lat, lon, step: float = 5.0) -> CurvinessStats:
    s, r = radii_along(lat, lon, step)
    counts = [0, 0, 0, 0]
    curvy = 0.0
    run, min_r, direction = 0, math.inf, 0
    for ri in list(r) + [np.inf]:
        inside = abs(ri) < CURVE_R
        d = int(np.sign(ri)) if np.isfinite(ri) else 0
        if run and (not inside or d != direction):
            if run * step >= 10:
                curvy += run * step
                counts[next(i for i, (_, lim) in enumerate(CLASSES) if min_r < lim)] += 1
            run, min_r = 0, math.inf
        if inside:
            if run == 0:
                direction = d
            run += 1
            min_r = min(min_r, abs(ri))
    length = float(s[-1]) if len(s) else 0.0
    km = max(0.1, length / 1000)
    weighted = sum(w * c for w, c in zip(WEIGHTS, counts)) / km
    return CurvinessStats(length, counts, curvy, 100 * (1 - math.exp(-weighted / 3)))


def read_gpx(path: str | Path) -> tuple[str, np.ndarray, np.ndarray]:
    """Název a body stopy (trkpt, případně rtept) z GPX."""
    root = ET.parse(path).getroot()
    ns = {"g": root.tag.split("}")[0].strip("{")} if root.tag.startswith("{") else {}
    q = (lambda t: f"g:{t}") if ns else (lambda t: t)
    name_el = root.find(f".//{q('trk')}/{q('name')}", ns) or root.find(f".//{q('name')}", ns)
    pts = root.findall(f".//{q('trkpt')}", ns) or root.findall(f".//{q('rtept')}", ns)
    lat = np.array([float(p.get("lat")) for p in pts])
    lon = np.array([float(p.get("lon")) for p in pts])
    return (name_el.text if name_el is not None else Path(path).stem), lat, lon


def load_library(path: Path = LIBRARY) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def cmd_list() -> None:
    lib = load_library()
    for r in lib["trasy"]:
        pts = [lib["mista"][b] for b in r["body"]]
        straight = sum(haversine(a["lat"], a["lon"], b["lat"], b["lon"]) for a, b in zip(pts, pts[1:]))
        print(f"{r['id']:16s} {r['typ']:6s} {len(pts):2d} bodů  ~{straight / 1000:5.0f} km vzdušnou čarou  {r['nazev']}")


def plot_profile(name: str, lat, lon, path: Path) -> Path:
    """Poloměr zatáček podél trasy (logaritmicky) – kde jsou ostré úseky."""
    apply_style()
    s, r = radii_along(lat, lon)
    ar = np.clip(np.abs(r), 5, 1000)
    fig, ax = plt.subplots(figsize=(11, 3.2))
    ax.fill_between(s / 1000, 1000, ar, color=SERIES[0], alpha=0.25, lw=0)
    ax.plot(s / 1000, ar, color=SERIES[0], lw=1)
    ax.set_yscale("log")
    ax.set_ylim(1000, 5)                    # ostré zatáčky (malý poloměr) nahoře
    ticks = [10, 25, 60, 150, 300, 1000]
    ax.set_yticks(ticks, [str(t) for t in ticks])
    ax.minorticks_off()
    for (label, lim), color in zip(CLASSES[:3], ["#e34948", "#eb6834", "#eda100"]):
        ax.axhline(lim, color=color, lw=1, ls="--")
        # popisek vpravo mimo graf, ať nepřekrývá data ani čáru
        ax.text(1.01, lim, f"{label} < {lim} m", transform=ax.get_yaxis_transform(), va="center", fontsize=8,
                color=color, clip_on=False)
    ax.set_xlim(0, s[-1] / 1000)
    ax.set_xlabel("vzdálenost [km]")
    ax.set_ylabel("poloměr zatáčky [m]")
    ax.set_title(f"Zatáčky podél trasy – {name}")
    return save(fig, path)


def cmd_analyze(files: list[str]) -> pd.DataFrame:
    res = repo_path(load_config()["paths"]["results"])
    rows = []
    for f in files:
        name, lat, lon = read_gpx(f)
        st = analyze(lat, lon)
        rows.append({"trasa": name, "delka_km": st.length_m / 1000, **{c: n for (c, _), n in zip(CLASSES, st.counts)},
                     "v_zatackach_pct": 100 * st.curvy_m / max(1, st.length_m), "skore": st.score})
        safe = "".join(ch if ch.isalnum() else "_" for ch in name)[:40]
        plot_profile(name, lat, lon, res / "figures" / f"trasa_{safe}_zatacky")
    df = pd.DataFrame(rows).sort_values("skore", ascending=False)
    (res / "tables").mkdir(parents=True, exist_ok=True)
    df.to_csv(res / "tables" / "trasy_zatacky.csv", index=False, float_format="%.1f")
    apply_style()
    fig, ax = plt.subplots(figsize=(8, 0.5 * len(df) + 1.4))
    grouped_barh(ax, list(df.trasa), {"skóre zatáčkovitosti": list(df.skore)})
    ax.set_xlim(0, 110)
    ax.set_title("Zatáčkovitost tras (0–100)")
    ax.get_legend().remove()
    save(fig, res / "figures" / "trasy_zatackovitost")
    print(df.to_string(index=False, float_format=lambda v: f"{v:.1f}"))
    return df


def main(argv=None):
    p = argparse.ArgumentParser(description="Analýza motorkářských tras")
    sub = p.add_subparsers(dest="cmd", required=True)
    sub.add_parser("list")
    a = sub.add_parser("analyze")
    a.add_argument("gpx", nargs="+")
    args = p.parse_args(argv)
    cmd_list() if args.cmd == "list" else cmd_analyze(args.gpx)


if __name__ == "__main__":
    main()
