"""Převod RSCD (27 tříd) na našich 5 tříd povrchu ve formátu „složka = třída“.

Výstup (výchozí data/processed/rscd_cls) – přímo pro torchvision.datasets.ImageFolder:

    train/asfalt/*.jpg  train/rozbity_asfalt/...  train/sterk/...  train/hlina_blato/...  train/mokro/...
    val/...
    test/...

Třídy RSCD mají tvar <tření>_<materiál>_<nerovnost>, např. „dry_asphalt_smooth“,
„wet_gravel“, „water_concrete_severe“, „fresh_snow“. Štítek se hledá v názvu
nadřazené složky, případně v názvu souboru (testovací část má štítek v názvu).

Mapování (lze upravit v map_rscd_label):
    sníh / led                      -> zahodit (pro motorku mimo rozsah)
    materiál mud                    -> hlina_blato
    materiál gravel                 -> sterk
    asfalt/beton + wet/water        -> mokro
    asfalt/beton + dry + severe     -> rozbity_asfalt
    asfalt/beton + dry + slight     -> asfalt (nebo rozbity_asfalt, viz config slight_is_damaged)
    asfalt/beton + dry + smooth     -> asfalt

Použití:
    python -m src.dataset.rscd_to_classes
    python -m src.dataset.rscd_to_classes --max-per-class 2000
"""

from __future__ import annotations

import argparse
import csv
import random
import re
from collections import Counter, defaultdict
from pathlib import Path

from tqdm import tqdm

from src.dataset.rdd_to_yolo import place_file
from src.utils.config import load_config, repo_path

FRICTIONS = ["fresh_snow", "melted_snow", "dry", "wet", "water", "ice"]
MATERIALS = ["asphalt", "concrete", "mud", "gravel"]
UNEVENNESS = ["smooth", "slight", "severe"]
IMAGE_EXT = {".jpg", ".jpeg", ".png"}

# Regulární výraz pro štítek RSCD kdekoli v textu (oddělovač _ nebo -)
_LABEL_RE = re.compile(
    r"(fresh[_-]snow|melted[_-]snow|ice|dry|wet|water)"
    r"(?:[_-](asphalt|concrete|mud|gravel))?"
    r"(?:[_-](smooth|slight|severe))?",
    re.IGNORECASE,
)


def parse_rscd_label(text: str) -> tuple[str, str | None, str | None] | None:
    """Z textu (název složky/souboru) vytáhne (tření, materiál, nerovnost).

    Vybere nejdelší shodu, aby „dry_asphalt_smooth“ nevyhrálo nad samotným „dry“.
    """
    best = None
    for m in _LABEL_RE.finditer(text.lower()):
        friction = m.group(1).replace("-", "_")
        material, uneven = m.group(2), m.group(3)
        # Tření bez materiálu dává smysl jen u sněhu a ledu
        if material is None and friction not in {"fresh_snow", "melted_snow", "ice"}:
            continue
        if best is None or len(m.group(0)) > best[0]:
            best = (len(m.group(0)), (friction, material, uneven))
    return best[1] if best else None


def map_rscd_label(
    friction: str, material: str | None, uneven: str | None, slight_is_damaged: bool = False
) -> str | None:
    """Převede trojici RSCD na naši třídu povrchu (None = nepoužít)."""
    if friction in {"fresh_snow", "melted_snow", "ice"}:
        return None
    if material == "mud":
        return "hlina_blato"
    if material == "gravel":
        return "sterk"
    if material in {"asphalt", "concrete"}:
        if friction in {"wet", "water"}:
            return "mokro"
        if uneven == "severe" or (uneven == "slight" and slight_is_damaged):
            return "rozbity_asfalt"
        return "asfalt"
    return None


def label_of_path(path: Path) -> tuple[str, str | None, str | None] | None:
    """Štítek hledá nejdřív v nadřazené složce, pak v názvu souboru."""
    return parse_rscd_label(path.parent.name) or parse_rscd_label(path.stem)


def original_split(path: Path) -> str | None:
    """Pokud cesta (relativní ke kořeni RSCD) obsahuje původní rozdělení train/vali/test, vrátí ho."""
    for part in (p.lower() for p in path.parts):
        if part.startswith("train"):
            return "train"
        if part.startswith("val"):
            return "val"
        if part.startswith("test"):
            return "test"
    return None


def balanced_sample(paths_by_src: dict[str, list[Path]], limit: int, rng: random.Random) -> list[Path]:
    """Vybere max. `limit` snímků rovnoměrně z podtříd RSCD (round-robin),
    aby např. třída „asfalt“ obsahovala jak asfalt, tak beton."""
    pools = []
    for src in sorted(paths_by_src):
        items = sorted(paths_by_src[src])
        rng.shuffle(items)
        pools.append(items)
    chosen: list[Path] = []
    i = 0
    while len(chosen) < limit and any(i < len(p) for p in pools):
        for p in pools:
            if i < len(p) and len(chosen) < limit:
                chosen.append(p[i])
        i += 1
    return chosen


def convert(
    raw_dir: Path,
    out_dir: Path,
    max_per_class: int = 6000,
    val_fraction: float = 0.1,
    test_fraction: float = 0.1,
    slight_is_damaged: bool = False,
    seed: int = 42,
    file_mode: str = "link",
) -> dict[tuple[str, str], int]:
    """Hlavní převod. Vrací počty snímků {(část, třída): počet}."""
    rng = random.Random(seed)

    # grouped[část][naše_třída][podtřída_RSCD] = [cesty]
    grouped: dict[str, dict[str, dict[str, list[Path]]]] = defaultdict(lambda: defaultdict(lambda: defaultdict(list)))
    unknown = 0
    all_images = [p for p in raw_dir.rglob("*") if p.suffix.lower() in IMAGE_EXT]
    for p in tqdm(all_images, desc="čtu RSCD"):
        lab = label_of_path(p)
        target = map_rscd_label(*lab, slight_is_damaged) if lab else None
        if target is None:
            unknown += lab is None
            continue
        src_name = "_".join(x for x in lab if x)
        grouped[original_split(p.relative_to(raw_dir)) or "?"][target][src_name].append(p)

    if not grouped:
        raise SystemExit(f"V {raw_dir} jsem nenašel žádné snímky RSCD se štítkem.")
    if unknown:
        print(f"[!] {unknown} snímků bez rozpoznatelného štítku – přeskočeno.")

    # Pokud data mají původní rozdělení, respektujeme ho: RSCD vzniklo z videí,
    # sousední snímky jsou skoro stejné a náhodné dělení by nadhodnotilo přesnost.
    use_original = "?" not in grouped and {"train", "test"} <= set(grouped)
    assignments: list[tuple[Path, str, str]] = []  # (cesta, část, třída)
    if use_original:
        print("[i] Používám původní rozdělení RSCD (train/val/test).")
        fractions = {"train": 1 - val_fraction - test_fraction, "val": val_fraction, "test": test_fraction}
        for part, by_class in grouped.items():
            limit = max(1, round(max_per_class * fractions[part]))
            for target, by_src in by_class.items():
                assignments += [(p, part, target) for p in balanced_sample(by_src, limit, rng)]
        # Když chybí validační část, vezmeme ji z trénovací
        if "val" not in grouped:
            train_idx = [i for i, a in enumerate(assignments) if a[1] == "train"]
            rng.shuffle(train_idx)
            n_val = round(len(train_idx) * val_fraction / (1 - test_fraction))
            for i in train_idx[:n_val]:
                p, _, target = assignments[i]
                assignments[i] = (p, "val", target)
    else:
        print("[i] Původní rozdělení nenalezeno – dělím náhodně.")
        merged: dict[str, dict[str, list[Path]]] = defaultdict(lambda: defaultdict(list))
        for by_class in grouped.values():
            for target, by_src in by_class.items():
                for src, paths in by_src.items():
                    merged[target][src] += paths
        for target, by_src in merged.items():
            chosen = balanced_sample(by_src, max_per_class, rng)
            rng.shuffle(chosen)
            n_test, n_val = round(len(chosen) * test_fraction), round(len(chosen) * val_fraction)
            for i, p in enumerate(chosen):
                part = "test" if i < n_test else "val" if i < n_test + n_val else "train"
                assignments.append((p, part, target))

    counts: Counter = Counter()
    for p, part, target in tqdm(assignments, desc="zapisuji"):
        dst_dir = out_dir / part / target
        dst_dir.mkdir(parents=True, exist_ok=True)
        # Prefix podsložkou zabrání kolizi stejných názvů z různých složek RSCD
        place_file(p, dst_dir / f"{p.parent.name}__{p.name}", file_mode)
        counts[(part, target)] += 1
    return dict(counts)


def save_stats_table(counts: dict[tuple[str, str], int], classes: list[str], path: Path) -> None:
    """Tabulka počtů snímků: třída × (train, val, test)."""
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["trida", "train", "val", "test"])
        for c in classes:
            w.writerow([c, *[counts.get((s, c), 0) for s in ("train", "val", "test")]])


def main(argv: list[str] | None = None) -> None:
    cfg = load_config()
    rcfg = cfg["rscd"]
    parser = argparse.ArgumentParser(description="RSCD (27 tříd) -> 5 tříd povrchu (ImageFolder)")
    parser.add_argument("--raw", default=str(repo_path(cfg["paths"]["raw"]) / "rscd"))
    parser.add_argument("--out", default=str(repo_path(cfg["paths"]["processed"]) / "rscd_cls"))
    parser.add_argument("--max-per-class", type=int, default=rcfg["max_per_class"])
    parser.add_argument("--mode", choices=["link", "symlink", "copy"], default="link")
    args = parser.parse_args(argv)

    counts = convert(
        Path(args.raw),
        Path(args.out),
        max_per_class=args.max_per_class,
        val_fraction=rcfg["val_fraction"],
        test_fraction=rcfg["test_fraction"],
        slight_is_damaged=rcfg["slight_is_damaged"],
        seed=cfg["seed"],
        file_mode=args.mode,
    )
    table = repo_path(cfg["paths"]["results"]) / "tables" / "rscd_statistiky.csv"
    save_stats_table(counts, cfg["surface"]["classes"], table)
    print(f"[✓] Hotovo -> {args.out}\n    Statistiky: {table}")


if __name__ == "__main__":
    main()
