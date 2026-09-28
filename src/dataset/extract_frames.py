"""Vytažení snímků z vlastních videí pro anotaci.

Ze vstupního videa (nebo složky s videi) uloží snímky s danou frekvencí (výchozí 2 FPS)
a přeskočí snímky, které se pro anotaci nehodí:
  * rozmazané  – malý rozptyl Laplaciánu (typicky otřesy, prudké zatáčení),
  * duplicitní – téměř stejné jako předchozí uložený snímek (stání na semaforu).

Výstup:
    data/custom/frames/<video>/<video>_<ms>.jpg   – <ms> = čas ve videu v milisekundách
    data/custom/frames/frames.csv                 – čas každého snímku (pro spárování s GPS/CSV)

Použití:
    python -m src.dataset.extract_frames data/custom/videos/jizda1.mp4
    python -m src.dataset.extract_frames data/custom/videos/ --fps 1 --blur 40
"""

from __future__ import annotations

import argparse
import csv
from pathlib import Path

import cv2
import numpy as np
from tqdm import tqdm

from src.utils.config import load_config, repo_path

VIDEO_EXT = {".mp4", ".mov", ".avi", ".mkv", ".m4v"}


def sharpness(gray: np.ndarray) -> float:
    """Míra ostrosti = rozptyl Laplaciánu (čím víc hran, tím ostřejší snímek)."""
    return float(cv2.Laplacian(gray, cv2.CV_64F).var())


def dhash(gray: np.ndarray, size: int = 8) -> int:
    """Rozdílový hash (dHash) – 64bitový otisk snímku pro hledání duplicit."""
    small = cv2.resize(gray, (size + 1, size), interpolation=cv2.INTER_AREA)
    bits = (small[:, 1:] > small[:, :-1]).flatten()
    return int(sum(1 << i for i, b in enumerate(bits) if b))


def hamming(a: int, b: int) -> int:
    """Počet rozdílných bitů mezi dvěma hashi."""
    return bin(a ^ b).count("1")


def resize_max_side(img: np.ndarray, max_side: int) -> np.ndarray:
    """Zmenší obrázek tak, aby delší strana měla max. `max_side` px."""
    h, w = img.shape[:2]
    scale = max_side / max(h, w)
    if scale >= 1:
        return img
    return cv2.resize(img, (round(w * scale), round(h * scale)), interpolation=cv2.INTER_AREA)


def extract_video(
    video: Path,
    out_root: Path,
    fps: float = 2.0,
    blur_threshold: float = 60.0,
    dedup_threshold: int = 4,
    max_side: int = 1280,
    start_s: float = 0.0,
    end_s: float | None = None,
) -> list[dict]:
    """Zpracuje jedno video. Vrací seznam záznamů (řádky do frames.csv)."""
    cap = cv2.VideoCapture(str(video))
    if not cap.isOpened():
        raise RuntimeError(f"Video nelze otevřít: {video}")
    video_fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
    n_frames = int(cap.get(cv2.CAP_PROP_FRAME_COUNT)) or None

    out_dir = out_root / video.stem
    out_dir.mkdir(parents=True, exist_ok=True)
    step = 1.0 / fps
    next_t = start_s
    last_hash: int | None = None
    records = []

    idx = -1
    with tqdm(total=n_frames, desc=video.name, unit="snímek") as bar:
        while True:
            # grab() jen posune video (rychlé); retrieve() dekóduje jen snímky, které chceme
            if not cap.grab():
                break
            idx += 1
            bar.update(1)
            t = idx / video_fps
            if end_s is not None and t > end_s:
                break
            if t + 1e-6 < next_t:
                continue
            next_t += step
            ok, frame = cap.retrieve()
            if not ok:
                continue

            gray = cv2.cvtColor(resize_max_side(frame, 640), cv2.COLOR_BGR2GRAY)
            sharp = sharpness(gray)
            h = dhash(gray)
            reason = ""
            if sharp < blur_threshold:
                reason = "rozmazany"
            elif last_hash is not None and hamming(h, last_hash) <= dedup_threshold:
                reason = "duplicita"

            name = f"{video.stem}_{round(t * 1000):08d}.jpg"
            if not reason:
                cv2.imwrite(str(out_dir / name), resize_max_side(frame, max_side), [cv2.IMWRITE_JPEG_QUALITY, 92])
                last_hash = h
            records.append(
                {
                    "soubor": f"{video.stem}/{name}" if not reason else "",
                    "video": video.name,
                    "snimek": idx,
                    "cas_s": round(t, 3),
                    "ostrost": round(sharp, 1),
                    "preskoceno": reason,
                }
            )
    cap.release()
    return records


def main(argv: list[str] | None = None) -> None:
    cfg = load_config()
    fcfg = cfg["frames"]
    parser = argparse.ArgumentParser(description="Vytažení snímků z videí pro anotaci")
    parser.add_argument("input", help="video nebo složka s videi")
    parser.add_argument("--out", default=str(repo_path(cfg["paths"]["custom"]) / "frames"))
    parser.add_argument("--fps", type=float, default=fcfg["fps"])
    parser.add_argument("--blur", type=float, default=fcfg["blur_threshold"], help="0 = nefiltrovat")
    parser.add_argument("--dedup", type=int, default=fcfg["dedup_threshold"], help="-1 = nefiltrovat")
    parser.add_argument("--max-side", type=int, default=fcfg["max_side"])
    parser.add_argument("--start", type=float, default=0.0, help="začátek (s)")
    parser.add_argument("--end", type=float, default=None, help="konec (s)")
    args = parser.parse_args(argv)

    src = Path(args.input)
    videos = sorted(p for p in src.iterdir() if p.suffix.lower() in VIDEO_EXT) if src.is_dir() else [src]
    if not videos:
        raise SystemExit(f"Nenalezeno žádné video v {src}")

    out_root = Path(args.out)
    all_records = []
    for v in videos:
        all_records += extract_video(
            v, out_root, args.fps, args.blur, args.dedup, args.max_side, args.start, args.end
        )

    # frames.csv doplňujeme – přepíšeme jen řádky zpracovaných videí
    csv_path = out_root / "frames.csv"
    fields = ["soubor", "video", "snimek", "cas_s", "ostrost", "preskoceno"]
    names = {v.name for v in videos}
    old = []
    if csv_path.exists():
        with open(csv_path, encoding="utf-8") as f:
            old = [r for r in csv.DictReader(f) if r["video"] not in names]
    with open(csv_path, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=fields)
        w.writeheader()
        w.writerows(old + all_records)

    saved = sum(1 for r in all_records if not r["preskoceno"])
    blurred = sum(1 for r in all_records if r["preskoceno"] == "rozmazany")
    dup = sum(1 for r in all_records if r["preskoceno"] == "duplicita")
    print(f"[✓] Uloženo {saved} snímků (přeskočeno: {blurred} rozmazaných, {dup} duplicit) -> {out_root}")


if __name__ == "__main__":
    main()
