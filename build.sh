#!/usr/bin/env bash
# ---------------------------------------------------------------------------
#  NEON DRIFT - build script
#
#  Builds a signed, installable APK straight from the Android SDK
#  command-line tools (aapt2 -> javac -> d8 -> zipalign -> apksigner).
#  No Gradle, no network: ~10 seconds on any machine with a JDK 17 and
#  Android SDK build-tools 34 installed.
#
#    ./build.sh              # build dist/NeonDrift-1.0.apk
#    ./build.sh --test       # run the JVM test suite first
# ---------------------------------------------------------------------------
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$PROJECT_DIR"

APP_NAME="NeonDrift"
VERSION_NAME="1.0"
VERSION_CODE="1"
MIN_SDK="21"
TARGET_SDK="34"
BUILD_TOOLS_VERSION="34.0.0"

# --------------------------------------------------------------- toolchain ---
find_sdk() {
  for candidate in "${ANDROID_SDK_ROOT:-}" "${ANDROID_HOME:-}" \
      "$HOME/Android/Sdk" "$HOME/android-sdk" /tmp/android-sdk /usr/local/lib/android/sdk; do
    if [ -n "$candidate" ] && [ -d "$candidate/build-tools" ]; then
      echo "$candidate"
      return 0
    fi
  done
  return 1
}

SDK="$(find_sdk)" || {
  echo "ERROR: Android SDK not found. Set ANDROID_SDK_ROOT." >&2
  exit 1
}

if [ -d "$SDK/build-tools/$BUILD_TOOLS_VERSION" ]; then
  BT="$SDK/build-tools/$BUILD_TOOLS_VERSION"
else
  BT="$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)"
fi
ANDROID_JAR="$(ls "$SDK"/platforms/*/android.jar | sort -V | tail -1)"
AAPT2="$BT/aapt2"
D8="$BT/d8"
ZIPALIGN="$BT/zipalign"
APKSIGNER="$BT/apksigner"

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
  JAVAC="$JAVA_HOME/bin/javac"
  JAVA="$JAVA_HOME/bin/java"
  KEYTOOL="$JAVA_HOME/bin/keytool"
else
  JAVAC="javac"; JAVA="java"; KEYTOOL="keytool"
fi

echo "== NEON DRIFT $VERSION_NAME =="
echo "   sdk      : $SDK"
echo "   tools    : $BT"
echo "   platform : $ANDROID_JAR"
"$JAVA" -version 2>&1 | head -1 | sed 's/^/   java     : /'

OUT="$PROJECT_DIR/build"
DIST="$PROJECT_DIR/dist"
rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex" "$DIST"

# ------------------------------------------------------------------- tests ---
if [ "${1:-}" = "--test" ]; then
  echo "-- running JVM test suite"
  mkdir -p "$OUT/test"
  "$JAVAC" -d "$OUT/test" $(find app/src/main/java/com/neondrift/rush/core -name '*.java') \
      test/EngineTest.java
  "$JAVA" -cp "$OUT/test" EngineTest | tail -4
fi

# --------------------------------------------------------------- resources ---
echo "-- aapt2 compile"
"$AAPT2" compile --dir app/src/main/res -o "$OUT/res.zip"

echo "-- aapt2 link"
"$AAPT2" link \
  -o "$OUT/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest app/src/main/AndroidManifest.xml \
  -R "$OUT/res.zip" \
  --java "$OUT/gen" \
  --min-sdk-version "$MIN_SDK" \
  --target-sdk-version "$TARGET_SDK" \
  --version-code "$VERSION_CODE" \
  --version-name "$VERSION_NAME" \
  --auto-add-overlay

# ------------------------------------------------------------------- compile --
echo "-- javac"
if ! "$JAVAC" -source 8 -target 8 -nowarn -encoding UTF-8 \
    -bootclasspath "$ANDROID_JAR" \
    -d "$OUT/classes" \
    $(find app/src/main/java -name '*.java') \
    "$OUT/gen/com/neondrift/rush/R.java" 2> "$OUT/javac.log"; then
  echo "!! compilation failed" >&2
  grep -v "^Note:" "$OUT/javac.log" >&2
  exit 1
fi

# ----------------------------------------------------------------------- dex --
echo "-- d8"
"$D8" --release --lib "$ANDROID_JAR" --min-api "$MIN_SDK" \
  --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')

echo "-- package"
cwd_backup="$PWD"
cd "$OUT/dex"
zip -q -X "$OUT/base.apk" classes.dex
cd "$cwd_backup"

# ------------------------------------------------------------------- signing --
KEYSTORE="$PROJECT_DIR/release/neondrift.keystore"
STORE_PASS="neondrift"
KEY_ALIAS="neondrift"
if [ ! -f "$KEYSTORE" ]; then
  echo "-- generating signing key"
  mkdir -p "$PROJECT_DIR/release"
  "$KEYTOOL" -genkeypair -v -keystore "$KEYSTORE" -storetype JKS -alias "$KEY_ALIAS" \
    -keyalg RSA -keysize 2048 -validity 10950 \
    -storepass "$STORE_PASS" -keypass "$STORE_PASS" \
    -dname "CN=NEON DRIFT, OU=Games, O=Neon Drift, L=Lome, C=TG" >/dev/null 2>&1
fi

echo "-- zipalign"
"$ZIPALIGN" -p -f 4 "$OUT/base.apk" "$OUT/aligned.apk"

echo "-- apksigner"
FINAL="$DIST/${APP_NAME}-${VERSION_NAME}.apk"
"$APKSIGNER" sign \
  --ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS" \
  --ks-pass "pass:$STORE_PASS" --key-pass "pass:$STORE_PASS" \
  --min-sdk-version "$MIN_SDK" \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out "$FINAL" "$OUT/aligned.apk"

"$APKSIGNER" verify --print-certs "$FINAL" | head -4

SIZE="$(du -h "$FINAL" | cut -f1)"
echo
echo "== OK =="
echo "   $FINAL  ($SIZE)"
