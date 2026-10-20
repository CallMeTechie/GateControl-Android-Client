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
Familie-Plugin mit Tab anbietet — **Familie**. Seit dem Messenger-Plugin
(GateControl 1.177+) kommt direkt nach Start der Tab **Chats** dazu
(siehe „Chats“).

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
  dem Titel darunter, Schalter gefüllt, solange sie an sind; seit 1.37
  fünf pro Zeile (weitere Zeilen bei mehr), ohne „+“; die Beschriftung
  darf zwei Zeilen haben (lange Wörter getrennt, notfalls etwas kleiner). Dieselben Symbole in
  der Liste, im Widget, in den App-Verknüpfungen und den
  Schnelleinstellungs-Kacheln. Alle 60 s aktualisiert, solange der Tab
  sichtbar ist.
- **Widgets** (seit 1.37, neu gebaut in 1.38, Server 1.172+): ein Widget gehört
  zu einem **Gerät** (Auto, Klimagerät, Licht, Schloss) und zeigt, was man
  auswählt: den Hauptwert (z. B. Innentemperatur, Ladestand), kleine
  Zusatzwerte mit Symbol (Reichweite; ein Schloss grün = verriegelt, rot =
  entriegelt), die Statuszeile, die Regler des Plugins (Stepper, Schieberegler,
  Auswahl) und die Aktionen des Geräts als Symbol-Buttons; bei Geräten mit
  eigenem Ein/Aus-Schalter (Klima, Licht) steht dieser oben rechts. Im Widget
  gibt es nur Symbol-Buttons (seit 1.39): die Aktionen teilen sich die volle
  Breite – bis zu drei in einer Reihe, vier als 2 × 2 –, die Auswahl (z. B.
  Klima-Modus Auto/Kühlen/Heizen/Entfeuchten/Lüften) zeigt Symbole, Text nur
  für einen Modus ohne bekanntes Symbol. Ein Plugin kann einen kleinen Wert in
  den Kopf des Widgets setzen (seit 1.40, Server 1.175+), z. B. beim Auto Akku
  und Ladestand rechts neben dem Namen; ohne Hauptwert entfällt die große Zahl. Halbe
  (zwei pro Zeile) oder volle Breite. Regler-Änderungen gehen 600 ms nach dem
  letzten Tipp an den Server. Höchstens 8 Widgets und 12 Buttons.
  Seit 1.47 sind zwei halbe Widgets nebeneinander **gleich hoch**: im
  niedrigeren wachsen die Buttons (bis 64 dp) und die Abstände, statt unten
  Leerraum zu lassen. Ein Hauptwert in °C steht mit dem Symbol „Haus mit
  Thermometer“ statt seines Namens da, der Zusatzwert `outdoor` mit „Sonne
  und Wolke“ in derselben Zeile („10,5° · 4,0°“). Teile, die ein Gerät erst
  nach der Auswahl bekommt (z. B. ein neuer Außenwert), werden angezeigt, bis
  man sie abwählt. **Langes Drücken** auf ein Widget oder eine Kachel öffnet
  direkt dessen Einstellungen (halbe/volle Breite, Häkchen je Teil bzw. je
  Button, „Vom Start lösen“, „Fertig“) mit „Erinnerung für dieses Gerät“ (wenn
  der Erinnerungs-Editor vorhanden ist) und „Start-Tab anordnen“; bei Türen
  bleibt es beim Tür-Bildschirm.
- **Schnellaktionen und Widgets** (Liste): eine Zeile pro Gerät, hier wird
  nichts geschaltet. Chips zeigen „Widget“ bzw. „N Schnellaktionen“. Tippen
  öffnet ein Menü von unten: **Schnellaktion** (Häkchen je Aktion – jede wird
  ein Button) oder **Widget** (Vorschau, Größe, Häkchen je Teil); „Lösen“
  nimmt das Widget wieder weg. Widgets aus 1.37 werden beim ersten Laden zu
  Geräte-Widgets mit allen Teilen.
- **Zustand als Symbol:** Schalter mit eigenem Aussehen je Zustand (z. B.
  Ver-/Entriegeln beim Auto als **ein** Button: grünes geschlossenes Schloss =
  verriegelt, rotes offenes = entriegelt) zeigen Symbol und Farbe auch in den
  Buttons, App-Verknüpfungen und dem Homescreen-Widget; gefragt wird nur vor dem
  Schritt, den das Plugin nennt (beim Schloss: vor dem Entriegeln).
- **Start-Tab bearbeiten:** langes Drücken auf eine freie Stelle des Tabs
  öffnet ein Menü mit „Schnellaktionen und Widgets“ (die Liste: anheften,
  Widget), „Inhalte der Info-Karte“ (der Picker) und „Start-Tab anordnen“.
  Ohne angeheftete Aktion steht nur ein leiser Hinweis darauf.
- **Start-Tab anordnen** (seit 1.47): Kacheln (untereinander; „−“ löst eine,
  „+ Kachel“ führt zur Liste) und darunter Widgets, Info-Karte und
  Datenverbrauch in **einer** gemeinsamen Reihenfolge – halten und ziehen,
  Doppeltipp auf ein Widget wechselt halbe/volle Breite; Screenreader
  bekommen „Nach oben/unten“ und „Breite ändern“. Die Reihenfolge gilt mit
  dem Konto auf allen Geräten (`GET/PUT /api/v1/client/layout`, Server
  1.181+; der Admin wählt pro Gerät, pro Benutzer oder global). Bei „pro
  Gerät“ oder älteren Servern bleibt sie auf dem Gerät; eine globale
  Reihenfolge, die nur Admins ändern dürfen, ist ausgegraut mit Hinweis.
- **Info-Karte** (unter den Schnellaktionen): zeigt die Inhalte, die man
  auswählt, als wischbare Seiten (Punkte darunter). Inhalte sind derzeit die
  **Start-Kacheln der Plugins** (`GET /api/v1/client/tiles?lang=de|en`,
  in der Sprache der App): je Seite Symbol, Titel, großer Wert mit Einheit,
  Plugin-Name klein und die Farbe des Zustands (gut grün, Warnung gelb,
  kritisch rot, an = Akzent). Seit 1.38 können Kacheln statt Text Symbol-Werte
  liefern (Fahrzeug: Batterie 78 %, Reichweite 312 km, Schloss grün/rot); lange
  Texte werden kleiner statt abgeschnitten. Die Karte hat keine Überschrift und keine
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
- **Netzwerk-Tab:** langes Drücken auf Dienste, Pi-hole oder Remote legt
  fest, womit der Tab öffnet („Als Standard festgelegt“); ein Link mit
  Ziel (Push, alte Remote-Route) geht weiterhin vor.
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

## Stundenplan (Plugin `gatecontrol-schule`, WebUntis)

Wenn der Server das Plugin „Schule“ anbietet, hat der Familie-Tab die Karte
**„Stundenplan“** (anordnen und ausblenden wie jede Karte). Sie zeigt für
heute — ab 15 Uhr, am Freitagnachmittag und am Wochenende für den nächsten
Schultag — je Kind „Planmäßig“, „2 Ausfälle“, „1 Vertretung“, „Frei“ oder
die Ferien und die nächsten ein, zwei geänderten Stunden; steht an dem Tag
eine Klassenarbeit an oder sind Hausaufgaben fällig, kommt die Zeile
„Morgen: Mathematik-Arbeit · 2 Hausaufgaben“ dazu (Arbeiten haben Vorrang
vor geänderten Stunden). Ein Tippen öffnet
den **Stundenplan**: Kind wählen (bei mehreren), Tage Mo–Fr (Sa, wenn
Unterricht ist) dieser und nächster Woche, Stunden mit Zeit, Stunde,
Fach, Lehrkraft und Raum; Ausfälle rot durchgestrichen („Entfällt“),
Vertretungen orange mit der durchgestrichenen ursprünglichen Lehrkraft /
dem ursprünglichen Raum, Zusatzstunden markiert, Ferien, „Stand“ des
letzten Abrufs. Ab Schule-Plugin 1.2 stehen oben die **Klassenarbeiten** des
Tages (die betroffene Stunde trägt „Arbeit“) und unten die fälligen
**Hausaufgaben** (erledigte abgehakt und durchgestrichen, „aufgegeben am …“).
Erinnerungen an Arbeiten und Hausaufgaben am Vorabend öffnen den Plan
ebenfalls bei Kind und Tag. Der Reiter **„Änderungen“** listet die erkannten Änderungen
der letzten 7 Tage nach Tag. Ein Tippen auf eine Stundenplan-Mitteilung
öffnet den Plan bei diesem Kind und Tag. Offline bleibt der zuletzt
geladene Stand sichtbar. Wer welche Kinder sieht, legt der Admin im
Plugin fest.

---

## Chats (Plugin `gatecontrol-messenger`, GateControl 1.177+)

Wenn der Server das Plugin „Messenger“ mit einem App-Tab für diese Person
anbietet (nicht auf Kiosk-Geräten — das entscheidet der Server), erscheint
der Tab **„Chats“** direkt nach „Start“, mit der Zahl ungelesener
Nachrichten (stumme Chats zählen nicht). Ältere Server ohne das Plugin
sehen keinen Unterschied.

- **Liste:** oben angeheftet **„GateControl“** — das bestehende Postfach
  (Mitteilungen des Servers, Regeln), kein Chat des Plugins; darunter
  Einzel- und Gruppenchats nach letzter Aktivität mit Vorschau, Uhrzeit,
  Ungelesen-Zähler, Häkchen der eigenen letzten Nachricht und
  „schreibt …“. Filter Alle / Ungelesen / Gruppen, Suche nach Namen,
  Ziehen zum Aktualisieren. Das Zahnrad öffnet die **Chat-Einstellungen**
  (Lesebestätigungen, „schreibt …“ zeigen, Inhalt in Benachrichtigungen —
  gelten für alle Geräte der Person).
- **Chat:** Blasen mit Tagestrennern, Häkchen (gesendet ✓, zugestellt ✓✓,
  gelesen ✓✓ farbig), Antworten mit Zitat (Tippen springt zum Original),
  Reaktionen, Bearbeiten und „Für alle löschen“ nur innerhalb des
  Zeitfensters des Servers, „Für mich löschen“ immer; ältere Nachrichten
  laden beim Hochscrollen; Suche im Chat (auf dem Server). Treffer der
  Suche und Zitate, die noch nicht geladen sind, holt die App samt
  Nachbarn (`messages?around=`, Messenger 1.1) und springt hin; was
  zwischen ihnen und den neuesten Nachrichten fehlt, zeigt eine Zeile
  „Weitere Nachrichten laden“, die beim Hinscrollen nachlädt (`after=`).
  Ältere Server ohne `around` bekommen weiter den Hinweis zum Hochscrollen.
  Systemzeilen („Anna hat Lena hinzugefügt“, „GateControl hat die Gruppe
  freigegeben – sie ist jetzt eine normale Gruppe“) und Hinweise anderer
  Plugins („GateControl“) in der Mitte. Gibt ein Plugin seine Gruppe frei
  (`conversation.update` mit `released`), lädt die App den Chat neu: Name,
  Bild und Mitglieder sind wieder bearbeitbar. Solange ein Chat offen ist, meldet die App
  ihn alle 20 s als aktiv (dann kommt dafür keine Push-Mitteilung) und
  sendet „schreibt …“ höchstens alle 3 s.
- **Anhänge:** Fotos (Photo Picker oder **direkt mit der Kamera** — die
  Kamera-Berechtigung wird erst beim ersten Foto erfragt; beides vor dem
  Hochladen auf 2048 px WebP verkleinert, mit Vorschaubild ≤ 64 KB), Dateien (Dateiauswahl, Größe und
  Typ wie vom Server erlaubt), **Videos** (Photo Picker — Bilder und Videos —,
  Dateiauswahl oder Teilen; auf dem Server eine Datei), **Standort** (einmalig, mit Adresse) und
  **Sprachnachrichten** (Mikrofon gedrückt halten, nach links wischen
  bricht ab, Höchstlänge vom Server; AAC/M4A mit Wellenform). Abspielen mit
  Springen per Tippen in die Wellenform; Bilder im Vollbild mit Zoom;
  Dateien öffnen oder teilen; Standort öffnet die Karten-App.
- **Videos & Dateivorschauen** (Messenger 1.3.0): beim Senden eines Videos
  nimmt die App ein Standbild bei etwa 1 s (MediaMetadataRetriever),
  verkleinert es auf höchstens 640 px (bzw. `limits.thumbMaxSide` des
  Servers) WebP ≤ 64 KB und liest Länge und
  Bildgröße; zuerst geht das Vorschaubild hoch, dann das Video, die
  Nachricht trägt `thumbMediaId` und `video { durationMs, width, height }`
  (bleibt `kind: file`). Bei PDFs wird die erste Seite (PdfRenderer) zum
  Vorschaubild. Im Chat zeigen Videos Standbild, Abspiel-Symbol und Länge
  (Tippen öffnet sie in einer Video-App), Dateien ihr Vorschaubild. Die
  neuen Felder gehen nur an Server, deren `GET me` sie unter `features`
  einschaltet (`video_meta: true`, `file_thumbs: true`); ältere bekommen die
  Datei wie bisher.
- **Hinweis auf eine neuere App:** schaltet der Server unter `features`
  eine Chat-Funktion ein, die diese App nicht kennt, zeigt der Tab „Chats“
  oben „Für alle Chat-Funktionen gibt es eine neuere App-Version“ mit
  „Aktualisieren“ (Play-Version: die Seite im Play Store; GitHub-Version:
  die Einstellungen mit der Update-Prüfung). Ausgeblendet bleibt er, bis
  eine weitere unbekannte Funktion dazukommt; er blockiert nie etwas.
- **Offline:** alles liegt in einer lokalen Datenbank (Room) und ist ohne
  Verbindung lesbar. Gesendet wird über einen **Ausgang**: die Nachricht
  steht sofort mit Uhr im Chat und geht mit ihrer `clientId` raus — bei
  Netzproblemen später erneut (auch per WorkManager, wenn die App zu ist),
  ohne je doppelt anzukommen. Was der Server ablehnt, bleibt rot markiert
  mit „Erneut versuchen“ / „Verwerfen“.
- **Live:** Ereignisse des Plugins kommen über die bestehende
  Push-Verbindung (`event: plugin`): neue Nachrichten, Änderungen,
  Lesebestätigungen, „schreibt …“. Lesebestätigungen tragen seit Messenger
  1.1 `readUpTo` / `deliveredUpTo`: die Häkchen ändern sich direkt, ohne
  den Chat neu zu laden (ältere Server: wie bisher über
  `GET conversations/:id`). Als nächstes `changedSince` dient die
  `serverTime` der Antwort. Nach jedem Verbindungsaufbau holt die App
  nach, was fehlt. Ist die Push-Verbindung aus, fragt ein offener Chat
  selbst alle paar Sekunden nach.
- **Benachrichtigungen:** eigener Kanal **„Chats“**, eine Mitteilung pro
  Chat im Messaging-Stil (die letzten Nachrichten, Gruppenname, Bild der
  Person), **Antworten direkt aus der Mitteilung** und **„Als gelesen
  markieren“**; gelesen auf einem anderen Gerät → verschwindet hier. Für den
  gerade offenen Chat wird nichts angezeigt. Chat-Mitteilungen landen nicht
  im Postfach.
- **Unterhaltungen, Verknüpfungen & Bubbles** (Android 11+): die letzten
  vier Chats sind Verknüpfungen mit Bild und Person (lange auf das App-Symbol
  drücken) und erscheinen direkt im **Teilen-Menü** — Teilen dorthin öffnet
  gleich diesen Chat. Chat-Mitteilungen gehören zu ihrer Unterhaltung
  (Bereich „Unterhaltungen“, Priorität in den Android-Einstellungen) und
  lassen sich als **Bubble** öffnen (schwebender Chat, Bilder darin im
  Vollbild). Ob Bubbles erscheinen, entscheiden die Android-Einstellungen;
  in den Chat-Einstellungen gibt es unter „Auf diesem Gerät“ die Schalter
  „Chat-Verknüpfungen“ und „Chat-Bubbles“ (mit Sprung in die
  Android-Einstellungen, wenn Bubbles dort aus sind). Verknüpfungen eines
  verlassenen oder entfernten Chats verschwinden (angeheftete werden
  deaktiviert), bei Wechsel von Server oder Token alle.
- **Neuer Chat / Neue Gruppe:** alle GateControl-Konten (ohne
  Kiosk-Geräte); **Chat-/Gruppen-Info** mit Mitgliedern, Umbenennen,
  Stummschalten (1 Std., 8 Std., 1 Woche, immer), selbstlöschenden
  Nachrichten, Mitglieder hinzufügen/entfernen, Admin-Rolle und „Gruppe
  verlassen“ — je nachdem, was der Server erlaubt (vom Familie-Plugin
  verwaltete Gruppen lassen Mitglieder, Namen und Bild in Ruhe).
  **Gruppenbild:** Tippen aufs Bild (oder „Gruppenbild“) → Galerie oder
  Kamera, quadratisch zuschneiden (zoomen, verschieben), hochgeladen über
  den Medien-Upload des Plugins und als `avatarMediaId` gesetzt; „Bild
  entfernen“ setzt es zurück. Darf jedes Gruppenmitglied, wie der Server es
  erlaubt (`can.editInfo`).
- **Medien & Dateien** (Chat-/Gruppen-Info): Bilder und Videos als Raster,
  Dateien und Sprachnachrichten als Listen, mit Anzahl je Art; lädt beim
  Scrollen ältere nach (`GET conversations/:id/media`). Server mit
  `video_meta` liefern Videos getrennt (`kind=video`): dann gibt es die
  Reiter Bilder, Videos, Dateien und Sprachnachrichten und die Anzahl der
  Videos; Videos und Dateien zeigen ihr Vorschaubild. Bilder öffnen im
  Vollbild, Videos und Dateien in einer passenden App, alles lässt sich
  teilen; Sprachnachrichten spielen direkt in der Liste.
- **Verlassen / entfernt:** wird man aus einer Gruppe entfernt, die Gruppe
  gelöscht oder verlässt man sie, schließt sich der Chat — auf breiten
  Bildschirmen zeigt die rechte Seite wieder „Chat auswählen“.
- **Teilen → GateControl-Chat:** Text, Bilder und Dateien aus anderen Apps;
  danach wählt man den Chat. Das Teilen-Ziel ist nur eingeschaltet, solange
  der Server Chats anbietet; gelesen werden nur Inhalte anderer Apps.
- Alles bleibt auf dem eigenen Server; Dateien werden mit dem Geräte-Token
  geladen und im App-Cache gehalten (höchstens 200 MB).

### Messenger 1.4 – Status, Einstellungen, Abstimmungen, Ereignisse, Haus-Aktionen

Jede Funktion erscheint nur, wenn der Server sie unter `GET me` →
`features` einschaltet (`status`, `stories`, `polls`, `events`,
`arrival`, `actions`, `msg_tools`, `privacy2`); ältere Server sehen keinen
Unterschied. Unbekannte Nachrichtenarten neuerer Server zeigt der Chat als
„Diese Nachricht braucht eine neuere App-Version“ mit „Aktualisieren“.

- **Status („Dein Status“):** Tippen aufs eigene Profilbild im Start-Tab
  öffnet das Status-Blatt (das Profil ist dort verlinkt): Text (≤ 60),
  Vorlagen Im Homeoffice, Unterwegs (mit Ziel und Ankunftszeit), Nicht
  stören (keine Chat-Pushes), Automatisch nach Ort (Familie-Orte), „Löschen
  nach“ (30 Min., 1 Std., bis 17:00, heute, nie) und „Sichtbar für“ (alle,
  Familie, ausgewählte Personen). Der Status steht unter der Kopfzeile des
  Start-Tabs.
- **Stories:** Text auf Farbe mit Schrift, Foto oder Video, 24 Std.,
  Sichtbarkeit wie beim Status. In der Chat-Liste steht unter
  „Angeheftet“ ein Streifen: „Mein Status“, dann die Personen — ungesehene
  mit leuchtendem Ring, gesehene grau. Der Betrachter zeigt
  Fortschrittsbalken, pausiert beim Halten oder per Taste, springt per
  Tippen links/rechts, bietet Reaktionen und eine Antwort, die als
  Direktnachricht mit Vorschau ankommt (`replyStory`); eigene Stories zeigen,
  wer sie gesehen hat (mit Reaktion), und lassen sich löschen.
- **Chat-Einstellungen** sind eine eigene Seite: Profil mit Status,
  Datenschutz (zuletzt online, Profilbild, Status-Standard, Ort im Status,
  Lesebestätigungen, „schreibt …“, Standard-Dauer selbstlöschender
  Nachrichten, wer einen zu Gruppen hinzufügen darf, blockierte Personen,
  App-Sperre, Bildschirmfotos verhindern), Chats (Enter sendet,
  Archiv bleibt archiviert, Chatverlauf exportieren), Darstellung
  (Hintergrund in Farbe oder eigenes Foto, Schriftgröße, Farbe eigener
  Nachrichten mit Vorschau), Benachrichtigungen (Töne im offenen Chat, Ton
  und Vibration getrennt für Einzel- und Gruppenchats über eigene
  Android-Kanäle, Vorschau, Reaktionen, nur bei Erwähnung, Status-Übersicht
  einmal am Tag, Erinnerung an Ereignisse, Ruhezeiten, Verknüpfungen,
  Bubbles), Speicher und Daten (Belegung auf dem Gerät nach Fotos, Videos,
  Sprache, Dateien, Server-Kontingent, Medien löschen, automatisch laden
  über WLAN / Mobilfunk, Qualität beim Senden), Geräte (dieses Gerät),
  Listen, Haus-Aktionen, Hilfe und Info. Was das Konto betrifft, geht per
  `PUT prefs` an den Server (gilt auf allen Geräten); Darstellung, Töne,
  Auto-Download, Qualität, App-Sperre und Bildschirmfotos gelten nur auf
  diesem Gerät (DataStore).
- **App-Sperre:** Fingerabdruck oder Displaysperre (BiometricPrompt) beim
  Öffnen der Chats, erneut nach einer Minute im Hintergrund.
  **Bildschirmfotos verhindern** setzt `FLAG_SECURE` für alle Chat-Seiten.
- **Werkzeuge:** Chats archivieren (lange drücken oder Chat-Info; eigener
  Bereich „Archiviert“ am Ende der Liste), Nachrichten anheften (höchstens
  drei, Banner oben im Chat, Tippen springt hin), eigene Listen als Filter
  neben Alle / Ungelesen / Gruppen / Ereignisse, Chatverlauf als Text teilen,
  „Erinnere mich“ (in 1 Std., heute Abend, morgen früh oder Uhrzeit — der
  Server schickt die Erinnerung), Personen blockieren. Lange auf eine
  Nachricht drücken zeigt, was der Server im Text erkennt: „Auf
  Einkaufsliste“ (mit Anzahl) und „Termin anlegen“ (mit Tag und Uhrzeit) —
  beides landet in der Familie.
- **Neues Anhang-Menü:** Galerie, Kamera, Standort, Datei, Abstimmung,
  Ereignis, Live-Ankunft und „Aus deinem Zuhause“ (Aktion senden,
  Tür-Anfrage, Gerät teilen).
- **Abstimmungen:** Frage, 2–12 Antworten, mehrere erlauben, anonym,
  andere dürfen ergänzen, Ende. Im Chat: abstimmen (nochmal tippen nimmt die
  Stimme zurück), Balken mit Anzahl und Köpfen, Antwort ergänzen, Stimmen
  ansehen, Unentschlossene erinnern und beenden (Ersteller bzw. Admin).
- **Ereignisse:** Titel, Tag, Uhrzeit von–bis oder ganztägig, Ort aus den
  Familie-Orten oder frei, Beschreibung, Mitbring-Liste, Zusagen (mit
  Gästen), Familien-Kalender, Erinnerung, Abfahrt-Hinweis. Die Karte zeigt
  Datum, „in 8 Tagen“, Ort (öffnet die Karte), Liste zum Abhaken und
  Zusage / Vielleicht / Absage; das nächste Ereignis steht als Banner über
  dem Chat. Ersteller oder Admin können absagen.
- **Live-Ankunft:** Ziel aus den Familie-Orten oder auf der Karte, optional
  eine Haus-Aktion bei Ankunft. Solange sie läuft, schickt ein
  Vordergrunddienst (Standort, laufende Mitteilung mit „Stopp“) höchstens
  alle 20 s die Position; bei Ankunft, „Stopp“ oder nach 3 Std. endet er.
  Die Karte im Chat zeigt Karte, Ankunftszeit, Restzeit, Strecke und
  Fortschritt. Der Abfahrt-Hinweis eines Ereignisses öffnet die Auswahl
  direkt mit dem Ort des Ereignisses.
- **Haus-Aktionen:** Aktion zum Ausführen senden, eine Freigabe anfragen
  (nur wer darf, kann freigeben oder ablehnen) oder ein Gerät bis zu einer
  Uhrzeit teilen; Aktionen mit Rückfrage fragen vorher. Welche das Anhang-Menü
  zeigt, wählt man in den Einstellungen; die Rechte kommen aus
  GateControl. **Karten von Plugins** (z. B. die Klingel mit Standbild)
  zeigen Bild, Text, Knöpfe und „Live-Bild“, das den Tür-Bildschirm öffnet.
- **Mitteilungen:** Texte für Abstimmungen, Ereignisse, Anfragen,
  Ankünfte und Erinnerungen kommen vom Server; Tippen öffnet den Chat an
  der richtigen Nachricht, der Abfahrt-Hinweis die Live-Ankunft.

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
- Darunter (seit 1.41, Familie 1.5+) „Orte für Regeln teilen“: Eltern legen
  im Portal / auf der Admin-Seite Orte an (z. B. Schule oder ein 500-m-Kreis
  um Zuhause = „kurz vor Zuhause“) und lassen Familienregeln auf „kommt an“ /
  „verlässt“ reagieren. Nach der Zustimmung (Erklärung, dann genauer
  Standort und „Immer zulassen“) überwacht das Handy nur die Orte, die eine
  aktive Regel für diese Person nutzt (Geofencing der Play-Dienste, zusammen
  mit den Orts-Erinnerungen höchstens 90), und meldet nur „angekommen“ /
  „verlassen“ mit Ort und Uhrzeit — nie die Position. Die Liste kommt beim
  App-Start, stündlich und nach jeder Änderung (`watchVersion`) vom Server;
  fehlt die Berechtigung, bietet ein Hinweis sie an. Ohne Familie 1.5 fehlt
  der Schalter
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
