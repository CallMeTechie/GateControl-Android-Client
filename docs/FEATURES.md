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

**Verbindungs-Chip (seit 1.36):**
Der Status steht als Pille in der Kopfzeile des Start-Tabs neben dem Profilbild: gefüllt grün **„Verbinden“** wenn getrennt, grün getönt **„Geschützt“** wenn verbunden (Tippen trennt), gelb mit Drehkreis während Auf-/Abbau (Tippen bricht ab), rot **„Fehlgeschlagen“** nach einem Fehler (Tippen versucht es erneut; Meldung, Protokoll und Diagnose in einem Banner darunter). Langes Drücken öffnet die Verbindungsdetails. Der große Verbindungskreis mit Statustext und der Button „Portal öffnen“ (bis 1.35) sind entfallen; das Portal öffnet ein Symbol in der Kopfzeile, solange verbunden ist.

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

### Navigation (1.35)
Die Leiste unten (auf breiten Bildschirmen die Navigationsleiste links)
hat nur noch **Start**, **Netzwerk** und — wenn der Server das
Familie-Plugin mit Tab anbietet — **Familie**.

- **Netzwerk** hat die Segmente **Dienste**, **Pi-hole** und **Remote**
  (die RDP-Hosts, bis 1.34 ein eigener Tab). Es erscheinen nur die
  Segmente, die das Token nutzen darf; mit nur einem entfällt der
  Umschalter. Pushes mit der App-Route `rdp`/`remote` öffnen Netzwerk →
  Remote, `services` öffnet Netzwerk → Dienste; die alte Route `rdp` leitet
  auf Netzwerk → Remote um.
- **Einstellungen** sind kein Tab mehr, sondern eine normale Seite mit
  Zurück-Pfeil: im **Profil** (Tippen auf das eigene Profilbild auf dem
  Start-Tab oder im Familie-Tab) oben rechts das **Zahnrad**, rechts davon
  der **Hell/Dunkel-Schalter** (Sonne/Mond, bis 1.34 in der Kopfzeile des
  Start-Tabs). Alle bisherigen Wege zu einzelnen Einstellungsseiten
  (Glocke → Benachrichtigungen, Standortfreigabe, Diagnose, Protokoll)
  öffnen weiterhin direkt die jeweilige Seite; auf breiten Bildschirmen die
  Einstellungen mit der Seite daneben.

### Startbildschirm (1.32, umgebaut in 1.35 und 1.36)
Seit 1.36 passt die Standardansicht (Kopfzeile, Schnellaktionen, Info-Karte,
Durchsatz-Karte, Tab-Leiste) ohne Scrollen auf ein Handy-Display.
- **Kopfzeile:** das eigene Profilbild (bzw. die Initialen auf der
  Lieblingsfarbe) statt „GateControl“ + Server; der Ring zeigt die eigene
  Anwesenheit (Familie, wenn geteilt; derselbe Ring wie im Familie-Tab).
  Tippen öffnet das Profil (und von dort die Einstellungen). Ohne Profilbild
  führt ein Personen-Symbol dorthin. Rechts daneben der
  **Verbindungs-Chip** („Verbinden“ / „Geschützt“, siehe
  Verbindungsmanagement), dann die Symbole Portal (nur verbunden),
  Standort (wenn Ortung an) und Mitteilungen. Der Hell/Dunkel-Schalter ist
  ins Profil gewandert.
- **Schnellaktionen:** angeheftete Aktionen als Symbol-Kacheln (das
  SVG-Symbol, das der Server je Aktion schickt; ohne Symbol der Blitz) mit
  dem Titel darunter, Schalter gefüllt, solange sie an sind; rechts ein
  „+“ zum Auswählen. Ohne angeheftete Aktion nur „Schnellaktionen
  auswählen“. Dieselben Symbole in der Liste, im Widget, in den
  App-Verknüpfungen und den Schnelleinstellungs-Kacheln.
- **Info-Karte** (unter den Schnellaktionen): zeigt die Inhalte, die man
  auswählt, als wischbare Seiten (Punkte darunter). Inhalte sind derzeit die
  **Start-Kacheln der Plugins** (`GET /api/v1/client/tiles?lang=de|en`,
  in der Sprache der App): je Seite Symbol, Titel, großer Wert mit Einheit,
  Plugin-Name klein und die Farbe des Zustands (gut grün, Warnung gelb,
  kritisch rot, an = Akzent). Die Karte hat keine Überschrift und keine
  Knöpfe; **langes Drücken** öffnet ein Menü von unten mit „Diese Seite
  zuerst zeigen“ und „Inhalte und Reihenfolge wählen“ (seit 1.36; bis 1.35
  ein Stift). Letzteres öffnet „Inhalte der
  Info-Karte“: alle Kacheln nach Plugin gruppiert mit Häkchen, oben die
  angezeigten in ihrer Reihenfolge (Pfeile verschieben sie). Die Auswahl
  wird je Anmeldung (Server + Token) gespeichert; Kacheln, die der Server
  nicht mehr liefert, werden still übersprungen (und erscheinen wieder,
  wenn sie zurückkommen). Ohne Auswahl nur „Inhalte auswählen“; ältere
  Server (404) oder keine Kacheln: keine Karte. Aktualisiert, wenn der
  Start-Tab sichtbar wird, und alle 60 s, solange er sichtbar ist (nur im
  Speicher zwischengespeichert). Das Auswahlmodell ist allgemein
  („InfoSource“), damit später eingebaute Inhalte dazukommen können.
- **Durchsatz & Datennutzung** in einer Karte: der Inhalt wischt
  seitlich, Rahmen und die kleine Beschriftung bleiben stehen; Punkte
  zeigen die Seite. **Lange drücken** auf eine Seite legt sie als Standard
  fest („Als Standard festgelegt“, Stern neben der Beschriftung): diese
  Seite erscheint zuerst. Ohne Wahl startet die Karte mit der Datennutzung.
  Bei der Info-Karte übernimmt das Langdruck-Menü diese Rolle.
- Kill-Switch und Split-Tunneling stehen (nur noch) in den Einstellungen,
  Pi-hole im Netzwerk-Tab. Die Verbindungsdetails (Tunnel-IP, verbunden
  seit, letzter Handshake, Server) und der **DNS-Leak-Test** stehen unter
  Einstellungen → Verbindung → Verbindungsdetails.

### Statistiken
Während der VPN-Verbindung werden folgende Metriken angezeigt
(Einstellungen → Verbindung → Verbindungsdetails):

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

Einstellungen → Verbindung → Verbindungsdetails (bis 1.34 eine Kachel auf
dem Start-Tab). Ein integrierter Test prüft, ob DNS-Anfragen korrekt über den VPN-Tunnel geleitet werden oder ob ein DNS-Leak vorliegt.

**Ergebnisse:**
- **Kein DNS-Leak erkannt** — Alle DNS-Anfragen laufen über den Tunnel
- **DNS-Leak erkannt!** — DNS-Anfragen umgehen den Tunnel (Handlungsempfehlung: Kill-Switch aktivieren)
- **DNS-Server:** Auflistung der erkannten DNS-Server

---

## RDP-Sitzungsverwaltung (Pro)

Vollständige Remote-Desktop-Verwaltung direkt in der App: Netzwerk →
Remote (bis 1.34 ein eigener Tab „Remote“).

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

## Tür & Kamera (1.34, Plugin `gatecontrol-door`, GateControl 1.169+)

Wenn der Server das Plugin „Tür & Kamera“ anbietet und der Person ein Recht
auf mindestens ein Schloss gegeben ist (Details: [DOOR.md](DOOR.md)). Ab
1.34.1 **ohne eigenen Tab** (die Leiste unten bleibt übersichtlich): Bedient
wird die Tür über die Schnellaktionen (Kacheln auf dem Start-Tab, Widget,
App-Verknüpfungen) und die Mitteilungen. Den Bildschirm **„Tür & Kamera“**
öffnet ein Tippen auf eine Tür-Mitteilung oder **langes Drücken auf eine
Tür-Schnellaktion** (auf dem Start-Tab oder in der Liste „Schnellaktionen“);
zurück geht es dorthin, wo man war.

- **Schlösser** (Nuki Smart Lock / Opener): Zustand mit Symbol
  (zugesperrt, aufgesperrt, geöffnet, blockiert, nicht erreichbar …),
  Türsensor, Akku; Knöpfe je nach Recht (Zusperren, Aufsperren, Öffnen,
  beim Opener „Summer“). Außerhalb des Zeitfensters oder auf einem
  Nur-Ansehen-Gerät sind sie mit Grund ausgegraut. Der Zustand wird alle
  10 s aktualisiert, solange „Tür & Kamera“ offen ist.
- **Sicherheit:** Rückfrage für jede Aktion, die das Plugin bestätigen
  lässt, und vor dem Aufsperren und Öffnen immer die Displaysperre des
  Handys (Fingerabdruck/Gesicht oder PIN/Muster). Ohne Displaysperre geht
  das nicht. Ergebnis als Snackbar.
- **Kameras** (Ring, inoffizielle Schnittstelle): letztes Bild, „Neues Bild“
  (mit Wartezeit, wenn Ring bremst), Schalter „Klingel-Push“ und
  „Bewegungs-Push“, Statuszeile „Ring: inoffizielle Verbindung“ / „gestört“.
- **Verlauf** mit Filter Alle / Aktionen / Klingeln; ein Klingeln öffnet
  das Bild und — mit dem Recht „öffnen“ — „Tür öffnen“.
- **Klingel-Push** mit Bild auf einem eigenen Kanal mit hoher Wichtigkeit;
  „Tür öffnen“ direkt in der Mitteilung (verlangt das Entsperren des
  Geräts), „Ansehen“ öffnet das Klingel-Bild in der App; andere
  Tür-Mitteilungen öffnen „Tür & Kamera“.
- Offline zeigt „Tür & Kamera“ den zuletzt bekannten Stand der Schlösser; Bilder
  und Verlauf bleiben nur auf dem eigenen Server.

---

## Einstellungen

Seit 1.35 kein Tab mehr: das Zahnrad oben rechts im Profil öffnet sie (mit
Zurück-Pfeil).

### Profil (1.32, GateControl 1.168+; neu gestaltet in 1.33)
- Tippen auf das eigene Profilbild auf dem Startbildschirm oder im
  Familie-Tab (auch erster Eintrag der Einstellungen; von dort führt er
  zurück ins Profil)
- Oben rechts das Zahnrad zu den Einstellungen und der Hell/Dunkel-Schalter
  (Sonne/Mond)
- Oben das große Profilbild mit Ring. Tippen öffnet direkt die
  Android-Fotoauswahl (keine Speicher-Berechtigung); das kleine „X“ am
  Bild entfernt es (nach kurzer Rückfrage), das Paletten-Symbol ändert
  nur die Ringfarbe
- Nach der Fotoauswahl ein Vollbild-Assistent: Schritt 1/2 Ausschnitt
  (quadratisch, Zoomen und Verschieben) → „Weiter“, Schritt 2/2 Ringfarbe
  aus der Palette des Servers (ohne Rot — Rot heißt „unterwegs“) mit
  Vorschau des Ausschnitts im Ring → „Fertig“ lädt das Bild hoch
  (256 × 256 px, WebP, JPEG als Rückfall, ≤ 96 KB) und setzt die Farbe.
  Zurück / Abbrechen verwirft alles. Das Paletten-Symbol öffnet direkt
  Schritt 2 (Vorschau mit dem aktuellen Bild oder den Initialen)
- Darunter der Name und — mit dem Familie-Plugin und dem Modul
  Anwesenheit — „Anwesenheit teilen“ (Schalter mit Erklärung und „Dieses
  Handy ist gerade zuhause/unterwegs“). Nach dem Einschalten wird der
  erste eindeutige Stand sofort gemeldet, spätere Wechsel erst nach zwei
  Minuten
- Ältere Server: der Eintrag fehlt (mit dem Anwesenheits-Modul enthält
  er nur „Anwesenheit teilen“)

### Verbindungsdetails
- Einstellungen → Verbindung → Verbindungsdetails: Tunnel-IP, verbunden
  seit (live), letzter Handshake, Server; bei veraltetem Handshake
  „Verbindung prüfen“ (Diagnose); ohne VPN „VPN ist aus“ und der Server
- Darunter (mit dem DNS-Recht des Tokens) der DNS-Leak-Test: Tippen fragt
  den Server, welchen Resolver der Tunnel nutzt (bis 1.34 auf dem Start-Tab)

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

### Bilder und Schnellaktionen in Mitteilungen (1.34, GateControl 1.169+)

- Mitteilungen von Plugins können ein **Bild** tragen (z. B. wer klingelt):
  Es wird nur vom eigenen Server geladen (`/notify-media/…`, höchstens
  1 MB, nur im Arbeitsspeicher), in der Mitteilung groß gezeigt und in
  „Mitteilungen“ als Vorschau; nach Ablauf auf dem Server ist es weg.
- Plugins können eine ihrer **Schnellaktionen** als Knopf mitschicken
  („Tür öffnen“, „Wieder zusperren“). Der Knopf läuft im Hintergrund über
  den eigenen Server; verlangt das Plugin die Geräte-Entsperrung, fragt
  Android vorher nach Fingerabdruck/PIN. Das Ergebnis erscheint in der
  Mitteilung; abgelaufene Mitteilungen lösen nichts mehr aus.

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
