# Split-Tunneling v2 — Design Spec

**Datum:** 2026-04-12
**Status:** Genehmigt
**Scope:** GC Server + Android Client (Windows Clients folgen separat)

## Zusammenfassung

Neues Split-Tunneling-System mit:
- **Include/Exclude-Modus** (waehlbar, Default: Exclude = Full-Tunnel mit Ausnahmen)
- **Netzwerk-Presets** (Private Netze, WLAN-Subnetz auto-detect) + Custom CIDR
- **App-Picker** mit Suchfunktion (nur client-seitig, kein Server-Upload)
- **Server-seitige Admin-Presets** (Global + Per-Token-Override, lockbar)
- **Privacy-First**: App-Ausnahmen sind IMMER user-kontrolliert, nie admin-gelockt

## Datenmodell

### Server: Globales Preset (settings-Tabelle)

Key: `split_tunnel_preset`, Wert: JSON

```json
{
  "mode": "exclude",
  "networks": [
    {"cidr": "192.168.0.0/16", "label": "Lokales Netzwerk"},
    {"cidr": "10.0.0.0/8", "label": "Private Netze 10.x"},
    {"cidr": "172.16.0.0/12", "label": "Private Netze 172.x"}
  ],
  "locked": true
}
```

- `mode`: `"exclude"` | `"include"` | `"off"`
- `networks`: Array von `{cidr, label}`
- `locked`: Boolean — wenn true, kann der User die Netzwerk-Konfiguration nicht aendern. App-Ausnahmen sind IMMER frei.

### Server: Per-Token-Override (api_tokens-Tabelle)

Neues Feld: `split_tunnel_override` (TEXT, nullable, JSON)

- `null` = globales Preset gilt
- Gesetzt = ueberschreibt das globale Preset fuer diesen Token
- Gleiches JSON-Format wie das globale Preset

### Server: API-Endpoint

`GET /api/v1/client/split-tunnel`

Erfordert Client-Token-Auth. Liefert das effektive Preset (Token-Override > Global > leer).

Response:
```json
{
  "ok": true,
  "mode": "exclude",
  "networks": [
    {"cidr": "192.168.0.0/16", "label": "Lokales Netzwerk"}
  ],
  "locked": true,
  "source": "global"
}
```

- `source`: `"global"` | `"token"` | `"none"`
- `"none"` + `locked: false` = kein Admin-Preset, User hat freie Hand
- Kein `apps`-Feld — App-Ausnahmen sind rein client-seitig

### Android: Lokale Speicherung (DataStore)

```
split_tunnel_mode: String          // "exclude" | "include" | "off"
split_tunnel_networks: String      // JSON-Array von {cidr, label}
split_tunnel_apps: String          // JSON-Array von {package, label}
split_tunnel_admin_locked: Boolean // true wenn Admin Netzwerke gesperrt hat
```

## Android UI

### Settings > Split-Tunneling

#### Modus-Auswahl

- Toggle: Split-Tunneling ein/aus
- Radio: "Alles durch VPN (Ausnahmen)" vs. "Nur Ausgewaehltes durch VPN"
- Bei `admin_locked`: Toggle + Modus ausgegraut, Banner: "Vom Administrator konfiguriert"

#### Netzwerke

Kontextabhaengige Ueberschrift:
- Exclude: "Diese Netzwerke NICHT durch VPN leiten"
- Include: "NUR diese Netzwerke durch VPN leiten"

Schnellauswahl (Checkboxen):
- Private Netze (10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16)
- Link-Local (169.254.0.0/16)
- Eigenes WLAN-Subnetz (auto-detect via ConnectivityManager/WifiManager, z.B. 192.168.178.0/24)

Benutzerdefiniert:
- Liste mit Label + CIDR + Loeschen-Button
- "+ Netzwerk hinzufuegen" Button oeffnet Eingabe-Dialog (Label + CIDR)
- CIDR-Validierung bei Eingabe

Bei `admin_locked`: Schnellauswahl + Custom ausgegraut, nur Anzeige.

#### Apps (immer editierbar, auch bei Admin-Lock)

Kontextabhaengige Ueberschrift:
- Exclude: "Diese Apps NICHT durch VPN leiten"
- Include: "NUR diese Apps durch VPN leiten"

Ausgewaehlte Apps:
- Liste mit App-Icon + Name + "Entfernen"-Button

"+ App hinzufuegen" oeffnet App-Picker-Modal:
- Zeigt alle installierten Apps via `PackageManager.getInstalledApplications()`
- App-Icon + App-Name
- Suchfeld filtert live (nach Name)
- Bereits ausgewaehlte Apps markiert
- System-Apps ausblendbar (Toggle "System-Apps anzeigen")
- Klick auf App fuegt hinzu / entfernt

App-Liste ist rein lokal. Wird nie zum Server uebertragen.

## Server Admin-UI

### Settings-Seite: Neuer Abschnitt "Split-Tunneling Preset"

- Modus-Dropdown: Aus / Alles durch VPN (Ausnahmen) / Nur Ausgewaehltes
- Netzwerk-Schnellauswahl: Checkboxen fuer Private Netze, Link-Local
- Benutzerdefinierte Netzwerke: Tabelle mit Label + CIDR + Loeschen
- "+ Netzwerk" Button
- Checkbox: "Einstellungen sperren (User kann Netzwerke nicht aendern)"
- Speichern-Button

### Token-Wizard / Token-Edit: Optionaler Override

In Schritt 3 des Token-Wizards (oder im Token-Edit-Modal):
- Checkbox: "Split-Tunnel-Preset ueberschreiben"
- Wenn aktiviert: gleiches Formular wie in Settings (Modus + Netzwerke + Lock)
- Wenn nicht aktiviert: globales Preset gilt

## Technische Implementation

### Server

1. **Settings-API erweitern**: `GET/PUT /api/v1/settings` mit `split_tunnel_preset` Key
2. **Neuer Client-Endpoint**: `GET /api/v1/client/split-tunnel` — liest Token, prueft Override, faellt auf Global zurueck
3. **Token-Schema erweitern**: `ALTER TABLE api_tokens ADD COLUMN split_tunnel_override TEXT DEFAULT NULL`
4. **Token-API erweitern**: `POST/PATCH` akzeptiert `split_tunnel_override`
5. **Settings-Template**: Neuer Abschnitt im Settings-Nunjucks-Template
6. **Settings-JS**: Formular-Logik fuer Netzwerk-Liste + Lock-Checkbox
7. **i18n**: de + en Strings

### Android

1. **SettingsRepository**: Neue Keys fuer erweitertes Format (JSON statt Plain-Text)
2. **SettingsViewModel**: Logik fuer Modus-Toggle, Netzwerk-Presets, App-Liste
3. **SettingsScreen**: Komplett neues Split-Tunnel-UI (Modus, Netzwerke, App-Picker)
4. **AppPickerScreen**: Neuer Composable — PackageManager Query, Suche, Icons
5. **TunnelManager**: `buildWgConfig()` anpassen fuer neues Datenformat
6. **VpnViewModel**: Preset vom Server abrufen beim Connect, in DataStore mergen
7. **WifiSubnetDetector**: Utility zum Auto-Detect des aktuellen WLAN-Subnetzes
8. **Migration**: Alte split_tunnel_routes/apps Daten ins neue Format konvertieren

### Datenfluss beim Connect

```
1. User drueckt "Verbinden"
2. VpnViewModel.connect():
   a. preResolveDns() (bestehend)
   b. GET /api/v1/client/split-tunnel → Preset
   c. Merge: Admin-Preset (Netzwerke, locked) + User-Apps
   d. Speichere in DataStore
   e. tunnelManager.connect(config, mergedNetworks, mergedApps)
3. TunnelManager:
   a. Exclude-Modus: AllowedIPs = 0.0.0.0/0, excludeApplications(apps)
      + addDisallowedRoute fuer excluded networks (via AllowedIPs-Berechnung)
   b. Include-Modus: AllowedIPs = nur die definierten CIDRs,
      includeApplications(apps) wenn gesetzt
```

### Exclude-Modus Netzwerk-Berechnung

WireGuard kennt kein "exclude route". Stattdessen: wenn der User 192.168.0.0/16 ausschliessen will, berechnen wir die AllowedIPs als Komplement:
- Start: 0.0.0.0/0
- Minus: 192.168.0.0/16
- Ergebnis: Liste von CIDR-Bereichen die 0.0.0.0/0 abdecken OHNE 192.168.0.0/16

Algorithmus: AllowedIPs-Subtraktion (existierende Libraries: `ipaddr` / eigene Bit-Arithmetik).

## Abgrenzung

- **Nicht in Scope**: Windows Client Split-Tunneling (folgt separat)
- **Nicht in Scope**: Server-seitiger App-Picker (Privacy — Apps bleiben lokal)
- **Nicht in Scope**: Per-Route Split-Tunneling (nur global pro Verbindung)
- **Nicht in Scope**: Automatische Preset-Synchronisation bei aenderung (erst nach Reconnect)
