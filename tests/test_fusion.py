"""Testy fáze 4 – stejné scénáře jako android/test/.../LogicTest.java (Python = aplikace)."""

from __future__ import annotations

import math

import numpy as np
import pandas as pd
import pytest

from src.fusion import curves, lamp, osm, road_path
from src.fusion.geo import LocalProjection, bearing_to_angle, haversine
from src.fusion.sensors import load_motorcam_csv
from src.fusion.vibration import VibrationMeter, window_features

EAST = bearing_to_angle(90)


def synthetic_overpass(proj: LocalProjection) -> dict:
    """Rovně na východ 500 m, zatáčka doleva R=50 m, rovně na sever; v x=200 odbočka na jih."""
    pts = [(x, 0.0) for x in range(0, 501, 50)]
    pts += [(500 + 50 * math.sin(math.radians(d)), 50 - 50 * math.cos(math.radians(d))) for d in range(5, 91, 5)]
    pts += [(550.0, y) for y in range(100, 401, 50)]
    els = []
    for i, (x, y) in enumerate(pts, start=1):
        lat, lon = proj.latlon(x, y)
        els.append({"type": "node", "id": i, "lat": lat, "lon": lon})
    n = len(pts)
    for i, (x, y) in enumerate([(200, -100), (210, -300)], start=n + 1):
        lat, lon = proj.latlon(x, y)
        els.append({"type": "node", "id": i, "lat": lat, "lon": lon})
    els.append({"type": "way", "id": 100, "nodes": list(range(1, n + 1)), "tags": {"highway": "secondary"}})
    els.append({"type": "way", "id": 101, "nodes": [5, n + 1, n + 2], "tags": {"highway": "track"}})
    return {"elements": els}


@pytest.fixture(scope="module")
def net():
    return osm.parse(synthetic_overpass(LocalProjection(50, 15)), 50, 15)


def path_at(net, x, heading=EAST, ahead=340):
    m = road_path.match(net, x, 0, heading, 40)
    p = road_path.build(net, m, ahead, 40, 5)
    r = curves.radii(p, 3, 4)
    return m, p, r, curves.find(p, r, 150, net)


def test_network_and_matching(net):
    assert len(net.ways) == 2 and net.ways[1].unpaved
    m = road_path.match(net, 300, 3, EAST, 40)
    assert m.way == 0 and m.forward and abs(m.distance - 3) < 0.5
    assert road_path.match(net, 300, 100, EAST, 40) is None


def test_path_follows_main_road(net):
    _, p, _, _ = path_at(net, 300, ahead=300)
    assert abs(p.s[-1] - 300) <= 5 and abs(p.s[0] + 40) <= 5
    assert abs(p.x[-1] - 550) < 2 and p.y[-1] > 30          # na křižovatce rovně po hlavní


def test_curve_detection(net):
    _, _, _, cs = path_at(net, 300, ahead=300)
    assert len(cs) == 1
    c = cs[0]
    assert abs(c.min_r - 50) < 12 and c.direction == 1 and abs(c.apex_s - 239) < 30


def test_speed_formulas():
    assert curves.v_max(50, 25) * 3.6 == pytest.approx(54.4, abs=0.5)
    # 90 km/h -> 54,4 km/h: d = 25·1 + (25² − 15,1²)/8
    assert curves.needed_distance(25, curves.v_max(50, 25), 1, 4) == pytest.approx(74.5, abs=0.5)


def test_advice_levels(net):
    prm = curves.CurveParams()
    _, _, _, far = path_at(net, 300)
    assert curves.advise(far, 25, False, prm).level == 0
    _, _, _, near = path_at(net, 440)
    assert curves.advise(near, 25, False, prm).level == 2
    assert curves.advise(near, 60 / 3.6, False, prm).level == 0      # ještě dost místa
    _, _, _, close = path_at(net, 470)
    assert curves.advise(close, 60 / 3.6, False, prm).level == 1
    wet = curves.advise(near, 60 / 3.6, True, prm)
    assert wet.v_max_kmh < 45 and wet.level == 2                     # štěrk -> menší náklon


def test_westbound_no_curve(net):
    m = road_path.match(net, 300, 3, bearing_to_angle(270), 40)
    p = road_path.build(net, m, 300, 40, 5)
    assert not m.forward and curves.find(p, curves.radii(p), 150, net) == []


def test_lamp_logic():
    L = lamp.Lamp()
    hole = [lamp.Det(0.4, 0.6, 0.6, 0.8, 0, 0.9)]
    t = 1.0
    inp = lamp.Inputs(detections=[], speed_kmh=40)
    for _ in range(20):
        t += 0.1
        L.update(t, inp)
    assert L.level == lamp.GREEN
    t += 0.1
    L.update(t, lamp.Inputs(detections=hole, speed_kmh=40))
    for _ in range(5):
        t += 0.1
        L.update(t, inp)
    assert L.level == lamp.GREEN                                     # jednorázový záblesk nevadí
    for _ in range(3):
        t += 0.1
        L.update(t, lamp.Inputs(detections=hole, speed_kmh=40))
    assert L.level == lamp.RED and L.reason == "DÍRA PŘED MOTORKOU"
    for _ in range(32):
        t += 0.1
        L.update(t, inp)
    assert L.level == lamp.GREEN
    gravel = np.array([0.05, 0.05, 0.85, 0.03, 0.02])
    for _ in range(30):
        t += 0.1
        L.update(t, lamp.Inputs(surface_probs=gravel, speed_kmh=70))
    assert L.level == lamp.RED and L.surface == lamp.STERK


def test_vibration_meter():
    rng = np.random.default_rng(1)
    v = VibrationMeter()
    for i in range(100):
        v.add(i * 0.02, *rng.normal(0, 0.4, 2), 9.81 + rng.normal(0, 0.4))
    assert v.level(50) == 0
    for i in range(100, 200):
        v.add(i * 0.02, *rng.normal(0, 2, 2), 9.81 + rng.normal(0, 2))
    assert v.level(50) == 2 and v.level(3) == -1


def test_window_features_bands():
    t = np.arange(0, 2, 0.02)
    acc = pd.DataFrame({"cas_s": t, "ax": 0.0, "ay": 0.0, "az": 9.81 + np.sin(2 * math.pi * 10 * t)})
    f = window_features(acc, 0, 2)
    assert f is not None and np.argmax(f[6:]) == 2                   # 10 Hz -> pásmo 8–15 Hz


def test_load_motorcam_csv(tmp_path):
    rows = ["cas_s,typ,lat,lon,rychlost_kmh,kurz_deg,presnost_m,ax,ay,az,gx,gy,gz,kontrolka,povrch,duvod",
            '0.100,udalost,,,,,,,,,,,,,,"video_start"',
            "1.100,gps,50.0000000,15.0000000,36.00,90.0,4.0,,,,,,,,,",
            "2.100,gps,50.0000000,15.0001400,36.00,90.0,4.0,,,,,,,,,",
            "1.120,acc,,,,,,0.100,0.200,9.800,,,,,,"]
    f = tmp_path / "jizda.csv"
    f.write_text("\n".join(rows) + "\n", encoding="utf-8")
    ride = load_motorcam_csv(f)
    assert ride.gps.cas_s.tolist() == pytest.approx([1.0, 2.0])      # čas videa (bez 0,1 s)
    assert ride.speed_at(1.5) == pytest.approx(36)
    assert haversine(50, 15, 50, 15.00014) == pytest.approx(10, abs=0.1)
    assert len(ride.acc) == 1
