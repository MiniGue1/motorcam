"""Vyhodnocení klasifikace: metriky, tabulky a grafy do práce."""

from __future__ import annotations

from pathlib import Path

import matplotlib.pyplot as plt
import numpy as np
import pandas as pd

from src.utils.plotting import SERIES, TEXT, TEXT_MUTED, apply_style, save


def classification_metrics(y_true, y_pred, labels: list[str]) -> tuple[dict, pd.DataFrame, np.ndarray]:
    """Vrací (souhrn, tabulka po třídách, confusion matrix)."""
    from sklearn.metrics import accuracy_score, confusion_matrix, f1_score, precision_recall_fscore_support

    idx = list(range(len(labels)))
    p, r, f, s = precision_recall_fscore_support(y_true, y_pred, labels=idx, zero_division=0)
    table = pd.DataFrame({"trida": labels, "precision": p, "recall": r, "f1": f, "pocet": s})
    summary = {
        "accuracy": accuracy_score(y_true, y_pred),
        "f1_macro": f1_score(y_true, y_pred, labels=idx, average="macro", zero_division=0),
        "f1_weighted": f1_score(y_true, y_pred, labels=idx, average="weighted", zero_division=0),
        "pocet": len(y_true),
    }
    cm = confusion_matrix(y_true, y_pred, labels=idx)
    return summary, table, cm


def plot_confusion(cm: np.ndarray, labels: list[str], path: Path, title: str) -> Path:
    """Normalizovaná confusion matrix (řádky = skutečná třída) s počty v buňkách."""
    apply_style()
    norm = cm / np.maximum(cm.sum(axis=1, keepdims=True), 1)
    fig, ax = plt.subplots(figsize=(6.2, 5.2))
    ax.imshow(norm, cmap="Blues", vmin=0, vmax=1)
    ax.grid(False)
    ax.set_xticks(range(len(labels)), labels, rotation=30, ha="right")
    ax.set_yticks(range(len(labels)), labels)
    ax.set_xlabel("predikce modelu")
    ax.set_ylabel("skutečnost")
    for i in range(len(labels)):
        for j in range(len(labels)):
            color = "white" if norm[i, j] > 0.55 else TEXT
            ax.text(j, i, f"{norm[i, j] * 100:.0f} %\n({cm[i, j]})", ha="center", va="center", fontsize=8, color=color)
    ax.set_title(title)
    return save(fig, path)


def plot_history(hist: pd.DataFrame, path: Path, title: str) -> Path:
    """Křivky učení: ztráta (train/val) a přesnost/F1 na validaci – dva grafy vedle sebe."""
    apply_style()
    fig, (a1, a2) = plt.subplots(1, 2, figsize=(10, 3.6))
    a1.plot(hist.epoch, hist.train_loss, color=SERIES[0], lw=2, label="trénink")
    a1.plot(hist.epoch, hist.val_loss, color=SERIES[1], lw=2, label="validace")
    a1.set_title("Ztráta (loss)")
    a1.set_xlabel("epocha")
    a1.legend()
    a2.plot(hist.epoch, hist.val_acc, color=SERIES[0], lw=2, label="accuracy")
    a2.plot(hist.epoch, hist.val_f1, color=SERIES[2], lw=2, label="F1 (makro)")
    a2.set_ylim(0, 1)
    a2.set_title("Validace")
    a2.set_xlabel("epocha")
    a2.legend()
    fig.suptitle(title, x=0.01, ha="left", fontweight="bold", color=TEXT)
    fig.tight_layout()
    return save(fig, path)


def plot_per_class_f1(tables: dict[str, pd.DataFrame], path: Path, title: str) -> Path:
    """Porovnání F1 po třídách pro víc modelů (fáze 6)."""
    from src.utils.plotting import grouped_barh

    apply_style()
    labels = list(next(iter(tables.values())).trida)
    fig, ax = plt.subplots(figsize=(7, 0.32 * len(labels) * max(1, len(tables)) + 1.4))
    grouped_barh(ax, labels, {name: list(t.f1 * 100) for name, t in tables.items()})
    ax.set_xlabel("F1 [%]")
    ax.set_xlim(0, 110)
    ax.set_title(title)
    for txt in ax.texts:
        txt.set_color(TEXT_MUTED)
    return save(fig, path)
