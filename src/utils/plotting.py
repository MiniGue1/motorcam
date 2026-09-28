"""Jednotný vzhled grafů do práce (matplotlib).

Všechny grafy v projektu používají stejné barvy a styl, aby v práci působily jednotně.
Barvy kategorií jsou v pevném pořadí (1. série = modrá, 2. = oranžová, 3. = tyrkysová ...)
a byly ověřeny na čitelnost i pro barvoslepé.
"""

from __future__ import annotations

from pathlib import Path

import matplotlib

matplotlib.use("Agg")  # vykreslování bez okna (Colab, server)
import matplotlib.pyplot as plt  # noqa: E402

# Kategorické barvy – vždy přiřazovat v tomto pořadí, nikdy necyklovat
SERIES = ["#2a78d6", "#eb6834", "#1baf7a", "#eda100", "#e87ba4", "#008300", "#4a3aa7", "#e34948"]
TEXT = "#0b0b0b"
TEXT_MUTED = "#52514e"
GRID = "#e4e3df"
SURFACE = "#ffffff"


def apply_style() -> None:
    """Nastaví globální styl matplotlibu (volat jednou na začátku skriptu)."""
    plt.rcParams.update({
        "figure.facecolor": SURFACE,
        "axes.facecolor": SURFACE,
        "axes.edgecolor": GRID,
        "axes.labelcolor": TEXT_MUTED,
        "axes.titlecolor": TEXT,
        "axes.titlesize": 12,
        "axes.titleweight": "bold",
        "axes.titlelocation": "left",
        "axes.titlepad": 24,
        "axes.spines.top": False,
        "axes.spines.right": False,
        "axes.grid": True,
        "axes.axisbelow": True,
        "grid.color": GRID,
        "grid.linewidth": 0.8,
        "xtick.color": TEXT_MUTED,
        "ytick.color": TEXT_MUTED,
        "legend.frameon": False,
        "font.size": 10,
        "savefig.dpi": 200,
        "savefig.bbox": "tight",
    })


def grouped_barh(ax, categories: list[str], series: dict[str, list[float]]) -> None:
    """Seskupený vodorovný sloupcový graf (kategorie na ose y, jedna barva na sérii).

    Mezi sloupci je 2px mezera v barvě pozadí, hodnoty jsou přímo u konců sloupců.
    """
    n = len(series)
    height = 0.8 / n
    for i, (name, values) in enumerate(series.items()):
        ys = [k + (i - (n - 1) / 2) * height for k in range(len(categories))]
        bars = ax.barh(ys, values, height=height, color=SERIES[i], label=name,
                       edgecolor=SURFACE, linewidth=1.5)
        ax.bar_label(bars, labels=[f"{v:,.0f}".replace(",", " ") for v in values],
                     padding=3, fontsize=8, color=TEXT_MUTED)
    ax.set_yticks(range(len(categories)), categories)
    ax.invert_yaxis()
    ax.grid(axis="y", visible=False)
    ax.margins(x=0.08)  # místo pro popisky hodnot
    # Legenda nad grafem v jedné řadě – nikdy nepřekrývá sloupce
    ax.legend(loc="lower left", bbox_to_anchor=(0, 1.0), ncol=n, handlelength=1.2, borderaxespad=0.2)


def save(fig, path: str | Path) -> Path:
    """Uloží graf jako PNG (a vedle i PDF – vektor se hodí do Wordu/LaTeXu)."""
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(path.with_suffix(".png"))
    fig.savefig(path.with_suffix(".pdf"))
    plt.close(fig)
    return path.with_suffix(".png")
