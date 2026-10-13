# GateControl — Datenschutzerklärung / Privacy Policy

*Stand / Last updated: 13. Oktober 2026 / October 13, 2026*

[Deutsch](#deutsch) · [English](#english)

---

## Deutsch

### Kurz gesagt

GateControl ist die App zu einem **selbst gehosteten** GateControl-Server. Sie spricht ausschließlich mit dem Server, den du bei der Einrichtung angibst (und über dessen VPN-Tunnel). Der Herausgeber der App (CallMeTechie) betreibt dafür keine Server und erhält **keine Daten** von der App. Es gibt kein Tracking, keine Werbung und keine Analyse- oder Werbe-SDKs.

### Welche Daten die App verarbeitet und wohin sie gehen

Alle folgenden Daten gehen nur an **deinen eigenen GateControl-Server** — verantwortlich dafür ist dessen Betreiber (oft du selbst oder dein Administrator):

| Daten | Zweck |
|---|---|
| Zugangsdaten (Server-Adresse, API-Token, WireGuard-Schlüssel) | Verbindung und VPN-Tunnel. Verschlüsselt auf dem Gerät gespeichert (Android Keystore). |
| Gerätename/-modell, App-Version, Verbindungsdauer, Datenvolumen | Statusmeldung („Heartbeat“), damit der Server das Gerät anzeigen und verwalten kann. |
| Benachrichtigungs-Einstellungen und Lesebestätigungen | Eigene Benachrichtigungszentrale; Mitteilungen kommen direkt vom Server, nicht über Google- oder andere Push-Dienste. |
| Support-Pakete (nur wenn du sie sendest) | Fehlersuche: App-Protokoll, Netzwerk- und Tunnelstatus, Einstellungen; bei „Problem melden“ zusätzlich deine optionale Problembeschreibung und die Diagnose-Ergebnisse. Geheimnisse (Schlüssel, Tokens, Passwörter) werden vor dem Senden geschwärzt. |
| Diagnose (nur wenn du sie startest) | Prüft die Verbindung zu deinem Server, inklusive kurzem Geschwindigkeitstest (Testdaten zu und von deinem Server) und Ping an das VPN-Gateway. Die Ergebnisse bleiben auf dem Gerät, bis du „Problem melden“ wählst. |
| Absturzberichte | Nach einem Absturz sendet die App beim nächsten Start den Fehlerbericht als geschwärztes Support-Paket an deinen Server. |
| Schnellaktionen | Ausgelöste Aktionen (z. B. „Licht an“) gehen an deinen Server und dessen Plugins. |

### Standort

Für **vertrauenswürdige WLANs** liest die App den Namen (SSID) des verbundenen WLANs. Android gibt den WLAN-Namen nur mit Standort-Berechtigung heraus — auch im Hintergrund, damit das VPN in einem vertrauenswürdigen WLAN automatisch getrennt und außerhalb wieder verbunden werden kann, wenn die App geschlossen ist. Die App erhebt dafür Standortdaten, **verwendet aber keine Koordinaten**, speichert keinen Standort und sendet weder Standort noch WLAN-Namen an irgendjemanden. Die Funktion ist optional und standardmäßig aus.

Die **Standortfreigabe** (Einstellungen › Standortfreigabe, standardmäßig aus) ist davon getrennt: Nur wenn du auf das Satelliten-Symbol auf dem Startbildschirm tippst, ermittelt die App einmal deinen genauen Standort (Koordinaten, Genauigkeit, Höhe, Zeitpunkt) und sendet ihn an **deinen eigenen GateControl-Server**. Dort sehen ihn du und die Administratoren auf einer Karte. Es gibt keine Ortung im Hintergrund und keine automatische Übertragung. Der Server behält die letzten 20 Standorte je Gerät höchstens 30 Tage; du kannst sie in der App jederzeit löschen. Die Karte im Server lädt Kartenbilder von OpenStreetMap — dabei erfährt OpenStreetMap den angezeigten Kartenausschnitt, aber nicht dein Gerät. Nichts geht an CallMeTechie, Google oder andere Dritte.

### Kamera

Nur zum Scannen des Einrichtungs-QR-Codes, wenn du den Scanner öffnest. Bilder werden weder gespeichert noch übertragen. Die Erkennung (Google ML Kit) läuft vollständig auf dem Gerät.

### Berechtigungen

| Berechtigung | Zweck |
|---|---|
| VPN-Dienst | WireGuard-Tunnel zu deinem Server |
| Internet, Netzwerkstatus | Verbindung zum Server, Erkennen von Netzwechseln |
| Benachrichtigungen | VPN-Status und Mitteilungen deines Servers |
| Dienst im Vordergrund | Verbindung der Benachrichtigungszentrale und Remote-Desktop-Sitzungen |
| Start nach dem Hochfahren | optional: automatisch verbinden |
| Akku-Optimierung ausnehmen | optional: Benachrichtigungen auch im Ruhezustand |
| Standort (auch im Hintergrund) | optional: WLAN-Namen für vertrauenswürdige WLANs; Standort auf Tipp an den eigenen Server (siehe oben) |
| Kamera | Einrichtungs-QR-Code |

### Speicherung und Löschung

Daten auf dem Gerät werden gelöscht, wenn du die App deinstallierst oder ihre Daten löschst. Daten auf deinem Server verwaltet dessen Betreiber.

### Kinder

Die App richtet sich an Betreiber und Nutzer eines GateControl-Servers und nicht an Kinder unter 13 Jahren.

### Kontakt

Fragen zum Datenschutz: [GitHub-Issues](https://github.com/CallMeTechie/GateControl-Android-Client/issues) oder [callmetechie.de](https://callmetechie.de). Zu Daten auf deinem Server wende dich an dessen Betreiber.

---

## English

### In short

GateControl is the app for a **self-hosted** GateControl server. It only talks to the server you enter during setup (and through its VPN tunnel). The publisher of the app (CallMeTechie) runs no servers for it and receives **no data** from the app. No tracking, no ads, no analytics or advertising SDKs.

### What the app processes and where it goes

Everything below goes only to **your own GateControl server** — its operator (often you or your administrator) is responsible for it:

| Data | Purpose |
|---|---|
| Credentials (server address, API token, WireGuard keys) | Connection and VPN tunnel. Stored encrypted on the device (Android Keystore). |
| Device name/model, app version, connection time, traffic volume | Status report ("heartbeat") so the server can list and manage the device. |
| Notification settings and read receipts | Built-in notification center; messages come straight from the server, not through Google or other push services. |
| Support bundles (only when you send one) | Troubleshooting: app log, network and tunnel status, settings; with "Report a problem" also your optional problem description and the diagnosis results. Secrets (keys, tokens, passwords) are redacted before sending. |
| Diagnostics (only when you start them) | Checks the connection to your server, including a short speed test (test data to and from your server) and a ping to the VPN gateway. The results stay on the device until you choose "Report a problem". |
| Crash reports | After a crash the app sends the report as a redacted support bundle to your server at the next start. |
| Quick actions | Actions you run (e.g. "lights on") go to your server and its plugins. |

### Location

For **trusted Wi-Fi networks** the app reads the name (SSID) of the connected Wi-Fi. Android only reveals the Wi-Fi name with the location permission — also in the background, so the VPN can disconnect automatically in a trusted Wi-Fi and connect again outside it while the app is closed. The app collects location data for this but **does not use coordinates**, does not store any location and never sends location or Wi-Fi names to anyone. The feature is optional and off by default.

**Location sharing** (Settings › Location sharing, off by default) is separate: only when you tap the satellite button on the start screen does the app get your precise location once (coordinates, accuracy, altitude, time) and send it to **your own GateControl server**. You and the administrators see it there on a map. There is no background location and no automatic sending. The server keeps the last 20 locations per device for at most 30 days; you can delete them in the app at any time. The map on the server loads map images from OpenStreetMap — OpenStreetMap learns the map area shown, not your device. Nothing goes to CallMeTechie, Google or other third parties.

### Camera

Only to scan the setup QR code when you open the scanner. No images are stored or transmitted. Recognition (Google ML Kit) runs entirely on the device.

### Permissions

| Permission | Purpose |
|---|---|
| VPN service | WireGuard tunnel to your server |
| Internet, network state | Connection to the server, noticing network changes |
| Notifications | VPN status and messages from your server |
| Foreground service | Notification center connection and remote desktop sessions |
| Start at boot | optional: connect automatically |
| Ignore battery optimisation | optional: notifications while the device sleeps |
| Location (incl. background) | optional: Wi-Fi names for trusted Wi-Fi; location on tap to your own server (see above) |
| Camera | Setup QR code |

### Retention and deletion

Data on the device is deleted when you uninstall the app or clear its data. Data on your server is managed by its operator.

### Children

The app is meant for operators and users of a GateControl server and is not directed at children under 13.

### Contact

Privacy questions: [GitHub issues](https://github.com/CallMeTechie/GateControl-Android-Client/issues) or [callmetechie.de](https://callmetechie.de). For data on your server, contact its operator.
