# GateControl — Datenschutzerklärung / Privacy Policy

*Stand / Last updated: 1. November 2026 / November 1, 2026*

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
| Profilbild und Lieblingsfarbe (nur wenn du sie setzt) | Dein Profilbild (aus der Fotoauswahl, auf 256 × 256 px verkleinert) und deine Lieblingsfarbe werden auf deinem Server gespeichert und sind für die Benutzer dieses Servers sichtbar (Apps, Portal, Wand-Tablet, Verwaltung). „Entfernen“ löscht das Bild auf dem Server. Die App behält Profilbilder von deinem Server nur im Zwischenspeicher. |

### Standort

Für **vertrauenswürdige WLANs** liest die App den Namen (SSID) des verbundenen WLANs. Android gibt den WLAN-Namen nur mit Standort-Berechtigung heraus — auch im Hintergrund, damit das VPN in einem vertrauenswürdigen WLAN automatisch getrennt und außerhalb wieder verbunden werden kann, wenn die App geschlossen ist. Die App erhebt dafür Standortdaten, **verwendet aber keine Koordinaten**, speichert keinen Standort und sendet weder Standort noch WLAN-Namen an irgendjemanden. Für „Heimnetz des Servers erkennen“ schickt die App die lokale Netzadresse des Handys im WLAN bzw. LAN (z. B. 192.168.1.23) an **deinen eigenen Server**, damit er prüfen kann, ob du in seinem Netz oder im Netz hinter einem seiner Gateways bist. Die Funktion ist optional und standardmäßig aus.

Die **Standortfreigabe** (Einstellungen › Standortfreigabe, standardmäßig aus) ist davon getrennt: Nur wenn du auf das Satelliten-Symbol auf dem Startbildschirm tippst, ermittelt die App einmal deinen genauen Standort (Koordinaten, Genauigkeit, Höhe, Zeitpunkt) und sendet ihn an **deinen eigenen GateControl-Server**. Dort sehen ihn du und die Administratoren auf einer Karte. Es gibt keine Ortung im Hintergrund und keine automatische Übertragung. Der Server behält die letzten 20 Standorte je Gerät höchstens 30 Tage; du kannst sie in der App jederzeit löschen. Die Karte im Server lädt Kartenbilder von OpenStreetMap — dabei erfährt OpenStreetMap den angezeigten Kartenausschnitt, aber nicht dein Gerät. Nichts geht an CallMeTechie, Google oder andere Dritte.

### Familie (Plugin „Familie“ deines Servers)

Den Tab **Familie** gibt es nur, wenn dein GateControl-Server das Plugin „Familie“ anbietet und du Mitglied der Familie bist. Einkaufslisten, Aufgaben, Termine, Orts-Erinnerungen, Fahrten, die Notfall-Karte, Taschengeld, Wunschlisten, Haushaltsbuch, Pinnwand und Essensplan werden auf **deinem eigenen Server** gespeichert; die Einkaufslisten und die Notfall-Karte (verschlüsselt) zusätzlich auf dem Gerät, damit sie offline funktionieren.

- **Kinder orten:** Der Standort eines Kindes wird nur ermittelt, wenn ein Elternteil der Familie danach fragt — einmalig, keine dauerhafte Verfolgung. Je nach Einstellung schickt das Handy des Kindes seinen genauen Standort (Koordinaten, Genauigkeit, Zeitpunkt, Akkustand) automatisch zurück — dafür auch im Hintergrund, wenn die App geschlossen ist — oder erst, wenn das Kind bestätigt. Das Kind wird **immer** benachrichtigt und sieht in der App, wer es wann geortet hat. Ab 18 Jahren endet die Ortung automatisch.
- **„Bin angekommen“ und SOS:** Nur wenn das Kind (oder ein Familienmitglied) tippt, geht die Meldung — beim SOS mit dem Standort, falls verfügbar — an die Eltern.
- **Orts-Erinnerungen:** Das Handy prüft die gewählten Orte selbst über das Geofencing von Android (Google-Play-Dienste), auch im Hintergrund. Dein Standort wird dafür **nicht gesendet** — nur, dass eine Erinnerung ausgelöst wurde (ohne Koordinaten), und nur, wenn die Erinnerung jemandem Bescheid gibt. Erinnerungen für andere Familienmitglieder laufen erst nach deren Zustimmung.
- **Abholen & Fahrten:** Wer wen wann wo abholt (Ortsname und, falls gewählt, Koordinaten) wird auf deinem Server gespeichert; „Wer kann?“ benachrichtigt die Eltern. Die Koordinaten einer Fahrt dienen nur einer freiwilligen Orts-Erinnerung auf dem Handy des Fahrers.
- **Notfall-Karte:** Gesundheitsdaten, die die Familie einträgt (Allergien, Medikamente, Vorerkrankungen, Blutgruppe, Gewicht, Krankenkasse, Notfallnummern), liegen auf **deinem eigenen Server** und sind für die Mitglieder der Familie lesbar. Damit die Karte auch ohne Verbindung funktioniert, speichert die App die zuletzt geladene Karte auf dem Handy — **verschlüsselt** (AES-256-GCM mit einem Schlüssel im Android Keystore), ausgenommen von Backups und gelöscht, wenn du die Familie verlässt oder die Funktion ausgeschaltet wird. Sie erscheint nie in Mitteilungen und nie in Protokollen. Die Anruf-Knöpfe öffnen nur die Telefon-App.
- **Taschengeld, Wunschlisten, Haushaltsbuch:** Beträge, die die Familie bucht, sind reine Buchhaltung — die App bezahlt, belastet oder überweist nie Geld und fragt keine Bank- oder Kartendaten ab. Wer einen Wunsch besorgt, sieht die Person, die ihn sich wünscht, nie.
- **Anwesenheit („Wer ist zuhause“):** Nur wenn du selbst „Anwesenheit teilen“ einschaltest (standardmäßig aus, jederzeit abschaltbar). Dann meldet die App **deinem eigenen Server** nur „zuhause“ oder „unterwegs“ — erkannt daran, ob dein Handy im Heimnetz ist (im Netz des Servers oder im Netz hinter einem seiner Gateways); dafür fragt die App deinen Server und schickt ihm die lokale Netzadresse deines Handys im WLAN bzw. LAN (z. B. 192.168.1.23) — **kein GPS**, keine Koordinaten, kein WLAN-Name. Gemeldet wird eine Änderung, wenn sie zwei Minuten anhält, auch im Hintergrund (bei Netzwechseln und etwa alle 15 Minuten). Die Familie sieht das in der App, am Wand-Tablet und im Portal; Eltern können Smart-Home-Regeln darauf reagieren lassen. Ohne deine Zustimmung erscheinst du als „nicht freigegeben“ und es wird nichts gesendet.
- **Speicherdauer:** Koordinaten löscht der Server 7 Tage nach der Antwort; wer wann gefragt hat (ohne Koordinaten), bleibt 90 Tage als Protokoll sichtbar.
- **Karten:** Die Karte in der App (osmdroid) lädt Kartenbilder von OpenStreetMap (tile.openstreetmap.org). Dabei erfährt OpenStreetMap den angezeigten Kartenausschnitt und deine IP-Adresse, aber nicht, wessen Standort gezeigt wird. Adressen ermittelt der Geocoder deines Android-Geräts, falls vorhanden.

Nichts davon geht an CallMeTechie oder andere Dritte; Daten auf deinem Server verwaltet dessen Betreiber.

### Tür & Kamera (Plugin „Tür & Kamera“ deines Servers)

Die Tür-Funktionen (Schnellaktionen, Mitteilungen und der Bildschirm „Tür & Kamera“) gibt es nur, wenn dein GateControl-Server das Plugin „Tür & Kamera“ anbietet und dir der Betreiber ein Recht auf mindestens ein Schloss gegeben hat.

- **Tür auf- und zusperren:** Ein Tipp schickt den Befehl an **deinen eigenen Server**, der das Schloss (Nuki) steuert. Vor dem Aufsperren und Öffnen fragt die App die Displaysperre des Handys ab (Fingerabdruck, Gesicht oder PIN/Muster/Passwort über Android); das Ergebnis bleibt auf dem Gerät, nur der Befehl geht an den Server.
- **Verlauf und Klingelbilder:** Wer wann auf- oder zugesperrt hat (und über welchen Weg), Klingeln, Bewegungen und Hinweise speichert **dein eigener Server** (Verlauf höchstens 90 Tage, Bilder je nach Einstellung 1–30 Tage). Die App lädt sie nur zum Anzeigen; Bilder und Verlauf werden **nicht auf dem Gerät gespeichert** (nur im Arbeitsspeicher, solange du sie ansiehst). Offline zeigt die App nur den zuletzt bekannten Zustand der Schlösser (ohne Bilder).
- **Bilder in Mitteilungen:** Mitteilungen deines Servers können ein Bild enthalten (z. B. wer an der Haustür klingelt). Die App lädt es nur von deinem eigenen Server und zeigt es in der Mitteilung und in „Mitteilungen“; es bleibt nur im Arbeitsspeicher. Je nach Android-Einstellung ist die Mitteilung samt Bild auch auf dem Sperrbildschirm sichtbar. Der Server löscht das Bild, wenn die Mitteilung abläuft (bei der Klingel nach einer Stunde).
- **„Tür öffnen“ aus der Mitteilung:** Der Knopf verlangt das Entsperren des Geräts, bevor der Befehl an deinen Server geht.
- **Ring:** Die Verbindung zu Ring (Klingeln, Kamerabilder) stellt dein Server her, nicht die App — über eine inoffizielle Schnittstelle, ohne Gewähr.

Nichts davon geht an CallMeTechie oder andere Dritte.

### Drive: Orte und Personen in den Fotos (Plugin „Drive“ deines Servers)

Nur, wenn dein Server Drive 1.16 oder neuer anbietet.

- **Orte:** Mit „Aufnahmeort mitsichern“ (Drive › Einstellungen › Orte und Personen, standardmäßig an, solange du die Foto-Sicherung nutzt) schickt die Foto-Sicherung die Koordinaten aus den EXIF-Daten des Fotos (Breiten- und Längengrad) an **deinen eigenen Server**. Dafür liest die App die Ortsangaben der Originale (Android-Berechtigung „Zugriff auf Fotostandorte“). „Orte nachtragen“ macht dasselbe für schon gesicherte Fotos, die noch auf dem Handy liegen. Den Ortsnamen ermittelt dein Server selbst, ohne externen Dienst. Ausgeschaltet sendet die App keine Orte mehr; „Alle Orte löschen“ entfernt sie auf dem Server. Die Karte lädt Kartenbilder von OpenStreetMap — dabei erfährt OpenStreetMap den angezeigten Kartenausschnitt, aber keine Fotos.
- **Personen (Gesichtsmerkmale):** Nur wenn du „Personen erkennen“ einschaltest (standardmäßig aus). Dann findet dein Handy Gesichter in deinen Fotos — **auf dem Gerät**, mit den Modellen YuNet und SFace, im WLAN und standardmäßig nur beim Laden — und schickt je Gesicht nur eine Zahlenreihe (Gesichtsmerkmal), den Bildausschnitt als Position und die Erkennungssicherheit an **deinen eigenen Server**. Fotos verlassen dafür weder das Handy noch deinen Server. Das dafür nötige Erkennungsmodell (SFace, 39 MB) lädt das Handy beim Einschalten einmalig herunter – von der Release-Seite der App auf GitHub, ersatzweise von OpenCV Zoo auf GitHub oder von Hugging Face, standardmäßig nur im WLAN. Dabei erfahren diese Anbieter nur die IP-Adresse und die üblichen Verbindungsdaten des Handys, keine Fotos und keine Daten deines Servers; Ausschalten löscht das Modell wieder. Für Fotos, die nicht mehr auf dem Handy liegen, lädt die App die Vorschau von deinem Server. Dein Server gruppiert die Merkmale zu Personen; nur du siehst deine Personen und Namen. Ausschalten löscht alle Gesichtsdaten (Merkmale, Personen, Namen) auf dem Server. Die Ordner von Kindern sind ausgenommen, solange du „Kinderordner einbeziehen“ nicht einschaltest.

Nichts davon geht an CallMeTechie, Google oder andere Dritte.

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
| Standort (auch im Hintergrund) | optional: WLAN-Namen für vertrauenswürdige WLANs; Standort auf Tipp an den eigenen Server; Familie: Standort auf Anfrage der Eltern, „Bin angekommen“/SOS, Orts-Erinnerungen (siehe oben) |
| Kamera | Einrichtungs-QR-Code |
| Anrufe tätigen (Telefon) | optional: Ein Tipp auf einen Kontakt-Favoriten auf Start ruft dessen Hauptnummer direkt an. Gefragt erst beim ersten Tipp; ohne die Berechtigung öffnet sich die Telefon-App mit der Nummer. Die App liest keine Anrufliste und keine Telefondaten und sendet nichts. Änderbar in den Android-Einstellungen: Apps › GateControl › Berechtigungen › Telefon |
| Biometrie / Displaysperre | Tür & Kamera: Bestätigung vor dem Aufsperren und Öffnen einer Tür (über Android, nichts wird gespeichert oder gesendet) |

### Speicherung und Löschung

Daten auf dem Gerät werden gelöscht, wenn du die App deinstallierst oder ihre Daten löschst. Daten auf deinem Server verwaltet dessen Betreiber. Wie du Daten löschst oder die Löschung beantragst: [Daten löschen](DATA_DELETION.md).

### Kinder

Die App richtet sich an Betreiber und Nutzer eines GateControl-Servers. Kinder nutzen sie nur über die Familie ihres eigenen Servers, eingerichtet von den Eltern bzw. dem Betreiber (siehe „Familie“); die App erhebt dafür nichts für CallMeTechie oder Dritte.

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
| Profile picture and favourite colour (only when you set them) | Your profile picture (from the photo picker, downscaled to 256 × 256 px) and your favourite colour are stored on your server and visible to the users of that server (apps, portal, wall tablet, admin). "Remove" deletes the picture on the server. The app keeps profile pictures from your server only in its cache. |

### Location

For **trusted Wi-Fi networks** the app reads the name (SSID) of the connected Wi-Fi. Android only reveals the Wi-Fi name with the location permission — also in the background, so the VPN can disconnect automatically in a trusted Wi-Fi and connect again outside it while the app is closed. The app collects location data for this but **does not use coordinates**, does not store any location and never sends location or Wi-Fi names to anyone. For "detect the server's home network" the app sends the phone's local Wi-Fi / LAN address (e.g. 192.168.1.23) to **your own server**, so it can check whether you are in its network or in the network behind one of its gateways. The feature is optional and off by default.

**Location sharing** (Settings › Location sharing, off by default) is separate: only when you tap the satellite button on the start screen does the app get your precise location once (coordinates, accuracy, altitude, time) and send it to **your own GateControl server**. You and the administrators see it there on a map. There is no background location and no automatic sending. The server keeps the last 20 locations per device for at most 30 days; you can delete them in the app at any time. The map on the server loads map images from OpenStreetMap — OpenStreetMap learns the map area shown, not your device. Nothing goes to CallMeTechie, Google or other third parties.

### Family (your server's "Familie" plugin)

The **Family** tab only exists when your GateControl server offers the "Familie" plugin and you are a member of the family. Shopping lists, tasks, dates, place reminders, rides, the emergency card, pocket money, wish lists, the household book, the pinboard and the meal plan are stored on **your own server**; the shopping lists and the emergency card (encrypted) also on the device so they work offline.

- **Locating children:** a child's location is only determined when a parent of the family asks for it — once, no continuous tracking. Depending on the setting the child's phone sends its precise location (coordinates, accuracy, time, battery level) back automatically — for this also in the background while the app is closed — or only after the child confirms. The child is **always** notified and sees in the app who located it and when. Locating ends automatically at 18.
- **"I've arrived" and SOS:** only when the child (or a family member) taps, the message — for an SOS with the location if available — goes to the parents.
- **Place reminders:** the phone checks the chosen places itself through Android's geofencing (Google Play services), also in the background. Your location is **not sent** for this — only that a reminder fired (without coordinates), and only when the reminder tells someone. Reminders for other family members only run after they agree.
- **Pick-ups & rides:** who picks up whom, when and where (a place name and, if chosen, its coordinates) is stored on your server; "who can?" notifies the parents. A ride's coordinates are only used for an optional place reminder on the driver's phone.
- **Emergency card:** health data the family enters (allergies, medication, conditions, blood type, weight, health insurance, emergency numbers) is stored on **your own server** and readable by the members of the family. So the card also works without a connection, the app keeps the last card on the phone — **encrypted** (AES-256-GCM with a key in the Android Keystore), excluded from backups and deleted when you leave the family or the feature is switched off. It never appears in notifications or logs. The call buttons only open the phone app.
- **Pocket money, wish lists, household book:** amounts the family books are bookkeeping only — the app never pays, charges or transfers money and asks for no bank or card details. Who reserved a wish is never shown to the person who wished for it.
- **Presence ("who is home"):** only if you switch on "Share presence" yourself (off by default, can be switched off any time). The app then reports to **your own server** only "home" or "away" — recognised by whether your phone is in the home network (the server's own network or the network behind one of its gateways); to check, the app asks your server and sends it your phone's local Wi-Fi / LAN address (e.g. 192.168.1.23) — **no GPS**, no coordinates, no Wi-Fi name. A change is reported once it held for two minutes, also in the background (on network changes and about every 15 minutes). The family sees it in the app, on the wall tablet and in the portal; parents can let smart-home rules react to it. Without your consent you are shown as "not shared" and nothing is sent.
- **Retention:** the server deletes coordinates 7 days after the answer; who asked when (without coordinates) stays visible as a log for 90 days.
- **Maps:** the map in the app (osmdroid) loads map images from OpenStreetMap (tile.openstreetmap.org). OpenStreetMap learns the map area shown and your IP address, but not whose location is shown. Addresses come from your Android device's geocoder, if it has one.

None of this goes to CallMeTechie or other third parties; data on your server is managed by its operator.

### Door & Camera (your server's "Tür & Kamera" plugin)

The door features (quick actions, notifications and the "Door & camera" screen) only exist when your GateControl server offers the "Door & Camera" plugin and its operator gave you a right on at least one lock.

- **Locking and unlocking:** a tap sends the command to **your own server**, which controls the lock (Nuki). Before unlocking or opening, the app asks for the phone's screen lock (fingerprint, face or PIN/pattern/password through Android); the result stays on the device, only the command goes to the server.
- **History and doorbell pictures:** who locked or unlocked when (and how), rings, motion and notices are stored on **your own server** (history for at most 90 days, pictures 1–30 days as set there). The app only loads them to show them; pictures and history are **not stored on the device** (only in memory while you look at them). Offline the app only shows the last known state of the locks (no pictures).
- **Pictures in notifications:** messages from your server may carry a picture (e.g. who is ringing at the front door). The app loads it only from your own server and shows it in the notification and in "Notifications"; it is kept in memory only. Depending on your Android settings the notification, picture included, may also be visible on the lock screen. The server deletes the picture when the message expires (for the doorbell after one hour).
- **"Open door" from a notification:** the button requires unlocking the device before the command goes to your server.
- **Ring:** the connection to Ring (rings, camera pictures) is made by your server, not by the app — through an unofficial interface, without warranty.

None of this goes to CallMeTechie or other third parties.

### Drive: places and people in the photos (your server's "Drive" plugin)

Only when your server offers Drive 1.16 or later.

- **Places:** with "Keep where photos were taken" (Drive › Settings › Places and people, on by default while you use the photo backup) the photo backup sends the coordinates from the photo's EXIF data (latitude and longitude) to **your own server**. For this the app reads the location data of the originals (Android permission "Access photo locations"). "Add places" does the same for photos already backed up that are still on the phone. Your server works out the place name itself, without an external service. Switched off, the app sends no places; "Delete all places" removes them on the server. The map loads map images from OpenStreetMap — OpenStreetMap learns the map area shown, but no photos.
- **People (face features):** only when you switch on "Recognise people" (off by default). Your phone then finds faces in your photos — **on the device**, with the YuNet and SFace models, on Wi-Fi and by default only while charging — and sends for each face only a series of numbers (the face feature), the face's position in the picture and how sure the detection is to **your own server**. No photo leaves the phone or your server for this. The recognition model this needs (SFace, 39 MB) is downloaded once when you switch it on — from the app's release page on GitHub, else from OpenCV Zoo on GitHub or from Hugging Face, by default only on Wi-Fi. These providers only learn the phone's IP address and the usual connection data, no photos and nothing of your server; switching off deletes the model again. For photos no longer on the phone the app loads the preview from your server. Your server groups the features into people; only you see your people and names. Switching off deletes all face data (features, people, names) on the server. Children's folders are left out unless you switch on "Include the children's folders".

None of this goes to CallMeTechie, Google or other third parties.

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
| Location (incl. background) | optional: Wi-Fi names for trusted Wi-Fi; location on tap to your own server; Family: location on a parent's request, "I've arrived"/SOS, place reminders (see above) |
| Camera | Setup QR code |
| Make phone calls (Phone) | optional: a tap on a contact favourite on Start calls its main number straight away. Asked only on the first tap; without the permission the phone app opens with the number. The app reads no call log or phone data and sends nothing. Can be changed in Android's settings: Apps › GateControl › Permissions › Phone |
| Biometrics / screen lock | Door & Camera: confirmation before a door is unlocked or opened (through Android; nothing is stored or sent) |

### Retention and deletion

Data on the device is deleted when you uninstall the app or clear its data. Data on your server is managed by its operator. How to delete your data or request its deletion: [Data deletion](DATA_DELETION.md).

### Children

The app is meant for operators and users of a GateControl server. Children only use it through the family of their own server, set up by their parents or the operator (see "Family"); nothing is collected for CallMeTechie or third parties.

### Contact

Privacy questions: [GitHub issues](https://github.com/CallMeTechie/GateControl-Android-Client/issues) or [callmetechie.de](https://callmetechie.de). For data on your server, contact its operator.
