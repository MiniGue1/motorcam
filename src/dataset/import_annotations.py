"""Import vlastních anotací (Label Studio JSON nebo YOLO export z CVAT/Label Studia).

Doporučený postup je Label Studio se šablonou docs/label_studio_config.xml – v jednom
průchodu anotuješ boxy (díra, trhlina), povrch celého snímku a podmínky (stín, noc, ...).

Výstup:
    data/processed/custom_yolo/   images|labels/{train,val,test}, data.yaml   – detekce
    data/processed/custom_cls/    {train,val,test}/<třída povrchu>/*.jpg      – klasifikace
    data/processed/custom_meta.csv                                            – metadata snímků
                                                                                 (pro analýzu chyb)

Rozdělení train/val/test je PO CELÝCH VIDEÍCH – sousední snímky jednoho videa jsou si
velmi podobné, a kdyby se dostaly do trénovací i testovací části, výsledky by byly
falešně dobré (tzv. data leakage).

Použití:
    python -m src.dataset.import_annotations labelstudio export.json --images data/custom/frames
    python -m src.dataset.import_annotations yolo cvat_export/ --images data/custom/frames
    # ruční volba testovacích videí:
    python -m src.dataset.import_annotations labelstudio export.json --test-videos jizda3 --val-videos jizda2
"""

from __future__ import annotations

import argparse
import csv
import json
import random
import re
import shutil
import unicodedata
from collections import defaultdict
from dataclasses import dataclass, field
from pathlib import Path
from urllib.parse import unquote

from src.dataset.rdd_to_yolo import place_file, write_data_yaml
from src.utils.config import load_config, repo_path

IMAGE_EXT = {".jpg", ".jpeg", ".png"}


def normalize(text: str) -> str:
    """„Rozbitý asfalt“ -> „rozbity_asfalt“ (bez diakritiky, malá písmena, podtržítka)."""
    text = unicodedata.normalize("NFKD", text).encode("ascii", "ignore").decode()
    return re.sub(r"[^a-z0-9]+", "_", text.lower()).strip("_")


# Synonyma názvů tříd, která se mohou objevit v anotacích -> naše interní jména
DETECTOR_ALIASES = {
    "dira": "dira", "diry": "dira", "vytluk": "dira", "pothole": "dira", "d40": "dira",
    "trhlina": "trhlina", "crack": "trhlina", "d00": "trhlina", "d10": "trhlina", "d20": "trhlina",
}
SURFACE_ALIASES = {
    "asfalt": "asfalt", "rozbity_asfalt": "rozbity_asfalt", "sterk": "sterk",
    "hlina_blato": "hlina_blato", "hlina": "hlina_blato", "blato": "hlina_blato", "mokro": "mokro",
}


@dataclass
class Box:
    cls: str
    xc: float  # střed a rozměry normalizované 0–1
    yc: float
    w: float
    h: float


@dataclass
class Item:
    image: Path
    boxes: list[Box] = field(default_factory=list)
    surface: str | None = None
    conditions: list[str] = field(default_factory=list)

    @property
    def video(self) -> str:
        """Jméno videa = název snímku bez koncového _<ms> (viz extract_frames)."""
        return re.sub(r"_\d+$", "", self.image.stem)


def index_images(root: Path) -> dict[str, Path]:
    """Slovník název souboru -> cesta pro všechny obrázky ve složce (rekurzivně)."""
    return {p.name: p for p in root.rglob("*") if p.suffix.lower() in IMAGE_EXT}


def load_labelstudio(json_path: Path, images: dict[str, Path]) -> list[Item]:
    """Načte „JSON“ export Label Studia (seznam úloh s anotacemi)."""
    tasks = json.loads(json_path.read_text(encoding="utf-8"))
    items = []
    for task in tasks:
        # Cesta k obrázku, např. "/data/local-files/?d=frames/jizda1/jizda1_00001000.jpg"
        # nebo "/data/upload/3/8f2a1c-jizda1_00001000.jpg" (Label Studio přidá prefix)
        name = Path(unquote(str(task["data"].get("image", "")))).name
        img = images.get(name) or images.get(name.split("-", 1)[-1])
        if img is None:
            print(f"[!] Obrázek {name} nenalezen – přeskakuji.")
            continue
        annotations = [a for a in task.get("annotations", []) if not a.get("was_cancelled")]
        if not annotations:
            continue
        item = Item(img)
        for r in annotations[-1].get("result", []):  # poslední = nejnovější anotace
            v = r.get("value", {})
            if r.get("type") == "rectanglelabels":
                cls = DETECTOR_ALIASES.get(normalize(v["rectanglelabels"][0]))
                if cls is None:
                    continue
                # Label Studio ukládá x, y (levý horní roh), šířku a výšku v procentech
                x, y, w, h = (v[k] / 100 for k in ("x", "y", "width", "height"))
                item.boxes.append(Box(cls, x + w / 2, y + h / 2, w, h))
            elif r.get("type") == "choices":
                values = [normalize(c) for c in v.get("choices", [])]
                if r.get("from_name") == "povrch" and values:
                    item.surface = SURFACE_ALIASES.get(values[0])
                elif r.get("from_name") == "podminky":
                    item.conditions = values
        items.append(item)
    return items


def load_yolo_export(export_dir: Path, images: dict[str, Path]) -> list[Item]:
    """Načte YOLO export (CVAT „YOLO 1.1“ / „Ultralytics YOLO“, Label Studio „YOLO“).
    Pořadí tříd v exportu se může lišit od našeho, proto přemapujeme podle názvů."""
    names: list[str] = []
    for fname in ("obj.names", "classes.txt"):
        f = next(export_dir.rglob(fname), None)
        if f:
            names = [ln.strip() for ln in f.read_text(encoding="utf-8").splitlines() if ln.strip()]
            break
    if not names:
        yml = next(export_dir.rglob("data.yaml"), None)
        if yml:
            import yaml

            raw = yaml.safe_load(yml.read_text(encoding="utf-8"))["names"]
            names = [raw[k] for k in sorted(raw)] if isinstance(raw, dict) else list(raw)
    if not names:
        raise SystemExit("V exportu chybí seznam tříd (obj.names / classes.txt / data.yaml).")

    # Obrázky mohou být i přímo v exportu (CVAT se „save images“)
    images = {**images, **index_images(export_dir)}
    items = []
    for txt in export_dir.rglob("*.txt"):
        if txt.name in {"classes.txt", "train.txt", "val.txt", "test.txt"}:
            continue
        img = next((images[txt.stem + e] for e in (".jpg", ".jpeg", ".png") if txt.stem + e in images), None)
        if img is None:
            continue
        item = Item(img)
        for ln in txt.read_text(encoding="utf-8").splitlines():
            parts = ln.split()
            if len(parts) != 5:
                continue
            cls = DETECTOR_ALIASES.get(normalize(names[int(parts[0])]))
            if cls:
                item.boxes.append(Box(cls, *map(float, parts[1:])))
        items.append(item)
    return items


def split_videos(
    items: list[Item], val_fraction: float, test_fraction: float, seed: int,
    val_videos: list[str] | None = None, test_videos: list[str] | None = None,
) -> dict[str, str]:
    """Přiřadí každé video do train/val/test. Ručně zadaná videa mají přednost,
    zbytek se rozdělí náhodně tak, aby podíl snímků odpovídal zlomkům."""
    counts: dict[str, int] = defaultdict(int)
    for it in items:
        counts[it.video] += 1
    assign = {v: "test" for v in test_videos or []} | {v: "val" for v in val_videos or []}
    rest = sorted(v for v in counts if v not in assign)
    random.Random(seed).shuffle(rest)
    total = sum(counts.values())
    target = {"test": test_fraction * total, "val": val_fraction * total}
    have = {p: sum(counts[v] for v, s in assign.items() if s == p) for p in ("test", "val")}
    for v in rest:
        # Hladové plnění: nejdřív test, pak val, zbytek do train
        part = next((p for p in ("test", "val") if have[p] < target[p]), "train")
        assign[v] = part
        if part != "train":
            have[part] += counts[v]
    # Trénovací část nesmí zůstat prázdná (málo videí) -> poslední video přesunout do train
    if rest and "train" not in assign.values():
        assign[rest[-1]] = "train"
    return assign


def export(items: list[Item], assign: dict[str, str], out_root: Path, det_classes: list[str], mode: str) -> Path:
    """Zapíše detekční dataset, klasifikační dataset a metadata."""
    det_dir, cls_dir = out_root / "custom_yolo", out_root / "custom_cls"
    for d in (det_dir, cls_dir):  # vždy znovu vytvořit – anotace se mohly změnit
        if d.exists():
            shutil.rmtree(d)
    for part in ("train", "val", "test"):
        (det_dir / "images" / part).mkdir(parents=True)
        (det_dir / "labels" / part).mkdir(parents=True)
    ids = {c: i for i, c in enumerate(det_classes)}

    meta_path = out_root / "custom_meta.csv"
    with open(meta_path, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["soubor", "video", "cast", "povrch", "podminky", *[f"pocet_{c}" for c in det_classes]])
        for it in items:
            part = assign[it.video]
            place_file(it.image, det_dir / "images" / part / it.image.name, mode)
            lines = [f"{ids[b.cls]} {b.xc:.6f} {b.yc:.6f} {b.w:.6f} {b.h:.6f}" for b in it.boxes]
            (det_dir / "labels" / part / f"{it.image.stem}.txt").write_text(
                "\n".join(lines) + ("\n" if lines else ""), encoding="utf-8"
            )
            if it.surface:
                (cls_dir / part / it.surface).mkdir(parents=True, exist_ok=True)
                place_file(it.image, cls_dir / part / it.surface / it.image.name, mode)
            w.writerow([it.image.name, it.video, part, it.surface or "", "|".join(it.conditions),
                        *[sum(b.cls == c for b in it.boxes) for c in det_classes]])

    write_data_yaml(det_dir, det_classes)
    with open(det_dir / "data.yaml", "a", encoding="utf-8") as f:
        f.write("test: images/test\n")
    return meta_path


def main(argv: list[str] | None = None) -> None:
    cfg = load_config()
    parser = argparse.ArgumentParser(description="Import vlastních anotací do tréninkových formátů")
    parser.add_argument("format", choices=["labelstudio", "yolo"])
    parser.add_argument("source", help="JSON export Label Studia nebo složka s YOLO exportem")
    parser.add_argument("--images", default=str(repo_path(cfg["paths"]["custom"]) / "frames"))
    parser.add_argument("--out", default=str(repo_path(cfg["paths"]["processed"])))
    parser.add_argument("--val-fraction", type=float, default=0.15)
    parser.add_argument("--test-fraction", type=float, default=0.25)
    parser.add_argument("--val-videos", nargs="*")
    parser.add_argument("--test-videos", nargs="*")
    parser.add_argument("--mode", choices=["link", "symlink", "copy"], default="copy")
    args = parser.parse_args(argv)

    images = index_images(Path(args.images))
    src = Path(args.source)
    items = load_labelstudio(src, images) if args.format == "labelstudio" else load_yolo_export(src, images)
    if not items:
        raise SystemExit("Nenačetl jsem žádné anotované snímky.")

    assign = split_videos(items, args.val_fraction, args.test_fraction, cfg["seed"], args.val_videos, args.test_videos)
    meta = export(items, assign, Path(args.out), cfg["detector"]["classes"], args.mode)

    print(f"[✓] {len(items)} snímků z {len(assign)} videí. Metadata: {meta}")
    for part in ("train", "val", "test"):
        vids = sorted(v for v, p in assign.items() if p == part)
        n = sum(1 for it in items if assign[it.video] == part)
        print(f"    {part:5s} {n:5d} snímků  videa: {', '.join(vids) or '-'}")


if __name__ == "__main__":
    main()
