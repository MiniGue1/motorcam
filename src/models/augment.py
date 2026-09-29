"""Augmentace snímků silnice – simulace podmínek, které kamera na motorce potkává.

Všechny funkce pracují s obrázkem numpy uint8 (H, W, 3, RGB) a náhodným generátorem.
Jsou napsané ručně (jen numpy + OpenCV), aby se daly v práci popsat a ukázat.

    jas/kontrast     – slunce vs. zataženo, přeexponovaná kamera
    stín             – stíny stromů a budov přes vozovku (tmavý mnohoúhelník)
    rozmazání pohybem– otřesy řídítek a rychlá jízda (lineární jádro pod náhodným úhlem)
    déšť             – šikmé čáry kapek + zamlžení a nižší kontrast
"""

from __future__ import annotations

import cv2
import numpy as np


def brightness_contrast(img: np.ndarray, rng: np.random.Generator, b=0.3, c=0.3) -> np.ndarray:
    alpha = 1 + rng.uniform(-c, c)          # kontrast
    beta = rng.uniform(-b, b) * 255         # jas
    return np.clip(img.astype(np.float32) * alpha + beta, 0, 255).astype(np.uint8)


def shadow(img: np.ndarray, rng: np.random.Generator, darkness=(0.35, 0.75)) -> np.ndarray:
    """Tmavý mnohoúhelník s rozmazaným okrajem (stín stromu / sloupu)."""
    h, w = img.shape[:2]
    mask = np.zeros((h, w), np.float32)
    n = rng.integers(3, 7)
    cx, cy = rng.uniform(0, w), rng.uniform(0.4 * h, h)     # stíny padají na vozovku (spodní část)
    rx, ry = rng.uniform(0.2, 0.7) * w, rng.uniform(0.2, 0.7) * h
    ang = np.sort(rng.uniform(0, 2 * np.pi, n))
    pts = np.stack([cx + rx * np.cos(ang), cy + ry * np.sin(ang)], 1).astype(np.int32)
    cv2.fillPoly(mask, [pts], 1.0)
    k = max(3, int(min(h, w) * 0.05) | 1)
    mask = cv2.GaussianBlur(mask, (k, k), 0)
    factor = 1 - mask * (1 - rng.uniform(*darkness))
    return np.clip(img.astype(np.float32) * factor[..., None], 0, 255).astype(np.uint8)


def motion_blur(img: np.ndarray, rng: np.random.Generator, length=(5, 21)) -> np.ndarray:
    size = int(rng.integers(length[0], length[1] + 1)) | 1
    kernel = np.zeros((size, size), np.float32)
    kernel[size // 2, :] = 1
    rot = cv2.getRotationMatrix2D((size / 2 - 0.5, size / 2 - 0.5), rng.uniform(0, 180), 1)
    kernel = cv2.warpAffine(kernel, rot, (size, size))
    kernel /= max(kernel.sum(), 1e-6)
    return cv2.filter2D(img, -1, kernel)


def rain(img: np.ndarray, rng: np.random.Generator, drops=(150, 500)) -> np.ndarray:
    h, w = img.shape[:2]
    layer = np.zeros((h, w), np.uint8)
    slant = rng.uniform(-0.3, 0.3)
    length = max(4, int(h * rng.uniform(0.03, 0.08)))
    for _ in range(int(rng.integers(*drops) * (h * w) / (224 * 224))):
        x, y = rng.integers(0, w), rng.integers(0, h)
        cv2.line(layer, (int(x), int(y)), (int(x + slant * length), int(y + length)), 200, 1)
    layer = cv2.blur(layer, (3, 3)).astype(np.float32) / 255
    out = img.astype(np.float32) * rng.uniform(0.7, 0.9)                   # zataženo
    out = out * (1 - layer[..., None] * 0.6) + 220 * layer[..., None] * 0.6
    out = cv2.GaussianBlur(out, (3, 3), 0)                                   # mokrá čočka
    gray = out.mean(axis=2, keepdims=True)
    out = gray + (out - gray) * 0.8                                          # méně barev
    return np.clip(out, 0, 255).astype(np.uint8)


class RoadAugment:
    """Náhodná kombinace augmentací s danými pravděpodobnostmi (jen pro trénink)."""

    def __init__(self, p_bc=0.8, p_shadow=0.4, p_blur=0.3, p_rain=0.15, p_flip=0.5, seed: int | None = None):
        self.p = dict(bc=p_bc, shadow=p_shadow, blur=p_blur, rain=p_rain, flip=p_flip)
        self.rng = np.random.default_rng(seed)

    def __call__(self, img: np.ndarray) -> np.ndarray:
        r = self.rng
        if r.random() < self.p["flip"]:
            img = img[:, ::-1].copy()
        if r.random() < self.p["shadow"]:
            img = shadow(img, r)
        if r.random() < self.p["bc"]:
            img = brightness_contrast(img, r)
        if r.random() < self.p["rain"]:
            img = rain(img, r)
        if r.random() < self.p["blur"]:
            img = motion_blur(img, r)
        return img
