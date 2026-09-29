"""Export modelů do TensorFlow Lite (pro aplikaci v telefonu) + test rychlosti.

    python -m src.demo.export_tflite surface models/surface_finetuned/best.pt
    python -m src.demo.export_tflite detector models/det_finetuned/weights/best.pt
    python -m src.demo.export_tflite bench models/export/surface.tflite

Výsledné soubory (models/export/*.tflite) zkopíruj do telefonu a v aplikaci je načti
přes menu ☰ -> „Načíst model…“.

Formát, který aplikace čeká (Models.java):
  povrch:   vstup [1, H, W, 3] float32 RGB 0–1, výstup [1, 5] pravděpodobnosti
  detektor: vstup [1, S, S, 3] float32 RGB 0–1, výstup [1, 4+nc, N] (ultralytics)
Názvy tříd jsou v metadata.json v ZIPu připojeném na konec souboru (jako u ultralytics).
"""

from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
import tempfile
import time
import zipfile
from pathlib import Path

import numpy as np

from src.utils.config import load_config, repo_path


def add_metadata(tflite: Path, meta: dict) -> None:
    """Připojí metadata.json jako ZIP na konec .tflite (TFLite interpret to ignoruje)."""
    with zipfile.ZipFile(tflite, "a", zipfile.ZIP_DEFLATED) as zf:
        zf.writestr("metadata.json", json.dumps(meta, ensure_ascii=False))


def read_metadata(tflite: Path) -> dict | None:
    try:
        with zipfile.ZipFile(tflite) as zf:
            return json.loads(zf.read("metadata.json"))
    except (zipfile.BadZipFile, KeyError):
        return None


def export_surface(checkpoint: str, out_dir: Path) -> Path:
    """PyTorch -> ONNX -> TFLite (onnx2tf převede i rozložení NCHW -> NHWC)."""
    import torch

    from src.models.surface import ProbsWrapper, load_checkpoint

    model, ck = load_checkpoint(checkpoint)
    wrapped = ProbsWrapper(model).eval()
    size = ck["img_size"]
    out_dir.mkdir(parents=True, exist_ok=True)
    dst = out_dir / "surface.tflite"
    with tempfile.TemporaryDirectory() as tmp:
        onnx = Path(tmp) / "surface.onnx"
        torch.onnx.export(wrapped, torch.rand(1, 3, size, size), str(onnx), input_names=["image"],
                          output_names=["probs"], opset_version=17, dynamo=False)
        _onnx_to_tflite(onnx, dst)
    add_metadata(dst, {"task": "classify", "names": {i: c for i, c in enumerate(ck["classes"])},
                       "imgsz": [size, size], "arch": ck["arch"]})
    print(f"[✓] {dst} ({dst.stat().st_size / 1e6:.1f} MB)")
    return dst


def _onnx_to_tflite(onnx: Path, dst: Path) -> None:
    """ONNX -> TFLite float32 přes onnx2tf (převede NCHW na NHWC, výstupy nechá)."""
    with tempfile.TemporaryDirectory() as tmp:
        # onnx2tf si jinak stahuje vzorová data z GitHubu (jen pro kontrolu přesnosti) -> dodáme vlastní
        np.save(Path(tmp) / "calibration_image_sample_data_20x128x128x3_float32.npy",
                np.random.rand(20, 128, 128, 3).astype(np.float32))
        r = subprocess.run([sys.executable, "-m", "onnx2tf", "-i", str(onnx.resolve()), "-o", str(Path(tmp) / "tf"),
                            "-n"], cwd=tmp, capture_output=True, text=True)
        if r.returncode:
            print(r.stdout[-3000:], r.stderr[-3000:])
            raise SystemExit("onnx2tf selhal")
        shutil.copy(next((Path(tmp) / "tf").glob("*_float32.tflite")), dst)


def export_detector(weights: str, out_dir: Path, imgsz: int = 640) -> Path:
    """ultralytics -> ONNX -> onnx2tf -> TFLite float32. Výstup [1, 4+nc, N] (souřadnice v px vstupu).

    Pozn.: přímý `export(format="tflite")` v ultralytics 8.4 používá litert-torch, který není
    kompatibilní se všemi verzemi PyTorch – cesta přes ONNX je spolehlivější.
    """
    from ultralytics import YOLO

    model = YOLO(weights)
    onnx = Path(model.export(format="onnx", imgsz=imgsz, simplify=True, opset=17))
    out_dir.mkdir(parents=True, exist_ok=True)
    dst = out_dir / "detector.tflite"
    _onnx_to_tflite(onnx, dst)
    add_metadata(dst, {"task": "detect", "names": {int(k): v for k, v in model.names.items()},
                       "imgsz": [imgsz, imgsz]})
    print(f"[✓] {dst} ({dst.stat().st_size / 1e6:.1f} MB)")
    return dst


def interpreter(path: Path):
    try:
        from ai_edge_litert.interpreter import Interpreter
    except ImportError:  # starší prostředí
        from tensorflow.lite import Interpreter
    it = Interpreter(model_path=str(path), num_threads=4)
    it.allocate_tensors()
    return it


def check_app_format(path: Path) -> dict:
    """Ověří, že model má tvar, který aplikace umí načíst (viz Models.java)."""
    it = interpreter(path)
    inp, out = it.get_input_details()[0], it.get_output_details()[0]
    ishape, oshape = [int(v) for v in inp["shape"]], [int(v) for v in out["shape"]]
    meta = read_metadata(path) or {}
    ok = len(ishape) == 4 and ishape[3] == 3 and out["dtype"] == np.float32
    kind = "povrch" if len(oshape) == 2 else "detektor"
    if kind == "povrch":
        ok = ok and oshape[-1] == 5
    else:
        ok = ok and len(oshape) == 3 and ishape[1] == ishape[2]
    info = {"typ": kind, "vstup": ishape, "vystup": oshape, "tridy": meta.get("names"), "ok": bool(ok)}
    print(("[✓] " if ok else "[✗] ") + json.dumps(info, ensure_ascii=False, default=str))
    return info


def benchmark(path: Path, runs: int = 50) -> dict:
    """Průměrná doba inference na tomto počítači (na telefonu ukazuje FPS přímo aplikace)."""
    it = interpreter(path)
    inp = it.get_input_details()[0]
    x = np.random.rand(*inp["shape"]).astype(inp["dtype"])
    for _ in range(5):
        it.set_tensor(inp["index"], x)
        it.invoke()
    t0 = time.perf_counter()
    for _ in range(runs):
        it.set_tensor(inp["index"], x)
        it.invoke()
    ms = (time.perf_counter() - t0) / runs * 1000
    res = {"model": path.name, "ms": round(ms, 2), "fps": round(1000 / ms, 1), "velikost_mb": round(path.stat().st_size / 1e6, 2)}
    print(res)
    return res


def main(argv=None):
    p = argparse.ArgumentParser()
    p.add_argument("what", choices=["surface", "detector", "bench", "check"])
    p.add_argument("path")
    p.add_argument("--out", default=None)
    p.add_argument("--imgsz", type=int, default=640)
    a = p.parse_args(argv)
    out = Path(a.out) if a.out else repo_path(load_config()["paths"]["models"]) / "export"
    if a.what == "surface":
        check_app_format(export_surface(a.path, out))
    elif a.what == "detector":
        check_app_format(export_detector(a.path, out, a.imgsz))
    elif a.what == "check":
        check_app_format(Path(a.path))
    else:
        r = benchmark(Path(a.path))
        tables = repo_path(load_config()["paths"]["results"]) / "tables"
        tables.mkdir(parents=True, exist_ok=True)
        f = tables / "tflite_rychlost.csv"
        import pandas as pd

        df = pd.concat([pd.read_csv(f), pd.DataFrame([r])]) if f.exists() else pd.DataFrame([r])
        df.drop_duplicates("model", keep="last").to_csv(f, index=False)


if __name__ == "__main__":
    main()
