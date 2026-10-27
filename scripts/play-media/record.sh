#!/usr/bin/env bash
# Records the Play review videos on the emulator (.github/workflows/play-videos.yml):
#   vpn       – VpnService declaration (connect, tunnel, split tunnel, disconnect)
#   location  – background location declaration (trusted Wi-Fi, both disclosures,
#               automatic connect / disconnect on a network change)
#   fgs       – foreground service declaration (notifications "always")
#   arrival   – foreground service type location (Messenger plugin: live arrival)
#   datasync  – foreground service type dataSync (Drive plugin: photo backup)
# plus store screenshots. Each video is a list of segments; every segment gets
# one caption (segments.tsv), compose.sh burns them in and joins the segments.
# The plugins for the last two are installed only after the first three
# (plugins.sh), so those stay as they were.
#
# Usage: record.sh APK ENROLL_LINK_FILE OUT_DIR [PHOTOS_DIR]
#   PHOTOS_DIR: JPEGs pushed to the camera folder for the photo backup video
set -u
APK="$1"
LINK="$(cat "$2")"
OUT="$3"
PHOTOS="${4:-}"
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
# shellcheck source=lib.sh
. "$HERE/lib.sh"

setup_app "$APK" "$LINK" || exit 1
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
sleep 4
rec_stop

rec_start vpn 04 "Purpose: secure remote access to the user's home network and own services (NAS, smart home, remote desktop)."
adb shell input swipe 540 1700 540 1000 400
sleep 3.5
shot 03-connected-details
adb shell input swipe 540 900 540 1700 400
sleep 1
rec_stop

rec_start vpn 05 "The user decides which apps or networks use the tunnel (split tunneling)."
nav "Settings"; sleep 1; shot 05-settings
tap "Split tunneling" --contains; sleep 3
key KEYCODE_BACK
rec_stop

rec_start vpn 06 "The user disconnects at any time. No ads, no tracking, no data collection, nothing goes to third parties."
nav "Home"; sleep 1
# The connected chip reads "Protected"; "Disconnect" is only its click label.
$UI tap "Disconnect" --timeout 3 >/dev/null 2>&1 || tap "Protected"; sleep 3
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

# Back to the start screen first: reopening the app shows the VPN state there.
key KEYCODE_BACK
nav "Home"
rec_start location 04 "App closed. Leaving the trusted Wi-Fi: the VPN connects automatically in the background."
key KEYCODE_HOME
adb shell svc wifi disable
vpn_wait up 40
sleep 2
app_front
sleep 2
rec_stop

rec_start location 05 "Back in the trusted Wi-Fi: the VPN disconnects automatically. No location is sent anywhere."
key KEYCODE_HOME
adb shell svc wifi enable
vpn_wait down 40
sleep 2
app_front
sleep 2
rec_stop

# ── Video 3: foreground service (notifications without Google) ─────
adb shell am force-stop "$PKG"
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 5
rec_start fgs 01 "Notifications from the user's own server, without Google services: the user chooses 'Always'."
nav "Settings"
tap "Notifications" --scroll || tap "Off – tap to set up" --contains --scroll
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
tap "Request a test message" --contains --scroll
sleep 4
shade_open 4
shade_close
rec_stop

# ── Videos 4 and 5: the plugins' foreground services ───────────────
# BACK only hides the keyboard while it is up; else it would close a sheet.
ime_shown() { adb shell dumpsys input_method 2>/dev/null | grep -qE "mInputShown=true|mIsInputViewShown=true"; }
hide_ime() { if ime_shown; then key KEYCODE_BACK; fi; }
geo() { adb emu geo fix "$2" "$1" >/dev/null 2>&1; }   # LAT LON (the console takes longitude first)
# WorkManager's foreground service runs while the photo backup uploads.
backup_running() { adb shell dumpsys activity services "$PKG" 2>/dev/null | grep -q "SystemForegroundService"; }
# A video that could not be finished: stop a running screenrecord and leave
# it out of segments.tsv, so no half video is published as complete.
video_failed() { # VIDEO
  echo "::error title=video $1::not complete – left out"
  adb shell pkill -INT screenrecord >/dev/null 2>&1
  [ -n "$REC_PID" ] && wait "$REC_PID" 2>/dev/null
  sed -i "/^$1\t/d" "$OUT/segments.tsv"
}
app_restart() {
  adb shell am force-stop "$PKG"
  adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  sleep 7
}

# Video 4: live arrival in a chat (FOREGROUND_SERVICE_LOCATION).
arrival_video() {
  # Off camera: the location permission back to "not asked yet" (trusted
  # Wi-Fi had it), so the video shows the request. The destination is picked
  # on the map's start view (its centre, 0°/0°); the phone is ~6 km away.
  local p
  for p in ACCESS_BACKGROUND_LOCATION ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION; do
    adb shell pm revoke "$PKG" "android.permission.$p" >/dev/null 2>&1
    adb shell pm clear-permission-flags "$PKG" "android.permission.$p" user-set user-fixed >/dev/null 2>&1
  done
  geo 0.0412 0.0371
  app_restart

  rec_start arrival 01 "Chats (optional, Messenger plugin on the user's own server): Anna asks when the user will be there."
  nav "Chats"
  tap "Anna" || return 1
  sleep 2.5
  rec_stop

  rec_start arrival 02 "The user starts a live arrival. Android asks for the location first: 'While using the app' is enough."
  tap "Attach" || return 1
  tap "Live arrival" || return 1
  tap_opt "While using the app" 10
  sleep 2
  rec_stop

  rec_start arrival 03 "The user picks the destination and starts. Only the people in this chat see the way, nobody else."
  tap "Pick on the map" || return 1                # the map's description; the tap sets the destination
  sleep 2
  tap "Name of the destination" || return 1
  adb shell input text "School"
  sleep 0.6
  hide_ime
  # Without the keyboard the whole sheet fits; no scrolling (a swipe on a
  # keyboard would type).
  if ! $UI tap "Start sharing" --timeout 4 >/dev/null 2>&1; then
    key KEYCODE_BACK                                # the keyboard was up after all
    tap "Start sharing" --scroll || return 1
  fi
  wait_text "LIVE" --timeout 15 >/dev/null 2>&1 || diag "no live arrival in the chat"
  geo 0.0380 0.0345
  sleep 2.5
  shot 09-live-arrival
  rec_stop

  rec_start arrival 04 "App in the background: a foreground service (type location) sends the position, shown by an ongoing notification."
  key KEYCODE_HOME
  geo 0.0350 0.0318
  shade_open 2
  $UI drag "Sharing your arrival" 320 --timeout 6 >/dev/null 2>&1 || diag "no arrival notification"
  sleep 3
  geo 0.0320 0.0290
  rec_stop

  rec_start arrival 05 "Sharing ends on arrival, after 3 hours at the latest, or at once with 'Stop' in the notification."
  if $UI tap "Stop" --timeout 5 >/dev/null 2>&1; then
    sleep 2
    shade_close
    app_front
  else
    diag "no Stop in the notification"
    shade_close
    app_front
    tap "Stop sharing" --scroll
  fi
  wait_text "Sharing ended" --timeout 10 >/dev/null 2>&1 || diag "arrival not ended"
  sleep 2
  rec_stop
}

# Video 5: photo backup to Drive (FOREGROUND_SERVICE_DATA_SYNC).
push_photos() {
  if [ -z "$PHOTOS" ] || ! ls "$PHOTOS"/*.jpg >/dev/null 2>&1; then
    echo "::warning title=photos::no test photos – the backup has nothing to upload"
    return 0
  fi
  adb shell mkdir -p /sdcard/DCIM/Camera
  adb push "$PHOTOS"/*.jpg /sdcard/DCIM/Camera/ >/dev/null
  # Into the media store (the backup reads it): rescan the volume.
  adb shell content call --uri content://media --method scan_volume --arg external_primary >/dev/null 2>&1
  sleep 4
  local n
  n=$(adb shell "content query --uri content://media/external/images/media --projection _id --where \"relative_path LIKE 'DCIM/%'\"" 2>/dev/null | grep -c "Row:")
  echo "::notice title=test photos::$n in the camera folder of the media store"
}

datasync_video() {
  push_photos
  # A slower link (about LTE upload) so the progress can be seen; undone below.
  adb emu network speed 40000:100000 >/dev/null 2>&1

  app_restart                                       # out of the chat of the arrival video
  rec_start datasync 01 "Photo backup (optional, Drive plugin): the user's photos go to their own GateControl server, not to a cloud."
  nav "Settings"
  tap "Photo backup" --scroll || return 1
  sleep 2
  rec_stop

  rec_start datasync 02 "The user switches it on, allows access to the photos and chooses which ones: all, or only new ones."
  tap "Back up photos" || return 1
  $UI tap "Allow all" --timeout 12 >/dev/null 2>&1 || tap "Allow" --timeout 4
  tap_opt "Allow" 3                                 # media location, should Android ask for it separately
  tap "All photos" --timeout 15 || return 1
  if ! wait_text "Uploading" --contains --timeout 20 >/dev/null 2>&1; then
    # "Only on Wi-Fi" is the default; should the emulator's network count as metered, allow any.
    if $UI wait "wait for Wi-Fi" --contains --timeout 2 >/dev/null 2>&1; then
      tap "Only on Wi-Fi" --scroll
      wait_text "Uploading" --contains --timeout 20 >/dev/null 2>&1 || diag "backup did not start"
    else
      diag "backup did not start"
    fi
  fi
  sleep 2
  shot 08-photo-backup
  rec_stop

  rec_start datasync 03 "App closed: the upload goes on as a foreground service (type dataSync) with a progress notification."
  key KEYCODE_HOME
  shade_open 2
  $UI drag "Photo backup" 320 --timeout 6 >/dev/null 2>&1 || diag "no backup notification"
  sleep 4
  shade_close
  rec_stop

  # Off camera: until the upload is through (the notification goes with it).
  local finished=0
  for _ in $(seq 1 100); do
    backup_running || break
    sleep 3
  done
  adb emu network speed full >/dev/null 2>&1
  app_front                                         # back on the backup page: counts refreshed
  wait_text "Everything is backed up." --timeout 20 >/dev/null 2>&1 && finished=1
  if [ "$finished" = 1 ]; then
    rec_start datasync 04 "Done: the notification ends with the upload. The photos are now in the user's Drive on their own server."
  else
    diag "photo backup not finished"
    rec_start datasync 04 "The notification stays as long as photos are uploading. The user can switch the backup off at any time."
  fi
  sleep 2
  shade_open 2
  shade_close
  rec_stop
  # Off camera: the backup off again.
  $UI tap "Back up photos" --timeout 4 >/dev/null 2>&1
  return 0
}

if "$HERE/plugins.sh"; then
  # The app reads the plugin list on start (a hint about the new plugins, if any, goes away).
  app_restart
  tap_opt "Not now" 3; tap_opt "Later" 2
  arrival_video || video_failed arrival
  datasync_video || video_failed datasync
  adb emu network speed full >/dev/null 2>&1
else
  echo "::error title=plugins::Drive / Messenger not set up – no live arrival and photo backup videos"
fi

adb shell am broadcast -a com.android.systemui.demo -e command exit >/dev/null
adb logcat -d > "$OUT/logcat.txt" 2>/dev/null
adb logcat -d -b crash > "$OUT/crash.txt" 2>/dev/null
ls -la "$OUT/segments" "$OUT/screenshots"
exit 0
