#!/usr/bin/env bash
# Starts a throwaway GateControl server for the Play review recordings
# (.github/workflows/play-videos.yml): the public image with WireGuard, an
# nginx TLS front on :8443 with a certificate for 10.0.2.2 (the host seen from
# the emulator), signed by a CA that only the debug build used for the
# recordings trusts. Writes the setup link to $OUT/enroll-link.txt and the
# admin password for plugins.sh to $GC_STATE (outside OUT: never published).
#
# Usage: server.sh OUT_DIR   (needs $APP_CA_DIR with ca.pem / server.pem / server.key)
set -euo pipefail
OUT="$1"
mkdir -p "$OUT"
CA="$APP_CA_DIR"
GC_STATE="${GC_STATE:-${RUNNER_TEMP:-/tmp}/gc-state}"
mkdir -p "$GC_STATE"
chmod 700 "$GC_STATE"
ADMIN_PASSWORD="Rec-$(openssl rand -hex 8)"
( umask 077; printf '%s' "$ADMIN_PASSWORD" > "$GC_STATE/admin-password" )

sudo modprobe wireguard || true
docker create --name gc --network host --cap-add NET_ADMIN --cap-add SYS_MODULE \
  -e GC_SECRET="$(openssl rand -hex 32)" \
  -e GC_ENCRYPTION_KEY="$(openssl rand -hex 32)" \
  -e GC_ADMIN_USER=admin -e GC_ADMIN_PASSWORD="$ADMIN_PASSWORD" \
  -e GC_HOST=127.0.0.1 -e GC_PORT=3000 \
  -e GC_BASE_URL=https://10.0.2.2:8443 \
  -e GC_WG_HOST=10.0.2.2 -e GC_WG_PORT=51820 \
  -e GC_WG_SUBNET=10.8.0.0/24 -e GC_WG_GATEWAY_IP=10.8.0.1 \
  -e GC_WG_ALLOWED_IPS=10.8.0.0/24 \
  -e GC_DEFAULT_LANGUAGE=en -e GC_LOG_LEVEL=info \
  -v gc-data:/data \
  ghcr.io/callmetechie/gatecontrol:latest >/dev/null

# RECORDING ONLY – a simulated plugin licence inside this throwaway container.
# Drive (photo backup → FOREGROUND_SERVICE_DATA_SYNC) and Messenger (live
# arrival → FOREGROUND_SERVICE_LOCATION) are paid plugins (license.required);
# their review videos need them running, and this server has no licence. So,
# before the first start, the container's own copy of license.js gets an
# entitlement for exactly these two plugin ids, as if the licence server had
# granted them. Nothing else changes: no licence key is set and no licence
# server is contacted, no key or secret is involved, the plugins are still
# installed from the official catalogue with their signatures checked by the
# server (plugins.sh), and the container with its data volume is gone with the
# runner after the job. The product and every real install are untouched.
# Without the patch the first three videos still record; plugins.sh then fails.
LIC="$GC_STATE/license.js"
if docker cp gc:/app/src/services/license.js "$LIC"; then
  cat >> "$LIC" <<'JS'

// ── Play review recording only (GateControl-Android-Client scripts/play-media/server.sh) ──
// Throwaway container: Drive and Messenger count as licensed (no tier: no seat limit).
{
  const RECORDING_PLUGINS = ['gatecontrol-drive', 'gatecontrol-messenger'];
  const real = module.exports.getPluginEntitlements;
  module.exports.getPluginEntitlements = () => [
    ...real().filter((e) => !RECORDING_PLUGINS.includes(e.slug)),
    ...RECORDING_PLUGINS.map((slug) => ({
      slug, name: slug, source: 'license', key_masked: null, valid: true, error: null,
      expires_at: null, updates_until: null, tier: null, seats: null,
    })),
  ];
}
JS
  docker cp "$LIC" gc:/app/src/services/license.js || echo "::warning title=licence::patch not copied"
else
  echo "::warning title=licence::license.js not found in the image – no recording licence"
fi
docker start gc >/dev/null

for i in $(seq 1 90); do
  curl -sf http://127.0.0.1:3000/health >/dev/null && break
  sleep 2
done
curl -sf http://127.0.0.1:3000/health >/dev/null || { docker logs gc | tail -80; exit 1; }

cat > "$OUT/nginx.conf" <<NGX
server {
  listen 8443 ssl;
  ssl_certificate /certs/server.pem;
  ssl_certificate_key /certs/server.key;
  # Photo backup: photos go up in one piece up to 8 MB (nginx allows 1 MB by default).
  client_max_body_size 64m;
  location / {
    proxy_pass http://127.0.0.1:3000;
    proxy_http_version 1.1;
    proxy_set_header Host \$host:8443;
    proxy_set_header X-Forwarded-For \$remote_addr;
    proxy_set_header X-Forwarded-Proto https;
    proxy_buffering off;
    proxy_read_timeout 3600s;
  }
}
NGX
docker run -d --name tls --network host \
  -v "$OUT/nginx.conf:/etc/nginx/conf.d/default.conf:ro" -v "$CA:/certs:ro" \
  nginx:1.27-alpine
sleep 3
curl -sf --cacert "$CA/ca.pem" --resolve 10.0.2.2:8443:127.0.0.1 https://10.0.2.2:8443/health >/dev/null \
  || { docker logs tls | tail -40; exit 1; }

# One-shot setup code for the admin user (as "Android-App einrichten" does).
# The service logs to stdout too: pick the code out by its shape.
CODE=$(docker exec gc node -e "
  const e = require('/app/src/services/clientEnrollment');
  const r = e.createCode({ userId: 1, name: 'Pixel 9' });
  process.stdout.write('\\nSETUPCODE=' + r.code + '\\n');
" | sed -n 's/^SETUPCODE=\([0-9A-F-]\{19\}\)$/\1/p' | tail -1)
[ -n "$CODE" ] || { echo "::error::no setup code"; exit 1; }
# The app would open the portal after every connect; not part of the videos.
docker exec gc node -e "require('/app/src/services/settings').set('portal.autoappear', '0')" >/dev/null
echo "gatecontrol://enroll?url=https%3A%2F%2F10.0.2.2%3A8443&code=$CODE" > "$OUT/enroll-link.txt"
echo "setup link written"

# The recording licence is active (see above): both ids listed as valid.
LICENSED=$(docker exec gc node -e "
  const l = require('/app/src/services/license');
  const ok = l.getPluginEntitlements().filter((e) => e.valid).map((e) => e.slug);
  process.stdout.write('\\nRECLICENCE=' + ok.join(',') + '\\n');
" | sed -n 's/^RECLICENCE=//p' | tail -1) || true
echo "::notice title=recording licence::${LICENSED:-<none>}"
