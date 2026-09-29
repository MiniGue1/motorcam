"""Rozhodovací logika kontrolky (port Fusion.java).

Vstupy jednoho kroku: pravděpodobnosti povrchu z kamery, detekce děr, stupeň vibrací,
rychlost z GPS a doporučení pro zatáčku. Výstup: ZELENÁ / ORANŽOVÁ / ČERVENÁ + důvod.

Proti blikání: stupeň se rozsvítí, až je „syrově“ naměřen aspoň ve 30 % vzorků
klouzavého okna, a pak se drží ještě hold_s sekund (rychle nahoru, pomalu dolů).
"""

from __future__ import annotations

import math
from collections import deque
from dataclasses import dataclass, field

import numpy as np

GREEN, ORANGE, RED = 0, 1, 2
LAMP_CZ = ["ZELENÁ", "ORANŽOVÁ", "ČERVENÁ"]
SURFACE_KEYS = ["asfalt", "rozbity_asfalt", "sterk", "hlina_blato", "mokro"]
SURFACE_CZ = ["asfalt", "rozbitý asfalt", "štěrk", "hlína / bláto", "mokro"]
ASFALT, ROZBITY, STERK, HLINA, MOKRO = range(5)


@dataclass
class Det:
    """Detekce; souřadnice 0–1 vůči snímku."""
    x1: float
    y1: float
    x2: float
    y2: float
    cls: int
    conf: float

    def in_corridor(self) -> bool:
        cx = (self.x1 + self.x2) / 2
        return 0.28 < cx < 0.72 and self.y2 > 0.55


@dataclass
class LampParams:
    high_speed_kmh: float = 50
    window_s: float = 1.0
    hold_s: float = 2.0
    surface_conf: float = 0.55


@dataclass
class Inputs:
    surface_probs: np.ndarray | None = None
    vib_level: int = -1
    detections: list[Det] | None = None
    pothole_class: tuple[bool, ...] = (True, False)
    speed_kmh: float = math.nan
    advice: object | None = None      # curves.Advice


@dataclass
class Lamp:
    prm: LampParams = field(default_factory=LampParams)
    level: int = GREEN
    reason: str = "OK"
    surface: int = -1
    surface_prob: float = 0.0
    low_grip: bool = False

    def __post_init__(self):
        self._hist: deque = deque()
        self._confirmed = [0.0, -1e18, -1e18]
        self._reason_of = ["", "", ""]
        self._smooth = None
        self._last_t = None
        self._stable, self._cand, self._cand_since, self._change_t = -1, -1, 0.0, -1e18

    def _update_surface(self, t: float, probs):
        if probs is None:
            self.surface, self._smooth = -1, None
            return
        probs = np.asarray(probs, dtype=float)
        dt = 0.1 if self._last_t is None else min(1.0, t - self._last_t)
        alpha = min(1.0, dt / max(0.05, self.prm.window_s))
        self._smooth = probs.copy() if self._smooth is None else self._smooth + alpha * (probs - self._smooth)
        best = int(np.argmax(self._smooth))
        self.surface_prob = float(self._smooth[best])
        now = best if self.surface_prob >= self.prm.surface_conf else self._stable
        if now != self._cand:
            self._cand, self._cand_since = now, t
        if self._cand != self._stable and t - self._cand_since >= 1.0:
            if self._stable >= 0 and self._cand >= 0:
                self._change_t = t
            self._stable = self._cand
        self.surface = self._stable

    def update(self, t: float, inp: Inputs) -> int:
        """t v sekundách. Vrací vyhlazený stupeň kontrolky."""
        self._update_surface(t, inp.surface_probs)
        speed = 0.0 if math.isnan(inp.speed_kmh) else inp.speed_kmh
        fast = speed > self.prm.high_speed_kmh
        loose = self.surface in (STERK, HLINA)
        self.low_grip = loose or self.surface == MOKRO or inp.vib_level == 2

        hole_ahead = hole_any = crack_ahead = False
        for d in inp.detections or []:
            hole = d.cls < len(inp.pothole_class) and inp.pothole_class[d.cls]
            if hole and d.in_corridor():
                hole_ahead = True
            elif hole:
                hole_any = True
            elif d.in_corridor():
                crack_ahead = True
        adv = inp.advice
        raw, why = GREEN, ""
        if hole_ahead:
            raw, why = RED, "DÍRA PŘED MOTORKOU"
        elif adv is not None and adv.level == 2:
            raw, why = RED, f"ZPOMAL! zatáčka R={adv.curve.min_r:.0f} m → {adv.v_max_kmh:.0f} km/h"
        elif loose and fast:
            raw, why = RED, f"{SURFACE_CZ[self.surface]} v {speed:.0f} km/h"
        elif inp.vib_level == 2 and fast:
            raw, why = RED, f"velmi nerovný povrch v {speed:.0f} km/h"
        elif adv is not None and adv.level == 1:
            raw, why = ORANGE, f"zatáčka R={adv.curve.min_r:.0f} m → {adv.v_max_kmh:.0f} km/h"
        elif t - self._change_t < 3.0:
            raw, why = ORANGE, f"změna povrchu: {SURFACE_CZ[self.surface]}"
        elif self.surface > ASFALT:
            raw, why = ORANGE, SURFACE_CZ[self.surface]
        elif hole_any or crack_ahead:
            raw, why = ORANGE, "díra v záběru" if hole_any else "trhlina před motorkou"
        elif inp.vib_level == 1:
            raw, why = ORANGE, "nerovný povrch"
        self._reason_of[raw] = why

        self._hist.append((t, raw))
        while self._hist and self._hist[0][0] < t - self.prm.window_s:
            self._hist.popleft()
        for lvl in (RED, ORANGE):
            count = sum(1 for _, r in self._hist if r >= lvl)
            if count >= max(1, round(0.3 * len(self._hist))):
                self._confirmed[lvl] = t
        self.level = GREEN
        for lvl in (RED, ORANGE):
            if t - self._confirmed[lvl] <= self.prm.hold_s:
                self.level = lvl
                break
        self.reason = "OK" if self.level == GREEN else self._reason_of[self.level]
        self._last_t = t
        return self.level
