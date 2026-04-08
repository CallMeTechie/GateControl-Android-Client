# FreeRDP Library Integration — Design Spec

**Datum:** 2026-04-08
**Status:** Approved
**Version:** 1.0

## Überblick

Integration der FreeRDP-Library als eingebetteter RDP-Client in die GateControl Android App. Ersetzt die bisherige Abhängigkeit von externen RDP-Apps (Microsoft Remote Desktop, aFreeRDP) durch eine native In-App-RDP-Session. Fallback auf externe Clients bleibt erhalten.

## Entscheidungen

| Entscheidung | Ergebnis |
|---|---|
| UI-Darstellung | Vollbild-Activity mit Floating Toolbar |
| Library-Quelle | aFreeRDP Fork als Git-Submodule → AAR |
| Parametrisierung | Alle Verbindungsparameter automatisch vom Server |
| Reconnect | Auto-Reconnect (5 Versuche, 30s Timeout, FreeRDP-nativ) |
| Notification | Separate RDP-Notification (unabhängig von VPN) |
| Integrationsansatz | Git-Submodule + AAR in `core/rdp/libs/` |
| Testing | Unit + Security + Instrumented + CI-integriert |
| CI/CD | Build & Release beobachten bis grün, Fehler sofort fixen |

---

## 1. FreeRDP AAR Build-Pipeline

### Fork & Submodule

- aFreeRDP Repo forken nach `CallMeTechie/aFreeRDP`
- Als Git-Submodule unter `android-client/freerdp/` einbinden
- Relevante Module: `libfreerdp` (C-Library), `freerdpcore` (JNI-Bridge + Session-API), `afreerdp` (Activity + Surface-Rendering)

### AAR-Build im CI

- Neuer GitHub Actions Workflow `freerdp-build.yml`
- Trigger: manuell (`workflow_dispatch`) + bei Änderungen im `freerdp/` Submodule
- Build: Android NDK + CMake → erzeugt `freerdp-android.aar`
- Artifact: AAR wird in `core/rdp/libs/` committed (gittracked, da <20MB)
- Architektur-Support: `arm64-v8a` + `armeabi-v7a`

### Versionierung

- AAR-Version folgt dem FreeRDP-Upstream-Tag (z.B. `3.x.y`)
- Dokumentiert in `freerdp/VERSION`

---

## 2. RDP Embedded Client — Architektur

### Neue Activity: `RdpSessionActivity`

- Vollbild-Activity (immersive mode, kein System-UI)
- Hostet die FreeRDP `SessionView` (Surface für Remote-Desktop-Rendering)
- Empfängt alle Verbindungsparameter via Intent-Extras
- Kein Compose — FreeRDP liefert eigene Views (SurfaceView + Touch-Input-Handler)

### Floating Toolbar (Overlay)

- Minimierbar, am oberen Rand
- Buttons: Disconnect, Tastatur ein/aus, Sondertasten (Ctrl+Alt+Del, Win), Zwischenablage-Sync
- Drag-fähig

### Lifecycle

- `onCreate` → FreeRDP-Session initialisieren, Verbindung aufbauen
- `onPause` → Session bleibt aktiv (Hintergrund erlaubt via Foreground-Service)
- `onDestroy` → Session sauber trennen, Server-Session beenden, Credentials wipen
- Back-Button → Bestätigungs-Dialog ("RDP-Session beenden?")

### Erweiterung `RdpEmbeddedClient`

- `isAvailable()` — bleibt (Reflection-Check auf `LibFreeRDP`)
- Neues `launchSession(context, RdpConnectionParams)` — baut Intent, startet `RdpSessionActivity`
- Neues Data-Class `RdpConnectionParams` — bündelt alle Parameter typsicher

### Änderung `RdpManager.connect()` (Zeile 178)

- Statt TODO: Prüfe `RdpEmbeddedClient.isAvailable()`
  - Ja → `embeddedClient.launchSession(params)`
  - Nein → Fallback auf `RdpExternalClient` (wie bisher, Passwort in Zwischenablage)

### Foreground Service: `RdpSessionService`

- Eigene Notification (Channel: `rdp_session`)
- Zeigt: Hostname, Session-Dauer, Disconnect-Action
- Hält die RDP-Session am Leben wenn die Activity kurz in den Hintergrund geht
- Heartbeat zum GateControl-Server (bestehender `RdpMonitor.startHeartbeat()`)

---

## 3. Verbindungsflow & Auto-Reconnect

### Erweiterter Connect-Flow (8 Schritte)

1. VPN-Check *(bestehend)*
2. TCP-Reachability-Check via Server *(bestehend)*
3. Maintenance-Mode-Check *(bestehend)*
4. E2EE-Credentials abrufen *(bestehend)*
5. **NEU:** Embedded Client starten (oder Fallback auf External)
6. Server-Session starten *(bestehend)*
7. Session im Monitor registrieren *(bestehend)*
8. **NEU:** `RdpSessionService` starten (Notification + Heartbeat)

### FreeRDP-Verbindungsaufbau in `RdpSessionActivity`

```
Intent empfangen → RdpConnectionParams parsen
  → LibFreeRDP.newInstance()
  → Connection-Settings setzen (host, port, credentials, display, redirects)
  → LibFreeRDP.connect()
  → SessionView anzeigen (Surface-Rendering)
  → RdpSessionService starten
```

### Auto-Reconnect

- FreeRDP-nativ: `/auto-reconnect-max-retries:5`
- Timeout: 30 Sekunden gesamt
- Während Reconnect: Overlay in der SessionView mit Spinner + "Verbindung wird wiederhergestellt..." + Countdown
- Nach Timeout: Session beenden, User zurück zur RDP-Liste, Fehlermeldung anzeigen
- Netzwerkwechsel (WLAN→Mobil): Android `ConnectivityManager`-Callback triggert manuellen Reconnect-Versuch

### Disconnect-Flow

```
User tippt Disconnect (Toolbar oder Notification)
  → Bestätigungs-Dialog (nur aus Activity, nicht aus Notification)
  → LibFreeRDP.disconnect()
  → Server-Session beenden (API: endRdpSession)
  → Credentials wipen (E2EEHandler.clear())
  → RdpSessionService stoppen
  → RdpSessionActivity finish()
  → Zurück zur RDP-Liste
```

---

## 4. Security & Credential-Handling

### Credentials-Lifecycle

```
E2EE-Decrypt → RdpConnectionParams (in-memory)
  → Intent-Extras an RdpSessionActivity (kein Parcelable/Serializable auf Disk)
  → An LibFreeRDP übergeben (native memory)
  → Nach Session-Ende: alle Referenzen nullen + E2EEHandler.clear()
```

- Credentials werden **nie** persistiert — kein SharedPreferences, kein DataStore, kein File
- Intent-Extras bleiben im App-Prozess-Speicher (keine IPC zu anderen Apps)
- Passwort wird bei Embedded Client **nicht** in die Zwischenablage kopiert (nur beim External-Client-Fallback)
- `FLAG_SECURE` auf `RdpSessionActivity` — verhindert Screenshots und Screen-Recording

### Clipboard-Redirect Security

- Wenn `redirect.clipboard = true` (Server-Konfiguration): FreeRDP-eigener Clipboard-Channel (bidirektional)
- Clipboard-Zugriff nur während aktiver Session, wird bei Disconnect gekappt
- Kein automatisches Clipboard-Sync im Hintergrund

### Process-Isolation

- FreeRDP läuft im gleichen App-Prozess — vereinfacht Lifecycle, Credentials bleiben im selben Memory-Space
- Native Memory (C-Layer) wird von FreeRDP selbst verwaltet und bei `disconnect()` freigegeben

### Zertifikatsvalidierung

- RDP-Server-Zertifikate: FreeRDP-Callback `OnVerifyCertificateEx`
- Automatisch akzeptieren für VPN-interne Hosts (Verbindung läuft über verschlüsselten WireGuard-Tunnel)
- Kein manueller Trust-Dialog

---

## 5. Testing-Strategie

### Unit Tests (JUnit 5 + MockK)

- **`RdpEmbeddedClientTest`** — `isAvailable()` mit/ohne FreeRDP auf Classpath, `launchSession()` Intent-Building mit allen Parameterkombinationen
- **`RdpConnectionParamsTest`** — Validierung aller Felder, Defaults für optionale Werte, Mapping von API-Model zu Params
- **`RdpManagerTest`** (erweitern) — Embedded-vs-External-Fallback, Connect-Flow mit 8 Schritten, Disconnect-Flow mit Credential-Wipe
- **`RdpSessionServiceTest`** — Notification-Aufbau, Heartbeat-Intervall, Service-Lifecycle (start/stop)

### Security Tests (JUnit 5)

- **`RdpCredentialSecurityTest`** — Credentials nach Session-Ende nicht mehr im Memory referenziert, Clipboard bei Embedded Client leer, `FLAG_SECURE` gesetzt
- **`RdpE2EEIntegrationTest`** — End-to-End: Key-Exchange → Encrypt (Server-Simulation) → Decrypt → an FreeRDP-Params → nach Disconnect gewipet
- **`RdpCertificateHandlingTest`** — Auto-Accept für VPN-interne Hosts, Callback korrekt registriert

### Instrumented Tests (AndroidTest)

- **`RdpSessionActivityTest`** — Activity startet im Vollbild, Floating-Toolbar sichtbar, Back-Button zeigt Bestätigungs-Dialog, Disconnect-Action beendet Activity
- **`RdpSessionServiceInstrumentedTest`** — Foreground-Service startet, Notification erscheint mit korrektem Channel, Disconnect-Action funktioniert

### CI-Integration (GitHub Actions)

- **`pr-check.yml`** erweitern:
  - Unit Tests: `./gradlew test --continue` (bestehend, deckt neue Tests automatisch ab)
  - Lint: `./gradlew lintRelease` (bestehend)
  - Security-Check: Neuer Step — prüft dass keine Credentials in Logs/Logcat landen
  - HTTP-Body-Logging-Guard auf RDP-Endpoints erweitern
- **`freerdp-build.yml`** (neu):
  - AAR bauen
  - FreeRDP-eigene Tests ausführen (`ctest` aus dem CMake-Build)
  - AAR-Artefakt validieren (enthält `arm64-v8a` + `armeabi-v7a` .so-Dateien)
- **`release.yml`** erweitern:
  - FreeRDP-AAR-Existenz prüfen vor Release-Build
  - Instrumentierte Tests auf Emulator (API 31) via `reactivecircus/android-emulator-runner`

---

## 6. Betroffene Dateien

### Neue Dateien

| Datei | Beschreibung |
|---|---|
| `app/.../RdpSessionActivity.kt` | Vollbild FreeRDP Activity |
| `app/.../service/RdpSessionService.kt` | Foreground Service für RDP |
| `core/rdp/.../RdpConnectionParams.kt` | Typsicheres Parameter-Dataclass |
| `core/rdp/libs/freerdp-android.aar` | FreeRDP AAR-Artifact |
| `.github/workflows/freerdp-build.yml` | AAR Build-Workflow |
| `freerdp/` | Git-Submodule (aFreeRDP Fork) |
| `freerdp/VERSION` | FreeRDP-Versionsdoku |
| Tests (6 neue Dateien) | Siehe Sektion 5 |

### Zu ändernde Dateien

| Datei | Änderung |
|---|---|
| `core/rdp/build.gradle.kts` | AAR-Dependency aktivieren (Zeile 52) |
| `core/rdp/.../RdpEmbeddedClient.kt` | `launchSession()` implementieren |
| `core/rdp/.../RdpManager.kt` | TODO Zeile 178 ersetzen, Schritt 5+8 |
| `core/rdp/.../RdpModels.kt` | `RdpProgress` um Schritte erweitern |
| `app/src/main/AndroidManifest.xml` | `RdpSessionActivity` + `RdpSessionService` registrieren |
| `app/proguard-rules.pro` | FreeRDP-Klassen schützen |
| `core/rdp/consumer-rules.pro` | FreeRDP ProGuard-Regeln |
| `.github/workflows/pr-check.yml` | Security-Check-Step hinzufügen |
| `.github/workflows/release.yml` | AAR-Check + Emulator-Tests |
| `settings.gradle.kts` | Submodule ggf. referenzieren |
| `app/.../ui/rdp/RdpViewModel.kt` | ConnectState um Embedded-States erweitern |
| `app/.../ui/rdp/RdpConnectSheet.kt` | ConnectedView für Embedded anpassen |

---

## 7. Nicht im Scope

- Eingebetteter FreeRDP ohne aFreeRDP-Fork (zu aufwändig)
- Lokale Konfigurationsüberschreibungen durch den User
- Picture-in-Picture-Modus
- Multi-Session (mehrere parallele RDP-Sessions)
- FreeRDP als separater Android-Prozess
