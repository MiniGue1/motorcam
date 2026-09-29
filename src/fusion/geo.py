"""Zeměpisné výpočty: lokální rovinné souřadnice v metrech a vzdálenosti.

Stejná logika jako v aplikaci (android/src/cz/motorcam/app/Geo.java).
"""

from __future__ import annotations

import math

EARTH_R = 6_371_000.0


class LocalProjection:
    """Ekvidistantní válcová projekce kolem bodu (lat0, lon0). x = východ, y = sever [m]."""

    def __init__(self, lat0: float, lon0: float):
        self.lat0, self.lon0 = lat0, lon0
        self.ky = math.radians(1) * EARTH_R
        self.kx = self.ky * math.cos(math.radians(lat0))

    def xy(self, lat, lon):
        """Funguje pro čísla i numpy pole."""
        return (lon - self.lon0) * self.kx, (lat - self.lat0) * self.ky

    def latlon(self, x, y):
        return self.lat0 + y / self.ky, self.lon0 + x / self.kx


def haversine(lat1, lon1, lat2, lon2) -> float:
    """Vzdálenost dvou bodů na Zemi v metrech."""
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * EARTH_R * math.asin(min(1.0, math.sqrt(a)))


def angle_diff(a: float, b: float) -> float:
    """Rozdíl úhlů v radiánech v intervalu (-π, π]."""
    d = (a - b + math.pi) % (2 * math.pi) - math.pi
    return math.pi if d == -math.pi else d


def bearing_to_angle(bearing_deg: float) -> float:
    """Kurz z GPS (° od severu po směru hodin) -> matematický úhel (rad od osy x)."""
    return math.radians(90 - bearing_deg)
