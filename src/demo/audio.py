"""Zvuková stopa s pípáním podle stupně varování (jako Beeper.java v aplikaci).

    stupeň 1 = jedno pípnutí (160 ms) každých ~1,5 s  -> zpomal mírně
    stupeň 2 = rychlé pípání (80 ms každých 310 ms)   -> zpomal hodně
    díra před motorkou = krátké dvojité pípnutí
"""

from __future__ import annotations

import wave
from pathlib import Path

import numpy as np

RATE = 44100


def _tone(n_samples: int, freq: float = 1000.0) -> np.ndarray:
    t = np.arange(n_samples) / RATE
    env = np.minimum(1, np.minimum(t, t[::-1]) / 0.005)        # 5ms náběh/doběh proti lupání
    return 0.5 * np.sin(2 * np.pi * freq * t) * env


def beep_track(levels: np.ndarray, alerts: np.ndarray, fps: float) -> np.ndarray:
    """levels/alerts po snímcích videa -> mono signál float32 (-1..1)."""
    duration = len(levels) / fps
    out = np.zeros(int(duration * RATE) + RATE, np.float32)
    t, next_beep = 0.0, 0.0
    beep_times: list[tuple[float, float]] = []                   # (začátek, délka)
    for i, lvl in enumerate(levels):
        t = i / fps
        if alerts[i]:
            beep_times += [(t, 0.07), (t + 0.21, 0.07)]
        if lvl >= 2 and t >= next_beep:
            beep_times.append((t, 0.08))
            next_beep = t + 0.31
        elif lvl == 1 and t >= next_beep:
            beep_times.append((t, 0.16))
            next_beep = t + 1.5
        elif lvl == 0:
            next_beep = t                                          # při novém varování pípnout hned
    for start, length in beep_times:
        a = int(start * RATE)
        tone = _tone(int(length * RATE))
        out[a:a + len(tone)] += tone[:max(0, len(out) - a)]
    return np.clip(out[:int(duration * RATE)], -1, 1)


def write_wav(signal: np.ndarray, path: str | Path) -> Path:
    path = Path(path)
    with wave.open(str(path), "wb") as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(RATE)
        wf.writeframes((signal * 32767).astype(np.int16).tobytes())
    return path
