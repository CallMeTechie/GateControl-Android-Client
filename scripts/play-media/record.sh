#!/usr/bin/env bash
# Records the Play review videos on the emulator (.github/workflows/play-videos.yml):
#   vpn       – VpnService declaration (connect, tunnel, split tunnel, disconnect)
#   location  – background location declaration (trusted Wi-Fi, both disclosures,
#               automatic connect / disconnect on a network change)
#   fgs       – foreground service declaration (notifications "always")
# plus store screenshots. Each video is a list of segments; every segment gets
# one caption (segments.tsv), compose.sh burns them in and joins the segments.
#
# Usage: record.sh APK ENROLL_LINK_FILE OUT_DIR
set -u
APK="$1"
LINK="$(cat "$2")"
OUT="$3"
PKG=com.gatecontrol.client
HERE="$(cd "$(dirname "$0")" && pwd)"
UI="python3 $HERE/ui.py"
mkdir -p "$OUT/segments" "$OUT/screenshots"
: > "$OUT/segments.tsv"

REC_PID=""
REC_NAME=""
rec_start() { # VIDEO SEGMENT CAPTION
  REC_NAME="$1-$2"
  printf '%s\t%s\t%s\n' "$1" "$REC_NAME" "$3" >> "$OUT/segments.tsv"
  adb shell rm -f "/sdcard/$REC_NAME.mp4" >/dev/null 2>&1
  adb shell screenrecord --bit-rate 8000000 "/sdcard/$REC_NAME.mp4" &
  REC_PID=$!
  sleep 1.2
}
# Reports (once) when the app process is gone, with the crash buffer.
alive() {
  adb shell pidof "$PKG" >/dev/null 2>&1 && return 0
  local crash
  crash=$(adb logcat -d -b crash 2>/dev/null | tail -40 | tr '\n' '|' | head -c 3000)
  echo "::error title=app not running after $1::${crash:-<empty crash buffer>}"
  return 1
}
rec_stop() {
  alive "$REC_NAME"
  sleep 0.8
  adb shell pkill -INT screenrecord >/dev/null 2>&1
  wait "$REC_PID" 2>/dev/null
  sleep 1.5
  adb pull "/sdcard/$REC_NAME.mp4" "$OUT/segments/$REC_NAME.mp4" >/dev/null 2>&1
  local size
  size=$(stat -c %s "$OUT/segments/$REC_NAME.mp4" 2>/dev/null || echo 0)
  [ "$size" -gt 20000 ] || echo "::warning title=segment $REC_NAME::missing or empty ($size bytes)"
}
shot() { adb exec-out screencap -p > "$OUT/screenshots/$1.png"; }
# What is on screen, as a CI annotation (artifacts are not always at hand).
diag() {
  local texts
  texts=$($UI dump 2>/dev/null | sed -E "s/ \[[0-9,]+\]\[[0-9,]+\]$//" | tr '\n' '|' | head -c 1800)
  echo "::notice title=$1::${texts:-<no texts>}"
}
tap() {
  $UI tap "$@" >/dev/null 2>&1 && return 0
  echo "::error::step failed: tap $*"
  diag "screen when 'tap $1' failed"
  adb exec-out screencap -p > "$OUT/fail-$(date +%s).png"
  return 1
}
tap_opt() { $UI tap "$1" --optional --timeout "${2:-4}"; }
wait_text() { $UI wait "$@"; }
key() { adb shell input keyevent "$1"; sleep 0.8; }
nav() { $UI tap "$1" --optional --timeout 6; sleep 1; }
shade_open() { adb shell cmd statusbar expand-notifications; sleep "${1:-3}"; }
shade_close() { adb shell cmd statusbar collapse; sleep 0.8; }

# ── Preparation (not recorded) ───────────────────────────────────────
adb install -r "$APK" || exit 1
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS
adb shell svc wifi enable
adb shell cmd location set-location-enabled true
adb shell settings put system screen_off_timeout 1800000
adb shell svc power stayon true
# Clean status bar for recordings and screenshots.
adb shell settings put global sysui_demo_allowed 1
adb shell am broadcast -a com.android.systemui.demo -e command enter >/dev/null
adb shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 0942 >/dev/null
adb shell am broadcast -a com.android.systemui.demo -e command battery -e level 87 -e plugged false >/dev/null
adb shell am broadcast -a com.android.systemui.demo -e command notifications -e visible false >/dev/null
sleep 8

# Set up like a person would: "Enter setup code", server URL, code.
CODE="${LINK##*code=}"
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
tap "Enter setup code" --timeout 40 || exit 1
$UI fill 0 "10.0.2.2:8443" || { diag "server URL not typed"; exit 1; }
echo "::notice title=setup code::length ${#CODE}"
$UI fill 1 "${CODE//-/}" --secret || { diag "no code field"; exit 1; }
sleep 0.5
key KEYCODE_BACK                                 # hide the keyboard
diag "setup form filled"
tap "Save & Register" || exit 1
tap_opt "Connect" 10                             # confirmation, if the app asks
# After the setup the app connects right away. Decline the VPN consent here:
# the VPN video shows it when the user taps Connect.
$UI tap "Cancel" --optional --timeout 40
sleep 3
diag "after setup"
tap_opt "Not now" 3; tap_opt "Later" 2; tap_opt "Close" 2
# Demo mode hides notification icons; turn that part off so the VPN key shows.
adb shell am broadcast -a com.android.systemui.demo -e command exit >/dev/null
adb shell am force-stop "$PKG"
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 6
shot 01-home-disconnected

adb logcat -c
# ── Video 1: VPN service ────────────────────────────────────────────
rec_start vpn 01 "GateControl is the app for the user's own, self-hosted GateControl server."
sleep 3
rec_stop

rec_start vpn 02 "Tap Connect: the app uses Android's VpnService to build a WireGuard tunnel to that server."
tap "Connect" || exit 1
tap_opt "OK" 8                                   # system "Connection request"
wait_text "Disconnect" --timeout 40 || true
sleep 3
rec_stop
shot 02-home-connected

rec_start vpn 03 "Connected. The key icon shows the active VPN. Traffic to the user's server is encrypted end to end."
shade_open 4
shade_close
rec_stop

rec_start vpn 04 "Purpose: secure remote access to the user's home network and own services (NAS, smart home, remote desktop)."
nav "Network"; sleep 3; shot 03-network
rec_stop

rec_start vpn 05 "The user decides which apps or networks use the tunnel (split tunneling)."
nav "Settings"; sleep 1; shot 05-settings
tap "Split tunneling" --contains; sleep 3
key KEYCODE_BACK
rec_stop

rec_start vpn 06 "The user disconnects at any time. No ads, no tracking, no data collection, nothing goes to third parties."
nav "Home"; sleep 1
tap "Disconnect"; sleep 3
rec_stop

# ── Video 2: background location (trusted Wi-Fi) ────────────────────
rec_start location 01 "Trusted Wi-Fi (optional): the VPN disconnects at home and connects again when the phone leaves."
nav "Settings"
tap "Trusted Wi-Fi networks"; sleep 1
tap "Disconnect and connect automatically"; sleep 1
rec_stop

rec_start location 02 "Android reveals the Wi-Fi name only with location access. The app explains this before asking."
tap "Add current Wi-Fi"; sleep 3.5
tap "Continue"
tap_opt "While using the app" 10
sleep 1.5
rec_stop

rec_start location 03 "Background access, again explained first: the location never leaves the device and is not stored."
tap "Open settings" || tap "Grant permission"
sleep 3.5
tap "Continue"
tap "Allow all the time" --timeout 15
sleep 1.5
key KEYCODE_BACK
sleep 1
tap "Add current Wi-Fi"; sleep 2.5
shot 06-trusted-wifi
rec_stop

rec_start location 04 "App closed. Leaving the trusted Wi-Fi: the VPN connects automatically in the background."
key KEYCODE_HOME
adb shell svc wifi disable
sleep 10
shade_open 3
shade_close
rec_stop

rec_start location 05 "Back in the trusted Wi-Fi: the VPN disconnects automatically. No location is sent anywhere."
adb shell svc wifi enable
sleep 12
shade_open 3
shade_close
rec_stop

# ── Video 3: foreground service (notifications without Google) ─────
adb shell am force-stop "$PKG"
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 5
rec_start fgs 01 "Notifications from the user's own server, without Google services: the user chooses 'Always'."
nav "Settings"
tap "Off – tap to set up" --contains || tap "Notifications"
sleep 1.5
tap "Receive notifications"
tap "Always (recommended)" --timeout 8 || tap "Always, also without VPN" --contains
tap_opt "Allow" 8                                 # battery optimisation exemption
sleep 2
shot 07-notifications
rec_stop

rec_start fgs 02 "A foreground service with a permanent notification keeps this one connection. It can be switched off at any time."
shade_open 4
shade_close
rec_stop

rec_start fgs 03 "Messages from the server arrive right away, also when the VPN is off."
tap "Request a test message" --contains
sleep 4
shade_open 4
shade_close
rec_stop

adb shell am broadcast -a com.android.systemui.demo -e command exit >/dev/null
adb logcat -d > "$OUT/logcat.txt" 2>/dev/null
adb logcat -d -b crash > "$OUT/crash.txt" 2>/dev/null
ls -la "$OUT/segments" "$OUT/screenshots"
exit 0
