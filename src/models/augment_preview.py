"""Obrázek s ukázkou augmentací (do kapitoly o tréninku).

    python -m src.models.augment_preview data/custom/frames/jizda1/jizda1_00012000.jpg
    -> results/figures/augmentace.png
"""

from __future__ import annotations

import argparse

import cv2
import matplotlib.pyplot as plt
import numpy as np

from src.models import augment
from src.utils.config import load_config, repo_path
from src.utils.plotting import apply_style, save


def main(argv=None):
    p = argparse.ArgumentParser()
    p.add_argument("image")
    p.add_argument("--seed", type=int, default=3)
    args = p.parse_args(argv)
    img = cv2.cvtColor(cv2.imread(args.image), cv2.COLOR_BGR2RGB)
    h, w = img.shape[:2]
    if max(h, w) > 800:
        img = cv2.resize(img, (800, int(800 * h / w)) if w >= h else (int(800 * w / h), 800))
    rng = np.random.default_rng(args.seed)
    panels = [
        ("originál", img),
        ("jas / kontrast", augment.brightness_contrast(img, rng, 0.35, 0.35)),
        ("stín", augment.shadow(img, rng)),
        ("rozmazání pohybem", augment.motion_blur(img, rng, (15, 25))),
        ("déšť", augment.rain(img, rng)),
        ("kombinace", augment.RoadAugment(p_bc=1, p_shadow=1, p_blur=1, p_rain=0, seed=args.seed)(img)),
    ]
    apply_style()
    fig, axes = plt.subplots(2, 3, figsize=(12, 5.6))
    for ax, (title, im) in zip(axes.flat, panels):
        ax.imshow(im)
        ax.set_title(title, loc="center", fontsize=11, pad=6)
        ax.axis("off")
    fig.tight_layout()
    out = save(fig, repo_path(load_config()["paths"]["results"]) / "figures" / "augmentace")
    print("[✓]", out)


if __name__ == "__main__":
    main()
