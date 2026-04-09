# FreeRDP Integration

**Status:** Phase 1 abgeschlossen und **bewusst geparkt**. Embedded-Pfad ist hart
deaktiviert via `RdpEmbeddedClient.PHASE_2_ENABLED = false`. Externer RDP-Client
bleibt der einzige aktive Pfad. Siehe "Known Gaps" unten für den Grund.

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

## Known Gaps (Warum Phase 1 geparkt ist)

Nach Abschluss von Phase 1 wurde festgestellt, dass der ursprüngliche Plan auf einer
**imaginären `LibFreeRDP`-API** basiert, die im echten FreeRDP-Upstream nicht existiert.
Die bereits committete Reflection-Glue in `RdpSessionActivity` würde beim ersten
tatsächlichen Connect-Versuch mit `NoSuchMethodException` crashen.

### API-Mismatch mit upstream `FreeRDP/FreeRDP` `client/Android/Studio/freeRDPCore`

| Unser Code | Upstream-Realität |
|------------|-------------------|
| `setConnectionInfo(long, String, int, String, String, String, int, int, int, boolean, boolean, boolean, String, boolean, int)` — 15-Parameter-Variante | **Existiert nicht.** Real: `setConnectionInfo(Context, long, BookmarkBase)` oder `setConnectionInfo(Context, long, Uri)` |
| `freeSession(long)` | **Existiert nicht.** Real: `freeInstance(long)` |
| `newInstance(Context) → long` | ✓ passt |
| `connect(long) → void` | ~ passt (real: `connect(long) → boolean`) |
| `disconnect(long) → void` | ~ passt (real: `disconnect(long) → boolean`) |

### Fehlende Komponenten im aktuellen Code

- **Rendering-Pipeline:** Kein `EventListener` registriert, kein `Bitmap`-Backbuffer,
  kein `updateGraphics(long, Bitmap, int, int, int, int)`-Callback-Handling. Die
  `SessionView` alleine zeigt nichts an.
- **Input-Forwarding:** `sendCursorEvent`, `sendKeyEvent`, `sendUnicodeKeyEvent`,
  `sendClipboardData` werden nie aufgerufen. Touch/Keyboard-Eingaben würden ins
  Leere laufen.
- **Zertifikats-Handling:** Kein `EventListener` für die `verifyCertificate*`-Callbacks.
  Verbindungen zu Servern mit selbstsignierten Zertifikaten würden stumm scheitern.
- **Submodule:** `freerdp/` enthält nur `VERSION`, nicht die FreeRDP-Sourcen.
  `freerdp-build.yml` schlägt ohne `CMakeLists.txt` fehl (deshalb jetzt manual-only).

### Warum der Code trotzdem committed bleibt

- Das **Kotlin/UI-Gerüst** (Service, Activity-Skeleton, `RdpConnectionParams`,
  `RdpEmbeddedClient`, i18n, Progress-UI, Security-Tests) ist framework-agnostisch,
  sauber getestet und **in Phase 2 wiederverwendbar**.
- Die **Routing-Logik in `RdpManager`** (Embedded → Intent, Fallback → External) ist
  korrekt und wird in Phase 2 unverändert benötigt.
- **`PHASE_2_ENABLED = false`** in `RdpEmbeddedClient` macht die defekte Reflection
  unerreichbar — `isAvailable()` liefert immer `false`, jeder Connect geht über den
  funktionierenden External-Client.
- Die Reflection-Platzhalter in `RdpSessionActivity.initFreeRdpSession` +
  `configureFreeRdpSettings` sind mit einem großen `⚠ PHASE-2 PLACEHOLDER`-Block
  markiert und dienen als **Struktur-Referenz** für den Phase-2-Rewrite.

### Benutzer-Impact: Keiner

- `RdpManager.connect()` routet jede Session durch `RdpExternalClient`
- Passwort landet wie bisher im Clipboard, externer RDP-Client wird gestartet
- Keine Verhaltensänderung gegenüber Version 1.1.26

## Phase 2 — Was für echte Integration nötig ist

> Dies ist **kein ausgeführter Plan**, sondern eine Skizze für einen späteren
> eigenständigen Plan.

1. **Submodule einbinden:** `git submodule add https://github.com/FreeRDP/FreeRDP freerdp`
   auf Tag `3.10.3` pinnen (siehe `freerdp/VERSION`). Alternativ eigenen Fork anlegen,
   falls Custom-Patches nötig sind.
2. **`freerdp-build.yml` fixen:** Build-Pfad auf
   `client/Android/Studio` umstellen, `:freeRDPCore:assembleRelease` dort aufrufen,
   AAR nach `core/rdp/libs/freerdp-android.aar` kopieren.
3. **Reflection-Glue neu schreiben** in `RdpSessionActivity`:
   - `BookmarkBase` oder `Uri` konstruieren statt 15-Parameter `setConnectionInfo`
   - `freeInstance` statt `freeSession`
   - `EventListener`-Interface implementieren: `OnConnectionSuccess`,
     `OnConnectionFailure`, `OnDisconnecting`, `OnPreConnect`, `OnVerify*Certificate`
4. **Rendering-Pipeline:** `SessionView` + `LibFreeRDPBroadcastReceiver` oder direkte
   `updateGraphics`-Callbacks → `Bitmap` → `ImageView`-Rendering. An upstream
   `aFreeRDP`-Client (`client/Android/Studio/aFreeRDP`) orientieren.
5. **Input-Events:** `OnTouchListener` → `sendCursorEvent`,
   Soft-Keyboard → `sendKeyEvent`/`sendUnicodeKeyEvent`.
6. **`PHASE_2_ENABLED = true`** setzen und `:freerdp-build.yml` wieder an
   `push`-Trigger anbinden.
7. **Device-Tests** auf echtem Android mit echten RDP-Servern.

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
