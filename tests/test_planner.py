"""Testy analýzy tras (Python = Curviness.java v aplikaci)."""

import math

import numpy as np

from src.fusion.geo import LocalProjection
from src.planner.routes import analyze, load_library, read_gpx


def _latlon(xy):
    proj = LocalProjection(50, 17)
    xy = np.asarray(xy, float)
    return proj.latlon(xy[:, 0], xy[:, 1])


def test_library_valid():
    lib = load_library()
    assert len(lib["trasy"]) >= 6
    for r in lib["trasy"]:
        assert all(b in lib["mista"] for b in r["body"]), r["id"]
    for p in lib["mista"].values():
        assert 49.5 < p["lat"] < 50.5 and 16.6 < p["lon"] < 17.7, p["nazev"]


def test_curviness_matches_app_scenarios():
    lat, lon = _latlon([(i * 25, 0) for i in range(201)])
    st = analyze(lat, lon)
    assert sum(st.counts) == 0 and st.score < 1
    pts, x, y, d = [], 0.0, 0.0, 1
    for _ in range(6):                               # 6 vraceček R=18 m (stejně jako RouteTest.java)
        pts += [(x + d * i * 5, y) for i in range(30)]
        x += d * 150
        pts += [(x + d * 18 * math.sin(math.pi * a / 18), y + 18 - 18 * math.cos(math.pi * a / 18)) for a in range(19)]
        y += 36
        d = -d
    lat, lon = _latlon(pts)
    st = analyze(lat, lon)
    assert st.counts[0] + st.counts[1] >= 5 and st.score > 80


def test_read_gpx(tmp_path):
    f = tmp_path / "t.gpx"
    f.write_text('<?xml version="1.0"?><gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">'
                 '<trk><name>Test</name><trkseg><trkpt lat="50.0" lon="17.0"/><trkpt lat="50.001" lon="17.0"/>'
                 '</trkseg></trk></gpx>', encoding="utf-8")
    name, lat, lon = read_gpx(f)
    assert name == "Test" and len(lat) == 2 and lon[1] == 17.0
