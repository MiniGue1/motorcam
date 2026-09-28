"""Načítání konfigurace a společné pomocné funkce pro cesty."""

from __future__ import annotations

from pathlib import Path
from typing import Any

import yaml

# Kořen repozitáře = o dvě úrovně výš než tento soubor (src/utils/config.py)
REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_CONFIG = REPO_ROOT / "configs" / "config.yaml"


def load_config(path: str | Path | None = None) -> dict[str, Any]:
    """Načte YAML konfiguraci. Bez argumentu použije configs/config.yaml."""
    path = Path(path) if path else DEFAULT_CONFIG
    with open(path, encoding="utf-8") as f:
        return yaml.safe_load(f)


def repo_path(relative: str | Path) -> Path:
    """Převede cestu z configu (relativní ke kořeni repozitáře) na absolutní."""
    p = Path(relative)
    return p if p.is_absolute() else REPO_ROOT / p
