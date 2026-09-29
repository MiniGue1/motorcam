"""Demo: z nahraného videa (+ CSV senzorů) vyrenderuje výstupní video s overlayem a pípáním.

    python -m src.demo.render data/custom/videos/jizda1.mp4 \
        --sensors data/custom/sensors/jizda1.csv \
        --det models/det_finetuned/weights/best.pt --surface models/surface_finetuned/best.pt \
        --out results/demo/jizda1_demo.mp4

CSV může být z aplikace MotorCam (synchronizace automaticky) nebo ze Sensor Loggeru
(pak --offset = čas senzoru v okamžiku začátku videa). Bez modelů se kreslí jen mapa,
zatáčky a vibrace. Vedle videa se uloží <out>_timeline.csv (pro grafy ve fázi 6).
"""

from __future__ import annotations

import argparse
import math
import subprocess
import time
from pathlib import Path

import cv2
import numpy as np
import pandas as pd

from src.demo.audio import beep_track, write_wav
from src.demo.overlay import FrameState, OverlayRenderer
from src.fusion import curves as C
from src.fusion import lamp as L
from src.fusion import road_path
from src.fusion.geo import bearing_to_angle
from src.fusion.vibration import VibrationMeter
from src.utils.config import load_config


class Models:
    """Modely na PC (PyTorch / ultralytics). Každý je volitelný."""

    def __init__(self, det: str | None, surface: str | None, det_conf: float, device: str):
        self.det = self.surf = None
        self.names, self.pothole = ("díra", "trhlina"), (True, False)
        self.det_conf, self.device = det_conf, device
        if det:
            from ultralytics import YOLO

            self.det = YOLO(det)
            raw = [self.det.names[i] for i in sorted(self.det.names)]
            self.names = tuple("díra" if n == "dira" else n for n in raw)
            self.pothole = tuple(n in ("dira", "díra", "pothole", "D40") or len(raw) == 1 for n in raw)
        if surface:
            from src.models.surface import load_checkpoint

            self.surf, ck = load_checkpoint(surface, device)
            self.surf_size, self.surf_classes = ck["img_size"], ck["classes"]

    def detect(self, rgb: np.ndarray) -> list[L.Det]:
        if self.det is None:
            return []
        h, w = rgb.shape[:2]
        r = self.det.predict(rgb[..., ::-1], conf=self.det_conf, verbose=False, device=self.device)[0]
        out = []
        for (x1, y1, x2, y2), c, p in zip(r.boxes.xyxy.cpu().numpy(), r.boxes.cls.cpu().numpy(),
                                          r.boxes.conf.cpu().numpy()):
            out.append(L.Det(x1 / w, y1 / h, x2 / w, y2 / h, int(c), float(p)))
        return out

    def classify(self, rgb: np.ndarray) -> np.ndarray | None:
        if self.surf is None:
            return None
        from src.models.surface import predict

        p = predict(self.surf, rgb, self.surf_size, full_frame=True, device=self.device)
        # přeřazení do pořadí L.SURFACE_KEYS
        out = np.zeros(len(L.SURFACE_KEYS))
        for i, c in enumerate(self.surf_classes):
            out[L.SURFACE_KEYS.index(c)] = p[i]
        return out


def mux(video: Path, wav: Path, out: Path) -> None:
    """Spojí obraz a zvuk (ffmpeg z balíčku imageio-ffmpeg – není potřeba instalovat zvlášť)."""
    import imageio_ffmpeg

    ff = imageio_ffmpeg.get_ffmpeg_exe()
    subprocess.run([ff, "-y", "-loglevel", "error", "-i", str(video), "-i", str(wav), "-c:v", "libx264",
                    "-pix_fmt", "yuv420p", "-crf", "22", "-preset", "veryfast", "-c:a", "aac", "-b:a", "128k",
                    "-shortest", str(out)], check=True)


def render(video: str, out: str, sensors: str | None = None, offset: float = 0.0, det: str | None = None,
           surface: str | None = None, every: int = 1, max_seconds: float | None = None, net=None,
           device: str = "cpu") -> pd.DataFrame:
    cfg = load_config()
    cp = C.CurveParams.from_config(cfg)
    fc = cfg["fusion"]
    lp = L.LampParams(fc["high_speed_kmh"], fc["window_s"], fc.get("hold_s", 2.0), fc.get("surface_conf", 0.55))
    cap = cv2.VideoCapture(video)
    if not cap.isOpened():
        raise SystemExit(f"Video nelze otevřít: {video}")
    fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
    w, h = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH)), int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    n_frames = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
    if max_seconds:
        n_frames = min(n_frames, int(max_seconds * fps))

    ride = None
    if sensors:
        from src.fusion.sensors import load_ride

        ride = load_ride(sensors, offset)
        if net is None and not ride.gps.empty:
            from src.fusion.osm import network_for_track

            print("Stahuji silnice z OpenStreetMap…")
            net = network_for_track(ride.gps.lat.to_numpy(), ride.gps.lon.to_numpy())
    models = Models(det, surface, fc.get("det_conf", 0.4), device)
    lamp = L.Lamp(lp)
    vib = VibrationMeter()
    acc = ride.acc.sort_values("cas_s").to_numpy() if ride is not None and not ride.acc.empty else np.zeros((0, 4))
    acc_i = 0
    renderer = OverlayRenderer(w, h)

    out = Path(out)
    out.parent.mkdir(parents=True, exist_ok=True)
    silent = out.with_name(out.stem + "_bez_zvuku.mp4")
    writer = cv2.VideoWriter(str(silent), cv2.VideoWriter_fourcc(*"mp4v"), fps, (w, h))
    rows, levels, alerts = [], [], []
    dets, probs = [], None
    trail: list[tuple[float, float]] = []
    proc_fps, prev_lamp = 0.0, 0
    for i in range(n_frames):
        ok, bgr = cap.read()
        if not ok:
            break
        t = i / fps
        rgb = cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB)
        t0 = time.time()
        if i % every == 0:
            dets = models.detect(rgb)
            probs = models.classify(rgb)
        if models.det is not None or models.surf is not None:
            dt = time.time() - t0
            if i % every == 0 and dt > 0:
                proc_fps = 1 / dt if proc_fps == 0 else 0.9 * proc_fps + 0.1 / dt

        speed, heading, pos, adv, path, radii = math.nan, math.nan, None, None, None, None
        map_status = "bez GPS dat"
        if ride is not None and not ride.gps.empty and ride.gps.cas_s.iloc[0] <= t <= ride.gps.cas_s.iloc[-1]:
            speed = float(ride.speed_at(t))
            lat, lon = ride.position_at(t)
            heading = bearing_to_angle(float(ride.heading_at(t)))
            while acc_i < len(acc) and acc[acc_i, 0] <= t:
                vib.add(*acc[acc_i])
                acc_i += 1
            if net is not None:
                pos = net.proj.xy(float(lat), float(lon))
                if not trail or math.hypot(pos[0] - trail[-1][0], pos[1] - trail[-1][1]) > 5:
                    trail.append(pos)
                m = road_path.match(net, pos[0], pos[1], heading, 40)
                map_status = "mimo silnici v mapě"
                if m is not None:
                    path = road_path.build(net, m, cp.lookahead_m + 40, 40, 5)
                    radii = C.radii(path)
                    found = [c for c in C.find(path, radii, cp.radius_threshold_m, net) if c.start_s <= cp.lookahead_m]
                    adv = C.advise(found, speed / 3.6, lamp.low_grip, cp)
        vib_level = vib.level(speed, fc.get("vibration_baseline", 0.8))
        lamp.update(t, L.Inputs(surface_probs=probs, vib_level=vib_level, detections=dets,
                                pothole_class=models.pothole, speed_kmh=speed, advice=adv))
        beep = adv.level if adv is not None and np.isfinite(speed) and speed > 10 else 0
        levels.append(beep)
        alerts.append(lamp.level == L.RED and prev_lamp != L.RED and lamp.reason.startswith("DÍRA"))
        prev_lamp = lamp.level

        if probs is not None and lamp.surface >= 0:
            surf_text, surf_src = f"{L.SURFACE_CZ[lamp.surface]} {round(lamp.surface_prob * 100)} %", "kamera"
        elif vib_level >= 0:
            surf_text, surf_src = ["hladký povrch", "nerovný povrch", "velmi nerovný povrch"][vib_level], \
                f"vibrace {vib.rms:.1f} m/s²"
        else:
            surf_text, surf_src = "povrch: —", ""
        status = f"t {t:6.1f} s"
        if models.det is not None or models.surf is not None:
            status += f" · modely {proc_fps:.1f} FPS"
        st = FrameState(
            detections=dets, det_names=models.names, pothole=models.pothole, surface_text=surf_text,
            surface_source=surf_src, lamp=lamp.level, reason=lamp.reason, speed_kmh=speed, advice=adv,
            map_status=map_status, status=status, net=net, path=path, radii=radii, pos=pos, heading=heading,
            trail=np.array(trail[-400:]) if trail else None, lean_deg=cp.lean_deg_low_grip if lamp.low_grip else cp.lean_deg,
            radius_threshold=cp.radius_threshold_m, lookahead=cp.lookahead_m)
        writer.write(cv2.cvtColor(renderer.draw(rgb, st), cv2.COLOR_RGB2BGR))
        rows.append({
            "cas_s": t, "rychlost_kmh": speed,
            "doporucena_kmh": adv.v_max_kmh if adv is not None and adv.curve is not None else np.nan,
            "vzdalenost_zatacka_m": adv.distance_m if adv is not None and adv.curve is not None else np.nan,
            "polomer_m": adv.curve.min_r if adv is not None and adv.curve is not None else np.nan,
            "varovani": beep, "kontrolka": lamp.level, "duvod": lamp.reason,
            "povrch": L.SURFACE_KEYS[lamp.surface] if lamp.surface >= 0 else "",
            "povrch_jistota": lamp.surface_prob if lamp.surface >= 0 else np.nan,
            "pocet_der": sum(1 for d in dets if d.cls < len(models.pothole) and models.pothole[d.cls]),
            "vibrace_rms": vib.rms,
        })
        if i % int(fps * 10) == 0:
            print(f"  {t:6.1f} s / {n_frames / fps:.1f} s")
    writer.release()
    cap.release()

    wav = write_wav(beep_track(np.array(levels), np.array(alerts), fps), out.with_suffix(".wav"))
    try:
        mux(silent, wav, out)
        silent.unlink()
        wav.unlink()
    except Exception as e:  # noqa: BLE001
        print(f"[!] Spojení se zvukem selhalo ({e}); video bez zvuku: {silent}, zvuk: {wav}")
    timeline = pd.DataFrame(rows)
    timeline.to_csv(out.with_name(out.stem + "_timeline.csv"), index=False, float_format="%.3f")
    print(f"[✓] {out}")
    return timeline


def main(argv=None):
    p = argparse.ArgumentParser(description="Demo video s overlayem a pípáním")
    p.add_argument("video")
    p.add_argument("--sensors", help="CSV z aplikace MotorCam nebo export Sensor Loggeru")
    p.add_argument("--offset", type=float, default=0.0, help="Sensor Logger: čas senzoru při začátku videa [s]")
    p.add_argument("--det", help="váhy YOLO (.pt)")
    p.add_argument("--surface", help="checkpoint modelu povrchu (.pt)")
    p.add_argument("--out", default="results/demo/demo.mp4")
    p.add_argument("--every", type=int, default=1, help="modely jen na každý N-tý snímek (rychlejší)")
    p.add_argument("--max-seconds", type=float)
    p.add_argument("--device", default="cpu")
    a = p.parse_args(argv)
    render(a.video, a.out, a.sensors, a.offset, a.det, a.surface, a.every, a.max_seconds, device=a.device)


if __name__ == "__main__":
    main()
