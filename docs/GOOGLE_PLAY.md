# Google Play – Veröffentlichung

Wie GateControl in den Play Store kommt: was die CI erledigt und was nur in der Play Console geht (mit den Antworten zum Übernehmen).

## Was die CI erledigt

| Workflow | Wann | Was |
|---|---|---|
| `release.yml` | jedes App-Release (feat/fix auf `main`) | AAB (Play-Variante ohne Selbst-Update) + R8-Mapping + Release-Notizen aus dem CHANGELOG → Track aus der Repo-Variable `PLAY_TRACK` (Standard `internal`) |
| `play-promote.yml` | nur von Hand / auf Zuruf | Version von einem Track in einen anderen, z. B. → `production` (optional Teilveröffentlichung `rollout` 0–1) |
| `play-upload.yml` | von Hand | ein bestehendes GitHub-Release nachträglich hochladen |
| `play-listing.yml` | von Hand | Store-Texte aus `fastlane/metadata/android/` hochladen |
| `play-data-safety.yml` | von Hand | Datensicherheit-Formular aus `docs/play/data-safety.csv` an Google senden (ersetzt die ganze Erklärung) |

Voraussetzung: Secret `PLAY_SERVICE_ACCOUNT_JSON` (Dienstkonto mit „Releases“ und „Store-Präsenz“; für `play-data-safety.yml` zusätzlich das Recht, Richtlinien-Erklärungen/App-Inhalte zu verwalten — antwortet Google mit 403, fehlt es). Versionsnummern: `versionCode = Major·10000 + Minor·100 + Patch` — AABs nicht mehr von Hand hochladen, sonst kollidieren sie.

**Tracks:** `internal` = Interner Test · `alpha` = Geschlossener Test · `beta` = Offener Test · `production`.
Jedes Release zusätzlich in den geschlossenen Test: Repo-Variable `PLAY_TRACK=alpha` setzen (Settings → Secrets and variables → Actions → Variables).

## Weg zur Produktion (Privatkonto, angelegt nach Nov. 2023)

1. **Geschlossener Test einrichten:** Play Console → Testen → Geschlossener Test → Track „Closed testing“ → Tester (E-Mail-Liste oder Google-Gruppe, **mindestens 12**). Danach `PLAY_TRACK=alpha`.
2. **14 Tage** müssen mindestens 12 Tester am Stück im Test bleiben (Opt-in-Link aus der Console an die Tester).
3. **App-Inhalte** ausfüllen (unten).
4. **Produktionszugang beantragen:** Play Console → Dashboard → „Zugriff auf Produktion beantragen“ (Fragen zum Test beantworten).
5. Nach der Freigabe: „veröffentliche ein Produktions-Release“ → `play-promote.yml`.

## App-Inhalte (Play Console → Richtlinien → App-Inhalte)

### Datenschutzerklärung
`https://github.com/CallMeTechie/GateControl-Android-Client/blob/main/docs/PRIVACY_POLICY.md`

### App-Zugriff
„Alle oder einige Funktionen sind eingeschränkt“ → Anleitung für die Prüfer:

> GateControl ist der Client für einen selbst gehosteten GateControl-Server. Zum Testen: App öffnen → „Einrichtungscode eingeben“ → Server `<DEMO-SERVER-URL>` und Code `<CODE>` eingeben (oder den beigefügten QR-Code scannen). Danach „Verbinden“ und die VPN-Anfrage bestätigen.

Dafür braucht es einen **Demo-Server mit einem dauerhaft gültigen Einrichtungscode** (ohne echte Daten). Der Code darf für mehrere Geräte gelten, weil Google mehrfach prüft.

### Werbung
Nein.

### Inhaltseinstufung
Kategorie „Tools/Dienstprogramme“ (keine Spiele, kein Social, keine Nutzerinhalte): alle Fragen „Nein“ → USK 0 / PEGI 3.

### Zielgruppe
18 Jahre und älter (nicht für Kinder; hält die Familien-Richtlinien außen vor).

### Datensicherheit
Die App sendet Daten nur an den vom Nutzer betriebenen Server; CallMeTechie erhält nichts. Zur sicheren Seite angeben.

Die vollständigen Antworten liegen als CSV im Format von Google („Datensicherheit → Aus CSV importieren“, Vorlage aus der Play-Console-Hilfe) in [`docs/play/data-safety.csv`](play/data-safety.csv). Der Workflow **Google Play Data Safety** (`.github/workflows/play-data-safety.yml`, nur von Hand) schickt sie über die Play Developer API (`applications.dataSafety`) an Google — das **ersetzt die ganze Erklärung**, also bei Änderungen immer die komplette Datei pflegen. Alternativ in der Play Console unter App-Inhalte → Datensicherheit → „Aus CSV importieren“ hochladen.

Allgemein:

| Frage | Antwort |
|---|---|
| Erhebt oder teilt die App Nutzerdaten? | Ja |
| Verschlüsselt bei der Übertragung? | Ja (HTTPS bzw. WireGuard zum eigenen Server) |
| Können Nutzer die Löschung beantragen? | Ja (Daten auf dem Gerät: App-Daten löschen; Daten auf dem Server: Betreiber) |

Für **jeden** erhobenen Datentyp gilt: nur erhoben, **nicht geteilt** (geht nur an den eigenen Server des Nutzers, nie an CallMeTechie oder Dritte), **nicht** sitzungsbezogen („ephemeral“: nein), Zweck **nur App-Funktionalität**.

| Datentyp | Pflicht/optional | Wofür |
|---|---|---|
| **Persönliche Daten → Name** | optional | Familie: Namen in Notfallkontakten und frei eingetragene Fahrer („Oma“); Chats: Gruppennamen. Konto- und Anzeigenamen legt der Server-Betreiber an. |
| **Persönliche Daten → Telefonnummer** | optional | Familie: Notfallnummern auf der Notfall-Karte |
| **Finanzinformationen → Kaufverlauf** | optional | Familie: Ausgaben im Haushaltsbuch (reine Buchhaltung, keine Zahlungen) |
| **Finanzinformationen → Sonstige Finanzinformationen** | optional | Familie: Taschengeld (Guthaben, Buchungen, Regeln) |
| **Standort → Ungefährer Standort** | optional | Familie: Anwesenheit „zuhause/unterwegs“ (Heimnetz-Erkennung, kein GPS) und „angekommen/verlassen“ bei Orten für Regeln (ohne Position) |
| **Standort → Genauer Standort** | optional | Standortfreigabe auf Tipp (ab 1.26), Standort-Nachricht im Chat (einmalig), Familie: Kind orten auf Anfrage der Eltern, SOS. Für die WLAN-Namen wird der Standort nur auf dem Gerät genutzt. |
| **Nachrichten → Sonstige In-App-Nachrichten** | optional | Chats (ab 1.42): Texte, Antworten, Reaktionen |
| **Fotos und Videos → Fotos** | optional | Chat-Bilder, eigenes Profilbild (ab 1.33) |
| **Fotos und Videos → Videos** | optional | Chats: Videos, die als Datei gesendet oder aus anderen Apps geteilt werden |
| **Audiodateien → Sprach- oder Tonaufnahmen** | optional | Chats: Sprachnachrichten (Mikrofon nur, solange die Taste gehalten wird) |
| **Gesundheit und Fitness → Gesundheitsinformationen** | optional | Familie: Notfall-Karte (Allergien, Medikamente, Vorerkrankungen, Blutgruppe …) |
| **App-Info und -Leistung → Absturzprotokolle** | Pflicht (automatisch) | Absturzbericht als geschwärztes Support-Paket an den eigenen Server |
| **App-Info und -Leistung → Diagnose** | optional | Support-Paket, „Problem melden“ |
| **Dateien und Dokumente** | optional | Chats: gesendete Dateien |
| **App-Aktivitäten → In-App-Suchverlauf** | optional | Chats: Suche im Chat läuft auf dem Server |
| **App-Aktivitäten → Sonstige von Nutzern erstellte Inhalte** | optional | Familie: Einkaufslisten, Aufgaben, Termine, Fahrten, Pinnwand, Wunschlisten, Essensplan |
| **Geräte- oder andere IDs** | Pflicht | Gerätename/Modell im Status an den eigenen Server |
| E-Mail, Adresse, sonstige persönliche Daten, Kontakte, Kalender, SMS/E-Mails, Web-Verlauf, Musik, Fitness, Seitenaufrufe, installierte Apps | nein |

### Berechtigungen und Erklärungen

**Standort im Hintergrund** (Formular „Sensible Berechtigungen → Standort“):
- *Funktion:* „Vertrauenswürdige WLANs: Das VPN wird in einem vom Nutzer als vertrauenswürdig markierten WLAN (z. B. zu Hause) automatisch getrennt und außerhalb automatisch wieder verbunden – auch wenn die App geschlossen ist. Android liefert den WLAN-Namen (SSID) nur mit Standort-Berechtigung. Die App verwendet keine Koordinaten, speichert und überträgt weder Standort noch WLAN-Namen.“
- *Warum im Hintergrund:* „Netzwechsel passieren, während die App geschlossen ist (Heimkommen, Verlassen der Wohnung). Ohne Hintergrund-Zugriff erkennt die App das vertrauenswürdige WLAN erst beim nächsten Öffnen.“
- *Video (30–60 s, Bildschirmaufnahme):* Einstellungen → Vertrauenswürdige WLANs → einschalten → „Aktuelles WLAN hinzufügen“ → Hinweisdialog zeigen → Standort „Bei Nutzung der App“ erlauben → Hinweis „Immer zulassen“ → in den Android-Einstellungen „Immer zulassen“ → App schließen → WLAN trennen/wechseln → VPN verbindet bzw. trennt sich automatisch (Benachrichtigung/Kachel zeigen). Als YouTube-Link (nicht gelistet) angeben.

**VPN-Dienst** (Formular „VPN-Dienst“, falls abgefragt):
- Kernfunktion der App ist ein VPN-Tunnel (WireGuard) zum eigenen GateControl-Server des Nutzers; kein Werbe-/Tracking-Zweck, keine Umleitung zu Dritten, keine Datenerhebung über den Tunnel.

**Dienste im Vordergrund** (Formular „Berechtigungen für Dienste im Vordergrund“, Typ *specialUse*):
- *Benachrichtigungsdienst:* „Hält die Verbindung zur eigenen Benachrichtigungszentrale des selbst gehosteten Servers offen (Server-Sent Events), da keine Google-Push-Dienste genutzt werden. Wird nur gestartet, wenn der Nutzer ‚Benachrichtigungen – immer, auch ohne VPN‘ wählt; der Nutzer sieht dauerhaft eine Benachrichtigung und kann es jederzeit abschalten.“
- *Remote-Desktop-Sitzung:* „Hält eine vom Nutzer gestartete RDP-Sitzung aufrecht, solange sie läuft.“

**Akku-Optimierung ausnehmen** (keine eigene Erklärung, Prüfer fragen ggf. nach): Die App fragt nur, wenn der Nutzer Benachrichtigungen „immer“ empfangen will; ohne Ausnahme beendet Doze die Verbindung zur Benachrichtigungszentrale.

## Store-Eintrag

Texte: `fastlane/metadata/android/{de-DE,en-US}/` (`title.txt` ≤ 30, `short_description.txt` ≤ 80, `full_description.txt` ≤ 4000 Zeichen) → `play-listing.yml`.

Grafiken (noch von Hand in der Console oder als Dateien unter `…/images/`):
- App-Symbol 512×512 PNG
- Vorstellungsgrafik 1024×500 PNG/JPG
- mindestens 2 Smartphone-Screenshots (z. B. 1080×2400), ideal 4–8
- App-Kategorie: Tools · Kontakt-E-Mail und Website (callmetechie.de)
