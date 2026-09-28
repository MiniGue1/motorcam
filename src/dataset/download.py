"""Stažení veřejných datasetů RDD2022 (poškození silnic) a RSCD (povrchy silnic).

Použití (z kořene repozitáře):

    # RDD2022 – výchozí země z configu (~2,4 GB)
    python -m src.dataset.download rdd2022

    # jen vybrané země
    python -m src.dataset.download rdd2022 --countries Czech China_MotorBike

    # RSCD – odkaz (Google Drive / přímé URL) nebo už stažený archiv
    python -m src.dataset.download rscd --url "https://drive.google.com/..."
    python -m src.dataset.download rscd --archive ~/Downloads/RSCD.zip

Před stažením víc než 5 GB se skript zeptá (přeskočit lze přepínačem --yes).
Stahování umí navázat na přerušený přenos (HTTP Range).
"""

from __future__ import annotations

import argparse
import sys
import zipfile
from pathlib import Path

import requests
from tqdm import tqdm

from src.utils.config import load_config, repo_path

# Velikosti archivů podle README sekilab/RoadDamageDetector (záloha, když server nevrátí Content-Length)
RDD2022_KNOWN_SIZES_MB = {
    "Japan": 1023,
    "India": 502,
    "Czech": 245,
    "Norway": 9900,
    "United_States": 424,
    "China_MotorBike": 183,
    "China_Drone": 153,
    "FULL": 12360,
}

ASK_LIMIT_GB = 5.0
CHUNK = 1 << 20  # 1 MB


def rdd2022_url(base_url: str, country: str) -> str:
    """Vrátí URL archivu RDD2022 pro danou zemi (nebo celý dataset pro 'FULL')."""
    if country == "FULL":
        return f"{base_url}/RDD2022.zip"
    return f"{base_url}/Country_Specific_Data_CRDDC2022/RDD2022_{country}.zip"


def remote_size(url: str) -> int | None:
    """Zjistí velikost souboru na serveru (bajty) pomocí HEAD požadavku."""
    try:
        r = requests.head(url, allow_redirects=True, timeout=20)
        if r.ok and "Content-Length" in r.headers:
            return int(r.headers["Content-Length"])
    except requests.RequestException:
        pass
    return None


def confirm_large(total_bytes: int, assume_yes: bool) -> bool:
    """Zeptá se uživatele, pokud stahování přesahuje ASK_LIMIT_GB."""
    gb = total_bytes / 1e9
    if gb <= ASK_LIMIT_GB or assume_yes:
        return True
    if not sys.stdin.isatty():
        print(f"[!] Stahování má {gb:.1f} GB (> {ASK_LIMIT_GB} GB). Spusť znovu s --yes, pokud opravdu chceš.")
        return False
    answer = input(f"Stahování má {gb:.1f} GB. Pokračovat? [a/N] ").strip().lower()
    return answer in {"a", "ano", "y", "yes"}


def download_file(url: str, dest: Path) -> Path:
    """Stáhne soubor s progress barem; když už část existuje, naváže (Range)."""
    dest.parent.mkdir(parents=True, exist_ok=True)
    done = dest.stat().st_size if dest.exists() else 0
    total = remote_size(url)
    if total is not None and done == total:
        print(f"[=] {dest.name} už je stažený.")
        return dest

    headers = {"Range": f"bytes={done}-"} if done else {}
    with requests.get(url, headers=headers, stream=True, timeout=60) as r:
        r.raise_for_status()
        # Server Range nepodporuje -> začneme znovu od nuly
        mode = "ab" if r.status_code == 206 else "wb"
        if mode == "wb":
            done = 0
        with open(dest, mode) as f, tqdm(
            total=total, initial=done, unit="B", unit_scale=True, desc=dest.name
        ) as bar:
            for chunk in r.iter_content(CHUNK):
                f.write(chunk)
                bar.update(len(chunk))
    return dest


def extract_zip(archive: Path, out_dir: Path) -> None:
    """Rozbalí ZIP archiv (přeskočí, pokud existuje značka hotového rozbalení)."""
    marker = out_dir / f".extracted_{archive.stem}"
    if marker.exists():
        print(f"[=] {archive.name} už je rozbalený.")
        return
    print(f"[>] Rozbaluji {archive.name} -> {out_dir}")
    with zipfile.ZipFile(archive) as zf:
        for member in tqdm(zf.infolist(), desc="rozbalování", unit="soubor"):
            zf.extract(member, out_dir)
    marker.touch()


def extract_nested(raw_dir: Path) -> None:
    """Rozbalí ZIPy uvnitř už rozbalených dat (celý RDD2022.zip obsahuje archivy zemí)."""
    for inner in sorted(raw_dir.rglob("*.zip")):
        if "archives" not in inner.parts:
            extract_zip(inner, inner.parent)


def cmd_rdd2022(args: argparse.Namespace, cfg: dict) -> None:
    rcfg = cfg["rdd2022"]
    countries = ["FULL"] if args.full else (args.countries or rcfg["countries"])
    raw_dir = repo_path(cfg["paths"]["raw"]) / "rdd2022"
    urls = {c: rdd2022_url(rcfg["base_url"], c) for c in countries}

    # Odhad celkové velikosti – nejdřív ze serveru, jinak ze známé tabulky
    total = 0
    for c, url in urls.items():
        size = remote_size(url) or RDD2022_KNOWN_SIZES_MB.get(c, 0) * 1_000_000
        print(f"  {c:16s} {size / 1e6:8.0f} MB  {url}")
        total += size
    print(f"  {'CELKEM':16s} {total / 1e6:8.0f} MB")
    if not confirm_large(total, args.yes):
        return

    for c, url in urls.items():
        archive = download_file(url, raw_dir / "archives" / Path(url).name)
        if not args.no_extract:
            extract_zip(archive, raw_dir)
    if not args.no_extract:
        extract_nested(raw_dir)
    print(f"[✓] RDD2022 připraven v {raw_dir}")


def cmd_rscd(args: argparse.Namespace, cfg: dict) -> None:
    raw_dir = repo_path(cfg["paths"]["raw"]) / "rscd"
    if args.archive:
        archive = Path(args.archive).expanduser()
    elif args.url:
        if "drive.google.com" in args.url:
            import gdown  # importujeme až tady – potřeba jen pro Google Drive

            archive = raw_dir / "archives" / "rscd.zip"
            archive.parent.mkdir(parents=True, exist_ok=True)
            gdown.download(args.url, str(archive), quiet=False, fuzzy=True, resume=True)
        else:
            size = remote_size(args.url)
            if size is not None and not confirm_large(size, args.yes):
                return
            archive = download_file(args.url, raw_dir / "archives" / Path(args.url).name)
    else:
        print(
            "RSCD se stahuje z oficiálního webu https://thu-rsxd.com/rscd/ (odkazy na Google Drive / Baidu).\n"
            "Zkopíruj odkaz a spusť:  python -m src.dataset.download rscd --url '<odkaz>'\n"
            "nebo archiv stáhni ručně a spusť:  python -m src.dataset.download rscd --archive <cesta.zip>\n"
            "Celý RSCD má ~1 mil. snímků – pokud je nabídnuta menší verze, stačí na tuto práci."
        )
        return
    extract_zip(archive, raw_dir)
    print(f"[✓] RSCD rozbalen v {raw_dir}")


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description="Stažení datasetů RDD2022 a RSCD")
    parser.add_argument("--config", default=None, help="cesta ke config.yaml")
    sub = parser.add_subparsers(dest="dataset", required=True)

    p = sub.add_parser("rdd2022", help="Road Damage Dataset 2022")
    p.add_argument("--countries", nargs="+", choices=[k for k in RDD2022_KNOWN_SIZES_MB if k != "FULL"])
    p.add_argument("--full", action="store_true", help="celý RDD2022.zip (12,4 GB)")
    p.add_argument("--no-extract", action="store_true", help="jen stáhnout, nerozbalovat")
    p.add_argument("--yes", action="store_true", help="neptat se u velkých stahování")

    p = sub.add_parser("rscd", help="Road Surface Classification Dataset")
    p.add_argument("--url", help="odkaz na archiv (Google Drive nebo přímé URL)")
    p.add_argument("--archive", help="cesta k už staženému archivu .zip")
    p.add_argument("--yes", action="store_true", help="neptat se u velkých stahování")

    args = parser.parse_args(argv)
    cfg = load_config(args.config)
    {"rdd2022": cmd_rdd2022, "rscd": cmd_rscd}[args.dataset](args, cfg)


if __name__ == "__main__":
    main()
