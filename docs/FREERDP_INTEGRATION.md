# FreeRDP Integration

**Status:** Phase 2 aktiv — der eingebettete FreeRDP-Client läuft in Release-Builds.
`RdpEmbeddedClient.isAvailable()` liefert `true` sobald die AAR unter
`core/rdp/libs/` liegt. `RdpManager` routet jede RDP-Verbindung durch den
eingebetteten Client; der externe RDP-Client-Pfad existiert nur noch als
defensiver Fallback, wenn die AAR fehlt.

**Datum:** 2026-04-09
**Plan:** `docs/superpowers/plans/2026-04-09-freerdp-phase2-integration.md`
**Vorheriger Plan (Phase 1, geparkt):** `docs/superpowers/plans/2026-04-08-freerdp-integration.md`

## Ziel

FreeRDP als eingebetteten In-App RDP-Client integrieren, mit automatischem Fallback
auf externen RDP-Client falls die FreeRDP Library (AAR) nicht verfügbar ist.

## Architektur

```
RdpManager.connect()
  └─► RdpEmbeddedClient.isAvailable()
        ├─ TRUE  → launchSession() → Intent → RdpSessionActivity
        │                                       ├─ FreeRDP SessionView (Reflection)
        │                                       └─ RdpSessionService (Foreground Notification)
        └─ FALSE → RdpExternalClient (bestehender Flow mit Clipboard-Passwort)
```

### Komponenten

| Komponente | Ort | Rolle |
|------------|-----|-------|
| `RdpConnectionParams` | `core/rdp/.../rdp/` | Typisiertes Parameter-Bundle (host, port, credentials, resolution, etc.) |
| `RdpEmbeddedClient` | `core/rdp/.../rdp/` | `isAvailable()` via `Class.forName`, `buildSessionIntent()`, `launchSession()` |
| `RdpSessionActivity` | `app/.../rdp/` | Fullscreen Activity mit `FLAG_SECURE`, floating Toolbar, FreeRDP via Reflection |
| `RdpSessionService` | `app/.../service/` | Foreground-Service mit Dauer-Notification + Disconnect-Action |
| `RdpManager` | `core/rdp/.../rdp/` | Routing: Embedded → Intent, External → Intent + Clipboard |

## Sicherheit

- **`FLAG_SECURE`** auf `RdpSessionActivity` — verhindert Screenshots & Screen-Recording
- **Kein Clipboard** bei Embedded Client — Passwörter werden per Intent-Extra übergeben
- **Kein Serializable/Parcelable** auf `RdpConnectionParams` — keine versehentlichen Disk-Writes
- **Credential Wipe** in `onDestroy()` (`params = null`) + `credentialHandler.clear()`
- **CI-Check**: Credential-Leak-Grep auf `Timber.*password` in `pr-check.yml`
- **CI-Check**: `FLAG_SECURE` in `RdpSessionActivity.kt` erzwungen

## i18n Strings (EN + DE)

- `rdp_embedded_connecting`, `rdp_embedded_reconnecting`
- `rdp_embedded_disconnect_confirm`, `rdp_embedded_disconnect_message`
- `rdp_embedded_connection_lost`, `rdp_embedded_reconnect_failed`
- `rdp_progress_service` (neue Progress-Stufe)
- `notif_rdp_session`, `notif_rdp_duration`, `notif_rdp_disconnect`

## Progress-Schritte

`RdpProgress` Enum um `SERVICE_START(6)` erweitert, `COMPLETE` ist jetzt Step 7.
`RdpConnectSheet` + `RdpViewModelTest` entsprechend aktualisiert.

## AAR-Integration

`core/rdp/build.gradle.kts` bindet das AAR konditional ein:

```kotlin
if (file("libs/freerdp-android.aar").exists()) {
    implementation(files("libs/freerdp-android.aar"))
}
```

Solange `core/rdp/libs/freerdp-android.aar` nicht existiert:
- `RdpEmbeddedClient.isAvailable()` → `false` (ClassNotFoundException)
- `RdpManager` nutzt automatisch `RdpExternalClient` (externer RDP Client + Clipboard)
- Keine Verhaltensänderung für Endnutzer

## CI Workflow

`.github/workflows/freerdp-build.yml` baut das AAR aus einem FreeRDP Git-Submodule
unter `freerdp/`. **Noch nicht funktional**, weil das Submodule noch nicht eingecheckt
ist — aktueller Run zeigt erwartungsgemäß "CMakeLists.txt not found".

### Nächste Schritte für vollständige Aktivierung

1. `git submodule add https://github.com/CallMeTechie/FreeRDP-Android freerdp/aFreeRDP`
2. Submodule initialisieren, Build lokal verifizieren
3. `freerdp-build.yml` erfolgreich durchlaufen lassen
4. AAR-Artefakt wird automatisch committet → `RdpEmbeddedClient.isAvailable()` → `true`

## Geänderte/neue Dateien (17 Commits)

### Neu
- `core/rdp/src/main/.../RdpConnectionParams.kt` + Test
- `core/rdp/src/test/.../RdpEmbeddedClientTest.kt`
- `core/rdp/src/test/.../RdpCredentialSecurityTest.kt`
- `app/src/main/.../rdp/RdpSessionActivity.kt` + Test (MockK)
- `app/src/main/.../service/RdpSessionService.kt` + Test
- `.github/workflows/freerdp-build.yml`
- `freerdp/VERSION`
- `core/rdp/libs/.gitkeep`

### Modifiziert
- `core/rdp/src/main/.../RdpModels.kt` (Progress Enum)
- `core/rdp/src/main/.../RdpEmbeddedClient.kt` (launchSession, buildSessionIntent, collectExtras)
- `core/rdp/src/main/.../RdpManager.kt` (Embedded → External Fallback Logik)
- `core/rdp/build.gradle.kts` (konditionale AAR-Dependency)
- `core/rdp/consumer-rules.pro` (FreeRDP ProGuard)
- `app/proguard-rules.pro` (FreeRDP ProGuard)
- `app/src/main/AndroidManifest.xml` (Activity + Service Registrierung)
- `app/src/main/res/values{,-de}/strings.xml` (10 neue i18n Keys)
- `app/src/main/.../ui/rdp/RdpConnectSheet.kt` (SERVICE_START Step)
- `app/src/test/.../ui/rdp/RdpViewModelTest.kt` (2 neue Tests)
- `.github/workflows/pr-check.yml` (Credential-Leak + FLAG_SECURE Checks)
- `.github/workflows/release.yml` (AAR-Existence Check)

## Fixes während Implementierung

1. **`AppCompatActivity` → `ComponentActivity`** — Das App-Modul nutzt Jetpack Compose
   ohne AppCompat-Dependency. `RdpSessionActivity` erbt jetzt von `ComponentActivity`.
2. **`RdpSessionActivityTest` auf MockK umgestellt** — `Intent()` in reinem JUnit ohne
   Android Framework speichert keine Extras. Tests nutzen jetzt MockK für Intent.

## Phase-2 Komponenten (jetzt live)

| Komponente | Datei | Rolle |
|------------|-------|-------|
| `RdpBookmarkBuilder` | `core/rdp/.../freerdp/RdpBookmarkBuilder.kt` | `RdpConnectionParams` → `ManualBookmark` |
| `RdpSessionEvent` | `core/rdp/.../freerdp/RdpSessionEvent.kt` | Sealed class für Lifecycle-Events |
| `GateControlUiEventListener` | `core/rdp/.../freerdp/GateControlUiEventListener.kt` | Implementiert `LibFreeRDP.UIEventListener`, postet in `StateFlow` |
| `RdpSessionController` | `core/rdp/.../freerdp/RdpSessionController.kt` | Background-Thread-Wrapper um `LibFreeRDP.connect`/`disconnect`/`freeInstance` |
| `RdpCanvasView` | `app/.../rdp/RdpCanvasView.kt` | Custom `View` mit `Bitmap`-Backbuffer + Touch → `sendCursorEvent` |
| `CertificateVerifyDialog` | `app/.../rdp/CertificateVerifyDialog.kt` | Compose-Dialog für unbekannte/geänderte Zertifikate |
| `RdpSessionActivity` (rewritten) | `app/.../rdp/RdpSessionActivity.kt` | Fullscreen `ComponentActivity` mit Compose + `runBlocking`-Bridge für Cert-Verdict |

## Upgrading the pinned FreeRDP version

1. Edit `core/rdp/FREERDP_VERSION` → write the new tag (e.g. `3.25.0`).
2. `cd freerdp && git fetch --tags && git checkout <new-tag> && cd ..`
3. `git add freerdp core/rdp/FREERDP_VERSION`
4. `git commit -m "chore: bump FreeRDP to <new-tag>"`
5. `git push` — this fires `freerdp-build.yml` which rebuilds the AAR,
   commits it, and pushes the auto-commit with the updated binary.
6. After the workflow completes: `git pull` to receive the new AAR.
7. Smoke-test on a device before tagging a release.

## Phase-2 manual QA checklist

- [ ] Connect to a Windows Server 2019+ target over local VPN
- [ ] Connect to an `xrdp` target
- [ ] Verify mouse clicks produce remote actions
- [ ] Verify soft keyboard input produces remote keystrokes (Phase-2.1 follow-up — only on-screen toolbar shortcuts in first release)
- [ ] Verify back-press disconnects cleanly (no process orphaned)
- [ ] Verify foreground notification appears and is dismissible
- [ ] Verify `FLAG_SECURE` — attempt to screenshot, confirm blank
- [ ] Verify certificate dialog fires for self-signed cert
- [ ] Verify "always trust" persists across app restart
- [ ] Verify APK install size < 50 MB

## Tests

- `core:rdp` Test Suite: **grün** (inkl. 6 `RdpCredentialSecurityTest`)
- `app` Test Suite: **grün** (inkl. 3 `RdpSessionActivityTest`, 3 `RdpSessionServiceTest`, 2 neue `RdpViewModelTest`)
- CI `Build and Release`: **✓ success**
- CI `Security & Quality`: **✓ success**
- CI `Build FreeRDP AAR`: **manual-only** (`workflow_dispatch`), kein `push`-Trigger mehr

## Licensing

- FreeRDP core (C + most Java code): **Apache License 2.0**
- `freeRDPCore/domain/*.java` (`BookmarkBase`, `ManualBookmark`, `ConnectionReference`, etc.): **Mozilla Public License 2.0**

Both licenses are compatible with the project's existing licensing. Attribution is maintained in the top-level `NOTICE` file. When distributing the APK, the NOTICE file must be included.
