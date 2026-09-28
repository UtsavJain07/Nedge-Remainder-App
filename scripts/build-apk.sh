#!/usr/bin/env bash
# Builds an up-to-date Nudge APK and copies it to dist/ with a clear name.
# Usage:
#   ./scripts/build-apk.sh                    # test build (includes Debug tools)
#   ./scripts/build-apk.sh release            # release build (needs your signing key in local.properties)
#   ./scripts/build-apk.sh --install          # also install on the phone/emulator connected via adb
#   ./scripts/build-apk.sh release --install
set -euo pipefail
cd "$(dirname "$0")/.."

TYPE="debug"
INSTALL=false
for arg in "$@"; do
  case "$arg" in
    release) TYPE="release" ;;
    debug) TYPE="debug" ;;
    --install) INSTALL=true ;;
    *) echo "Unknown option: $arg"; exit 1 ;;
  esac
done

export JAVA_HOME="${JAVA_HOME:-$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home}"
ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
VERSION="$(grep '^nudge.versionName=' gradle.properties | cut -d= -f2)"

if [ "$TYPE" = "release" ] && ! grep -q '^KEYSTORE_PATH=' local.properties 2>/dev/null; then
  echo "✖ No signing key configured. See HOW-TO-BUILD-AND-TEST.md, section 'Release APK'."
  exit 1
fi

echo "▶ Building the $TYPE APK (the first build can take a few minutes)…"
if [ "$TYPE" = "release" ]; then ./gradlew assembleRelease -q; else ./gradlew assembleDebug -q; fi

SRC="$(ls app/build/outputs/apk/$TYPE/*.apk | head -1)"
case "$SRC" in *unsigned*) echo "✖ Release APK is unsigned; check the KEYSTORE_* values in local.properties."; exit 1 ;; esac
mkdir -p dist
OUT="dist/Nudge-$VERSION-$TYPE-$(date +%Y%m%d-%H%M).apk"
cp "$SRC" "$OUT"
echo "✔ APK ready: $PWD/$OUT ($(du -h "$OUT" | cut -f1))"

if $INSTALL; then
  if ! "$ADB" devices | grep -qE "device$"; then
    echo "✖ No phone or emulator connected (check with: adb devices)."
    exit 1
  fi
  echo "▶ Installing on the connected device…"
  "$ADB" install -r "$OUT" >/dev/null
  echo "✔ Installed. Open Nudge on the device."
fi
