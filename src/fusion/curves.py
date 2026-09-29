"""Zatáčky: poloměr zakřivení, doporučená rychlost a varování (port Curves.java).

Poloměr v bodě i = kružnice přes body (i-k, i, i+k) vyhlazené trasy:
    R = |AB|·|BC|·|CA| / (4·S)
Doporučená rychlost:   v_max = √(g · R · tan θ)
Potřebná vzdálenost:   d = v·t_r + (v² − v_max²) / (2·a)
Varování, když vzdálenost k zatáčce ≤ d + rezerva a v > v_max.
Stupeň 2 (rychlé pípání), když překročení ≥ 15 km/h nebo je zatáčka blíž než reakční dráha.
"""

from __future__ import annotations

import math
from dataclasses import dataclass

import numpy as np

from src.fusion.osm import RoadNetwork
from src.fusion.road_path import RoadPath

G = 9.81


@dataclass
class CurveParams:
    lean_deg: float = 25
    lean_deg_low_grip: float = 15
    reaction_s: float = 1.0
    brake_mps2: float = 4.0
    margin_m: float = 20
    lookahead_m: float = 300
    radius_threshold_m: float = 150

    @classmethod
    def from_config(cls, cfg: dict) -> "CurveParams":
        c = cfg["curves"]
        return cls(c["lean_deg"], c["lean_deg_low_grip"], c["reaction_s"], c["brake_mps2"], c["margin_m"],
                   c["lookahead_m"], c["radius_threshold_m"])


@dataclass
class Curve:
    start_s: float
    end_s: float
    apex_s: float
    min_r: float
    direction: int          # +1 vlevo, -1 vpravo
    unpaved: bool = False


@dataclass
class Advice:
    curve: Curve | None
    distance_m: float = math.nan
    v_max_kmh: float = math.nan
    needed_m: float = math.nan
    level: int = 0


def smooth(v: np.ndarray, half: int) -> np.ndarray:
    """Klouzavý průměr s okrajem zkráceným na dostupné body (shodně s aplikací)."""
    n = len(v)
    c = np.concatenate([[0], np.cumsum(v)])
    i = np.arange(n)
    a, b = np.maximum(0, i - half), np.minimum(n - 1, i + half)
    return (c[b + 1] - c[a]) / (b - a + 1)


def circle_radius(ax, ay, bx, by, cx, cy):
    """Poloměr kružnice přes 3 body se znaménkem (+ vlevo). Funguje i pro pole."""
    ab, bc, ca = np.hypot(bx - ax, by - ay), np.hypot(cx - bx, cy - by), np.hypot(ax - cx, ay - cy)
    cross = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
    with np.errstate(divide="ignore", invalid="ignore"):
        r = ab * bc * ca / (2 * cross)
    return np.where(np.abs(cross) < 1e-9, np.inf, r)


def radii(p: RoadPath, smooth_half: int = 3, k: int = 4) -> np.ndarray:
    xs, ys = smooth(p.x, smooth_half), smooth(p.y, smooth_half)
    r = np.full(len(xs), np.inf)
    if len(xs) > 2 * k:
        r[k:-k] = circle_radius(xs[:-2 * k], ys[:-2 * k], xs[k:-k], ys[k:-k], xs[2 * k:], ys[2 * k:])
    return r


def find(p: RoadPath, r: np.ndarray, threshold_m: float, net: RoadNetwork | None = None) -> list[Curve]:
    out, cur = [], None
    for i in range(len(r)):
        in_curve = abs(r[i]) < threshold_m
        if in_curve and cur is not None and int(np.sign(r[i])) != cur.direction:
            out.append(cur)
            cur = None
        if in_curve:
            if cur is None:
                cur = Curve(p.s[i], p.s[i], p.s[i], math.inf, int(np.sign(r[i])))
            cur.end_s = p.s[i]
            if abs(r[i]) < cur.min_r:
                cur.min_r, cur.apex_s = abs(r[i]), p.s[i]
            if net is not None and net.ways[p.way[i]].unpaved:
                cur.unpaved = True
        elif cur is not None:
            out.append(cur)
            cur = None
    if cur is not None:
        out.append(cur)
    return [c for c in out if c.end_s >= 0]


def v_max(radius_m: float, lean_deg: float) -> float:
    """[m/s]"""
    return math.sqrt(G * radius_m * math.tan(math.radians(lean_deg)))


def needed_distance(v: float, vmax: float, reaction_s: float, brake: float) -> float:
    return v * reaction_s + max(0.0, v * v - vmax * vmax) / (2 * brake)


def advise(curves: list[Curve], speed_mps: float, low_grip: bool, prm: CurveParams) -> Advice:
    best = Advice(None)
    for c in curves:
        lean = prm.lean_deg_low_grip if (low_grip or c.unpaved) else prm.lean_deg
        vm = v_max(c.min_r, lean)
        dist = max(0.0, c.start_s)
        need = needed_distance(speed_mps, vm, prm.reaction_s, prm.brake_mps2)
        level = 0
        if speed_mps > vm and dist <= need + prm.margin_m:
            excess = (speed_mps - vm) * 3.6
            level = 2 if excess >= 15 or dist <= speed_mps * prm.reaction_s else 1
        if best.curve is None or level > best.level:
            best = Advice(c, dist, vm * 3.6, need, level)
    return best
