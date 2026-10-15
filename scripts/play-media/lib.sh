#!/usr/bin/env bash
# Shared by record.sh (Play review videos) and layout-shots.sh (large-screen
# screenshots): adb/uiautomator helpers and the app setup against the
# throwaway server from server.sh. Expects PKG, UI and OUT to be set.

# Reports (once) when the app process is gone, with the crash buffer.
alive() {
  adb shell pidof "$PKG" >/dev/null 2>&1 && return 0
  local crash
  crash=$(adb logcat -d -b crash 2>/dev/null | tail -40 | tr '\n' '|' | head -c 3000)
  echo "::error title=app not running after $1::${crash:-<empty crash buffer>}"
  return 1
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
# A tab of the bar (Home, Network, Familie). Sub pages (settings, profile)
# hide the bar: back until it shows. "Settings" is no tab since 1.35.
nav() {
  if [ "$1" = "Settings" ]; then open_settings; return 0; fi
  local i
  for i in 1 2 3 4; do
    $UI tap "$1" --timeout 3 >/dev/null 2>&1 && { sleep 1; return 0; }
    # Bar visible: the tab just is not there (never back out of the app).
    $UI wait "Home" --timeout 1 >/dev/null 2>&1 && break
    adb shell input keyevent KEYCODE_BACK; sleep 0.8
  done
  sleep 1
}
# Settings: own avatar (or the profile button) on Home → gear top right.
open_settings() {
  nav "Home"
  $UI tap "– profile" --contains --timeout 4 >/dev/null 2>&1 || $UI tap "Profile and settings" --optional --timeout 4
  sleep 1
  $UI tap "Settings" --optional --timeout 6; sleep 1
}
# VPN state from the system: waits until it is up / down (max S seconds).
vpn_wait() { # up|down S
  local i
  for i in $(seq 1 "$2"); do
    if adb shell dumpsys connectivity 2>/dev/null | grep -E "NetworkAgentInfo" | grep -qE "Transports: VPN|VPN CONNECTED"; then
      [ "$1" = up ] && return 0
    else
      [ "$1" = down ] && return 0
    fi
    sleep 1
  done
  echo "::warning title=vpn::not $1 after $2 s"
  return 1
}
app_front() { adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 2.5; }
shade_open() { adb shell cmd statusbar expand-notifications; sleep "${1:-3}"; }
shade_close() { adb shell cmd statusbar collapse; sleep 0.8; }

# Installs the app, prepares the emulator and sets the app up like a person
# would ("Enter setup code"). Leaves the app on its start screen, not connected.
setup_app() { # APK LINK
  local APK="$1" LINK="$2"
  adb install -r "$APK" || return 1
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
  local CODE="${LINK##*code=}"
  # A freshly booted emulator sometimes shows "System UI isn't responding":
  # wait it out and start the app again.
  local try
  for try in 1 2 3; do
    adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
    $UI tap "Enter setup code" --timeout 40 >/dev/null 2>&1 && break
    $UI tap "Wait" --optional --timeout 3
    [ "$try" = 3 ] && { tap "Enter setup code" --timeout 5 || return 1; }
    sleep 5
  done
  $UI fill 0 "10.0.2.2:8443" || { diag "server URL not typed"; return 1; }
  echo "::notice title=setup code::length ${#CODE}"
  $UI fill 1 "${CODE//-/}" --secret || { diag "no code field"; return 1; }
  sleep 0.5
  key KEYCODE_BACK                                 # hide the keyboard
  diag "setup form filled"
  tap "Save & Register" || return 1
  tap_opt "Connect" 10                             # confirmation, if the app asks
  # After the setup the app connects right away. Decline the VPN consent here:
  # the VPN video shows it when the user taps Connect.
  $UI tap "Cancel" --optional --timeout 40
  sleep 3
  diag "after setup"
  tap_opt "Not now" 3; tap_opt "Later" 2; tap_opt "Close" 2
  tap_opt "Dismiss" 4                              # "Notifications are off" hint
}
