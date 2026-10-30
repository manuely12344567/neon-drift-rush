#!/usr/bin/env bash
# ---------------------------------------------------------------------------
#  Renders the game off-device through Robolectric's native graphics backend
#  and runs the resize regression test (the one that reproduced the 1.0 crash).
#
#  Needs: JDK 17, Maven, curl, unzip, an Android SDK with platforms;android-34.
#  The Robolectric classpath is resolved once and cached in build/.
# ---------------------------------------------------------------------------
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_DIR"

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/usr/local/lib/android/sdk}}"
ANDROID_JAR="$(ls "$SDK"/platforms/*/android.jar | sort -V | tail -1)"
OUT="$PROJECT_DIR/build/render"
CPFILE="$OUT/cp.txt"
mkdir -p "$OUT"

if [ ! -s "$CPFILE" ]; then
  echo "-- résolution des dépendances Robolectric (une seule fois)"
  mvn -q -B -f tools/render/pom.xml dependency:build-classpath \
      -Dmdep.outputFile="$CPFILE"
fi
CP="$(cat "$CPFILE")"

# Android libraries are .aar: the classes live in classes.jar inside.
AARCP=""
i=0
for jar in $(tr ':' '\n' < "$CPFILE" | grep '\.aar$' || true); do
  i=$((i + 1))
  dest="$OUT/aar$i"
  if [ ! -f "$dest/classes.jar" ]; then
    mkdir -p "$dest"
    (cd "$dest" && unzip -qo "$jar" classes.jar)
  fi
  AARCP="$AARCP:$dest/classes.jar"
done

echo "-- compilation du moteur, du rendu et des tests"
rm -rf "$OUT/classes"
mkdir -p "$OUT/classes"
javac -nowarn -encoding UTF-8 \
  -cp "$ANDROID_JAR:$CP$AARCP" -d "$OUT/classes" \
  app/src/main/java/com/neondrift/rush/core/*.java \
  app/src/main/java/com/neondrift/rush/Palette.java \
  app/src/main/java/com/neondrift/rush/NeonRenderer.java \
  test/render/*.java

echo "-- exécution"
java -cp "$OUT/classes:$CP$AARCP:$ANDROID_JAR" \
  -Drobolectric.graphicsMode=NATIVE \
  -Drobolectric.logging=stdout \
  -Dneondrift.out="$PROJECT_DIR/screenshots/" \
  org.junit.runner.JUnitCore RenderHarness ResizeRegressionTest
