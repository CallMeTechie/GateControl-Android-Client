#!/usr/bin/env bash
# Screenshots of the large-screen layouts (.github/workflows/layout-shots.yml):
# navigation rail, home in two columns, settings and messages as list +
# detail, foldable postures (book: split at the hinge, tabletop: connection
# above the fold). Also usable as tablet screenshots for the store listing.
#
# Usage: layout-shots.sh APK ENROLL_LINK_FILE OUT_DIR tablet|fold|phone
set -u
APK="$1"
LINK="$(cat "$2")"
OUT="$3"
DEVICE="$4"
PKG=com.gatecontrol.client
HERE="$(cd "$(dirname "$0")" && pwd)"
UI="python3 $HERE/ui.py"
mkdir -p "$OUT/screenshots"
# shellcheck source=lib.sh
. "$HERE/lib.sh"

shot() { adb exec-out screencap -p > "$OUT/screenshots/$DEVICE-$1.png"; echo "shot $DEVICE-$1"; }
rotate() { # 0 = natural, 1 = 90°, …
  adb shell settings put system accelerometer_rotation 0
  adb shell settings put system user_rotation "$1"
  sleep 3
}
# Foldable postures: 0 closed, 1 half opened, 2 open (pixel_fold emulator).
posture() {
  adb shell cmd device_state state "$1" >/dev/null 2>&1 || adb emu posture "$1" >/dev/null 2>&1
  sleep 4
}
connect() {
  tap "Connect" || return 1
  tap_opt "OK" 8                                  # system "Connection request"
  wait_text "Disconnect" --timeout 40 || true
  sleep 4
}
# Notifications on and one test message, so the inbox has something to show.
notifications_on() {
  nav "Settings"; sleep 1
  tap "Notifications" --scroll --scroll-at 0.2 || tap "Off – tap to set up" --contains --scroll --scroll-at 0.2
  sleep 1.5
  tap "Receive notifications"
  tap "Always (recommended)" --timeout 8 || tap "Always, also without VPN" --contains
  tap_opt "Allow" 8
  sleep 2
  tap "Request a test message" --contains --scroll --scroll-at 0.7
  sleep 5
}
# Diagnostics: run all checks, screenshot the result.
diagnosis() {
  nav "Settings"; sleep 1
  tap "Diagnostics & report a problem" --scroll --scroll-at 0.2 || return 1
  sleep 1.5
  tap "Start diagnostics" || return 1
  wait_text "Check again" --timeout 90 || diag "diagnosis not finished"
  sleep 1
  shot "$1-diagnosis"
}
inbox() {
  nav "Home"; sleep 1
  tap "Messages" --contains || return 1
  sleep 2
  shot "$1-inbox"
  tap "Test message" --contains && sleep 2 && shot "$1-inbox-message"
  key KEYCODE_BACK
}

setup_app "$APK" "$LINK" || exit 1
adb shell am force-stop "$PKG"
echo "::notice title=$DEVICE display::$(adb shell wm size | tr '\n' ' ') $(adb shell wm density | tr '\n' ' ')"
adb shell cmd device_state print-states 2>/dev/null | head -12 | tr '\n' '|' | sed 's/^/::notice title=device states::/'; echo
app_front; sleep 4

case "$DEVICE" in
  tablet)
    shot 01-home
    connect && shot 02-home-connected
    nav "Settings"; sleep 2; shot 03-settings
    tap "Split tunneling" --contains; sleep 2; shot 04-settings-split
    notifications_on; shot 05-settings-notifications
    inbox 06
    diagnosis 09
    rotate 1                                      # portrait
    nav "Home"; sleep 2; shot 07-portrait-home
    nav "Settings"; sleep 2; shot 08-portrait-settings
    rotate 0
    ;;
  fold)
    posture 2                                     # fully open
    shot 01-open-home
    connect && shot 02-open-home-connected
    nav "Settings"; sleep 2; shot 03-open-settings
    notifications_on
    inbox 04-open
    posture 1                                     # half open
    nav "Settings"; sleep 2; shot 05-book-settings
    nav "Home"; sleep 2; shot 06-book-home
    rotate 1                                      # hinge now horizontal: tabletop
    sleep 2; shot 07-tabletop-home
    rotate 0
    posture 0                                     # closed: outer display
    sleep 3; app_front; shot 08-closed-home
    posture 2
    ;;
  phone)
    rotate 1                                      # landscape
    shot 01-landscape-home
    connect && shot 02-landscape-home-connected
    nav "Settings"; sleep 2; shot 03-landscape-settings
    rotate 0
    nav "Home"; sleep 2; shot 04-portrait-home
    diagnosis 05
    ;;
esac

alive "layout shots"
adb logcat -d -b crash > "$OUT/crash-$DEVICE.txt" 2>/dev/null
ls -la "$OUT/screenshots"
exit 0
