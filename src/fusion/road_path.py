"""Map matching a trasa před motorkou (port RoadPath.java).

1. match(): nejbližší úsek silnice, jehož směr odpovídá kurzu z GPS.
2. build(): jde po silnici dopředu (a kousek dozadu); na křižovatce pokračuje silnicí,
   která nejméně zatáčí (bez navigace nevíme, kam jezdec odbočí). Převzorkuje po `step` m.
"""

from __future__ import annotations

import math
from dataclasses import dataclass

import numpy as np

from src.fusion.geo import angle_diff
from src.fusion.osm import RoadNetwork


@dataclass
class Match:
    way: int
    seg: int
    t: float
    forward: bool
    distance: float
    px: float
    py: float


@dataclass
class RoadPath:
    x: np.ndarray
    y: np.ndarray
    s: np.ndarray          # vzdálenost od motorky (záporná = za ní)
    way: np.ndarray
    zero_idx: int


def match(net: RoadNetwork, px: float, py: float, heading: float, max_dist: float = 40) -> Match | None:
    """heading v radiánech (matematický úhel), NaN = neznámý."""
    best, best_cost = None, math.inf
    for w, way in enumerate(net.ways):
        ns = way.nodes
        ax, ay = net.nx[ns[:-1]], net.ny[ns[:-1]]
        bx, by = net.nx[ns[1:]], net.ny[ns[1:]]
        dx, dy = bx - ax, by - ay
        len2 = dx * dx + dy * dy
        with np.errstate(invalid="ignore", divide="ignore"):
            t = np.where(len2 > 0, ((px - ax) * dx + (py - ay) * dy) / len2, 0.0)
        t = np.clip(t, 0, 1)
        qx, qy = ax + t * dx, ay + t * dy
        d = np.hypot(px - qx, py - qy)
        for k in np.nonzero(d <= max_dist)[0]:
            seg_angle = math.atan2(dy[k], dx[k])
            forward, cost = True, float(d[k])
            if not math.isnan(heading):
                diff_f = abs(angle_diff(heading, seg_angle))
                diff_b = abs(angle_diff(heading, seg_angle + math.pi))
                if way.oneway == 1:
                    diff_b = math.inf
                if way.oneway == -1:
                    diff_f = math.inf
                forward = diff_f <= diff_b
                diff = min(diff_f, diff_b)
                if diff > math.radians(100):
                    continue
                cost += 20 * diff
            if cost < best_cost:
                best_cost = cost
                best = Match(w, int(k), float(t[k]), forward, float(d[k]), float(qx[k]), float(qy[k]))
    return best


def walk(net: RoadNetwork, m: Match, forward: bool, distance: float, ignore_oneway: bool) -> list[tuple]:
    """Body (x, y, index cesty) od bodu zápasu daným směrem, celkem `distance` metrů."""
    w, d = m.way, (1 if forward else -1)
    npos = m.seg + 1 if forward else m.seg
    cx, cy = m.px, m.py
    out = [(cx, cy, w)]
    remaining = distance
    for _ in range(20000):
        if remaining <= 0:
            break
        node = int(net.ways[w].nodes[npos])
        tx, ty = net.nx[node], net.ny[node]
        length = math.hypot(tx - cx, ty - cy)
        if length >= remaining:
            f = remaining / length
            out.append((cx + f * (tx - cx), cy + f * (ty - cy), w))
            break
        remaining -= length
        in_angle = math.atan2(ty - cy, tx - cx) if length > 0.01 else math.nan
        cx, cy = tx, ty
        if length > 0.01:
            out.append((cx, cy, w))
        best, best_score = None, math.inf
        for w2, p in net.node_ways[node]:
            cand = net.ways[w2]
            for dd in (-1, 1):
                p2 = p + dd
                if p2 < 0 or p2 >= len(cand.nodes):
                    continue
                if not ignore_oneway and ((cand.oneway == 1 and dd < 0) or (cand.oneway == -1 and dd > 0)):
                    continue
                if w2 == w and p == npos and dd == -d:
                    continue
                nn = int(cand.nodes[p2])
                ox, oy = net.nx[nn] - cx, net.ny[nn] - cy
                if math.hypot(ox, oy) < 0.01:
                    continue
                turn = 0.0 if math.isnan(in_angle) else abs(angle_diff(math.atan2(oy, ox), in_angle))
                if turn > math.radians(150):
                    continue
                score = turn - (0.35 if w2 == w else 0)
                if score < best_score:
                    best_score, best = score, (w2, p2, dd)
        if best is None:
            break
        w, npos, d = best
    return out


def build(net: RoadNetwork, m: Match, ahead: float, behind: float, step: float = 5) -> RoadPath:
    fwd = walk(net, m, m.forward, ahead, False)
    back = walk(net, m, not m.forward, behind, True)
    pts = back[:0:-1] + fwd
    zero = len(back) - 1
    arr = np.array(pts, dtype=float)
    seg = np.hypot(np.diff(arr[:, 0]), np.diff(arr[:, 1]))
    cum = np.concatenate([[0], np.cumsum(seg)])
    offset = cum[zero]
    start = -math.floor(offset / step) * step
    n = max(1, int(math.floor((cum[-1] - offset - start) / step)) + 1)
    s = start + np.arange(n) * step
    x = np.interp(s + offset, cum, arr[:, 0])
    y = np.interp(s + offset, cum, arr[:, 1])
    idx = np.clip(np.searchsorted(cum, s + offset, side="left"), 0, len(pts) - 1)
    ways = arr[idx, 2].astype(int)
    return RoadPath(x, y, s, ways, int(np.argmin(np.abs(s))))
