#!/usr/bin/env bash
# Installs the two SDK pieces build.sh needs, on a machine where the Android
# command line tools are already present (GitHub's ubuntu runners ship them).
set -euo pipefail

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/usr/local/lib/android/sdk}}"
SDKMANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager"
if [ ! -x "$SDKMANAGER" ]; then
  echo "sdkmanager introuvable dans $SDK" >&2
  ls -d "$SDK"/cmdline-tools/* 2>/dev/null || true
  exit 1
fi

(yes | "$SDKMANAGER" --licenses >/dev/null 2>&1) || true
"$SDKMANAGER" --install "platforms;android-34" "build-tools;34.0.0" "platform-tools" >/dev/null

if [ -n "${GITHUB_ENV:-}" ]; then
  echo "ANDROID_SDK_ROOT=$SDK" >> "$GITHUB_ENV"
fi
echo "ANDROID_SDK_ROOT=$SDK"
"$SDK"/build-tools/34.0.0/aapt2 version
