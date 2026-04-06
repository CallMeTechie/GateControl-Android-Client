# GateControl Android Client — Feature-Dokumentation

Detaillierte Beschreibung aller Funktionen des GateControl VPN Client.

---

## VPN-Tunnel

### Verbindungsmanagement
Der VPN-Tunnel basiert auf dem WireGuard-Protokoll und nutzt die offizielle Android-WireGuard-Bibliothek. Die Verbindung wird über den Android `VpnService` aufgebaut und als Foreground Service betrieben, um vom System nicht im Hintergrund beendet zu werden.

**Verbindungsstatus:**
- **Getrennt** — Kein Tunnel aktiv
- **Verbinde...** — Tunnelaufbau läuft
- **Verbunden** — Tunnel steht, Daten fließen
- **Trenne...** — Tunnel wird abgebaut
- **Verbinde erneut (x/y)...** — Automatische Wiederverbindung nach Abbruch
- **Verbindungsfehler** — Tunnelaufbau fehlgeschlagen

**Verbindungsring:**
Ein animierter Ring auf dem Hauptbildschirm zeigt den aktuellen Status visuell an. Grün = verbunden, Gelb = verbindend/trennend, Rot = Fehler, Grau = getrennt.

### Auto-Connect
Bei aktivierter Einstellung verbindet sich die App automatisch mit dem VPN-Tunnel:
- Beim Start der App
- Nach einem Neustart des Geräts (via Boot Receiver)

### Auto-Reconnect
Bei Verbindungsverlust versucht die App automatisch eine Wiederverbindung:
- Exponentieller Backoff: 2s, 4s, 8s, 16s, 32s, bis maximal 60s
- Maximal 10 Versuche
- Nach 3 aufeinanderfolgenden Fehlschlägen wird der Tunnel getrennt
- Der Status zeigt den aktuellen Versuch an (z. B. "Verbinde erneut 3/10...")

### Always-on VPN
Die App registriert sich als VPN-Provider im Android-System. Über die Systemeinstellungen kann das Always-on-VPN aktiviert werden — der Tunnel wird dann automatisch bei jedem Netzwerkwechsel und Systemstart aufgebaut.

---

## Kill-Switch

Blockiert **jeglichen Netzwerkverkehr**, solange kein VPN-Tunnel aktiv ist. Die Implementierung nutzt die native Android-Always-on-VPN-Funktion mit der Option "Verbindungen ohne VPN blockieren" — keine Umgehung möglich, kein Root erforderlich.

**Schutz vor:**
- DNS-Leaks bei Verbindungsabbruch
- Datenverkehr außerhalb des Tunnels
- Versehentlichem Surfen im offenen Netz

---

## Split-Tunneling

Ermöglicht selektives Routing von Datenverkehr durch den VPN-Tunnel.

### IP-basierte Routen
Definieren Sie, welche Netzwerke durch den Tunnel geroutet werden:
```
10.0.0.0/8
192.168.1.0/24
172.16.0.0/12
```
Alle anderen Ziele gehen direkt ins Internet.

### App-basierte Ausschlüsse
Wählen Sie einzelne Apps aus, die den VPN-Tunnel umgehen sollen (z. B. Streaming-Dienste, Gaming-Apps). Die Konfiguration erfolgt direkt in den App-Einstellungen unter **Tunnel > Split-Tunneling > Ausgeschlossene Apps**.

---

## Echtzeit-Monitoring

### Bandbreitengraph
Ein Live-Graph zeigt die aktuelle Upload- und Download-Geschwindigkeit der letzten 60 Sekunden. Die Daten werden jede Sekunde aktualisiert und als Ringpuffer verwaltet.

- **Blau:** Download-Geschwindigkeit
- **Grün:** Upload-Geschwindigkeit

### Statistiken
Während der VPN-Verbindung werden folgende Metriken angezeigt:

| Metrik | Beschreibung |
|---|---|
| **Server** | Hostname des verbundenen WireGuard-Endpunkts |
| **Handshake** | Zeit seit dem letzten WireGuard-Handshake |
| **Empfangen** | Gesamte empfangene Datenmenge (formatiert in KB/MB/GB) |
| **Gesendet** | Gesamte gesendete Datenmenge |

### Datenverbrauch (Pro)
Serverseitige Verkehrsstatistiken über vier Zeiträume:

- **24 Stunden** — Verbrauch der letzten 24 Stunden
- **7 Tage** — Wochensicht
- **30 Tage** — Monatssicht
- **Gesamt** — Kumulierter Gesamtverbrauch

---

## Quick Settings Tile

Eine Kachel im Android-Quick-Settings-Panel ermöglicht das Ein-/Ausschalten des VPN-Tunnels ohne die App zu öffnen.

**Status-Anzeige:**
- Kachel aktiv (eingefärbt): VPN verbunden
- Kachel inaktiv: VPN getrennt
- Untertitel zeigt "GateControl VPN" bzw. "Nicht verbunden"

---

## Server-Einrichtung

### QR-Code-Scan
Die App nutzt CameraX und Google ML Kit (On-Device) zum Scannen von QR-Codes. Die Kamera wird nur aktiviert, wenn der QR-Scanner explizit geöffnet wird. Es werden keine Bilder gespeichert oder übertragen.

### Deep-Link-Provisionierung
Die App unterstützt Deep-Links im Format:
```
gatecontrol://setup?url=https://gate.example.com&token=gc_XXXXXXXX
```
Ideal für automatisierte Bereitstellung per E-Mail, MDM oder Intranet-Portal.

### Konfigurationsdatei-Import
Standard-WireGuard-Konfigurationsdateien (`.conf`) können direkt importiert werden. In diesem Modus stehen nur grundlegende VPN-Funktionen zur Verfügung.

### Verbindungstest
Vor der Registrierung kann die Erreichbarkeit des Servers getestet werden. Die App sendet eine Testanfrage an den `/api/v1/client/`-Endpunkt und zeigt das Ergebnis an.

---

## Dienstverwaltung (Pro)

Der GateControl-Server stellt eine Liste erreichbarer Dienste bereit, die über den VPN-Tunnel zugänglich sind. Jeder Dienst wird mit Name, Domain und Authentifizierungsstatus angezeigt.

**Anzeige:**
- **Name** — Bezeichnung des Dienstes
- **Domain** — Erreichbare Adresse
- **Auth-Badge** — Zeigt an, ob der Dienst eine Authentifizierung erfordert

---

## DNS-Leak-Test (Pro)

Ein integrierter Test prüft, ob DNS-Anfragen korrekt über den VPN-Tunnel geleitet werden oder ob ein DNS-Leak vorliegt.

**Ergebnisse:**
- **Kein DNS-Leak erkannt** — Alle DNS-Anfragen laufen über den Tunnel
- **DNS-Leak erkannt!** — DNS-Anfragen umgehen den Tunnel (Handlungsempfehlung: Kill-Switch aktivieren)
- **DNS-Server:** Auflistung der erkannten DNS-Server

---

## RDP-Sitzungsverwaltung (Pro)

Vollständige Remote-Desktop-Verwaltung direkt in der App.

### Host-Übersicht
Alle vom GateControl-Server konfigurierten RDP-Hosts werden aufgelistet und können nach Status gefiltert werden:
- **Alle** — Gesamtliste
- **Online** — Aktuell erreichbare Hosts
- **Offline** — Nicht erreichbare Hosts

Jeder Host zeigt: Name, IP-Adresse, Online/Offline-Status, aktive Sitzungen und verfügbare Credential-Modi.

### Verbindungsablauf (7 Schritte)
1. **VPN-Prüfung** — Ist der VPN-Tunnel aktiv?
2. **Host-Erreichbarkeit** — TCP-Check auf RDP-Port (mit optionalem Wake-on-LAN)
3. **Wartungsfenster** — Befindet sich der Host in einer geplanten Wartung?
4. **Credential-Abruf** — E2EE-verschlüsselte Zugangsdaten vom Server (ECDH-Schlüsselaustausch)
5. **Client-Start** — Externer RDP-Client (Microsoft Remote Desktop / aFreeRDP)
6. **Session-Start** — Server-seitige Sitzungsregistrierung
7. **Monitoring** — Heartbeat und Session-Tracking

### Credential-Modi
| Modus | Beschreibung |
|---|---|
| **Automatische Anmeldung** | Benutzername + Passwort werden E2EE übertragen, Login erfolgt automatisch |
| **Benutzername vorhanden** | Nur der Benutzername wird übertragen, Passwort muss manuell eingegeben werden |
| **Manuelle Anmeldung** | Keine Credentials vom Server, vollständig manueller Login |

### Wake-on-LAN
Offline-Hosts können direkt aus der App per Wake-on-LAN aufgeweckt werden. Die App sendet den WoL-Befehl über die GateControl-API und wartet per TCP-Polling, bis der Host erreichbar ist.

### Ende-zu-Ende-Verschlüsselung (E2EE)
RDP-Zugangsdaten werden **niemals im Klartext** übertragen oder gespeichert:
- **Schlüsselaustausch:** ECDH P-256 (Ephemeral-Schlüsselpaar pro Sitzung)
- **Schlüsselableitung:** HKDF-SHA256
- **Verschlüsselung:** AES-256-GCM
- **Lokale Speicherung:** EncryptedSharedPreferences (AES-256-SIV/GCM)

---

## Einstellungen

### Server-Konfiguration
- Server-URL und API-Token bearbeiten
- Verbindungstest durchführen
- Peer-Registrierung erneuern

### Tunnel-Konfiguration
- Split-Tunneling aktivieren/deaktivieren
- IP-Routen bearbeiten
- Apps vom VPN ausschließen

### App-Einstellungen
- **Design:** Dark / Light Theme
- **Sprache:** Deutsch / Englisch
- **Auto-Connect:** Automatische Verbindung beim App-Start
- **Prüfintervall:** Frequenz der Tunnel-Health-Checks (Sekunden)
- **Konfigurationsabfrage:** Frequenz der Konfigurationsaktualisierung (Sekunden)

### Lizenz
- Aktueller Lizenzstatus (Community / Pro)
- Lizenz aktivieren

### Protokolle
- Filterbarer Log-Viewer (1h / 12h / 24h / Alle)
- Log-Export als Textdatei
- Automatische Log-Rotation

### Updates
- In-App Update-Prüfung gegen den GateControl-Server
- Semantische Versionserkennung
- Direkt-Download neuer Versionen

---

## Benachrichtigungen

Die App nutzt drei Benachrichtigungskanäle:

| Kanal | Inhalt |
|---|---|
| **VPN-Status** | Verbindungsstatus, Geschwindigkeit, Wiederverbindung |
| **Updates** | Verfügbare App-Updates |
| **RDP-Sitzungen** | Aktive Sitzungen, Ablaufwarnungen |

Zusätzlich werden Peer-Ablaufwarnungen angezeigt, wenn der WireGuard-Peer in wenigen Tagen abläuft oder bereits abgelaufen ist.

---

## Sicherheitsmerkmale

| Merkmal | Implementierung |
|---|---|
| **VPN-Protokoll** | WireGuard (ChaCha20-Poly1305, Curve25519, BLAKE2s) |
| **Credential-Verschlüsselung** | ECDH P-256 + HKDF-SHA256 + AES-256-GCM |
| **Lokale Speicherung** | EncryptedSharedPreferences (AES-256-SIV/GCM) |
| **Netzwerkverkehr** | Ausschließlich zu Ihrem eigenen Server |
| **Kill-Switch** | Native Android-Integration (kein Root) |
| **Telemetrie** | Keine |
| **Drittanbieter-Netzwerk** | Keines |
| **Root-Erkennung** | Warnung bei erkanntem Root-Zugang |
| **Code-Obfuskierung** | R8/ProGuard im Release-Build |
