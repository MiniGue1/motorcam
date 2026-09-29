"""Test dema (fáze 5) bez modelů: syntetické video + CSV z aplikace + syntetická mapa."""

import math

import cv2
import numpy as np

from src.demo.audio import beep_track
from src.demo.render import render
from src.fusion import osm, road_path
from src.fusion.geo import LocalProjection
from tests.test_fusion import synthetic_overpass


def test_beep_track():
    fps = 10
    levels = np.array([0] * 10 + [2] * 10 + [1] * 20)
    sig = beep_track(levels, np.zeros(40, bool), fps)
    assert len(sig) == 4 * 44100
    loud = np.abs(sig) > 0.1
    # rychlé pípání v 1.–2. s: víc pípnutí než v klidové 0. sekundě
    assert loud[:44100].sum() == 0 and loud[44100:88200].sum() > 0.2 * 44100 * 0.08


def test_render_curve_warning(tmp_path):
    proj = LocalProjection(50, 15)
    net = osm.parse(synthetic_overpass(proj), 50, 15)
    p = road_path.build(net, road_path.match(net, 300, 0, 0.0, 40), 400, 0, 1)
    v, T, fps = 80 / 3.6, 10, 10
    rows = ["cas_s,typ,lat,lon,rychlost_kmh,kurz_deg,presnost_m,ax,ay,az,gx,gy,gz,kontrolka,povrch,duvod",
            '0.000,udalost,,,,,,,,,,,,,,"video_start"']
    for k in range(T + 1):
        lat, lon = proj.latlon(np.interp(v * k, p.s, p.x), np.interp(v * k, p.s, p.y))
        rows.append(f"{k:.3f},gps,{lat:.7f},{lon:.7f},80.0,,4.0,,,,,,,,,")
    csv = tmp_path / "jizda.csv"
    csv.write_text("\n".join(rows) + "\n", encoding="utf-8")
    video = tmp_path / "jizda.avi"
    w = cv2.VideoWriter(str(video), cv2.VideoWriter_fourcc(*"MJPG"), fps, (320, 180))
    for _ in range(T * fps):
        w.write(np.full((180, 320, 3), 90, np.uint8))
    w.release()
    tl = render(str(video), str(tmp_path / "out.mp4"), sensors=str(csv), net=net)
    assert (tmp_path / "out.mp4").exists() and len(tl) == T * fps
    assert tl.varovani.max() == 2 and tl.kontrolka.max() == 2          # 80 km/h do zatáčky R≈50 m
    assert math.isclose(tl.doporucena_kmh.min(), 54, abs_tol=2)
