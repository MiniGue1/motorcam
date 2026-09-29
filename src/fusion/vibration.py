"""Povrch z vibrací akcelerometru.

Dvě varianty:
1. `VibrationMeter` – jednoduché pravidlo jako v aplikaci (Vibration.java): RMS otřesů
   za 1 s porovnané s kalibrovaným „hladkým asfaltem“ -> 0 hladký / 1 nerovný / 2 velmi nerovný.
2. `VibrationClassifier` – (volitelné) strojové učení: z 2s oken akcelerometru spočítá
   příznaky (RMS, směrodatné odchylky os, špičky, energie ve frekvenčních pásmech)
   a náhodný les (scikit-learn) je naučí na štítky povrchu z anotovaných snímků / kamery.
   Slouží jako nezávislé „potvrzení kamery“ – hodnotí se v experimentech.
"""

from __future__ import annotations

from collections import deque

import numpy as np
import pandas as pd


class VibrationMeter:
    def __init__(self, window_s: float = 1.0):
        self.window_s = window_s
        self.g = None
        self.buf: deque = deque()
        self.sum_sq = 0.0
        self.rms = 0.0

    def add(self, t: float, ax: float, ay: float, az: float) -> None:
        a = np.array([ax, ay, az], dtype=float)
        if self.g is None:
            self.g = a.copy()
        self.g = 0.95 * self.g + 0.05 * a
        sq = float(np.sum((a - self.g) ** 2))
        self.buf.append((t, sq))
        self.sum_sq += sq
        while self.buf and self.buf[0][0] < t - self.window_s:
            self.sum_sq -= self.buf.popleft()[1]
        self.rms = float(np.sqrt(max(0.0, self.sum_sq) / len(self.buf))) if self.buf else 0.0

    def level(self, speed_kmh: float, baseline: float = 0.8) -> int:
        if len(self.buf) < 10 or not np.isfinite(speed_kmh) or speed_kmh < 8:
            return -1
        ratio = self.rms / max(0.05, baseline)
        return 0 if ratio < 1.8 else 1 if ratio < 3.0 else 2


def window_features(acc: pd.DataFrame, t_start: float, t_end: float, fs: float = 50.0) -> np.ndarray | None:
    """Příznaky jednoho okna akcelerometru (sloupce cas_s, ax, ay, az)."""
    w = acc[(acc.cas_s >= t_start) & (acc.cas_s < t_end)]
    if len(w) < fs * (t_end - t_start) * 0.5:
        return None
    a = w[["ax", "ay", "az"]].to_numpy(float)
    a = a - a.mean(axis=0)                           # odstranění gravitace (v okně skoro konstantní)
    mag = np.linalg.norm(a, axis=1)
    # spektrum po osách a sečíst (spektrum z |a| by „usměrněním“ zdvojnásobilo frekvence)
    spec = (np.abs(np.fft.rfft(a, axis=0)) ** 2).sum(axis=1)
    freqs = np.fft.rfftfreq(len(a), d=1 / fs)
    bands = [(0.5, 3), (3, 8), (8, 15), (15, 25)]    # pomalé vlny, výtluky, štěrk, motor
    band_e = [spec[(freqs >= lo) & (freqs < hi)].sum() for lo, hi in bands]
    total = sum(band_e) + 1e-9
    return np.array([
        np.sqrt(np.mean(mag ** 2)), *a.std(axis=0), np.percentile(mag, 95), mag.max(),
        *[e / total for e in band_e],
    ])


FEATURE_NAMES = ["rms", "std_x", "std_y", "std_z", "p95", "max", "pasmo_0_3Hz", "pasmo_3_8Hz",
                 "pasmo_8_15Hz", "pasmo_15_25Hz"]


class VibrationClassifier:
    """Náhodný les nad příznaky z vibrací."""

    def __init__(self, window_s: float = 2.0):
        from sklearn.ensemble import RandomForestClassifier

        self.window_s = window_s
        self.model = RandomForestClassifier(n_estimators=200, min_samples_leaf=3, class_weight="balanced",
                                            random_state=42)

    def dataset(self, acc: pd.DataFrame, labels: pd.DataFrame) -> tuple[np.ndarray, np.ndarray]:
        """labels: sloupce cas_s, povrch (štítek platný v daném čase)."""
        X, y = [], []
        for _, row in labels.iterrows():
            f = window_features(acc, row.cas_s - self.window_s / 2, row.cas_s + self.window_s / 2)
            if f is not None:
                X.append(f)
                y.append(row.povrch)
        return np.array(X), np.array(y)

    def fit(self, X, y):
        self.model.fit(X, y)
        return self

    def predict(self, X):
        return self.model.predict(X)
