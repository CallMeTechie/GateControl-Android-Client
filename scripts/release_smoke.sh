#!/usr/bin/env bash
# Installs the APK, starts the launcher activity and fails when the process
# died or logged a fatal exception (see .github/workflows/release-smoke.yml).
set -u
APK="$1"
PKG=com.gatecontrol.client

adb install -r "$APK" || exit 1
adb logcat -c
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null
sleep 20

adb logcat -d -b crash > crash.txt || true
if grep -q "Process: $PKG" crash.txt; then
  echo "::error::The release build crashed at start"
  cat crash.txt
  exit 1
fi
if ! adb shell pidof "$PKG" >/dev/null; then
  echo "::error::The app process is not running 20 s after start"
  adb logcat -d | tail -200
  exit 1
fi
echo "Release build started and is still running."
