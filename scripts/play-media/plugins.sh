#!/usr/bin/env bash
# Sets up the throwaway server from server.sh for the last two Play review
# videos (record.sh): the paid plugins Drive (photo backup → foreground
# service type dataSync) and Messenger (live arrival → type location).
#
#   * Signed in to the admin UI like an administrator (form login, CSRF
#     token), "Install" from the official plugin catalogue: the server
#     downloads the package, checks its sha256 and its signature, the admin
#     confirms the permissions (the same two calls the admin UI makes).
#     Their licence is simulated inside the container (see server.sh).
#   * Drive: the admin gets a seat (Drive's own admin API, as on its page
#     "Personen"); the weekly "Drive files are not backed up" hint is off so
#     it does not show up in the recordings. Storage: this server (default).
#   * Messenger: a second account "Anna" (no web login, like a family member)
#     with an app token writes the admin a direct message, so there is a
#     chat to share the arrival in.
#
# Usage: plugins.sh   (needs $APP_CA_DIR and $GC_STATE/admin-password from server.sh)
set -uo pipefail
GC_STATE="${GC_STATE:-${RUNNER_TEMP:-/tmp}/gc-state}"
B=https://10.0.2.2:8443
JAR="$GC_STATE/admin-cookies.txt"
PW="$(cat "$GC_STATE/admin-password" 2>/dev/null)" || PW=""
: > "$JAR"

fail() { echo "::error title=plugins::$*"; exit 1; }
[ -n "$PW" ] || fail "no admin password from server.sh"
# https through the nginx front: the session cookie is Secure.
c() { curl -sS --max-time 180 --cacert "$APP_CA_DIR/ca.pem" --resolve 10.0.2.2:8443:127.0.0.1 -b "$JAR" -c "$JAR" "$@"; }
# One value of a JSON answer ("" when it is not JSON or has no such value).
val() { printf '%s' "$1" | jq -r "${@:3}" "$2 // empty" 2>/dev/null; }
short() { printf '%s' "$1" | head -c 400; }

# ── Admin session ───────────────────────────────────────────────────
LOGIN_CSRF=$(c "$B/login" | sed -n 's/.*name="_csrf" value="\([^"]*\)".*/\1/p' | head -1)
[ -n "$LOGIN_CSRF" ] || fail "no CSRF token on /login"
R=$(c -o /dev/null -w '%{http_code} %{redirect_url}' --data-urlencode "username=admin" \
  --data-urlencode "password=$PW" --data-urlencode "_csrf=$LOGIN_CSRF" "$B/login")
case "$R" in 302*dashboard*) ;; *) fail "admin login: $R" ;; esac
CSRF=$(c -L "$B/dashboard" | sed -n "s/.*csrfToken: '\([^']*\)'.*/\1/p" | head -1)
[ -n "$CSRF" ] || fail "no CSRF token after login"

api() { # METHOD PATH [JSON] – the admin API with the session
  local body=()
  [ $# -ge 3 ] && body=(--data "$3")
  c -X "$1" -H "X-CSRF-Token: $CSRF" -H 'Accept: application/json' -H 'Content-Type: application/json' \
    "${body[@]}" "$B/api/v1/$2"
}

# ── Anna: an account with an app token ──────────────────────────────
# Before the plugins start: the messenger knows the people as they were
# when it last looked (a cache of a few seconds).
ANNA=$(timeout 90 docker exec gc node -e "
  const users = require('/app/src/services/users');
  const tokens = require('/app/src/services/tokens');
  const u = users.createClientUser({ username: 'anna', displayName: 'Anna' });
  const t = tokens.create({ name: 'Anna – Pixel 8', scopes: ['client'], userId: u.id });
  process.stdout.write('\\nANNATOKEN=' + t.rawToken + '\\n');
" | sed -n 's/^ANNATOKEN=\(gc_[0-9a-f]*\)$/\1/p' | tail -1)
[ -n "$ANNA" ] || fail "no account for Anna"

# ── Install from the catalogue ──────────────────────────────────────
CAT=""
for _ in 1 2 3; do
  CAT=$(api GET plugin-catalog)
  [ "$(val "$CAT" '.available')" = true ] && break
  sleep 10
done
for ID in gatecontrol-drive gatecontrol-messenger; do
  # shellcheck disable=SC2016 # $id is jq's
  VER=$(val "$CAT" '.plugins[] | select(.id == $id) | .latest.version' --arg id "$ID")
  [ -n "$VER" ] || fail "$ID: no installable version in the catalogue ($(short "$CAT"))"
  INSPECT=$(api POST plugin-catalog/install "{\"id\":\"$ID\",\"version\":\"$VER\"}")
  TOKEN=$(val "$INSPECT" 'select(.ok and .canInstall and .plugin.verified) | .token')
  [ -n "$TOKEN" ] || fail "$ID $VER: download or check refused ($(short "$INSPECT"))"
  DONE=$(api POST plugins/install "{\"token\":\"$TOKEN\",\"accept\":true}")
  [ "$(val "$DONE" '.ok')" = true ] || fail "$ID $VER: install failed ($(short "$DONE"))"
  ST=""
  for _ in $(seq 1 60); do
    ST=$(val "$(api GET "plugins/$ID")" '.plugin.status + " (licence " + .plugin.license.state + ")"')
    case "$ST" in running*) break ;; esac
    sleep 1
  done
  echo "::notice title=plugin $ID $VER::$ST"
  case "$ST" in running*) ;; *) fail "$ID does not run: $ST" ;; esac
done

# ── Drive: a seat for the admin (user 1), no backup hint ────────────
SEAT=$(api PUT plugins/gatecontrol-drive/api/admin/seats/1 '{"kind":"family"}')
[ "$(val "$SEAT" '.seat.kind')" = family ] || fail "Drive seat: $(short "$SEAT")"
api PUT plugins/gatecontrol-drive/api/admin/backup-hint '{"on":false}' >/dev/null

# ── Messenger: Anna writes the admin ────────────────────────────────
M="$B/api/v1/client/plugins/gatecontrol-messenger/api"
anna() { curl -sS --max-time 60 --cacert "$APP_CA_DIR/ca.pem" --resolve 10.0.2.2:8443:127.0.0.1 \
  -H "Authorization: Bearer $ANNA" -H 'Accept: application/json' -H 'Content-Type: application/json' "$@"; }
CHAT="" CONV=""
for _ in 1 2 3 4; do
  CHAT=$(anna --data '{"kind":"direct","userIds":[1]}' "$M/conversations")
  CONV=$(val "$CHAT" '.conversation.id')
  [ -n "$CONV" ] && break
  sleep 6
done
[ -n "$CONV" ] || fail "no chat Anna → admin ($(short "$CHAT"))"
MSG=$(anna --data '{"clientId":"rec-anna-0001","text":"Are you on your way? School is out at 4."}' "$M/conversations/$CONV/messages")
[ "$(val "$MSG" '.ok')" = true ] || fail "Anna's message: $(short "$MSG")"
echo "plugins ready (chat $CONV)"
