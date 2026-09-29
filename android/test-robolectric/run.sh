#!/usr/bin/env bash
# Spuštění aplikace v Robolectricu (Android framework na JVM) – kontrola, že aplikace
# nespadne: start, GPS jízda k zatáčce, overlay, REC/STOP, simulace, menu, ukončení.
# A vykreslení náhledu obrazovky do docs/img/aplikace_overlay.png.
#
# Použití (z kořene repozitáře, po `python android/build.py`):  bash android/test-robolectric/run.sh
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
A="$HERE/.."
W="$HERE/.work"
mkdir -p "$W/deps" "$W/out" "$W/stubout"
cd "$HERE"
# Robolectric z Maven Central (androidx.test je jen na Google Mavenu -> nahrazeno stuby ve stubs/)
[ -d "$W/libs" ] || mvn -q -B dependency:copy-dependencies -DoutputDirectory="$W/libs"
J="$W/deps/android-all-instrumented-14-robolectric-10818077-i7.jar"
[ -s "$J" ] && unzip -tq "$J" >/dev/null 2>&1 || curl -fsS --retry 5 --retry-delay 15 -o "$J" https://repo.maven.apache.org/maven2/org/robolectric/android-all-instrumented/14-robolectric-10818077-i7/android-all-instrumented-14-robolectric-10818077-i7.jar
ln -sf "$J" "$W/deps/android-all-instrumented-15-robolectric-12650502-i7.jar"
AJ="$A/.tools/android-all.jar"
javac -nowarn -d "$W/stubout" -cp "$AJ" $(find stubs -name '*.java')
CP="$W/out:$W/stubout:$(ls "$W"/libs/*.jar | tr '\n' ':')$A/.tools/tflite-classes.jar:$A/.tools/tflite-api-classes.jar:$AJ"
javac -encoding UTF-8 -nowarn -d "$W/out" -cp "$CP" "$A"/src/cz/motorcam/app/*.java "$A"/test/cz/motorcam/app/LogicTest.java $(find src -name '*.java')
java -Dstdout.encoding=UTF-8 -Dshot="$A/../docs/img/aplikace_overlay.png" -Drobolectric.offline=true \
     -Drobolectric.dependency.dir="$W/deps" -cp "$CP" org.junit.runner.JUnitCore \
     cz.motorcam.app.ActivitySmokeTest cz.motorcam.app.ScreenshotTest
