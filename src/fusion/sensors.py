"""Načtení dat ze senzorů ve dvou formátech a sjednocení na společné tabulky.

1. CSV z aplikace MotorCam (RideLogger.java) – jeden soubor, sloupec `typ`
   (gps | acc | gyr | stav | udalost). Čas `cas_s` běží od stisku REC; událost
   `video_start` říká, kdy začal první snímek videa -> přesná synchronizace.
2. Aplikace Sensor Logger (Android/iOS) – složka / ZIP s Location.csv, Accelerometer.csv,
   Gyroscope.csv (sloupec `seconds_elapsed`). Synchronizace s videem ručně parametrem
   `offset_s` (čas ve videu = čas senzoru − offset), např. podle ťuknutí na začátku.

Výstup `Ride`: tabulky gps (cas_s, lat, lon, rychlost_kmh, kurz_deg), acc (cas_s, ax, ay, az),
gyr; všechny časy jsou už v čase VIDEA.
"""

from __future__ import annotations

import io
import zipfile
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import pandas as pd


@dataclass
class Ride:
    gps: pd.DataFrame
    acc: pd.DataFrame
    gyr: pd.DataFrame

    def speed_at(self, t) -> np.ndarray:
        """Rychlost [km/h] v časech t (lineární interpolace)."""
        if self.gps.empty:
            return np.full(np.shape(t), np.nan)
        return np.interp(t, self.gps.cas_s, self.gps.rychlost_kmh, left=np.nan, right=np.nan)

    def position_at(self, t) -> tuple[np.ndarray, np.ndarray]:
        return (np.interp(t, self.gps.cas_s, self.gps.lat), np.interp(t, self.gps.cas_s, self.gps.lon))

    def heading_at(self, t) -> np.ndarray:
        """Kurz [°] – interpolace přes sin/cos, aby přechod 359° -> 1° nedělal skok."""
        rad = np.radians(self.gps.kurz_deg.to_numpy(float))
        s = np.interp(t, self.gps.cas_s, np.sin(rad))
        c = np.interp(t, self.gps.cas_s, np.cos(rad))
        return np.degrees(np.arctan2(s, c)) % 360


def _fill_speed_heading(gps: pd.DataFrame) -> pd.DataFrame:
    """Dopočítá chybějící rychlost / kurz z po sobě jdoucích poloh."""
    from src.fusion.geo import LocalProjection

    if gps.empty:
        return gps
    proj = LocalProjection(gps.lat.iloc[0], gps.lon.iloc[0])
    x, y = proj.xy(gps.lat.to_numpy(), gps.lon.to_numpy())
    dt = np.gradient(gps.cas_s.to_numpy())
    vx, vy = np.gradient(x) / np.maximum(dt, 1e-3), np.gradient(y) / np.maximum(dt, 1e-3)
    speed = np.hypot(vx, vy) * 3.6
    heading = (90 - np.degrees(np.arctan2(vy, vx))) % 360
    gps = gps.copy()
    gps["rychlost_kmh"] = gps["rychlost_kmh"].fillna(pd.Series(speed, index=gps.index))
    gps["kurz_deg"] = gps["kurz_deg"].where(gps["rychlost_kmh"] > 5).fillna(pd.Series(heading, index=gps.index))
    return gps


def load_motorcam_csv(path: str | Path) -> Ride:
    df = pd.read_csv(path)
    ev = df[(df.typ == "udalost") & (df.duvod == "video_start")]
    t0 = float(ev.cas_s.iloc[0]) if len(ev) else 0.0      # posun na čas videa
    df = df.assign(cas_s=df.cas_s - t0)
    gps = df[df.typ == "gps"][["cas_s", "lat", "lon", "rychlost_kmh", "kurz_deg", "presnost_m"]].reset_index(drop=True)
    acc = df[df.typ == "acc"][["cas_s", "ax", "ay", "az"]].reset_index(drop=True)
    gyr = df[df.typ == "gyr"][["cas_s", "gx", "gy", "gz"]].reset_index(drop=True)
    return Ride(_fill_speed_heading(gps), acc, gyr)


def _read_sl(source: Path, name: str) -> pd.DataFrame | None:
    if source.suffix == ".zip":
        with zipfile.ZipFile(source) as z:
            match = [n for n in z.namelist() if n.lower().endswith(name.lower())]
            return pd.read_csv(io.BytesIO(z.read(match[0]))) if match else None
    f = source / name
    return pd.read_csv(f) if f.exists() else None


def load_sensor_logger(source: str | Path, offset_s: float = 0.0) -> Ride:
    """Sensor Logger export (složka nebo ZIP). offset_s = čas senzoru, kdy začalo video."""
    source = Path(source)
    loc = _read_sl(source, "Location.csv")
    acc = _read_sl(source, "Accelerometer.csv")
    gyr = _read_sl(source, "Gyroscope.csv")
    gps = pd.DataFrame(columns=["cas_s", "lat", "lon", "rychlost_kmh", "kurz_deg", "presnost_m"])
    if loc is not None:
        gps = pd.DataFrame({
            "cas_s": loc.seconds_elapsed - offset_s,
            "lat": loc.latitude, "lon": loc.longitude,
            "rychlost_kmh": loc.speed.where(loc.speed >= 0) * 3.6 if "speed" in loc else np.nan,
            "kurz_deg": loc.bearing.where(loc.bearing >= 0) if "bearing" in loc else np.nan,
            "presnost_m": loc.horizontalAccuracy if "horizontalAccuracy" in loc else np.nan,
        })
    # Sensor Logger ukládá akcelerometr bez gravitace (osy z, y, x) – na RMS otřesů to nevadí
    a = pd.DataFrame(columns=["cas_s", "ax", "ay", "az"]) if acc is None else pd.DataFrame(
        {"cas_s": acc.seconds_elapsed - offset_s, "ax": acc.x, "ay": acc.y, "az": acc.z})
    g = pd.DataFrame(columns=["cas_s", "gx", "gy", "gz"]) if gyr is None else pd.DataFrame(
        {"cas_s": gyr.seconds_elapsed - offset_s, "gx": gyr.x, "gy": gyr.y, "gz": gyr.z})
    return Ride(_fill_speed_heading(gps.reset_index(drop=True)), a, g)


def load_ride(path: str | Path, offset_s: float = 0.0) -> Ride:
    """Rozpozná formát podle obsahu."""
    path = Path(path)
    if path.suffix == ".csv":
        head = path.read_text(encoding="utf-8", errors="ignore")[:200]
        if head.startswith("cas_s,typ"):
            return load_motorcam_csv(path)
    return load_sensor_logger(path, offset_s)
