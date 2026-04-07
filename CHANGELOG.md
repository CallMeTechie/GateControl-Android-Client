# Changelog

## [1.1.11] - 2026-04-07

### Fixes
- set TunnelState.Connected in RdpViewModelTest for RDP connect tests

---

## [1.1.10] - 2026-04-07

### Fixes
- save API token before register call to prevent 401

---

## [1.1.9] - 2026-04-07

### Fixes
- update dependencies for Android 16 (API 36) compatibility

---

## [1.1.8] - 2026-04-07

### Fixes
- move crash handler to attachBaseContext, write to Downloads folder

---

## [1.1.7] - 2026-04-07

### Fixes
- disable R8 minification temporarily for crash diagnosis

---

## [1.1.6] - 2026-04-07

### Fixes
- add debug APK to release for crash diagnosis

---

## [1.1.5] - 2026-04-07

### Fixes
- add crash logger to write crash reports to external storage

---

## [1.1.4] - 2026-04-06

### Fixes
- startup crash - ProGuard rules, EncryptedStorage lazy init, compileSdk 35

---

## [1.1.3] - 2026-04-06

### Changes
- add product description, setup guide, feature docs, architecture and FAQ

---

## [1.1.2] - 2026-04-06

### Changes
- add privacy policy markdown to docs/

---

## [1.1.1] - 2026-04-06

### Fixes
- replace removeFirst() with removeAt(0) for API 31 compat

---

## [1.1.0] - 2026-04-06

### Features
- initial release with com.gatecontrol.client

---

## [1.1.0] - 2026-04-06

### Features
- enable R8 with ProGuard rules, native debug symbols, mapping file for Play Store

---

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
