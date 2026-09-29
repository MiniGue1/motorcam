"""Sestavení APK aplikace MotorCam bez Android SDK / Gradle.

Potřeba jen: JDK 17+ (javac), Python 3.10+ s balíčkem `cryptography`, internet
(jednorázově stáhne závislosti z Maven Central do android/.tools).

Kroky:
    1. stažení závislostí (android-all = API Androidu pro překlad, dx, TensorFlow Lite)
    2. javac   – překlad Java zdrojáků (bytekód Java 8)
    3. dx      – převod .class -> classes.dex (formát pro Android)
    4. manifest – textový AndroidManifest.xml -> binární AXML (tools/axml.py)
    5. zip     – složení APK (manifest, dex, nativní knihovny TFLite)
    6. podpis  – APK Signature Scheme v2 (tools/apksign.py)

Použití:
    python android/build.py            # -> android/build/motorcam.apk
    python android/build.py --test     # testy logiky na JVM
"""

from __future__ import annotations

import shutil
import subprocess
import sys
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT / "tools"))
import apksign  # noqa: E402
import axml  # noqa: E402

TOOLS = ROOT / ".tools"
BUILD = ROOT / "build"
MAVEN = "https://repo.maven.apache.org/maven2"
DEPS = {
    # API Androidu 14 (34) – jen pro překlad, do APK se nebalí
    "android-all.jar": "org/robolectric/android-all/14-robolectric-10818077/android-all-14-robolectric-10818077.jar",
    # dx – převod bytekódu na DEX (přebalený z AOSP)
    "dx.jar": "com/jakewharton/android/repackaged/dalvik-dx/16.0.1/dalvik-dx-16.0.1.jar",
    # TensorFlow Lite – běh modelů na telefonu
    "tflite.aar": "org/tensorflow/tensorflow-lite/2.16.1/tensorflow-lite-2.16.1.aar",
    "tflite-api.aar": "org/tensorflow/tensorflow-lite-api/2.16.1/tensorflow-lite-api-2.16.1.aar",
}
ABIS = ["arm64-v8a", "armeabi-v7a"]  # telefony; x86 (emulátory) vynecháváme kvůli velikosti


def fetch_deps() -> None:
    TOOLS.mkdir(exist_ok=True)
    for name, path in DEPS.items():
        dst = TOOLS / name
        if not dst.exists() or dst.stat().st_size < 1000:
            print(f"[>] stahuji {name}")
            urllib.request.urlretrieve(f"{MAVEN}/{path}", dst)
    # classes.jar z AAR knihoven
    for aar in ("tflite.aar", "tflite-api.aar"):
        jar = TOOLS / aar.replace(".aar", "-classes.jar")
        if not jar.exists():
            with zipfile.ZipFile(TOOLS / aar) as z:
                jar.write_bytes(z.read("classes.jar"))


def run(cmd: list[str]) -> None:
    res = subprocess.run(cmd, capture_output=True, text=True)
    out = "\n".join(ln for ln in (res.stdout + res.stderr).splitlines() if "JAVA_TOOL_OPTIONS" not in ln)
    if out.strip():
        print(out)
    if res.returncode:
        raise SystemExit(f"Příkaz selhal: {' '.join(cmd[:3])} ...")


def build() -> Path:
    fetch_deps()
    if BUILD.exists():
        shutil.rmtree(BUILD)
    classes = BUILD / "classes"
    classes.mkdir(parents=True)
    libs = [TOOLS / "tflite-classes.jar", TOOLS / "tflite-api-classes.jar"]

    # 2) javac – Java 8 bytekód (dx neumí novější); lambdy nepoužíváme (dx je nepřevádí)
    sources = sorted(str(p) for p in (ROOT / "src").rglob("*.java"))
    print(f"[>] javac ({len(sources)} souborů)")
    run(["javac", "--release", "8", "-encoding", "UTF-8", "-nowarn", "-Xlint:-options",
         "-cp", ":".join(str(p) for p in [TOOLS / "android-all.jar", *libs]),
         "-d", str(classes), *sources])

    # 3) dx – aplikace + knihovny do jednoho classes.dex
    print("[>] dx")
    dex = BUILD / "classes.dex"
    run(["java", "-cp", str(TOOLS / "dx.jar"), "com.android.dx.command.Main", "--dex", "--min-sdk-version=26",
         f"--output={dex}", str(classes), *[str(p) for p in libs]])

    # 4) + 5) manifest a zip
    print("[>] manifest + zip")
    manifest = axml.compile_manifest((ROOT / "AndroidManifest.xml").read_text(encoding="utf-8"))
    unsigned = BUILD / "unsigned.apk"
    with zipfile.ZipFile(unsigned, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("AndroidManifest.xml", manifest)
        z.write(dex, "classes.dex")
        with zipfile.ZipFile(TOOLS / "tflite.aar") as aar:
            for abi in ABIS:
                z.writestr(f"lib/{abi}/libtensorflowlite_jni.so", aar.read(f"jni/{abi}/libtensorflowlite_jni.so"))
        assets = ROOT / "assets"
        if assets.exists():
            for f in sorted(assets.rglob("*")):
                if f.is_file():
                    z.write(f, f"assets/{f.relative_to(assets)}")

    # 6) podpis
    print("[>] podpis v2")
    key, cert = apksign.load_or_create_key(ROOT / "debug-key.pem", ROOT / "debug-cert.pem")
    apk = BUILD / "motorcam.apk"
    apk.write_bytes(apksign.sign_v2(unsigned.read_bytes(), key, cert))
    print(f"[✓] {apk} ({apk.stat().st_size / 1e6:.1f} MB)")
    return apk


def test() -> None:
    """Testy logiky (mapa, zatáčky, YOLO, kontrolka) na běžném JVM – bez telefonu."""
    fetch_deps()
    out = BUILD / "test-classes"
    if out.exists():
        shutil.rmtree(out)
    out.mkdir(parents=True)
    cp = str(TOOLS / "android-all.jar")   # obsahuje i org.json, které má Android vestavěné
    logic = ["Settings", "Geo", "RoadNetwork", "RoadPath", "Curves", "Detection", "Fusion", "Vibration",
             "Route", "Curviness", "RouteFollower", "RoutingService", "RouteExport", "MapService"]
    srcs = [str(ROOT / "src/cz/motorcam/app" / f"{n}.java") for n in logic]
    srcs += [str(p) for p in (ROOT / "test").rglob("*.java")]
    run(["javac", "-encoding", "UTF-8", "-nowarn", "-cp", cp, "-d", str(out), *srcs])
    for cls, extra in [("LogicTest", []), ("RouteTest", [str(ROOT / "assets" / "trasy.json")])]:
        res = subprocess.run(["java", "-Dstdout.encoding=UTF-8", "-cp", f"{out}:{cp}", f"cz.motorcam.app.{cls}", *extra],
                             capture_output=True, text=True)
        print(res.stdout)
        if res.returncode:
            print(res.stderr)
            raise SystemExit(f"Testy {cls} selhaly")


if __name__ == "__main__":
    if "--test" in sys.argv:
        test()
    else:
        build()
