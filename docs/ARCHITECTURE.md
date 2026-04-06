# GateControl Android Client — Architektur

Technische Dokumentation der App-Architektur für Entwickler und Integratoren.

---

## Projektstruktur

Das Projekt folgt Clean Architecture mit einem Multi-Modul-Aufbau:

```
android-client/
├── app/                     # UI-Schicht (Compose, ViewModels, Services)
│   └── src/main/java/com/gatecontrol/android/
│       ├── GateControlApp.kt          # Application-Klasse
│       ├── MainActivity.kt            # Compose-Host, Deep-Link-Handling
│       ├── navigation/                # Screen-Routen, NavHost, Bottom Navigation
│       ├── ui/
│       │   ├── vpn/                   # VPN-Bildschirm + Subkomponenten
│       │   ├── rdp/                   # RDP-Bildschirm + Subkomponenten
│       │   ├── services/              # Dienste-Bildschirm
│       │   ├── settings/              # Einstellungen + Log-Viewer
│       │   ├── setup/                 # Ersteinrichtung + QR-Scanner
│       │   ├── components/            # Wiederverwendbare UI-Bausteine
│       │   └── theme/                 # Farben, Typografie, Theme-Provider
│       ├── service/                   # VpnForegroundService, VpnTileService
│       └── receiver/                  # BootReceiver
├── core/
│   ├── common/              # Shared Utilities
│   │   └── E2EEHandler, Validation, Formatters, MachineId, Logger
│   ├── data/                # Persistenzschicht
│   │   └── EncryptedStorage, SetupRepository, SettingsRepository, LicenseRepository
│   ├── network/             # API-Kommunikation
│   │   └── ApiClient, ApiModels, AuthInterceptor, ApiClientProvider, UpdateChecker
│   ├── tunnel/              # WireGuard-Tunnelverwaltung
│   │   └── TunnelManager, TunnelMonitor, TunnelConfig, TunnelState, TunnelStats
│   └── rdp/                 # Remote-Desktop-Modul
│       └── RdpManager, RdpCredentialHandler, RdpExternalClient, WolClient, RdpMonitor
└── .github/workflows/       # CI/CD-Pipelines
    ├── release.yml           # Build, Versioning, Release
    ├── pr-check.yml          # Pull-Request-Validierung
    └── security.yml          # CodeQL + Lint
```

---

## Modulabhängigkeiten

```
app
├── core:common
├── core:data       → core:common
├── core:network    → core:common
├── core:tunnel     → core:common, core:network
└── core:rdp        → core:common, core:network
```

Jedes Modul stellt seine Abhängigkeiten über ein Hilt-`@Module` mit `@InstallIn(SingletonComponent::class)` bereit.

---

## Technologie-Stack

| Bereich | Technologie |
|---|---|
| **Sprache** | Kotlin 1.9.24 |
| **UI-Framework** | Jetpack Compose (BOM 2024.05) |
| **Design-System** | Material Design 3 |
| **Navigation** | Compose Navigation |
| **DI** | Hilt 2.51 |
| **Netzwerk** | Retrofit 2.11 + OkHttp 4.12 |
| **Serialisierung** | Gson |
| **VPN** | WireGuard Android Tunnel Library 1.0.20230707 |
| **Kamera** | CameraX + Google ML Kit Barcode |
| **Persistenz** | EncryptedSharedPreferences + Jetpack DataStore |
| **Logging** | Timber |
| **Coroutines** | kotlinx.coroutines |
| **Tests** | JUnit 5 + MockK + Turbine + Robolectric |
| **Build** | Gradle 8.4 (Kotlin DSL), Version Catalog |
| **Code Shrinking** | R8 (ProGuard-kompatibel) |

---

## Datenschicht

### EncryptedStorage
Wrapper um Androids `EncryptedSharedPreferences`:
- **Key-Encryption:** AES-256-SIV (deterministic)
- **Value-Encryption:** AES-256-GCM
- Speichert: Server-URL, API-Token, Peer-ID, WireGuard-Konfiguration, Config-Hash

### SettingsRepository
Jetpack DataStore für Benutzereinstellungen:
- Theme, Sprache, Auto-Connect, Kill-Switch
- Split-Tunnel-Konfiguration (Routen, ausgeschlossene Apps)
- Prüf- und Abfrageintervalle

### LicenseRepository
Verwaltet den Lizenzstatus als `StateFlow<Permissions>`:
- Fragt Berechtigungen vom Server ab
- Steuert Feature-Gates (RDP, Services, Traffic, DNS)

---

## Netzwerkschicht

### API-Client
Retrofit-Interface mit 17 Endpunkten gegen `/api/v1/client/*`:

| Gruppe | Endpunkte |
|---|---|
| **Peer** | Register, Config, Status, Heartbeat |
| **Traffic** | 24h, 7d, 30d, Total |
| **Services** | Liste, DNS-Leak-Test |
| **RDP** | Hosts, Connect, Session, WoL, ECDH-Exchange |
| **System** | Permissions, Update-Check |

### AuthInterceptor
OkHttp-Interceptor, der automatisch folgende Header hinzufügt:
- `X-API-Token` — API-Authentifizierung
- `X-Client-Version` — App-Version
- `X-Client-Platform` — `android`
- `X-Machine-Fingerprint` — SHA-256 des ANDROID_ID

### ApiClientProvider
Cached Retrofit-Instanzen pro Base-URL. Timeouts: 15 Sekunden (Connect, Read, Write).

---

## Tunnelschicht

### TunnelManager
Zentrale WireGuard-Verwaltung über die offizielle `GoBackend`-Implementierung:
- `connect(config)` — Tunnel aufbauen
- `disconnect()` — Tunnel abbauen
- `getStatistics()` — RX/TX-Bytes, Speed, Handshake

### TunnelMonitor
Health-Monitoring mit Backoff:
- Polling-Intervall: 2s (Start) → 60s (Maximum)
- Handshake-Alter-Prüfung
- Automatischer Reconnect nach 3 Fehlschlägen

### TunnelConfig
Parser für WireGuard `.conf`-Dateien:
- `parse(confString)` → TunnelConfig
- `toWgQuick()` → String
- `getServerHost()` / `getServerPort()` — Endpoint-Extraktion

---

## RDP-Schicht

### 7-Schritt-Verbindungsflow

```
1. VPN Check        → TunnelState == Connected?
2. TCP Check        → Host:Port erreichbar? (+ optional WoL)
3. Maintenance      → Wartungsfenster? → Benutzer fragen
4. Credentials      → ECDH-Exchange → AES-256-GCM Decrypt
5. Client Launch    → MS Remote Desktop / aFreeRDP Intent
6. Server Session   → POST /api/v1/client/rdp/session
7. Local Monitor    → Heartbeat + Session-Tracking
```

### E2EE-Credential-Delivery

```
Client                              Server
  │                                    │
  ├─ Generate ECDH P-256 Keypair      │
  ├─ POST public_key ─────────────────►│
  │                                    ├─ ECDH Shared Secret
  │                                    ├─ HKDF-SHA256 Key Derivation
  │                                    ├─ AES-256-GCM Encrypt Credentials
  │◄──────────────── encrypted_blob ───┤
  ├─ ECDH Shared Secret               │
  ├─ HKDF-SHA256 Key Derivation       │
  ├─ AES-256-GCM Decrypt              │
  └─ Credentials in Memory            │
```

Ephemeral-Schlüsselpaar pro Sitzung. Kein Schlüsselmaterial wird persistent gespeichert.

---

## UI-Architektur

### MVVM-Pattern
Jeder Bildschirm besteht aus:
- **Screen** (Composable) — Reine UI-Darstellung
- **ViewModel** (Hilt-injected) — Geschäftslogik, StateFlows
- **State** — Immutable Data Classes via `collectAsState()`

### Navigation
Bottom Navigation Bar mit 4 Tabs (VPN, RDP, Services, Einstellungen). RDP- und Services-Tabs sind nur sichtbar, wenn die entsprechende Lizenz vorhanden ist. Der Setup-Bildschirm wird als initialer Screen angezeigt, wenn noch keine Konfiguration vorliegt.

### Theming
Eigenes `GateControlTheme` mit `CompositionLocal` für erweiterte Farben:
- `extraColors.success` — Verbunden-Zustand
- `extraColors.warn` — Warnungen
- `extraColors.border` — Rahmenfarben
- `extraColors.cardBg` — Karten-Hintergrund

Dark und Light Theme über `SettingsRepository` steuerbar.

---

## CI/CD-Pipeline

### Release-Workflow (`release.yml`)

```
Push to main
  │
  ├─ Test Gate
  │   ├─ ./gradlew test --continue
  │   └─ ./gradlew lintRelease
  │
  └─ Build & Publish (nach erfolgreichem Test Gate)
      ├─ Version-Bump (feat: → minor, sonst → patch)
      ├─ CHANGELOG.md aktualisieren
      ├─ Keystore einrichten (Secret oder generiert)
      ├─ assembleRelease + bundleRelease
      ├─ Artefakte umbenennen (GateControl-Android-X.Y.Z.apk/.aab)
      ├─ Version-Commit + Tag (vX.Y.Z)
      └─ GitHub Release erstellen
```

### Artefakte pro Release
- `GateControl-Android-X.Y.Z.apk` — Direkt installierbare APK
- `GateControl-Android-X.Y.Z.aab` — Play-Store-Bundle
- `GateControl-Android-X.Y.Z-mapping.txt` — R8-Symbolzuordnung

### Versionierung
- **Commit mit `feat:` Prefix** → Minor-Bump (1.0.0 → 1.1.0)
- **Alle anderen Commits** → Patch-Bump (1.0.0 → 1.0.1)
- **versionCode:** `MAJOR * 10000 + MINOR * 100 + PATCH`

---

## Berechtigungen

| Berechtigung | Verwendung |
|---|---|
| `INTERNET` | VPN-Verbindung und API-Kommunikation |
| `FOREGROUND_SERVICE` | VPN als Foreground Service |
| `FOREGROUND_SERVICE_SPECIAL_USE` | VPN Service Typ |
| `POST_NOTIFICATIONS` | Statusbenachrichtigungen |
| `RECEIVE_BOOT_COMPLETED` | Auto-Connect nach Neustart |
| `ACCESS_NETWORK_STATE` | Netzwerkstatus-Erkennung |
| `CAMERA` | QR-Code-Scanner (optional) |
| `REQUEST_INSTALL_PACKAGES` | Direkt-Updates von GitHub |
