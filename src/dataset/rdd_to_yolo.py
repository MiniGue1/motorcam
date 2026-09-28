"""Převod RDD2022 (anotace Pascal VOC .xml) do YOLO formátu pro ultralytics.

Výstup (výchozí data/processed/rdd2022_yolo):

    images/train/*.jpg   labels/train/*.txt
    images/val/*.jpg     labels/val/*.txt
    data.yaml            – popis datasetu pro ultralytics
    split.csv            – který snímek je v jaké části (pro reprodukovatelnost)

Řádek v .txt: `<id_třídy> <x_střed> <y_střed> <šířka> <výška>` (normalizováno 0–1).

Použití:
    python -m src.dataset.rdd_to_yolo
    python -m src.dataset.rdd_to_yolo --countries Czech --out data/processed/rdd_czech
"""

from __future__ import annotations

import argparse
import csv
import os
import random
import shutil
import xml.etree.ElementTree as ET
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from pathlib import Path

import cv2
from tqdm import tqdm

from src.utils.config import load_config, repo_path

# Seznam zemí – podle prefixu názvu souboru (např. "China_MotorBike_000123.jpg")
KNOWN_COUNTRIES = ["China_MotorBike", "China_Drone", "United_States", "Czech", "Japan", "India", "Norway"]


@dataclass
class Box:
    label: str      # původní třída RDD (D00, D40, ...)
    xmin: float
    ymin: float
    xmax: float
    ymax: float


@dataclass
class Annotation:
    image_path: Path
    width: int
    height: int
    country: str
    boxes: list[Box] = field(default_factory=list)


def country_of(name: str) -> str:
    """Určí zemi podle začátku názvu souboru."""
    for c in KNOWN_COUNTRIES:
        if name.startswith(c):
            return c
    return "unknown"


def parse_voc(xml_path: Path) -> tuple[str | None, int, int, list[Box]]:
    """Načte jeden Pascal VOC soubor. Vrací (filename, šířka, výška, boxy)."""
    root = ET.parse(xml_path).getroot()
    filename = root.findtext("filename")
    size = root.find("size")
    w = int(float(size.findtext("width", "0"))) if size is not None else 0
    h = int(float(size.findtext("height", "0"))) if size is not None else 0
    boxes = []
    for obj in root.iter("object"):
        bb = obj.find("bndbox")
        if bb is None:
            continue
        boxes.append(
            Box(
                label=(obj.findtext("name") or "").strip(),
                xmin=float(bb.findtext("xmin")),
                ymin=float(bb.findtext("ymin")),
                xmax=float(bb.findtext("xmax")),
                ymax=float(bb.findtext("ymax")),
            )
        )
    return filename, w, h, boxes


def find_annotations(raw_dir: Path, countries: list[str] | None) -> list[Annotation]:
    """Najde všechny XML anotace (trénovací část) a k nim odpovídající obrázky."""
    # Index všech obrázků podle jména – struktura archivů se mezi verzemi trochu liší
    images = {p.name: p for p in raw_dir.rglob("*.jpg")}
    images_by_stem = {p.stem: p for p in images.values()}

    result = []
    for xml_path in sorted(raw_dir.rglob("*.xml")):
        filename, w, h, boxes = parse_voc(xml_path)
        img = images.get(filename or "") or images_by_stem.get(xml_path.stem)
        if img is None:
            continue
        country = country_of(img.name)
        if countries and country not in countries:
            continue
        if w <= 0 or h <= 0:  # některé XML nemají velikost -> načteme z obrázku
            im = cv2.imread(str(img))
            if im is None:
                continue
            h, w = im.shape[:2]
        result.append(Annotation(img, w, h, country, boxes))
    return result


def to_yolo_lines(
    ann: Annotation, class_map: dict[str, str], class_ids: dict[str, int], min_box_px: int
) -> tuple[list[str], Counter]:
    """Převede boxy jednoho snímku na řádky YOLO. Vrací i počty zahozených štítků."""
    lines, skipped = [], Counter()
    for b in ann.boxes:
        target = class_map.get(b.label)
        if target is None:
            skipped[b.label] += 1
            continue
        # Oříznutí na hranice obrázku (v datech se občas vyskytují přesahy)
        x1, x2 = max(0.0, min(b.xmin, b.xmax)), min(float(ann.width), max(b.xmin, b.xmax))
        y1, y2 = max(0.0, min(b.ymin, b.ymax)), min(float(ann.height), max(b.ymin, b.ymax))
        if x2 - x1 < min_box_px or y2 - y1 < min_box_px:
            skipped["_maly_box"] += 1
            continue
        xc, yc = (x1 + x2) / 2 / ann.width, (y1 + y2) / 2 / ann.height
        bw, bh = (x2 - x1) / ann.width, (y2 - y1) / ann.height
        lines.append(f"{class_ids[target]} {xc:.6f} {yc:.6f} {bw:.6f} {bh:.6f}")
    return lines, skipped


def split_by_country(anns: list[Annotation], val_fraction: float, seed: int) -> dict[str, str]:
    """Rozdělí snímky na train/val zvlášť v každé zemi (stratifikace podle země)."""
    rng = random.Random(seed)
    groups: dict[str, list[Annotation]] = defaultdict(list)
    for a in anns:
        groups[a.country].append(a)
    split = {}
    for country in sorted(groups):
        items = sorted(groups[country], key=lambda a: a.image_path.name)
        rng.shuffle(items)
        n_val = round(len(items) * val_fraction)
        for i, a in enumerate(items):
            split[a.image_path.name] = "val" if i < n_val else "train"
    return split


def place_file(src: Path, dst: Path, mode: str) -> None:
    """Umístí obrázek do výstupu: hardlink (šetří místo), symlink nebo kopie."""
    if dst.exists():
        return
    if mode == "link":
        try:
            os.link(src, dst)
            return
        except OSError:
            pass  # jiný disk / nepodporováno -> kopie
    if mode == "symlink":
        dst.symlink_to(src.resolve())
        return
    shutil.copy2(src, dst)


def write_data_yaml(out_dir: Path, class_names: list[str]) -> None:
    """Zapíše data.yaml pro ultralytics."""
    lines = [
        f"path: {out_dir.resolve()}",
        "train: images/train",
        "val: images/val",
        "names:",
        *[f"  {i}: {n}" for i, n in enumerate(class_names)],
    ]
    (out_dir / "data.yaml").write_text("\n".join(lines) + "\n", encoding="utf-8")


def convert(
    raw_dir: Path,
    out_dir: Path,
    class_map: dict[str, str],
    class_names: list[str],
    countries: list[str] | None = None,
    val_fraction: float = 0.15,
    min_box_px: int = 8,
    seed: int = 42,
    file_mode: str = "link",
) -> dict:
    """Hlavní převod. Vrací slovník se statistikami (pro tabulky do práce)."""
    class_ids = {n: i for i, n in enumerate(class_names)}
    anns = find_annotations(raw_dir, countries)
    if not anns:
        raise SystemExit(f"V {raw_dir} jsem nenašel žádné anotace RDD2022 (.xml + .jpg).")
    split = split_by_country(anns, val_fraction, seed)

    for part in ("train", "val"):
        (out_dir / "images" / part).mkdir(parents=True, exist_ok=True)
        (out_dir / "labels" / part).mkdir(parents=True, exist_ok=True)

    stats = defaultdict(Counter)  # stats[(země, část)][třída]
    skipped_total = Counter()
    rows = []
    for a in tqdm(anns, desc="RDD2022 -> YOLO"):
        part = split[a.image_path.name]
        lines, skipped = to_yolo_lines(a, class_map, class_ids, min_box_px)
        skipped_total.update(skipped)
        place_file(a.image_path, out_dir / "images" / part / a.image_path.name, file_mode)
        label_path = out_dir / "labels" / part / (a.image_path.stem + ".txt")
        label_path.write_text("\n".join(lines) + ("\n" if lines else ""), encoding="utf-8")

        key = (a.country, part)
        stats[key]["snimky"] += 1
        stats[key]["bez_objektu"] += int(not lines)
        for ln in lines:
            stats[key][class_names[int(ln.split()[0])]] += 1
        rows.append((a.image_path.name, a.country, part, len(lines)))

    write_data_yaml(out_dir, class_names)
    with open(out_dir / "split.csv", "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["soubor", "zeme", "cast", "pocet_boxu"])
        w.writerows(rows)
    return {"stats": stats, "skipped": skipped_total}


def save_stats_table(result: dict, class_names: list[str], path: Path) -> None:
    """Uloží souhrnnou tabulku (země × část) jako CSV do results/tables."""
    path.parent.mkdir(parents=True, exist_ok=True)
    cols = ["snimky", "bez_objektu", *class_names]
    with open(path, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["zeme", "cast", *cols])
        for (country, part), c in sorted(result["stats"].items()):
            w.writerow([country, part, *[c[k] for k in cols]])


def main(argv: list[str] | None = None) -> None:
    cfg = load_config()
    rcfg = cfg["rdd2022"]
    parser = argparse.ArgumentParser(description="RDD2022 (VOC XML) -> YOLO")
    parser.add_argument("--raw", default=str(repo_path(cfg["paths"]["raw"]) / "rdd2022"))
    parser.add_argument("--out", default=str(repo_path(cfg["paths"]["processed"]) / "rdd2022_yolo"))
    parser.add_argument("--countries", nargs="+", default=None, help="jen tyto země (výchozí: vše staženo)")
    parser.add_argument("--val-fraction", type=float, default=rcfg["val_fraction"])
    parser.add_argument("--mode", choices=["link", "symlink", "copy"], default="link")
    args = parser.parse_args(argv)

    class_names = cfg["detector"]["classes"]
    result = convert(
        Path(args.raw),
        Path(args.out),
        rcfg["class_map"],
        class_names,
        countries=args.countries,
        val_fraction=args.val_fraction,
        min_box_px=rcfg["min_box_px"],
        seed=cfg["seed"],
        file_mode=args.mode,
    )
    table = repo_path(cfg["paths"]["results"]) / "tables" / "rdd2022_statistiky.csv"
    save_stats_table(result, class_names, table)
    print(f"[✓] Hotovo -> {args.out}")
    print(f"    Statistiky: {table}")
    if result["skipped"]:
        print(f"    Ignorované štítky: {dict(result['skipped'])}")


if __name__ == "__main__":
    main()
