#!/usr/bin/env bash
# Builds the debug app, starts the "nudge_api36" emulator (with a window) if needed, installs and launches Nudge.
# Usage: ./scripts/run-on-emulator.sh
set -euo pipefail
cd "$(dirname "$0")/.."

export JAVA_HOME="${JAVA_HOME:-$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home}"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="$SDK/platform-tools/adb"
AVD="${AVD:-nudge_api36}"

echo "▶ Building the app…"
./gradlew assembleDebug -q

if ! "$ADB" devices | grep -q "emulator-.*device$"; then
  echo "▶ Starting emulator '$AVD' (a phone window will open)…"
  nohup "$SDK/emulator/emulator" -avd "$AVD" -no-snapshot-save >/dev/null 2>&1 &
  "$ADB" wait-for-device
  until [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 2; done
fi

echo "▶ Installing…"
"$ADB" install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
"$ADB" shell am start -n app.nudge.reminders.debug/app.nudge.MainActivity >/dev/null
echo "✔ Nudge is running in the emulator window."
