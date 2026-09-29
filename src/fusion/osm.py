"""Silniční síť z OpenStreetMap přes Overpass API (zdarma, bez klíče).

Stejný dotaz i zpracování jako v aplikaci (MapService.java, RoadNetwork.java), aby
vyhodnocení na počítači odpovídalo tomu, co dělá telefon. Odpovědi se ukládají do
data/raw/osm/, takže se každá oblast stahuje jen jednou.

Pozn.: pro ruční průzkum mapy je pohodlné i `osmnx` (osmnx.graph_from_point), ale tady
používáme přímo Overpass kvůli shodě s aplikací.
"""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np
import requests

from src.fusion.geo import LocalProjection

ENDPOINTS = [
    "https://overpass-api.de/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
]
HIGHWAYS = ("motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street|service|track|"
            "motorway_link|trunk_link|primary_link|secondary_link|tertiary_link")
UNPAVED = {"unpaved", "gravel", "fine_gravel", "compacted", "dirt", "earth", "ground", "mud", "sand", "grass",
           "pebblestone"}


@dataclass
class Way:
    nodes: np.ndarray          # indexy uzlů
    highway: str
    name: str
    surface: str | None
    oneway: int                # 0 obousměrná, 1 ve směru uzlů, -1 proti

    @property
    def unpaved(self) -> bool:
        return self.surface in UNPAVED if self.surface else self.highway == "track"


@dataclass
class RoadNetwork:
    proj: LocalProjection
    nx: np.ndarray
    ny: np.ndarray
    ways: list[Way]
    node_ways: list[list[tuple[int, int]]] = field(default_factory=list)

    def __post_init__(self):
        self.node_ways = [[] for _ in range(len(self.nx))]
        for w, way in enumerate(self.ways):
            for k, n in enumerate(way.nodes):
                self.node_ways[n].append((w, k))


def overpass_query(lat: float, lon: float, radius_m: int) -> str:
    return (f'[out:json][timeout:25];way(around:{radius_m},{lat:.6f},{lon:.6f})[highway~"^({HIGHWAYS})$"];'
            "(._;>;);out body;")


def overpass_bbox_query(south: float, west: float, north: float, east: float) -> str:
    return (f'[out:json][timeout:60];way({south:.6f},{west:.6f},{north:.6f},{east:.6f})[highway~"^({HIGHWAYS})$"];'
            "(._;>;);out body;")


def fetch(query: str, cache_dir: str | Path = "data/raw/osm") -> dict:
    """Stáhne odpověď Overpass (s mezipamětí na disku)."""
    cache_dir = Path(cache_dir)
    cache_dir.mkdir(parents=True, exist_ok=True)
    f = cache_dir / (hashlib.sha1(query.encode()).hexdigest()[:16] + ".json")
    if f.exists():
        return json.loads(f.read_text(encoding="utf-8"))
    last = None
    for url in ENDPOINTS:
        try:
            r = requests.post(url, data={"data": query}, timeout=90,
                              headers={"User-Agent": "MotorCam/0.1 (studentsky projekt)"})
            r.raise_for_status()
            f.write_text(r.text, encoding="utf-8")
            return r.json()
        except requests.RequestException as e:
            last = e
    raise RuntimeError(f"Overpass API nedostupné: {last}")


def parse(data: dict, lat0: float, lon0: float) -> RoadNetwork:
    """Převod JSON odpovědi na síť v lokálních souřadnicích."""
    proj = LocalProjection(lat0, lon0)
    index, xs, ys = {}, [], []
    for el in data["elements"]:
        if el.get("type") == "node":
            index[el["id"]] = len(xs)
            x, y = proj.xy(el["lat"], el["lon"])
            xs.append(x)
            ys.append(y)
    ways = []
    for el in data["elements"]:
        if el.get("type") != "way":
            continue
        nodes = [index[n] for n in el["nodes"] if n in index]
        if len(nodes) < 2:
            continue
        tags = el.get("tags", {})
        hw = tags.get("highway", "")
        ow = tags.get("oneway", "")
        implied = hw == "motorway" or tags.get("junction") == "roundabout"
        oneway = 1 if ow in ("yes", "1", "true") or (implied and ow != "no") else -1 if ow == "-1" else 0
        ways.append(Way(np.array(nodes), hw, tags.get("name", tags.get("ref", "")), tags.get("surface"), oneway))
    return RoadNetwork(proj, np.array(xs), np.array(ys), ways)


def network_for_track(lats: np.ndarray, lons: np.ndarray, margin_deg: float = 0.01,
                      cache_dir: str | Path = "data/raw/osm") -> RoadNetwork:
    """Síť silnic pro celou jízdu (obdélník kolem GPS stopy + okraj ~1 km)."""
    s, n = float(np.nanmin(lats)) - margin_deg, float(np.nanmax(lats)) + margin_deg
    w, e = float(np.nanmin(lons)) - margin_deg, float(np.nanmax(lons)) + margin_deg
    data = fetch(overpass_bbox_query(s, w, n, e), cache_dir)
    return parse(data, (s + n) / 2, (w + e) / 2)
