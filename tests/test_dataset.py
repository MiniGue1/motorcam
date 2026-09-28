"""Testy datových skriptů na malých syntetických datech (bez stahování)."""

from __future__ import annotations

import json
from pathlib import Path

import cv2
import numpy as np
import pytest

from src.dataset import extract_frames, import_annotations, rdd_to_yolo, rscd_to_classes

CLASS_MAP = {"D40": "dira", "D00": "trhlina", "D10": "trhlina", "D20": "trhlina"}
DET_CLASSES = ["dira", "trhlina"]


def _write_img(path: Path, w: int = 200, h: int = 100, seed: int = 0) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    rng = np.random.default_rng(seed)
    cv2.imwrite(str(path), rng.integers(0, 255, (h, w, 3), dtype=np.uint8))


def _write_voc(path: Path, filename: str, objects: list[tuple], w: int = 200, h: int = 100) -> None:
    objs = "".join(
        f"<object><name>{n}</name><bndbox><xmin>{a}</xmin><ymin>{b}</ymin>"
        f"<xmax>{c}</xmax><ymax>{d}</ymax></bndbox></object>"
        for n, a, b, c, d in objects
    )
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        f"<annotation><filename>{filename}</filename><size><width>{w}</width>"
        f"<height>{h}</height></size>{objs}</annotation>"
    )


# ------------------------------------------------------------------ RDD2022 -> YOLO
def test_rdd_to_yolo(tmp_path: Path) -> None:
    raw = tmp_path / "raw"
    for i in range(10):
        name = f"Czech_{i:06d}"
        _write_img(raw / "Czech/train/images" / f"{name}.jpg", seed=i)
        _write_voc(
            raw / "Czech/train/annotations/xmls" / f"{name}.xml",
            f"{name}.jpg",
            # díra, trhlina, neznámá třída, příliš malý box, box přesahující obrázek
            [("D40", 10, 10, 50, 50), ("D00", 60, 20, 100, 90), ("Repair", 0, 0, 20, 20),
             ("D40", 5, 5, 8, 8), ("D20", 150, 50, 250, 120)],
        )
    out = tmp_path / "yolo"
    res = rdd_to_yolo.convert(raw, out, CLASS_MAP, DET_CLASSES, val_fraction=0.2, min_box_px=8, file_mode="copy")

    assert len(list((out / "images/train").glob("*.jpg"))) == 8
    assert len(list((out / "images/val").glob("*.jpg"))) == 2
    assert res["skipped"]["Repair"] == 10 and res["skipped"]["_maly_box"] == 10

    lines = (out / "labels/train").glob("*.txt").__next__().read_text().split("\n")
    first = [float(x) for x in lines[0].split()]
    # D40 box 10..50 x 10..50 v obrázku 200x100
    assert first == pytest.approx([0, 30 / 200, 30 / 100, 40 / 200, 40 / 100], abs=1e-5)
    # Oříznutý box: x 150..200, y 50..100
    last = [float(x) for x in lines[2].split()]
    assert last == pytest.approx([1, 175 / 200, 75 / 100, 50 / 200, 50 / 100], abs=1e-5)
    assert "0: dira" in (out / "data.yaml").read_text()


def test_country_of() -> None:
    assert rdd_to_yolo.country_of("China_MotorBike_000001.jpg") == "China_MotorBike"
    assert rdd_to_yolo.country_of("United_States_000001.jpg") == "United_States"


# ------------------------------------------------------------------ RSCD -> 5 tříd
@pytest.mark.parametrize(
    "text, expected",
    [
        ("dry_asphalt_smooth", "asfalt"),
        ("dry-concrete-slight", "asfalt"),
        ("dry_asphalt_severe", "rozbity_asfalt"),
        ("wet_asphalt_smooth", "mokro"),
        ("water_concrete_severe", "mokro"),
        ("dry_gravel", "sterk"),
        ("wet_gravel", "sterk"),
        ("water_mud", "hlina_blato"),
        ("fresh_snow", None),
        ("ice", None),
        ("20220312-120000-dry-asphalt-smooth", "asfalt"),
    ],
)
def test_rscd_mapping(text: str, expected: str | None) -> None:
    lab = rscd_to_classes.parse_rscd_label(text)
    assert lab is not None
    assert rscd_to_classes.map_rscd_label(*lab) == expected


def test_rscd_slight_config() -> None:
    lab = rscd_to_classes.parse_rscd_label("dry_asphalt_slight")
    assert rscd_to_classes.map_rscd_label(*lab, slight_is_damaged=True) == "rozbity_asfalt"


def test_rscd_convert_original_split(tmp_path: Path) -> None:
    raw = tmp_path / "rscd"
    i = 0
    for cls in ["dry_asphalt_smooth", "dry_concrete_smooth", "wet_gravel", "ice"]:
        for _ in range(20):
            _write_img(raw / "train" / cls / f"{i}.jpg", 36, 24, seed=i)
            i += 1
    for cls in ["dry-asphalt-smooth", "wet-gravel"]:
        for _ in range(5):
            _write_img(raw / "test_50k" / f"2022-{i}-{cls}.jpg", 36, 24, seed=i)
            i += 1
    out = tmp_path / "cls"
    counts = rscd_to_classes.convert(raw, out, max_per_class=20, val_fraction=0.1, test_fraction=0.2, file_mode="copy")

    # train limit = 20 * 0.7 = 14, z toho část přesunuta do val; test limit = 4
    assert counts[("test", "asfalt")] == 4 and counts[("test", "sterk")] == 4
    assert counts[("train", "asfalt")] + counts[("val", "asfalt")] == 14
    assert counts[("val", "asfalt")] > 0
    assert not (out / "train" / "ice").exists()
    # Třída asfalt obsahuje asfalt i beton (vyvážený výběr)
    names = [p.name for p in (out / "train" / "asfalt").iterdir()] + [p.name for p in (out / "val" / "asfalt").iterdir()]
    assert any(n.startswith("dry_concrete") for n in names) and any(n.startswith("dry_asphalt") for n in names)


# ------------------------------------------------------------------ snímky z videa
def _make_video(path: Path, seconds: int = 5, fps: int = 10) -> None:
    writer = cv2.VideoWriter(str(path), cv2.VideoWriter_fourcc(*"MJPG"), fps, (160, 120))
    rng = np.random.default_rng(1)
    for i in range(seconds * fps):
        t = i / fps
        if t < 2:     # 0–2 s: ostrá, měnící se scéna
            frame = rng.integers(0, 255, (120, 160, 3), dtype=np.uint8)
        elif t < 4:   # 2–4 s: stání – stále stejný snímek
            frame = np.tile(np.arange(160, dtype=np.uint8), (120, 1))[..., None].repeat(3, 2)
            frame = cv2.rectangle(frame.copy(), (40, 30), (120, 90), (0, 0, 255), 3)
        else:         # 4–5 s: jednobarevný (neostrý) snímek
            frame = np.full((120, 160, 3), 128, np.uint8)
        writer.write(frame)
    writer.release()


def test_extract_frames(tmp_path: Path) -> None:
    video = tmp_path / "jizda1.avi"
    _make_video(video)
    recs = extract_frames.extract_video(video, tmp_path / "frames", fps=2, blur_threshold=10, dedup_threshold=4)
    assert len(recs) == 10  # 5 s × 2 FPS
    reasons = [r["preskoceno"] for r in recs]
    assert reasons[:5] == [""] * 5          # 0,0.5,...,2.0 s – ostré, různé
    assert reasons[5:8] == ["duplicita"] * 3  # stání
    assert reasons[8:] == ["rozmazany"] * 2
    saved = sorted(p.name for p in (tmp_path / "frames/jizda1").iterdir())
    assert saved[0] == "jizda1_00000000.jpg" and saved[1] == "jizda1_00000500.jpg"


# ------------------------------------------------------------------ import anotací
def test_import_labelstudio(tmp_path: Path) -> None:
    frames = tmp_path / "frames"
    tasks = []
    for v in ["jizda1", "jizda2", "jizda3", "jizda4"]:
        for k in range(5):
            name = f"{v}_{k * 500:08d}.jpg"
            _write_img(frames / v / name, seed=k)
            tasks.append({
                "data": {"image": f"/data/upload/1/ab12cd34-{name}"},
                "annotations": [{"was_cancelled": False, "result": [
                    {"type": "rectanglelabels", "from_name": "objekty",
                     "value": {"x": 10, "y": 20, "width": 30, "height": 40, "rectanglelabels": ["díra"]}},
                    {"type": "choices", "from_name": "povrch", "value": {"choices": ["Rozbitý asfalt"]}},
                    {"type": "choices", "from_name": "podminky", "value": {"choices": ["stín", "noc"]}},
                ]}],
            })
    export_json = tmp_path / "export.json"
    export_json.write_text(json.dumps(tasks, ensure_ascii=False), encoding="utf-8")

    images = import_annotations.index_images(frames)
    items = import_annotations.load_labelstudio(export_json, images)
    assert len(items) == 20 and items[0].surface == "rozbity_asfalt" and items[0].conditions == ["stin", "noc"]
    b = items[0].boxes[0]
    assert (b.cls, b.xc, b.yc, b.w, b.h) == pytest.approx(("dira", 0.25, 0.4, 0.3, 0.4))

    assign = import_annotations.split_videos(items, 0.25, 0.25, seed=0, test_videos=["jizda4"])
    assert assign["jizda4"] == "test" and "train" in assign.values()
    # Žádné video není rozdělené mezi víc částí
    assert len(assign) == 4

    out = tmp_path / "processed"
    import_annotations.export(items, assign, out, DET_CLASSES, mode="copy")
    assert len(list((out / "custom_cls/test/rozbity_asfalt").iterdir())) == 5
    assert (out / "custom_yolo/labels/test/jizda4_00000000.txt").read_text().startswith("0 0.25")


def test_import_yolo_remaps_classes(tmp_path: Path) -> None:
    exp = tmp_path / "cvat"
    (exp / "obj_train_data").mkdir(parents=True)
    (exp / "obj.names").write_text("trhlina\ndíra\n", encoding="utf-8")  # jiné pořadí než naše
    _write_img(exp / "obj_train_data" / "jizda1_00000000.jpg")
    (exp / "obj_train_data" / "jizda1_00000000.txt").write_text("1 0.5 0.5 0.2 0.2\n0 0.1 0.1 0.1 0.1\n")
    items = import_annotations.load_yolo_export(exp, {})
    assert [b.cls for b in items[0].boxes] == ["dira", "trhlina"]


# ------------------------------------------------------------------ stahování (bez sítě)
def test_confirm_large_non_interactive(monkeypatch) -> None:
    from src.dataset import download

    monkeypatch.setattr("sys.stdin.isatty", lambda: False)
    assert download.confirm_large(2_000_000_000, assume_yes=False)       # 2 GB – bez ptaní
    assert not download.confirm_large(9_900_000_000, assume_yes=False)   # 9,9 GB – odmítne
    assert download.confirm_large(9_900_000_000, assume_yes=True)


def test_extract_nested_zip(tmp_path: Path) -> None:
    import zipfile

    from src.dataset import download

    inner = tmp_path / "RDD2022_Czech.zip"
    with zipfile.ZipFile(inner, "w") as zf:
        zf.writestr("Czech/train/images/Czech_000001.jpg", b"x")
    outer = tmp_path / "archives" / "RDD2022.zip"
    outer.parent.mkdir()
    with zipfile.ZipFile(outer, "w") as zf:
        zf.write(inner, "RDD2022_all/RDD2022_Czech.zip")
    raw = tmp_path / "raw"
    download.extract_zip(outer, raw)
    download.extract_nested(raw)
    assert (raw / "RDD2022_all/Czech/train/images/Czech_000001.jpg").exists()
