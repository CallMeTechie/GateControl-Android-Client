# Changelog

## [1.0.3] - 2026-04-06

### Fixes
- rename release artifacts to GateControl-Android-VERSION.apk/.aab

---

## [1.0.2] - 2026-04-06

### Fixes
- handle empty env vars in signing config (fallback to generated keystore)

---

## [1.0.1] - 2026-04-05

### Fixes
- unignore CHANGELOG.md, fix awk-based changelog update in CI

---

## [1.0.0] — 2026-04-05

### Features
- Initial release
- WireGuard VPN tunnel management
- RDP session management (Pro)
- Auto-connect, auto-reconnect with exponential backoff
- Kill-Switch via Android system VPN settings
- Split tunneling (IP-based + app-based)
- Quick Settings tile
- QR code setup + deep-link support
- DNS leak test
- Traffic statistics (24h/7d/30d/total)
- Real-time bandwidth graph
- Dark/Light theme (GateControl branded)
- German + English localization
- Auto-update from server + Play Store
- E2EE credential delivery for RDP
- Wake-on-LAN for offline RDP hosts
