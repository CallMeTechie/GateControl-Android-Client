# Changelog

## [1.3.10] - 2026-04-15

### Fixes
- remove implicit network exclusion for Android Auto

---

## [1.3.9] - 2026-04-15

### Fixes
- broaden Wi-Fi Direct exclusion to full 192.168.0.0/16 + multicast

---

## [1.3.8] - 2026-04-15

### Fixes
- add mock context to SetupViewModelTest for new constructor parameter

---

## [1.3.7] - 2026-04-15

### Fixes
- reject VPN-internal DNS responses in preResolveDns

---

## [1.3.6] - 2026-04-15

### Changes
- trigger release for v1.3.5

---

## [1.3.5] - 2026-04-15

### Fixes
- expand Wi-Fi Direct exclusion with link-local for mDNS discovery

---

## [1.3.4] - 2026-04-14

### Fixes
- invalidate DNS cache on VPN disconnect

---

## [1.3.3] - 2026-04-14

### Fixes
- add org.json test dependency and handle JSON null correctly

---

## [1.3.2] - 2026-04-14

### Fixes
- auto-exclude Wi-Fi Direct subnet when Android Auto is excluded

---

## [1.3.1] - 2026-04-14

### Fixes
- load recommended apps via getApplicationInfo instead of launcher query

---

## [1.3.0] - 2026-04-14

### Features
- recommended apps section in split-tunnel app picker

---

## [1.2.29] - 2026-04-13

### Fixes
- add ::/0 to AllowedIPs in exclude mode to prevent IPv6 leak

---

## [1.2.28] - 2026-04-13

### Fixes
- missing imports, success field in UiState, locale test update

---

## [1.2.27] - 2026-04-13

### Fixes
- use local split-tunnel settings when no admin preset exists

---

## [1.2.26] - 2026-04-13

### Fixes
- add missing imports for toBitmap and Modifier.size

---

## [1.2.25] - 2026-04-13

### Fixes
- add queries element for launcher intent visibility (Android 11+)

---

## [1.2.24] - 2026-04-13

### Fixes
- use queryIntentActivities for app list (Android 11+ package visibility)

---

## [1.2.23] - 2026-04-13

### Fixes
- WifiSubnetDetector iterates allNetworks to find WiFi (not VPN)

---

## [1.2.22] - 2026-04-13

### Fixes
- remove 10.0.0.0/8 from private nets preset (conflicts with WireGuard VPN subnet)

---

## [1.2.21] - 2026-04-12

### Fixes
- update VpnViewModelTest to use new connect(config, SplitTunnelConfig) signature

---

## [1.2.20] - 2026-04-12

### Fixes
- don't overwrite DNS cache + delay API calls after VPN connect

---

## [1.2.19] - 2026-04-11

### Fixes
- add missing imports + update theme default test to 'system'

---

## [1.2.18] - 2026-04-11

### Fixes
- call suspend preResolveDns inside coroutine scope

---

## [1.2.17] - 2026-04-11

### Fixes
- use explicit Dns object instead of SAM lambda (Kotlin type inference)

---

## [1.2.16] - 2026-04-11

### Fixes
- validate API token on app start, redirect to setup when expired

---

## [1.2.15] - 2026-04-11

### Fixes
- use disconnect-all endpoint instead of endRdpSession (no sessionId needed)

---

## [1.2.14] - 2026-04-11

### Fixes
- scale RDP resolution proportionally to preserve device aspect ratio

---

## [1.2.13] - 2026-04-11

### Changes
- cap mobile RDP resolution to 1920x1080 + enable GFX pipeline

---

## [1.2.12] - 2026-04-11

### Fixes
- auto-detect device resolution + direct pixel rendering (no Compose)

---

## [1.2.11] - 2026-04-11

### Fixes
- create bitmap from SettingsChanged (FreeRDP 3.x never fires GraphicsResize)

---

## [1.2.10] - 2026-04-11

### Changes
- log session map state, all FreeRDP events, and connection lifecycle

---

## [1.2.9] - 2026-04-11

### Fixes
- register bitmap with FreeRDP and call updateGraphics for rendering

---

## [1.2.8] - 2026-04-11

### Changes
- fix RdpCredentialHandlerTest serverEncrypt to use separate data+authTag format

---

## [1.2.7] - 2026-04-10

### Fixes
- correct diagnostic log references (E2eePayload.data.length, remove e2eeEnabled)

---

## [1.2.6] - 2026-04-10

### Fixes
- only show auth dialog when credentials are actually missing

---

## [1.2.5] - 2026-04-10

### Fixes
- accept FreeRDP OnAuthenticate callback instead of rejecting

---

## [1.2.4] - 2026-04-10

### Fixes
- initialize FreeRDP sessionMap via reflection to prevent RDP crash

---

## [1.2.3] - 2026-04-10

### Changes
- update save() test to match commitBatch() signature

---

## [1.2.2] - 2026-04-09

### Changes
- clarify 1.2.1 CHANGELOG entry to reflect Phase 2 FreeRDP content

---

## [1.2.1] - 2026-04-09

### Changes
- Initial unreleased cut carrying the Phase 2 embedded FreeRDP client work
  from 1.2.0 plus the emulator smoke test harness (workflow_dispatch only).
  Not published as a GitHub release — superseded by 1.2.2.

---

## [1.2.0] - 2026-04-09

### Added
- **Embedded FreeRDP client:** RDP sessions now render inside the GateControl
  app via the upstream FreeRDP 3.24.2 library (arm64-v8a). Touch → mouse and
  certificate verification dialogs are wired in. No more dependency on an
  external RDP app — everything happens in one process with `FLAG_SECURE`
  protecting the remote desktop from screenshots.
- `RdpBookmarkBuilder`, `RdpSessionEvent`, `GateControlUiEventListener`,
  `RdpSessionController`, `RdpCanvasView`, `CertificateVerifyDialog` — new
  embedded-session stack under `com.gatecontrol.android.rdp.freerdp.*` and
  `com.gatecontrol.android.rdp.*`.
- `NOTICE` file at repo root with FreeRDP Apache 2.0 + `freeRDPCore/domain`
  MPL 2.0 attribution.
- `scripts/sync-freerdp-aar.sh` for developers who prefer to download the
  AAR artifact from CI instead of building FreeRDP natively.

### Changed
- `core/rdp/libs/freerdp-android.aar` is now a required build dependency,
  produced by `.github/workflows/freerdp-build.yml` from the `freerdp/`
  submodule pinned to tag `3.24.2`. The AAR is committed automatically by
  the CI bot.
- `RdpEmbeddedClient.isAvailable()` now returns `true` whenever the AAR is
  on the classpath (always in shipped builds). `RdpManager.connect()` routes
  every session through the embedded client; the external RDP-client path
  is now the defensive fallback.
- APK ships arm64-v8a only; release size ≈ +6 MB compared to 1.1.26.
- `:core:rdp` consumes the AAR via `compileOnly` + `testImplementation`; `:app`
  packages it via `implementation`. This is the AGP-recommended split for
  library modules that need to reference classes from a local AAR.

### Removed
- `RdpEmbeddedClient.PHASE_2_ENABLED` feature flag.
- Phase-1 reflection placeholder in `RdpSessionActivity`.

---

## [1.1.26] - 2026-04-08

### Fixes
- RDP TCP-Check über Server statt lokalen Socket

---

## [1.1.25] - 2026-04-08

### Fixes
- ensureHttps in Setup + Settings ViewModels

---

## [1.1.24] - 2026-04-08

### Fixes
- SettingsViewModelTest nutzt String-Literal statt undefinierter Variable

---

## [1.1.23] - 2026-04-08

### Fixes
- add retrofit + okhttp dependencies for RDP error handling

---

## [1.1.22] - 2026-04-08

### Fixes
- add split-tunnel mock returns to VpnViewModelTest

---

## [1.1.21] - 2026-04-08

### Changes
- add dependency scanning, JaCoCo coverage, R8 verification

---

## [1.1.20] - 2026-04-08

### Fixes
- pass ApplicationContext to ApiClientProvider in NetworkModule

---

## [1.1.19] - 2026-04-07

### Fixes
- Gson LenientBooleanAdapter for SQLite 0/1, revert fields to Boolean

---

## [1.1.18] - 2026-04-07

### Fixes
- second RdpRoute test fixture also needs Int? for boolean fields

---

## [1.1.17] - 2026-04-07

### Fixes
- handle startActivityAndCollapse for API 31-33 and suppress lint error

---

## [1.1.16] - 2026-04-07

### Fixes
- remove CodeQL job (requires GitHub Advanced Security on private repos)

---

## [1.1.15] - 2026-04-07

### Fixes
- handshake display, dark mode buttons, license status, Quick Settings tile

---

## [1.1.14] - 2026-04-07

### Fixes
- add actions:read permission for CodeQL workflow

---

## [1.1.13] - 2026-04-07

### Fixes
- VPN permission request, file logging, QR scanner, config import

---

## [1.1.12] - 2026-04-07

### Fixes
- replace CodeQL autobuild with explicit debug build

---

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
