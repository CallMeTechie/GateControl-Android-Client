# FreeRDP Integration

**Status:** Implementiert (Phase 1 — Framework komplett, AAR ausstehend)
**Datum:** 2026-04-09
**Plan:** `docs/superpowers/plans/2026-04-08-freerdp-integration.md`

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

## Tests

- `core:rdp` Test Suite: **grün** (inkl. 6 `RdpCredentialSecurityTest`)
- `app` Test Suite: **grün** (inkl. 3 `RdpSessionActivityTest`, 3 `RdpSessionServiceTest`, 2 neue `RdpViewModelTest`)
- CI `Build and Release`: **✓ success**
- CI `Security & Quality`: **✓ success**
- CI `Build FreeRDP AAR`: **✗ expected failure** (freerdp-Submodule noch nicht eingecheckt)
