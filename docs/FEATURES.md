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

- **Netzwerk** hat die Segmente **Dienste**, **DNS-Schutz** und **Remote**
  (die RDP-Hosts, bis 1.34 ein eigener Tab). **DNS-Schutz** erscheint,
  wenn der Server einen DNS-Schutz meldet (siehe „DNS-Schutz“); die
  frühere Pi-hole-Anbindung gibt es nicht mehr. Es erscheinen nur die
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

### Seiten neben Start statt Tabs
Jede Funktion, die sonst ein Tab ist (Chats, Mail, Vault, Drive, Netzwerk,
Familie – soweit vorhanden), kann wahlweise **Tab** in der Leiste oder
**Seite neben Start** sein. Start wird dann eine Reihe von Seiten: Start
bleibt immer die erste Seite (und immer in der Leiste), rechts davon die
angehefteten Seiten in ihrer Reihenfolge, ganz rechts die **Plus-Seite**
(„Seite anheften“). Wischen wechselt die Seite; die **Seitenpunkte** über
der Leiste (aktiver Punkt breit in Akzentfarbe, die Plus-Seite als kleines
„+“) sind antippbar und tragen die Zähler (ungelesene Chats, Remote-Hosts
online). Die Punkte haben keinen eigenen Hintergrund: Start und
Widget-Seiten laufen bis zur Leiste unter ihnen durch, ein feiner Rand hält
sie auf Karten erkennbar, und die letzte Karte lässt sich ganz über die
Punkte scrollen. Eine angeheftete Funktion zeigt ihren echten Bildschirm, ihr
Symbol verschwindet aus der Leiste (bzw. der Navigationsleiste links).

- **Anheften:** Plus-Seite → „Seite an Start anheften“ (Abschnitte „Aus der
  Leiste“ und „Mehr Platz für Widgets“); einen Tab unten **lange drücken** →
  „An Start anheften“ oder „Leiste und Seiten …“; Einstellungen → Darstellung
  → **„Leiste und Seiten“**; „Seiten anordnen“ → „Anheften“. Danach zeigt
  Start die neue Seite, eine Snackbar meldet „„X“ ist jetzt eine Seite neben
  Start“ mit **Rückgängig**.
- **Lösen:** „⋯“ in der Kopfzeile der Seite → „Als Tab anzeigen“ (bei einer
  Widget-Seite „Seite entfernen“, mit Rückfrage, wenn sie etwas enthält – ihre
  Schnellaktionen und Widgets kommen zurück auf Start) und „Seiten anordnen“.
- **Widget-Seiten** („Widgets 2“, „Widgets 3“ …) nehmen Schnellaktionen und
  Widgets auf wie Start (ohne Info- und Verbrauchskarte): lange auf eine freie
  Stelle drücken → „Schnellaktionen und Widgets“ (was dort angeheftet wird,
  kommt auf diese Seite) und „Anordnen“. Leer zeigt sie einen gestrichelten
  Rahmen mit „Widgets hinzufügen“.
- **Seiten anordnen:** Start fest oben, die Seiten halten und ziehen
  (Screenreader: „Nach oben/unten“), je „Als Tab“ bzw. „Entfernen“; darunter
  „Unten in der Leiste“ mit „Anheften“ und „Neue Widget-Seite“.
- **Leiste und Seiten** (Einstellungen): Vorschau der Leiste, je Funktion
  „Tab | Seite“ und **„Leiste ausblenden, wenn nur Start bleibt“** (Standard
  an). Ist alles angeheftet, gibt es dann keine Leiste mehr, nur die
  Seitenpunkte mit einer Zeile der Seitennamen. Der Schalter gilt nur für
  dieses Gerät (er ändert nur die Darstellung hier; die Seiten selbst gelten
  mit dem Konto).
- **Regeln:** Tippen auf Start in der Leiste zeigt die erste Seite (auch wenn
  Start schon gewählt ist). Die Zurück-Taste auf einer anderen Seite führt
  erst zu Start, dann wie bisher aus der App. Auf einer Seite wechseln innere
  Reiter nur per Tippen (Drive: Dateien/Fotos, Schule: die Tage);
  Listen- und Kartenkarussells (Info-Karte, Chips-Leisten) scrollen weiter
  selbst und geben am Ende an den Seitenwechsel ab.
- **Pushes und Links** auf eine angeheftete Funktion (Chat, Netzwerk →
  Remote, Familie, Vault, Drive) öffnen ihre Seite bzw. den Unterbildschirm
  darüber, Zurück führt auf die Seite. Die alten Routen bleiben gültig und
  leiten auf die Seite um.
- **Speicherung:** in der Start-Reihenfolge (`start` von
  `GET/PUT /api/v1/client/layout`, ohne Serveränderung) als Trenn-Einträge
  `page:<id>` (`page:chats`, `page:vault`, `page:drive`, `page:network`,
  `page:familie`, `page:w2` …). Alles vor dem ersten `page:` gehört zu Start,
  Einträge nach `page:wN` zu dieser Widget-Seite; Funktionsseiten haben keine
  Einträge. Damit gelten die Seiten wie die Start-Reihenfolge mit dem Konto
  (gleiche Seiten auf dem zweiten Handy); legt der Administrator die
  Reihenfolge fest, sind Anheften, Lösen und Anordnen gesperrt („Die
  Anordnung legt der Administrator fest.“), seine Seiten gelten trotzdem.
  **Ältere App-Versionen** überspringen die `page:`-Einträge, zeigen alle
  Widgets wieder auf Start und behalten die Einträge beim Speichern.

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
- **Netzwerk-Tab:** langes Drücken auf Dienste, DNS-Schutz oder Remote legt
  fest, womit der Tab öffnet („Als Standard festgelegt“); ein Link mit
  Ziel (Push, alte Remote-Route) geht weiterhin vor. Ein früher gewählter Standard
  „Pi-hole“ öffnet den DNS-Schutz, ohne DNS-Schutz das erste Segment.
- Kill-Switch und Split-Tunneling stehen (nur noch) in den Einstellungen.
  Die Verbindungsdetails (Tunnel-IP, verbunden
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

## DNS-Schutz (Plugin `gatecontrol-dns` 1.0, GateControl 1.264+)

Der DNS-Filter des Servers für die VPN-Geräte. Die App zeichnet nur, was
der Server sagt (`may` in `GET status`); jede Regel setzt das Plugin durch.

- **Erkennung:** `vpnDns` in `GET /api/v1/client/permissions`
  (`{ plugin, state }`). Fehlt das Feld oder ist es `null` (älterer Server,
  Plugin aus, Schutz ausgeschaltet), gibt es weder den Reiter noch die
  Kachel — auch dann nicht, wenn ein älterer Server noch Pi-hole meldet
  (`pihole`/`piholeControl` liest die App nicht mehr).
  Die Plugin-Kennung kommt immer aus `vpnDns.plugin`.
- **Bildschirm „DNS-Schutz“** (Netzwerk, zwischen Dienste und Remote):
  Statuskarte (aktiv · unterbrochen mit Restzeit · ausgefallen · wird nicht
  gefiltert · startet), Zähler von heute (dieses Gerät, deine Geräte oder
  alle Geräte des Servers), „Schutz unterbrechen“ mit den Dauern 5 Min,
  30 Min, 1 Std (nicht erlaubte ausgegraut), 4 Std nur wenn erlaubt, und
  „Bis ich wieder einschalte“ (ausgegraut ohne Freigabe). Unterbrochen
  zählt der gewählte Chip die Restzeit gegen die Uhr des Servers herunter,
  darunter „Wieder einschalten“. „Unterbrechen nicht erlaubt“ (Profil)
  ersetzt die Chips durch einen Hinweis; bei „Nur Admins“ gibt es keinen
  Knopf. Dazu „Zeiten heute“ und „Top gesperrte Domains“, wenn der Server
  sie liefert.
- **Kachel auf Start:** Zustand, Zähler und Knopf („Unterbrechen“ öffnet
  den Bildschirm, „Einschalten“ schaltet direkt wieder ein).
- **Für andere unterbrechen** (Admins, Eltern; `status.manage`): Personen
  und ihre Geräte aus `GET people`, Admins auch „Alle VPN-Geräte“, jeweils
  nur mit den für das Ziel erlaubten Dauern.
- **Abfragen:** beim Öffnen, alle 45 s solange der Bildschirm vorn ist,
  kurz nach dem Ende einer Unterbrechung; nach `rate_limited` alle 2 Min.
- **Fehler:** kein Netz → letzter Stand mit Hinweis; Plugin gestört
  (502/503/504) → „gerade nicht erreichbar“; `off`, `unsupported`, 404 des
  Kerns, `no_user` → Karte weg und `permissions` neu laden; Ablehnungen
  zeigen den Satz des Servers.

Code: `dnsprotect/` (Modelle, `DnsJson`, `DnsLogic`, `DnsRepository`),
`ui/dnsprotect/`.

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
zurück geht es dorthin, wo man war. Wer nur Kameras sehen darf (kein Schloss,
keine Tür-Schnellaktion), findet auf dem Start-Tab unter den Kacheln die Zeile
**„Tür & Kamera · Kameras ansehen“**.

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
- **Kameras im Heimnetz** (Plugin 1.6.0, GateControl 1.240.0, Gateway
  1.28.0; ONVIF/RTSP ohne Hersteller-Cloud): nur die Kameras, die die Person
  laut Plugin „sieht“. Titel „Kameras · n online“, Chips je Kamera, darunter
  das Bild: mit VPN (oder im LAN des Servers) Live-Video (HLS, „● LIVE · HD ·
  über VPN“, Name und Uhrzeit), ohne VPN alle 10 s ein neues Standbild mit
  Hinweis. Knöpfe **Standbild** (in die Galerie unter Bilder › GateControl
  oder teilen), **Vollbild**, **Ton** (nur wenn die Kamera Ton liefert).
  **Clips heute** mit Vorschaubild, Länge und Art (Person / Auto / Tier);
  ein Tipp spielt den Clip. Die Medien-Tokens (10 Min.) erneuert die App vor
  dem Ablauf, das Video läuft dabei weiter.
  Plugin 1.7.0: Schalter **„Push bei Personen“** je Kamera (nur für dich),
  die Mitteilung „Person an der …“ hat den Knopf **„Ansehen“**, der genau
  diese Kamera öffnet; HD steht schon vor dem ersten Bild fest (Auflösung aus
  der Liste).
- **Verlauf** mit Filter Alle / Aktionen / Klingeln; ein Klingeln öffnet
  das Bild und — mit dem Recht „öffnen“ — „Tür öffnen“.
- **Klingel-Push** mit Bild auf einem eigenen Kanal mit hoher Wichtigkeit;
  „Tür öffnen“ direkt in der Mitteilung (verlangt das Entsperren des
  Geräts), „Ansehen“ öffnet das Klingel-Bild in der App; andere
  Tür-Mitteilungen öffnen „Tür & Kamera“.
- **„Personen an der Kamera“** (Heimnetz-Kameras): Push mit Standbild auf
  einem Kanal mit hoher Wichtigkeit; ein Tipp öffnet „Tür & Kamera“ mit
  dieser Kamera.
- Offline zeigt „Tür & Kamera“ den zuletzt bekannten Stand der Schlösser; Bilder
  und Verlauf bleiben nur auf dem eigenen Server.

---

## Fahrtenbuch (Plugin `gatecontrol-skoda` 1.6, GateControl 1.189+)

Kein eigener Tab: Die Schnellaktion „Fahrtenbuch“ des Fahrzeuge-Plugins
**öffnet** das Fahrtenbuch, statt etwas auszuführen (Feld `opens` der
Schnellaktion; Details: [LOGBOOK.md](LOGBOOK.md)). Im Widget auf dem
Start-Tab ist sie ein breiter Knopf „Öffnen“; Kachel, Home-Widget,
App-Verknüpfung und Schnelleinstellungen öffnen die App dort. Die vier
Zweck-Knöpfe im Widget setzen weiter den Zweck der letzten Fahrt.

- **Fahrtenbuch:** Monat wählen (keine Zukunft), Summe in km mit Zahl der
  Fahrten, Balken und Summen nach Arbeitsweg / Geschäftlich / Privat,
  Kartenvorschau, Fahrten nach Tag (neueste zuerst). Fahrten ohne Zweck
  haben die drei Zweck-Knöpfe direkt in der Liste. Fahrzeugwahl bei mehreren
  eigenen Fahrzeugen, Export des Monats als PDF oder CSV über „Teilen“.
- **Karte:** alle Fahrten des Monats, farbig nach Zweck (offen grau
  gestrichelt, Luftlinie gestrichelt); gleiche Strecken einmal mit Anzahl
  („18×“); Filter nach Zweck; Tippen zeigt Strecke, Zweck, Fahrten und km
  mit „n Fahrten zeigen“ und „Letzte Fahrt“.
- **Fahrt:** Karte mit A und B, „Strecke berechnet“ oder „Luftlinie“,
  Start/Ziel mit Uhrzeit und Kilometerstand, Zweck, „Anlass /
  Geschäftspartner“, Fahrer, Dauer, „Start und Ziel ändern“. Ohne
  Bearbeitungsrecht nur ansehen.
- Nichts davon wird auf dem Gerät gespeichert; ohne Verbindung bleibt der
  zuletzt geladene Stand sichtbar, solange die App läuft. Tablets: Liste
  links, Karte oder Fahrt rechts.

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
- **Teilen → „An Chat senden“:** Text, Bilder und Dateien aus anderen Apps;
  danach wählt man den Chat. Das Teilen-Ziel ist nur eingeschaltet, solange
  der Server Chats anbietet; bietet er GateDrop an, heißt der Eintrag
  stattdessen **„GateDrop“** und öffnet das GateDrop-Blatt (die Chats sind
  dort einen Tipp entfernt). Es gibt immer nur einen der beiden Einträge;
  gelesen werden nur Inhalte anderer Apps.
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

## Vault (Plugin `gatecontrol-vault` 1.0)

Wenn der Server das Plugin „Vault“ anbietet, hat die untere Leiste den Tab
**Vault** (zwischen Chats und Netzwerk): ein Passwort-Manager mit
Ende-zu-Ende-Verschlüsselung. Der Server sieht nur verschlüsselte Daten;
Abgleich über die normale Server-Adresse, auch ohne aktiven Tunnel.

- **Einrichten:** Master-Passwort (Stärke-Anzeige, mind. 12 Zeichen),
  Wiederherstellungsschlüssel (56 Zeichen, kopieren, drucken), danach
  Fingerabdruck-Entsperren und Autofill einschalten. Schlüssel:
  Argon2id (64 MiB, t=3), XChaCha20-Poly1305, X25519 — dasselbe Format wie
  die Windows-Apps (Testvektoren aus `gatecontrol-client-core`).
- **Entsperren:** Master-Passwort, Fingerabdruck (Schlüssel im Keystore,
  an starke Biometrie gebunden) oder Wiederherstellungsschlüssel mit neuem
  Master-Passwort. Automatisch sperren nach 0/1/5/15/60 Minuten im
  Hintergrund (Standard 5).
- **Einträge:** Login (mit TOTP-Code und Ring), Einmal-Code, Notiz, Karte,
  WLAN (QR-Code), Identität; eigene Felder, Favoriten, Suche, Filter nach
  Typ, Verlauf früherer Versionen, Papierkorb. Kopierte Geheimnisse sind
  als vertraulich markiert und werden nach 30 s aus der Zwischenablage
  gelöscht. Passwort-Generator (Zeichen oder Wörter).
- **2FA per QR-Code:** Beim Login und beim 2FA-Code öffnet „QR-Code
  scannen“ die Kamera; „Aus Bild (Screenshot)“ liest den QR-Code aus einem
  Bild, wenn die Website auf demselben Handy offen ist. Übernommen werden
  `otpauth://totp`-Codes (mit Ziffern, Intervall, Algorithmus) und Exporte
  aus dem Google Authenticator (`otpauth-migration://`, Auswahl des
  Kontos); Titel und – beim Login – Benutzername kommen aus dem Code, wenn
  sie leer sind. HOTP wird abgelehnt.
- **WLAN in der Nähe:** Beim WLAN-Eintrag listet „WLAN in der Nähe“ die
  Netze in Reichweite (verbundenes zuerst, dann nach Signal); ein Tipp
  übernimmt Name und Sicherheit (WPA2, WPA3, WPA2/WPA3, WPA, WEP, Offen).
  Android liefert Scan-Ergebnisse nur mit Standort-Berechtigung und
  eingeschaltetem Standort – erfragt erst beim ersten Suchen, nichts wird
  gespeichert oder gesendet.
- **Offline:** eine verschlüsselte Kopie liegt auf dem Gerät; Änderungen
  ohne Verbindung gehen in eine Warteschlange. Bei Konflikten entsteht eine
  Kopie „(Konflikt)“, nichts geht verloren.
- **Teilen:** weitere Tresore anlegen, Personen des Servers mit Rolle
  (Lesen, Bearbeiten, Verwalten) hinzufügen, Sicherheitsnummer zum
  Vergleichen; beim Entfernen werden die Schlüssel des Tresors erneuert.
- **Notfallzugriff:** Vertrauenspersonen mit Wartezeit; sie können nach
  Ablauf der Frist (ohne Widerspruch) den Tresor lesen.
- **Import:** Bitwarden (JSON/CSV), Chrome, 1Password, KeePass, Firefox
  (CSV); Duplikate werden erkannt.
- **Autofill:** GateControl als Android-Autofill-Dienst (Einstellungen →
  Passwörter & Autofill). Passende Einträge nach App oder Webseite,
  „Entsperren“ solange gesperrt, „In Vault suchen …“ (wahlweise für diese
  App merken) und Speichern neuer Logins. Die Vorschläge zeigen nur Titel
  und Benutzername; das Passwort wird erst beim Auswählen eingesetzt.
- **Browser ohne eigenes Autofill** (Edge, Samsung Internet, DuckDuckGo,
  Vivaldi, Kiwi, Ecosia, Opera Mini/GX/Touch, Chromium-Ableger, Brave und
  Chrome vor 135): `compatibility-package` in
  `res/xml/vault_autofill_service.xml` schaltet Androids
  Autofill-Kompatibilitätsmodus ein (Chrome mit Beta/Dev/Canary und Brave nur
  bis zur Version mit eigenem Autofill – Chrome unterstützt den Modus nicht
  mehr). Die Domain der Seite steht dann in der Adressleiste des Browsers
  (`VaultBrowsers.URL_BARS`, View-Ids je Paket) und zählt nur, wenn dort
  eindeutig eine Adresse steht (ein Wort, `http(s)://` oder ohne Schema, Host
  mit Punkt; kein Suchbegriff, kein Seitentitel, nicht `edge://`, `about:`,
  `javascript:`, keine Benutzerangaben `@`). Die Adressleiste wird nie als
  Feld zum Ausfüllen genommen und nicht, solange man in ihr tippt. Firefox und
  Opera melden die Domain selbst und laufen ohne den Modus.
- **Browser mit eigenem Autofill für andere Dienste** (Chrome ab 135 mit
  „Autofill mit einem anderen Dienst“, Edge ab 147 mit dem Passwortmanager von
  Android): Chromium meldet `webDomain`/`webScheme` am Wurzelknoten der Seite,
  ein Frame einer anderen Seite an seinem eigenen. Jedes Feld gehört zur Seite
  des nächsten Knotens darüber; nur `http`/`https` zählt (`chrome://`,
  `edge://`, `file:`, `blob:` … nicht). Liegen die Felder auf verschiedenen
  Seiten (Anmelde-Frame eines anderen Anbieters, Werbe-Frame), entscheidet
  die Seite des Passworts; Felder anderer Seiten werden nicht ausgefüllt
  (`VaultBrowsers.pickPage`).
- **Sicherheit:** Eine Webseite passt nur über ihre Domain, nie über das
  Paket des Browsers. Ohne sicher erkannte Domain schlägt Vault nichts vor
  („Seite nicht erkannt“, nur „In Vault suchen …“ zum selbst Wählen), merkt
  den Browser nie als „App“ eines Logins und speichert neue Logins ohne
  App-Zuordnung. Gespeichert wird auch bei Seiten, die per Skript anmelden
  (`SaveInfo.FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE`).
- **Hinweis „Browser einrichten“** (Vault-Einstellungen unter „In anderen Apps
  ausfüllen“ und im letzten Schritt der Einrichtung): nur für installierte
  Browser, die noch einen eigenen Schalter brauchen – Chrome (Einstellungen ›
  Autofill-Dienste › „Autofill mit einem anderen Dienst“), Edge ab 147
  (Passwort-Einstellungen: Passwortmanager von Android statt des
  Edge-eigenen; die Auswahl erscheint erst, wenn in Android nicht „Google“ der
  Autofill-Dienst ist), Brave und Vivaldi. Ob der Schalter schon an ist, fragt
  die App beim Browser selbst ab (Chromiums Content-Provider
  `<paket>.AutofillThirdPartyModeContentProvider`, `0`/`1`); ohne Antwort
  entscheidet die Version. Der Knopf „<Browser> öffnen“ öffnet dessen
  Autofill-Einstellungen (`ACTION_APPLICATION_PREFERENCES`), sonst den
  Browser. Die Pakete stehen in `<queries>` des Manifests.

---


## Mail (Plugin `gatecontrol-mail` 1.0, GateControl 1.216+)
Eigener Tab **Mail** (Tab-Id `mail`, nach Chats), nur wenn der Server das
Plugin mit Tab für die Person listet (installiert und freigeschaltet);
wie die anderen Tabs als **Seite neben Start** anheftbar. Ohne Plugin
ändert sich nichts (auch der Microsoft-Redirect ist dann abgeschaltet).

- **Posteingang:** „Alle Posteingänge“ oder ein Konto (Chips mit dem
  Farbpunkt des Kontos), **Relevant | Sonstige** (nur in Posteingängen),
  Wischen nach links/rechts wie in den Mail-Einstellungen
  (`archive`/`delete`/`read`/`none`, auch als Bedienungshilfen-Aktionen),
  lange drücken: gelesen, markieren, Relevant/Sonstige (lernt pro
  Absender), archivieren, verschieben, Spam, löschen. „Neue Mail“ unten
  rechts; nach dem Senden 10 s die Leiste **Rückgängig** (Server-Fenster
  `undoUntil`, bei abweichender Uhr 10 s ab Antwort).
- **Neue Mails oben:** Kommen beim Aktualisieren, per Push oder beim
  Nachladen im Hintergrund neue Mails vor die oberste sichtbare, bleibt die
  Liste oben stehen, wenn man ganz oben war – die neuen Mails sind sofort zu
  sehen (vorher blieb die alte erste Mail im Blick und die neuen lagen
  darüber). Wer weiter unten liest, wird nicht verschoben; oben erscheint
  stattdessen die Pille **„n neue Mails“**, ein Tipp scrollt nach oben, sie
  verschwindet, sobald die Liste oben ist. Gezählt werden nur Mails, die in
  dieser Liste noch nie zu sehen waren und über der ersten sichtbaren Zeile
  stehen (nicht die nächste Seite, nicht eine nach „Rückgängig“
  zurückgekehrte Mail). Beim ersten Laden (Gerätekopie → Server) und beim
  Wechsel von Posteingang, Konto, Ordner oder Relevant/Sonstige gibt es keine
  Pille; ein Wechsel beginnt immer oben (`MailLogic.newMails`).
- **Seitenmenü:** Alle Posteingänge, **Zum Ablegen** (mit Drive),
  **Postausgang** (wenn etwas wartet), die Konten mit ihren Ordnern,
  Speicherbelegung, „Konto hinzufügen“ und „Mail-Einstellungen“.
  Kalender/Ressourcen kommen erst in Phase 2/3.
- **Lesen:** KI-Leiste (nur mit Feature `ai` und KI am Konto): „Kurz
  gesagt“, Zu tun als Chips (Termine/Fristen in den Familienkalender oder
  den eigenen Kalender), Antwortvorschläge über „Antworten“. Anhänge mit
  Drives Vorschlag, „In Drive speichern“, „Anderer Ordner“ und „künftig
  automatisch ablegen“ (nur mit Feature `drive`). „Bilder laden“ /
  „Immer für diesen Absender“ (Feature `images`). Unterhaltung, Abmelden
  (List-Unsubscribe).
- **HTML-Anzeige:** WebView ohne JavaScript, ohne Datei-/Content-Zugriff,
  ohne Speicher und Fenster, mit Content-Security-Policy; alle Anfragen
  werden abgefangen: nur `img/<token>` (JSON mit Base64, ≤ 700 KB) und
  `cid/<mail>/<anhang>` (JSON mit `mediaId`, Bild aus der Plugin-Media) lädt
  die App mit ihrer Anmeldung über die Plugin-API (`cid:` wird
  umgeschrieben); nur Rasterbilder, nie SVG; alles andere bleibt leer. Links öffnen außerhalb der App, `mailto:` im
  Schreiben-Bildschirm.
- **Schreiben:** Absender-Konto wählbar, Empfänger mit Vorschlägen
  (`GET contacts`), Cc/Bcc, Anhänge vom Gerät (≤ 25 MB, Plugin-Media) oder
  „Aus Drive“ (Drive-Suche, je Datei Kopie oder Link, Link 14 Tage),
  Warnungen des Servers, Entwurf wird laufend gespeichert, „Senden planen“
  (lange auf Senden drücken oder Menü). Antworten/Weiterleiten lässt den
  Server Empfänger, Betreff und Zitat vorfüllen; die Signatur des Kontos
  setzt die App oben in neue Mails, Antworten und Weiterleitungen.
  Microsoft-Konten: Gerätedateien und Anhänge aus Mails anderer Konten
  gehen nicht (Warnung, Senden → `attachments_unsupported` mit Erklärung).
- **Konto hinzufügen:** Anbieterliste des Servers, Formular mit Hinweis des
  Anbieters, bei „Andere / eigene Domain“ IMAP/SMTP von Hand (nur TLS bzw.
  STARTTLS). **Microsoft:** Autorisierungscode mit PKCE im Custom Tab,
  Redirect `msauth://com.gatecontrol.client/<URL-kodierter Base64-SHA-1 der
  App-Signatur>` (zur Laufzeit berechnet, `MailSigninActivity`), der Server
  löst den Code ein. Ohne Client-ID: „noch nicht eingerichtet“.
- **Push:** Topic `mail` (Kanal „Mail“) öffnet die Mail, `mail_failed`
  den Postausgang. Realtime-Event `mail` lädt offene Listen neu.
- **Offline:** zuletzt geladene Listen (Posteingang mindestens 30 Tage,
  wenigstens die neuesten 100) und geöffnete Mails (30 Tage, höchstens 500)
  liegen pro Anmeldung in `noBackupFilesDir/mail`; sie werden gelöscht,
  wenn das Plugin verschwindet, sich Server/Token ändern oder der Server
  401 antwortet (Fernlöschen), ebenso geöffnete Anhänge (`cache/mail-open`).

## Notizen & Aufgaben (Plugin `gatecontrol-notizen` 1.0, offline ändern ab 1.1, Listen bearbeiten/teilen ab 1.2, GateControl 1.216+)
Eigener Tab **Aufgaben** (Tab-Id `notizen`, nach Mail), nur wenn der Server
das Plugin für die Person listet (installiert, lizenziert, freigeschaltet);
antwortet `GET me` nicht (keine Lizenz, keine Berechtigung), sagt der Tab das
statt leerer Listen. Wie die anderen Tabs als **Seite neben Start**
anheftbar. Die Seite zeigt **Mein Tag** oder die **Notizen**, rechts der
Bereich **Privat / Arbeit** (wird für alle Geräte der Person gemerkt,
`prefs.area`) und die Suche, unten die Schnell-Eingabe bzw. „Neue Notiz“;
Herunterziehen lädt neu.

- **Menü ☰ (Seitenleiste wie in Drive, `NotizenDrawer`, Teile in
  `ui/components/GcDrawer.kt` mit Drive gemeinsam):** Kopf mit Name und
  Bereich (Privat | Arbeit), Mein Tag, Wichtig, Geplant, Mir zugewiesen,
  Aufgaben (Standardliste); **Meine Listen** mit „+“ („Neue Liste oder
  Gruppe“) und Gruppen als aufklappbare Ordner (Zahl = offene Aufgaben
  darin); **Aus anderen Plugins**; **Notizbücher** mit „+“ (Notizbuch oder
  Tresor-Notizbuch), „Alle Notizen“; unten links das Zahnrad
  **Einstellungen**. Geteilte Listen haben das Personen-Symbol (ich habe
  geteilt) oder das kleine Bild des Besitzers (mit mir geteilt).
- **Langes Drücken auf eine Liste** (oder ⋯ auf ihrer Seite): Besitzer –
  Bearbeiten (Name, Farbe, Symbol, Gruppe, Wochentage für „Mein Tag“,
  „Morgens daran erinnern“), Teilen, In Gruppe verschieben, Einkaufstag,
  Liste löschen; Mitglied – In Gruppe verschieben, In „Mein Tag“ zeigen
  (nur für mich), Liste verlassen. Löschen fragt mit offenen/erledigten
  Aufgaben und Personen nach, offene Aufgaben wandern auf Wunsch nach
  „Aufgaben“, danach **„Rückgängig“** (`POST lists/<id>/restore`). Die
  Standardliste lässt sich nur umbenennen. **Gruppe:** Neue Liste in dieser
  Gruppe, Umbenennen, Auflösen (Listen bleiben). **Notizbuch:** Bearbeiten
  (Name, Farbe, geteilt mit), Teilen, Löschen (Notizen nach „Notizen“ oder
  mitlöschen; ein Tresor fragt nach seinem Namen), Verlassen.
- **Teilen:** Personen suchen (`GET people`), hinzufügen, **Ansehen /
  Bearbeiten** wählen, entfernen. Wer nur ansehen darf: kein Eingabefeld,
  Häkchen nur bei mir zugewiesenen Aufgaben, Aufgabe und Notiz nur lesen.
  In geteilten Listen das Bild der zugewiesenen Person an jeder Aufgabe
  (Antippen weist zu) und Filter **Alle / Mir zugewiesen / je Person /
  Niemandem**.
- **Plugin 1.2.0** meldet in `GET me` → `features` `listDays`,
  `shareRoles`, `groupEdit`, `deleteKeep`, `listIcons`; ohne sie (1.1.x)
  fehlen Wochentage, Erinnerung, „In ‚Mein Tag‘ zeigen“, Rollen, Symbole,
  Rückgängig und die Einstellungen zu „Mein Tag“ und Benachrichtigungen –
  Bearbeiten, Teilen (alle dürfen ändern), Gruppen und Löschen gehen mit den
  Routen von 1.1.
- **Einstellungen** (bisher „Quellen“): Aufgaben aus anderen Plugins, „Mein
  Tag“ (Listen-Tage oben, Erledigte zeigen, Beim Öffnen), Uhrzeiten der
  Schnell-Eingabe (`prefs.times`), Benachrichtigungen (`prefs.notify`) und
  Umziehen.

- **Mein Tag:** Termine heute (Uhrzeit vorne), Überfällig (rot), Heute,
  Demnächst (mit „Geplant“), „Erledigt heute (n)“ zum Aufklappen – eigene
  Aufgaben und alles „Zu tun“ aus Familie, Mail, Drive, Schule und Alltag
  mit **Quellen-Badge** („Mail · Zu tun“, „Drive · Frist“, „Schule ·
  Hausaufgabe“ …), Unteraufgaben n/m, Wiederholung, Zuweisung, Sterne,
  Betrag. **Abhaken** hakt ein Element eines anderen Plugins dort ab
  (`items/<id>/complete`); was das Plugin nicht abhaken lässt, hat kein
  Häkchen.
- **Mein Tag am Listen-Tag (1.2.0):** oben die Karte „Heute ist
  Einkaufstag“ (`listdays` von `views/myday`) mit Zahl der offenen Dinge, den
  ersten Einträgen zum Abhaken und „Ganze Liste öffnen“.
- **Aufgaben und Erinnerungen, Stufe 1 (Plugin 1.3.0):** erkannt nur an den
  Wörtern in `me.features` – `reminderActions`, `yesterdayOpen`,
  `taskCalendar` –, nie an einer Version; ohne sie bleibt alles wie bisher.
  - **Meldung zur Fälligkeit mit Knöpfen** (Erledigt · In 1 Std. · Heute
    Abend / Morgen früh): `data.actions` vom Typ `callback` gehen wie jede
    Callback-Aktion als `POST push/ack { seqs, state: "read", action }` an den
    Server (`PushActionReceiver`), ohne „Gesehen“ daneben.
  - **Das Handy merkt sich die Erinnerung selbst** (`NotizenReminderCenter`):
    `GET reminders` liegt in der Kopie des Plugins auf dem Gerät und wird
    nach dem Start, nach `changed` mit `tasks`, nach eigenem Schreiben, nach
    Boot, App-Update, Uhr- oder Zeitzonenwechsel und täglich (WorkManager)
    neu geholt; `ReminderPlan` stellt daraus die Alarme (die nächsten 60, je
    Aufgabe einen, Verpasstes bis 24 Stunden sofort). **Exakt** nur, wenn
    Android es erlaubt (`SCHEDULE_EXACT_ALARM`, `canScheduleExactAlarms()` –
    bis Android 13 von selbst, ab Android 14 nur mit dem Schalter „Wecker
    und Erinnerungen“; die App fragt nie per Dialog danach, in den
    Einstellungen steht ein Hinweis mit Link). Sonst zwei ungenaue Alarme:
    einer im 10-Minuten-Fenster, einer, der auch im Ruhezustand kommen darf.
    `USE_EXACT_ALARM` wird nicht verwendet (bei Google Play Weckern und
    Kalendern vorbehalten). Die lokale Meldung zeigt Titel, Text und Knöpfe
    aus `GET reminders` unverändert; die Knöpfe gehen direkt an die API
    (`tasks/<id>/complete`, `tasks/<id>/snooze { until, tag }`), ohne Netz in
    den Postausgang. Der Schalter „Mitteilungen“ und das Thema des Geräts
    gelten auch hier, in der Ruhezeit kommt sie stumm.
  - **Keine doppelten Meldungen** (`ReminderGuard`): Alarm und Push teilen
    eine Benachrichtigung (die Id des `collapse_key` `task:<id>`, derselbe
    Kanal). Wer zuerst kommt, wird gezeigt, der andere ersetzt ihn still;
    ein `tag`, den die Person entschieden hat (Knopf, weggewischt, geöffnet),
    wird 48 Stunden lang nicht wieder gezeigt – ein Push dazu wird still als
    gelesen quittiert. Vor dem Zeigen prüft der Alarm mit Netz, ob der `tag`
    noch in `GET reminders` steht.
  - **„Gestern offen geblieben“:** Karte in „Mein Tag“ über „Heute“
    (`yesterday` von `views/myday`; ihre Aufgaben fehlen dann unter
    „Überfällig“), je Zeile Erledigt · Heute · Verschieben (`moveOptions` +
    „Anderes Datum“), im Kopf „Alle heute“ (`POST yesterday`). Der
    Morgen-Hinweis (`kind: 'yesterday'`) öffnet beim Antippen die Karte;
    „Erledigt“ bei mehreren fragt einmal „Alle N abhaken?“, „Verschieben“
    bietet die Tage an – beides fängt die App ab, statt das Portal zu öffnen
    (`NotizenPush.yesterdayButton`); „Heute“ ist ein Callback.
  - **Kalender:** Chip „Kalender: Aufgaben“ in der Schnell-Eingabe (bei
    Datum mit Uhrzeit) und das Zeichen „im Kalender“ an der Aufgabe, nur wenn
    `me.calendar.available && enabled && mine`. In den Einstellungen der
    Schalter „Aufgaben mit Uhrzeit in meinen Kalender eintragen“
    (`prefs.calendar.tasks`) sowie der Morgen-Hinweis mit Uhrzeit
    (`prefs.notify.yesterday`, `prefs.yesterdayAt`).
- **Aufgaben und Erinnerungen, Stufe 2 (Plugin 1.4.0, Familie 1.21.0):**
  erkannt nur an den Wörtern in `me.features` – `shoppingLists`, `taskList`,
  `familyShopping` – und an `home` in Familie's `GET shopping`; mit älteren
  Plugins bleibt alles wie bisher.
  - **Einkaufslisten in „Aufgaben“** (`shoppingLists`): Abschnitt
    „Einkaufslisten“ im Seitenmenü (die Listen des Bereichs, „+“ legt eine
    an), die Liste zum Abhaken unter `notizen/shopping/<uid>`
    (`NotizenShoppingScreen`): offene Punkte zuerst, Menge am Ende, Tippen
    hakt ab, langes Drücken ändert oder löscht, unten „Punkt hinzufügen“,
    „Abgehakte entfernen“, im Menü Umbenennen und Löschen (nur wer
    `canManage` hat); bei „nur ansehen“ nichts davon. Offline-fähig über
    denselben Speicher wie die Einkaufsliste der Familie (`ShoppingStore`,
    `files/familie/hub-shopping.json`): Änderungen warten im Postausgang und
    gehen in Reihenfolge an `POST shopping/ops`, danach
    `GET shopping?since=<serverTime>`; der Hub nennt jede Liste in jeder
    Antwort – eine fehlende wird entfernt, für eine neue wird einmal alles
    geladen. „Ich gehe einkaufen“ / „… bitten“ nur bei Familien-Listen und
    nur mit `familyShopping` (und `me.shopping.family.available`).
  - **Liste an der Aufgabe** (`taskList`): „Einkauf 0/8“ an der Zeile (Mein
    Tag, Listen, Suche) – Tippen darauf öffnet die Liste, die Zeile selbst
    die Aufgabe; in der Aufgabe die Zeile „Angehängte Liste“ mit Auswahl
    („Familie · 8 offen“, „nur ich“, die Namen), „Keine Liste“, „Neue Liste“
    und „Liste öffnen“ (`attachedListId` in `PATCH tasks/<id>`, mit `base`).
    Die Schnell-Eingabe fragt den Server (`POST parse` → `attach`) und zeigt
    den Baustein „Liste: Einkauf · 8 offen“ (wegnehmen oder tauschen; bei
    mehreren die Auswahl); der Parser auf dem Gerät bleibt unverändert,
    offline gibt es keinen Vorschlag.
  - **„Alles abgehakt“:** nennt die Antwort von `POST shopping/ops`
    `completed`, zeigt die offene Liste „Alles abgehakt. ‚…‘ ist erledigt.“
    mit „Rückgängig“ (`POST tasks/<id>/complete { done: false }`).
  - **Erinnerung mit Liste:** der Knopf „Liste öffnen“ (Push: Aktion
    `open_list` mit `data.notizen.attachedList`; lokaler Alarm: `actions` aus
    `GET reminders`) öffnet die angehängte Liste direkt
    (`NotizenPush.listButton`), Text und Knöpfe bleiben die des Servers.
  - **Familien-Karte „Einkaufsliste“** (`ShoppingHome`, `ShoppingEntry`):
    meldet Familie `home: notizen`, steht an der Karte „aus Notizen &
    Aufgaben“, und „Öffnen“ – ebenso die Orts-Erinnerung mit Liste und die
    Einkaufs-Pushes der Familie – führt in die Liste des Hubs mit derselben
    Kennung, sofern die App dort `shoppingLists` kennt und eine Kopie hat;
    sonst wie bisher auf die Seite des Familien-Plugins.
- **Liste:** offene und erledigte Aufgaben, unten ein Feld für eine neue
  Aufgabe (nicht bei „nur ansehen“).
- **Aufgabe:** Titel, Wichtig, Unteraufgaben (hinzufügen, abhaken,
  löschen), In „Mein Tag“, Fällig, Erinnerung (Push des Servers), Wiederholen
  (täglich, werktags, wöchentlich am Fälligkeitstag, alle 2 Wochen,
  monatlich, jährlich, n Tage nach Erledigung – Text wie `ui/repeat.js`),
  Zugewiesen (`GET people`), Anhänge (Datei vom Gerät → Plugin-Media →
  `POST attachments`, öffnen über `cache/notizen-open`), Notiz. Eine
  Aufgabe aus einem anderen Plugin zeigt „Aus Mail · … · Mail öffnen“.
- **Element eines anderen Plugins:** Details, **„… öffnen“** (die Route
  `open { plugin, route }` = `gatecontrol://plugin/<plugin>/<route>`:
  Mail `message/<id>` → die Mail, Drive `node/<id>` → die Datei, Familie
  `task/…` → Aufgaben, `shopping/<id>` → die Einkaufsliste, Schule →
  Stundenplan, Alltag → Termine; sonst der Tab des Plugins,
  `NotizenOpen`), abhaken, **„Unteraufgaben, Notiz, Erinnerung …“**
  (`items/<id>/carry`: eine eigene Aufgabe dazu).
- **Schnell-Eingabe:** `ui/quickparse.js` 1:1 nach Kotlin portiert
  (`QuickParse`, reine Funktion, ohne KI, auf dem Gerät); die erkannten Teile
  sind im Text farbig hinterlegt und stehen als Chips darunter („Mo. 9. Nov.,
  9:00“, „Erinnerung 9:00“, „Wichtig“, „Liste: Gesundheit“, Bereich) – ein
  Tipp ändert (Datum, Liste, Bereich) oder entfernt sie. „#neu“ ohne Liste
  legt die Liste an. Umschalter **Aufgabe | Notiz** (erste Zeile = Titel).
  Die Regex sind so geschrieben, dass JVM und Androids ICU sie gleich lesen
  (`\s` = JavaScripts Leerraum, `\d` = `[0-9]`). Alle 51 Vektoren von
  `test/vectors/quickparse.json` laufen als Unit-Test.
- **Notizen:** Notizbuch aus dem Menü (ein gesperrter Tresor öffnet erst seine Seite), angeheftete
  als farbige Karten, „Zuletzt geändert“ mit Bild-Hinweis, Schlagwort,
  Notizbuch, Checklisten-Stand. **Notiz:** Markdown (CommonMark + GitHub-
  Tabellen/Task-Listen wie `ui/notemd.js`) mit eigenem kleinen, sicheren
  Renderer (`NoteMd`: kein HTML, Links nur http/https/mailto; die App hat
  keine Markdown-Bibliothek) – Überschriften, fett, kursiv, durchgestrichen,
  Code, Zitate, Listen, **Checklisten zum Abhaken** (`notes/<id>/check`),
  Tabellen, Bilder der Notiz, „Aufgabe · Mi.“ öffnet die Aufgabe einer
  Zeile. Werkzeugleiste: Fett, Kursiv, Überschrift, Aufzählung, Checkliste,
  Tabelle, Bild/Datei anhängen, **Foto aufnehmen** (Kamera →
  `cache/camera`), **Zeile → Aufgabe** (`notes/<id>/task`, auch lange auf
  eine Checklisten-Zeile drücken). Speichert beim Tippen (1,5 s) mit `rev`;
  wurde die Notiz anderswo geändert (`409 conflict`), fragt die App „Meine
  behalten / Die andere laden“. Anheften, Farbe, Teilen, Löschen. Erkannter
  Text in Bildern (ML Kit, auf dem Gerät) geht für die Suche an
  `PUT attachments/<id>/text` (nie bei Tresoren). **Suche** über das
  Volltext-Register des Servers (`GET search`).
- **Tresor-Notizbücher:** Drives Tresor-Code wiederverwendet –
  `DriveVaultCrypto` nimmt das Label-Präfix als Parameter (`gcdrive-v1` für
  Drive, `gcnotes-v1` hier), eigener Geräteschlüssel (X25519, privater Teil
  nur mit Keystore-Schlüssel hinter Fingerabdruck/PIN verschlüsselt, eigene
  Datei `notizen-vault/device.key` und eigene Keystore-Aliase), HPKE-Wraps,
  Wiederherstellungsschlüssel, „Zugang anfragen“ mit Sicherheitsnummer,
  Geräte freigeben, neuer Schlüssel bei `needsRotation`. Titel, Text und
  Anhänge werden nur im Speicher entschlüsselt (Bildschirm FLAG_SECURE),
  die Schlüssel nach 15 Minuten ohne Nutzung vergessen. Geprüft gegen
  `test/vault-vectors.json` des Plugins.
- **Quellen:** je Quelle an/aus, Familie „Aufgaben der Kinder zeigen“ und
  „Einkaufslisten in Mein Tag“, Mail „Markierte Mails als Aufgabe“, Alltag
  „Erinnern um“, Gruppen (Mail-Konten, Kinder) → Privat/Arbeit/ausblenden,
  „Jetzt abgleichen“ (`PUT prefs`, `sources/<plugin>/sync`).
- **Umziehen:** **Microsoft To Do** über das Microsoft-Konto aus Mail: beim
  ersten Mal zeigt die App Microsofts Gerätecode und den Link
  (`import/mstodo/consent`, wartet bis „done“), dann die Listen mit Zahlen,
  Bereich je Liste, erledigte der letzten Tage, Übergangszeit in beide
  Richtungen, Fortschritt des Auftrags, „Abgleich beenden“. **Synology DS
  Note:** die `.nsx`-Datei wählt man per Dateiauswahl (SAF); die App liest
  sie **auf dem Gerät** (`Nsx`, Port von `ui/nsx.js`: ZIP mit ZIP64,
  stückweise gelesen, HTML → Markdown) und lädt nur die Anhänge einzeln
  hoch. Geschützte Notizen öffnet das eingegebene Passwort
  (OpenSSL/CryptoJS-Form); sie werden je Notizbuch ein neues
  Tresor-Notizbuch, dessen Wiederherstellungsschlüssel vorher erscheint –
  sonst übersprungen und gezählt.
- **Teilen → „Als Notiz oder Aufgabe“:** Share-Ziel für Text, Links, Bilder
  und PDFs (`NotizenShareActivity`, nur aktiv, solange der Server das Plugin
  anbietet): Notiz oder Aufgabe, Bereich, Dateien als Anhänge.
- **Offline:** `me`, das Menü, die Listen, geöffnete Aufgaben und Notizen
  liegen pro Anmeldung in `noBackupFilesDir/notizen-cache` (Tresore nur als
  Ciphertext) und werden ohne Verbindung gezeigt (Hinweis oben). **Abhaken
  wartet offline** in einer Warteschlange (`NotizenOutbox`, gesendet beim
  nächsten Kontakt bzw. per WorkManager mit Netz) – das geht, weil
  `complete { done }` einen Zustand setzt. 401/404 des Plugins löschen die
  Kopie samt Warteschlange.
- **Offline anlegen und ändern (Plugin 1.1, Fähigkeit `offlineEdits` in
  `GET me`):** Aufgaben, Unteraufgaben, Listen, Notizbücher und Notizen
  lassen sich auch ohne Verbindung anlegen, ändern und löschen (Schnell-
  Eingabe, Feld einer Liste, Aufgabe, Notiz, Menü, Teilen ohne Dateien). Sie
  erscheinen sofort – in ihrer Liste, in „Mein Tag“, „Wichtig“, bei den
  Notizen – mit **„Noch nicht gesendet“**; Neues hat bis zur Antwort des
  Servers eine negative Id (auch Routen und Verweise: eine Unteraufgabe
  einer offline angelegten Aufgabe, eine Aufgabe einer offline angelegten
  Liste). Die Warteschlange geht **in der Reihenfolge** raus (WorkManager
  mit Netz, Stream-Verbindung, `GET me`); solange etwas wartet, reiht sich
  auch online alles dahinter ein. Jedes Anlegen trägt eine `clientId`
  (UUID) – kommt es doppelt an (Antwort verloren), legt der Server nichts
  zweimal an; die Antwort liefert die Server-Id, die App tauscht damit die
  negative Id in allem, was noch wartet (`NotizenOutbox.mapped`).
  Änderungen an einer offline angelegten Sache wandern in ihr Anlegen,
  mehrere Änderungen derselben Sache werden eine; Löschen von etwas offline
  Angelegtem nimmt es samt Inhalt aus der Warteschlange. Aufgaben schicken
  **`base`** (die Werte vor der Änderung, Feld für Feld), Notizen `rev`.
  Lehnt der Server mit `409` ab (dasselbe Feld anderswo geändert, bei
  Tresoren auch `stale_key`), bekommt der Eintrag **„Anderswo geändert“**
  und beim Öffnen fragt die App „Meine behalten / Die andere laden“ (Aufgabe
  wie Notiz; online sofort). Andere Ablehnungen (gelöscht `410 gone`, kein
  Recht) verwerfen die Änderung. **Tresor-Notizen** offline nur bei
  geöffnetem Tresor; in die Warteschlange kommt nur Ciphertext, „Meine
  behalten“ verschlüsselt mit dem aktuellen Schlüssel neu. Bilder und
  Anhänge, „Zeile → Aufgabe“ und Teilen mit Dateien brauchen weiter eine
  Verbindung (die App sagt das). Ohne `offlineEdits` (Plugin 1.0) bleibt es
  beim Lesen und Abhaken – 1.0 erkennt doppelt gesendetes Anlegen nicht.
- **Live:** Realtime-Event `changed` lädt offene Listen neu, `vault`
  (Anfrage/Freigabe) die Tresor-Seite.
- **Pushes öffnen die Seite** (`NotizenPush`): eine Erinnerung oder eine
  Zuweisung die Aufgabe, eine geteilte Liste und der Einkaufstag am Morgen
  (`listday`, 1.2.0) die Liste, ein geteiltes
  Notizbuch die Notizen mit diesem Notizbuch (ein Tresor: seine Seite, erst
  entsperren), „Neues Gerät möchte Zugriff“ die Tresor-Seite mit der
  Anfrage (gesperrt: erst entsperren, dann bleibt die Seite mit „Freigeben“).
  Gelesen wird `data.notizen = { kind, area, taskId | listId | notebookId |
  vaultId }` (Plugin 1.1) und die Schlüssel von 1.0 (`task`, `list`,
  `notebook`, `vault`) nach dem Thema. Immer erst der Tab (Zurück führt
  dorthin, auch nach einem Kaltstart), mit dem Bereich des Pushes; ist die
  Aufgabe oder Liste weg, zeigt der Tab „Nicht mehr da“; ohne Plugin oder
  Lizenz öffnet nur der Tab, der den Grund nennt.
- **Später:** Widgets „Mein Tag“ und eine Schnell-Kachel sind als nächster
  Schritt vorgesehen.

## Drucken (Plugin `gatecontrol-print` 1.0, GateControl 1.236+)
Drucken auf den Druckern zu Hause **über GateControl**: der Auftrag geht
über die normale Server-Verbindung der App (mit und ohne VPN) an das
Plugin, das Gateway zu Hause druckt ihn per IPP. Ist der Drucker aus,
wartet der Auftrag bis zu 24 Stunden. Der eigene WLAN-Druckdienst von
Android findet den Drucker nur per mDNS im selben Netz – unterwegs und
durch den Tunnel (kein Multicast, Heimnetz nicht geroutet) also nie.

- **Druckdienst „GateControl“** (`GateControlPrintService`, Einstellungen ›
  Verbundene Geräte › Drucken, einmal einschalten): listet die Drucker,
  die die Person nutzen darf (`GET overview`), als „GateControl – <Name>“
  mit Ort, Zustand („aus – druckt, sobald er an ist“), Seiten heute und
  „nur schwarz-weiß“. Fähigkeiten: A4 und Letter (Vorgabe nach Land),
  300 dpi, Farbe nur bei Farbdrucker und ohne „nur s/w“, beidseitig
  (lange Kante) nur bei Duplex-Druckern. Ein Drucker, der aus ist, ist
  „beschäftigt“, nie „nicht verfügbar“ – der Auftrag wartet ja.
- **Auftrag:** das PDF des Druckdialogs → `PUT …/media` → `POST print`
  (Kopien, Farbe, beidseitig, Name). Die Rechte (drucken, Seiten am Tag,
  nur s/w) prüft das Plugin; seine Fehlermeldung (z. B. „Das wären 6
  Seiten – heute gehen noch 4.“) steht am fehlgeschlagenen Auftrag.
  Zustände: `queued`/`sending`/`printing` → gestartet mit Status,
  `waiting` → blockiert „Wartet, bis der Drucker an ist“ (bzw. beschäftigt,
  nimmt nichts an, Zertifikat), `done` → fertig, `failed`/`expired` →
  fehlgeschlagen mit Grund, `canceled` → abgebrochen. Die Server-Id steht
  im Tag des Auftrags (nach einem Neustart des Dienstes wird weiter
  gefragt). Abfragen: 5 s, ab 2 Min. 20 s, ab 15 Min. 60 s, wartend alle
  2 Min. Ohne Verbindung bleibt der Auftrag bis zu 30 Min. blockiert und
  wird erneut gesendet. Abbrechen geht, solange er beim Server wartet.
- **Teilen › „Mit GateControl drucken“** (`PrintShareActivity`, PDF und
  Bilder – die `media.types` des Plugins; andere Bilder wie GIF/BMP macht
  die App zu JPEG, die Umwandlung von Bildern für den Drucker macht der
  Server): Blatt wie im Mockup mit Datei, Drucker, Kopien, Farbe,
  beidseitig, „Zu Hause drucken“, danach der Zustand je Datei (3 Min.
  lang). Nur an, solange der Server das Plugin für die App listet.
- **Hinweis:** Einstellungen › Drucken › „Drucken über GateControl“ (auch
  als Einstellungen des Druckdienstes und über die Info-Taste am Drucker im
  Druckdialog) zeigt die Drucker oder warum es keine gibt (Plugin nicht für
  die App verfügbar, kein Recht, keine Verbindung) und erklärt, dass der
  Drucker im Druckdialog „GateControl – …“ heißt.
- **Ohne Plugin, ohne Recht:** der Druckdienst zeigt keine Drucker, das
  Teilen-Ziel und die Einstellungszeile sind aus.

## Drive: „Auf dem Gerät“ (die Dateien des Handys)
Ein Festplatten-Symbol rechts neben „Übersicht · Dateien · Fotos“ (eigener
48-dp-Knopf, kein Reiter) öffnet die Seite **„Auf dem Gerät“**: die Dateien
des Handys selbst, wie in einem Dateimanager (Ersatz für „Files by Google“).

- **Zugriff:** Android-Berechtigung „Zugriff auf alle Dateien“
  (`MANAGE_EXTERNAL_STORAGE`, Einstellung
  `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`). Ohne sie erklärt die
  Seite, wofür sie gebraucht wird (bleibt auf dem Handy, jederzeit
  widerrufbar, ohne Zugriff läuft Drive wie bisher) – „Zugriff erlauben“
  öffnet Androids Einstellung, „Nicht jetzt“ geht zurück zu Drive. Bei
  jedem Aufruf wird neu geprüft. Die Berechtigung steht in
  `app/src/allfiles/AndroidManifest.xml` und kommt nur in die GitHub-Version;
  die Google-Play-Version bekommt sie erst mit `-Pgc.playAllFiles=true`
  (Repo-Variable `PLAY_ALL_FILES`, siehe GOOGLE_PLAY.md) und sagt bis dahin
  auf der Seite, dass es dort noch nicht geht (`BuildConfig.ALL_FILES_ACCESS`).
- **Start:** Interner Speicher mit Balken (Bilder & Videos, Dokumente,
  Audio, Apps & Sonstiges = der Rest des belegten Platzes; Größe und frei
  über `StorageStatsManager`); **Kategorien** Download, Bilder, Videos,
  Dokumente, Audio, APKs & ZIP mit Anzahl und Größe (ein Durchgang über
  MediaStores Dateitabelle auf allen Speichern, Art nach Endung, sonst nach
  MIME-Typ; versteckte Ordner und `Android/data|obb|media` zählen nicht;
  Download wird als Ordner durchgezählt), im Hintergrund berechnet und eine
  Minute gemerkt; **Speicherorte** intern, SD-Karte, USB-Stick
  (`StorageManager`, aktualisiert sich beim Anstecken/Abziehen über
  `StorageVolumeCallback`; ohne Stick der Hinweis „Erscheint, wenn einer
  angeschlossen ist“); **Zuletzt hinzugefügt** (die drei neuesten, „Alle“:
  die letzten 30 Tage).
- **„In Drive“ / „Nur hier“:** Die App fragt Drives Suche nach dem
  Dateinamen (`GET search?q=<Name>`, höchstens drei Anfragen gleichzeitig,
  5 Minuten gemerkt, nach einem Upload oder einer Änderung in Drive neu).
  „In Drive“, wenn Drive eine Datei mit demselben Namen (Groß-/Kleinschreibung
  egal) und genau derselben Größe hat – in einem Bereich, den die Person
  sieht, nicht im Papierkorb, nicht im Tresor. „Nur hier“, wenn Drive
  vollständig geantwortet hat (weniger als 100 Treffer) und keine solche
  Datei hat. Sonst (offline, Fehler, 100 Treffer, Name kürzer als 2 oder
  länger als 100 Zeichen) steht nichts da. Eine Kopie unter anderem Namen
  zählt als „Nur hier“; eine nur im Tresor liegende Datei ebenso (Tresor-Namen
  sind verschlüsselt).
- **Ordner, Kategorie:** Pfad zum Antippen, Sortierung (Neueste zuerst,
  Name, Größe; Ordner immer oben), Art-Chips (nur die vorhandenen),
  langes Drücken wählt aus. Leiste: **In Drive** (Ordnerwahl wie „In Drive
  speichern“, dann die Upload-Warteschlange des Drive-Tabs über einen
  `content://…devicefiles`-URI; Ordner nicht – dafür „Ordner sichern“),
  **Teilen**, **Verschieben** (eigene Ordnerwahl auf dem Handy; anderer
  Speicher: kopieren und löschen), **Löschen** (Bilder, Videos, Audio und
  Downloads, die MediaStore kennt, in Androids Papierkorb – Android fragt
  selbst, 30 Tage wiederherstellbar; alles andere und Ordner endgültig, erst
  nach einer Rückfrage der App), **Mehr**: Kopieren, Umbenennen, ZIP
  entpacken, Details. Jede Änderung geht an den MediaScanner.
- **ZIP entpacken:** in einen neuen Ordner daneben (Name ohne `.zip`, sonst
  „(2)“). Einträge mit `..`, absolutem Pfad oder Laufwerksbuchstaben werden
  übersprungen (und zusätzlich per kanonischem Pfad geprüft); höchstens
  20 000 Einträge, höchstens 8 GB, nie mehr als das 200-Fache der ZIP-Datei
  und nie mehr als der freie Platz minus 200 MB – gezählt wird beim
  Schreiben, nicht nach den Angaben im ZIP; bei Überschreitung wird der
  Ordner wieder gelöscht.
- **Öffnen:** Bilder (gedreht nach EXIF, HEIC), Videos (Player), PDFs, Texte
  und Markdown in Drives eigenen Ansichten; alles andere „Mit anderer App
  öffnen“ über den `DeviceFileProvider` (nur `/storage`, nie App-Daten,
  Freigabe nur für den einen Intent). Antippen einer ZIP fragt nach dem
  Entpacken.
- **„Nur auf diesem Gerät löschen“ in Drive** löscht auch eine Datei, die
  über „In Drive“ hochging (wie eine ausgewählte Datei), ebenso „auf allen
  Geräten löschen“ von einem anderen Gerät.

## Drive in der Dateiauswahl von Android
„GateControl Drive“ erscheint in der Dateien-App und in jedem „Datei
öffnen“/„Speichern unter“-Dialog (`DriveDocumentsProvider`, seit #127): Lesen
lädt die Datei (aktuelle Version, ohne Verbindung die gemerkte), Schreiben
lädt beim Schließen hoch (neue Version bzw. neue Datei). Nur Androids
Dokumente-UI darf den Provider aufrufen (`MANAGE_DOCUMENTS`). Die Wurzel gibt
es nur mit eingerichtetem Server und Drive-Plugin; Tresor-Ordner und
-Dateien erscheinen nie, und nichts lässt sich in einen Tresor anlegen,
verschieben, umbenennen oder löschen (es käme unverschlüsselt an).

## Drive: „Erinnerungen“ (Drive-Plugin 1.26, Feature `memoryCollections`)
In der Übersicht öffnet „Alle“ bei „Erinnerungen“ die Seite **„Erinnerungen“**
(`DriveMemoriesScreen`, Route `drive/photos/memories`). Ohne das Feature, also bei
einem älteren Plugin, öffnet „Alle“ wie bisher den Reiter „Fotos“.

- **Inhalt** (`GET memories`): Karten mit Titelbild, nach Abschnitten sortiert:
  - **Heute in früheren Jahren**: „Heute vor X Jahren“, jedes Jahr mit mehr als 10 Fotos an diesem Tag (± 1 Tag)
  - **Ausflüge und Reisen**: Tage am Stück an einem Ort, der mindestens 40 km von zu Hause entfernt ist, ab 10 Fotos, z. B. „Hamburg, Lübeck · 5 Tage · Juli 2025 · 87 Fotos“
  - **Mit deinen Leuten**: „Mit Noah“, benannte Personen ab 10 Fotos
  - **Diesen Monat in früheren Jahren**: ab 20 Fotos
  - **Jahresrückblick**: „2025 im Rückblick“, vergangene Jahre ab 50 Fotos
  - **Orte**: z. B. „Langenlonsheim · 12 Fotos“, ab 10 Fotos

  Die Mindestzahlen legt der Server fest (Plugin `server/memories.js`).
- **Öffnen:** Eine Karte zeigt ihre Fotos im Fotoraster (`PhotoGrid`,
  `GET memories/photos?id=`, seitenweise). Ein Tipp öffnet den Foto-Betrachter
  (`PhotoViewer`), Zurück führt zur Liste. Eine Karte der Übersicht öffnet
  den Betrachter mit allen Fotos dieser Sammlung.
- **Übersicht ohne „Heute vor X Jahren“** (Drive-Plugin 1.28, Feature
  `memoryPicks`): Hat der Tag keine solche Karte, zeigt „Erinnerungen“ in der
  Übersicht bis zu vier Sammlungen dieser Seite (`GET overview` `memoryPicks`)
  in derselben Kartenreihe: Titelbild, Titel wie auf der Seite („Hamburg“,
  „Mit Noah“, „2024 im Rückblick“) und Fotoanzahl. Ein Tipp öffnet die Sammlung
  auf der Seite „Erinnerungen“ wie ein Tipp dort (Route
  `drive/photos/memories?open=<id>`), Zurück führt zur Liste. „Alle“ öffnet die
  Seite. Ohne Karten und ohne Auswahl bleibt der Abschnitt weg; ein älteres
  Plugin ohne das Feld ändert nichts.
- **Nur private Fotos:** Weggelassen werden der Arbeitsordner, Fotos unter
  „Prüfen“ und Team-Ordner. Bei einem älteren Plugin ohne `overview` baut die
  App die Übersichtskarten selbst; auch dann zählen nur private Fotos
  (`DriveMemoriesLogic.privateDays`) und nur Tage mit mehr als 10 Fotos.
- **Arbeitsfotos:** Mit dem Feature heißt der Schalter in den
  Arbeitsfoto-Einstellungen „Arbeitsfotos nie im Fotorahmen“. Er gilt nur noch
  für den Fotorahmen, denn Erinnerungen sind ohnehin privat.

## Drive: „Offline verfügbar“ (Drive-Plugin 1.20, Feature `offline`)
Im Menü ⋮ einer Datei oder eines Ordners (Ordner, Suche): „Offline
verfügbar“. Die App behält dann eine **echte Kopie auf dem Handy**
(`DriveOfflineStore`), die auch ohne Verbindung öffnet, angezeigt und
geteilt werden kann; bei einem Ordner alle Dateien darunter (höchstens
2000 Dateien, 500 Unterordner). Drive merkt die Markierung zusätzlich für
den Zwischenspeicher der Heim-Gateways vor.

- **Speicherort:** `filesDir/drive-offline/<Datei-Id>/<Name>` (App-intern,
  nie im Backup, vom FileProvider nie selbst freigegeben – zum Öffnen oder
  Teilen wird die eine Datei als Hardlink, notfalls als Kopie, nach
  `cache/drive-open` gelegt); Liste in `filesDir/drive-offline-index.json`
  mit dem Server – ein anderer Server, Drive ohne Feature `offline` oder
  401/403/404 auf `GET offline` löschen alle Kopien.
- **Abgleich** (WorkManager `drive-offline-now` nach jeder Änderung und
  beim Öffnen der Liste, `drive-offline-periodic` alle 6 Stunden, solange
  Kopien da sind; „Speicher nicht knapp“): `GET offline`, die Ordner
  darunter (`children`), dann der Plan – neue Dateien laden, geänderte
  (andere Revision oder SHA-256) laden neu (die alte Kopie öffnet bis
  dahin), nicht mehr markierte, vom Virenscan blockierte oder nicht mehr
  in den Speicher passende Kopien gehen. Geladen wird wie beim Öffnen
  (`GET …/content`): zu Hause aus dem Zwischenspeicher des Heim-Gateways
  (Zertifikat gepinnt, SHA-256 geprüft), sonst über den Server; die Bytes
  werden gegen Drives SHA-256 geprüft. Nur vollständige Antworten löschen
  etwas – ohne Verbindung bleibt alles, wie es ist.
- **Einstellungen** (Drive-Einstellungen › Daten auf diesem Gerät):
  „Speicher für Offline-Dateien“ (500 MB … 20 GB, Standard 2 GB, gefüllt
  in der Reihenfolge der Markierungen, neueste zuerst) und „Offline-Dateien
  nur im WLAN laden“ (Standard an). Auf dem Handy bleiben immer 500 MB frei.
  Der „Zwischenspeicher“ (Vorschauen, geöffnete Dateien) zählt die Kopien
  nicht und „Leeren“ löscht sie nicht.
- **Zustände** (Seite „Offline verfügbar“): „Auf dem Handy“, „Wird
  geladen“, „Wartet auf WLAN“, „Passt nicht in den Speicher für
  Offline-Dateien“, „Zu wenig Platz auf dem Handy“, „Neuere Version wird
  geladen“, „Konnte nicht geladen werden“; ein Ordner „12 von 14 Dateien
  auf dem Handy“. Oben belegt/höchstens und „Jetzt aktualisieren“. Ohne
  Verbindung zeigt die Seite die Kopien auf dem Handy zum Öffnen und
  Teilen. Öffnen aus Ordnern, der Suche oder den Fotos nimmt die Kopie,
  wenn sie die aktuelle Version ist; sieht die App eine neuere Version,
  wird die Kopie sofort erneuert.
- **Entfernen:** „Nicht mehr offline verfügbar“ (Menü oder ✕ in der
  Liste) löscht die Kopie gleich; „auf allen Geräten gelöscht“ ebenso.
- **Tresor:** siehe „Tresor-Dateien offline“ – auf dem Handy liegt nur der
  verschlüsselte Inhalt. Auf einem Gerät, das für den Tresor nicht
  freigegeben ist, steht eine markierte Tresor-Datei weiter mit „Tresor:
  nur im Zwischenspeicher zu Hause“ in der Liste; ohne Verbindung zeigt die
  Seite „Offline verfügbar“ nur Dateien außerhalb von Tresoren (die
  Tresor-Dateien öffnen auf den Tresor-Seiten).

### Tresor-Dateien offline

Auf den Tresor-Seiten (Möglichkeit A der Entwürfe): oben **„Ganzen Tresor
offline halten“** (im Unterordner „Diesen Ordner offline halten“) mit
Schalter und „7 von 19 Dateien offline · 48 MB verschlüsselt auf dem Handy“;
im Menü ⋮ jeder Datei und jedes Ordners der Schalter **„Offline verfügbar“**
(„Liegt verschlüsselt auf diesem Handy. Öffnen geht auch ohne Netz.“). Eine
Datei unter einem markierten Ordner oder im ganz markierten Tresor zeigt den
Schalter an und fest („Kommt vom Ordner darüber – dort ausschalten.“ / „Der
ganze Tresor ist offline.“). Zeilen tragen „Offline“ oder „Wird geladen“,
Ordner „n offline“; wartende oder fehlgeschlagene Kopien sagen es wie auf
der Seite „Offline verfügbar“. Es sind dieselben Markierungen von Drive
(`PUT|DELETE nodes/<id>/offline` auf den Tresor-Knoten, der ganze Tresor =
sein Ordner) – kein neuer Endpunkt, keine Änderung am Plugin.

- **Nur Ciphertext auf der Platte:** die Kopie ist der Inhalt genau so, wie
  der Server ihn hat (Kopf und Chunks, `GET …/content`, gegen Drives
  SHA-256 geprüft), in `filesDir/drive-offline/<Id>/<undurchsichtiger
  Name>`. Kein Klartext, kein Klarname: im Index stehen der undurchsichtige
  Name, `nameCipher` und die Ordner-Ids. Nie entschlüsselt abgelegt, nie an
  andere Apps gegeben (`stored`/`current`/FileProvider lassen sie aus).
- **Öffnen:** wie bisher im Betrachter der App, nur im Arbeitsspeicher
  (`DriveVaultSession.decryptInto` liest dann die Datei auf dem Handy statt
  zu laden). Videos laufen Chunk für Chunk aus der Kopie
  (`DriveVaultVideoSource` über einen Hardlink im Betrachter-Ordner – eine
  neue Version, die der Abgleich hinlegt, ändert das laufende Video nicht).
  Ist die Kopie veraltet, lädt der Betrachter mit Netz wie bisher und stößt
  den Abgleich an; ohne Netz öffnet die ältere Kopie.
- **Abgleich** (derselbe WorkManager-Lauf wie oben, ohne entsperrten
  Tresor): für jeden Tresor unter den Markierungen `GET vaults/<id>` und
  `GET vaults/<id>/keys?deviceId=` – nur ein freigegebenes Gerät behält
  Ciphertext; dann die Ordner darunter (Tresor-Inhalt nur unter Markierungen
  desselben Tresors, nie über einen normalen Ordner). Neue Versionen
  ersetzen die Kopie (andere Revision oder SHA-256), gelöschte oder aus dem
  markierten Ordner verschobene Dateien gehen. Die Tresor-Dateien zählen
  zum „Speicher für Offline-Dateien“ und folgen „nur im WLAN“.
- **Ohne Netz entsperren:** solange ein Tresor Offline-Dateien hat, merkt
  sich das Handy in `noBackupFilesDir/drive-vault/offline/<Tresor-Id>.json`
  den Tresor (Id, Ordner, `nameCipher`, Schlüsselversion, meine Geräte),
  die **verpackten** Schlüssel dieses Geräts für jede Schlüsselversion
  (HPKE an den Geräteschlüssel, den nur der Android-Keystore öffnet – wie
  bisher nach Fingerabdruck, Gesicht oder PIN) und die zuletzt gesehenen
  Ordnerlisten (undurchsichtige und verschlüsselte Namen, Größen; höchstens
  300). Nie ein entpackter Tresor-Schlüssel. Aufgefrischt bei jedem Lauf,
  beim Öffnen des Tresors mit Netz und nach „Tresor-Schlüssel wechseln“ –
  so öffnen auch Dateien einer neuen Schlüsselversion offline.
- **Ohne Netz:** Banner „Kein Netz – n Dateien sind offline da. Die anderen
  öffnen sich, sobald du wieder verbunden bist.“ (mit „Erneut versuchen“),
  „Offline da“ (die Dateien auf dem Handy unter diesem Ordner, mit
  Ordnername) und ausgegraut „Nur mit Netz“; nichts lässt sich ändern
  (kein Hochladen, Umbenennen, Verschieben, keine Anfragen), nur ansehen und
  – nach Rückfrage wie bisher – „Mit anderer App öffnen“.
- **Löschen:** sagt der Server beim nächsten Kontakt, dass dieses Gerät
  nicht mehr freigegeben ist (`keys` mit anderem Zustand als `approved`,
  404 für Gerät oder Tresor, 403), gehen der Ciphertext dieses Tresors und
  sein Eintrag sofort – im Abgleich und beim Öffnen des Tresors. Ebenso mit
  der letzten Offline-Datei eines Tresors, beim Abmelden oder Entfernen des
  Kontos (alles), bei einem anderen Server (alles) und bei einer anderen
  Anmeldung am selben Server (alle Tresore; `DriveOfflineStore.watchAccount`).
- **Sicherheit (Android, ergänzt die Bedrohungstabelle „Viewing vault
  files“ der Drive-README):**

  | Bedrohung | Regel | Android |
  |---|---|---|
  | Klartext auf der Platte | nur im Speicher entschlüsseln | Offline-Kopien sind Ciphertext wie auf dem Server; der Betrachter entschlüsselt in Byte-Arrays, ein `memfd` (PDF) oder Chunk für Chunk (Video) |
  | Schlüssel auf der Platte | nur verpackt | gespeichert werden nur die Wraps dieses Geräts; der Geräteschlüssel bleibt im Keystore (hinter der Bestätigung); der Tresor-Schlüssel nur im Speicher, gelöscht beim Sperren |
  | Gerät gestohlen, gesperrt | ohne Entsperren nichts lesbar | Ciphertext und Wraps ohne Keystore-Schlüssel nutzlos; App-Daten nie im Backup |
  | Gerät entzogen | Kopien löschen | beim nächsten Kontakt (Abgleich oder Öffnen): Ciphertext und Wraps des Tresors weg; eine Rotation schützt neue Dateien wie bisher |
  | Metadaten | wie auf dem Server | Größen, Zeiten, Ordnerstruktur und Anzahl liegen auf dem Handy so, wie der Server sie kennt – keine Namen, keine Inhalte |
  | Betrachter | unverändert | `FLAG_SECURE` und keine Vorschau in „Zuletzt verwendet“, gesicherte Video-Fläche, das Sperren schließt den Betrachter, keine Klartextdateien |

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
- **Eigener APK-Download** (nur die Version aus GitHub, `gc.distribution=github`;
  die Google-Play-Version aktualisiert sich nur über Play): „Installieren“
  fragt die Update-Prüfung noch einmal mit den LAN-Adressen des Handys
  (`X-GC-Local`, nur wenn der Server sagt „zu Hause“). Liefert sie `lan`
  (das APK liegt im Zwischenspeicher des Heim-Gateways, GateControl 1.231+),
  lädt die App es dort – Zertifikat gepinnt, SHA-256 geprüft, an das WLAN
  gebunden; bei jedem Fehler nimmt sie still den normalen `downloadUrl`
  (GitHub ohne Token der App, der Proxy des eigenen Servers mit Token, nur
  https, keine Umleitung auf http).
- **Pflichtprüfung vor der Installation:** SHA-256 gegen `sha256` der
  Update-Prüfung (GateControl 1.253+, sonst entfällt nur dieser Teil), dann
  immer Paketname, höhere versionCode und das Signaturzertifikat der
  installierten App (bei Schlüsselwechsel die Signaturlinie). Ein APK, das
  durchfällt, wird gelöscht und nie angeboten. Danach öffnet Android seinen
  Installer (`cache/update/update.apk` über den FileProvider); fehlt
  „Unbekannte Apps installieren“, öffnet die App zuerst diese Seite.
- Die Berechtigung `REQUEST_INSTALL_PACKAGES` steht nur im Manifest der
  GitHub-Version (`app/src/github/AndroidManifest.xml`, per
  `androidComponents` nur bei `gc.distribution=github`), nie im Play-AAB.

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
