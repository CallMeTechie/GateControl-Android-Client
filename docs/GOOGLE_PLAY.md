# Google Play – Veröffentlichung

Wie GateControl in den Play Store kommt: was die CI erledigt und was nur in der Play Console geht (mit den Antworten zum Übernehmen).

## Was die CI erledigt

| Workflow | Wann | Was |
|---|---|---|
| `release.yml` | jedes App-Release (feat/fix auf `main`) | AAB (Play-Variante ohne Selbst-Update) + R8-Mapping + Release-Notizen aus dem CHANGELOG → Track aus der Repo-Variable `PLAY_TRACK` (Standard `internal`) |
| `play-promote.yml` | nur von Hand / auf Zuruf | Version von einem Track in einen anderen, z. B. → `production` (optional Teilveröffentlichung `rollout` 0–1) |
| `play-upload.yml` | von Hand | ein bestehendes GitHub-Release nachträglich hochladen |
| `play-listing.yml` | von Hand | Store-Texte aus `fastlane/metadata/android/` hochladen |

Voraussetzung: Secret `PLAY_SERVICE_ACCOUNT_JSON` (Dienstkonto mit „Releases“ und „Store-Präsenz“). Versionsnummern: `versionCode = Major·10000 + Minor·100 + Patch` — AABs nicht mehr von Hand hochladen, sonst kollidieren sie.

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
Die App sendet Daten nur an den vom Nutzer betriebenen Server; CallMeTechie erhält nichts. Zur sicheren Seite angeben:

| Frage | Antwort |
|---|---|
| Erhebt oder teilt die App Nutzerdaten? | Ja |
| Verschlüsselt bei der Übertragung? | Ja |
| Können Nutzer die Löschung beantragen? | Ja (Daten auf dem Gerät: App-Daten löschen; Daten auf dem Server: Betreiber) |
| **App-Info und -Leistung → Absturzprotokolle** | erhoben, nicht geteilt, nicht optional (automatisch), Zweck: App-Funktionalität |
| **App-Info und -Leistung → Diagnose** | erhoben, nicht geteilt, optional (Support-Paket), Zweck: App-Funktionalität |
| **Geräte- oder andere IDs** | erhoben, nicht geteilt, nicht optional, Zweck: App-Funktionalität (Gerätename/Modell im Status an den eigenen Server) |
| **Standort → Genauer Standort** | erhoben (ab 1.26: Standortfreigabe), **nicht geteilt** (geht nur an den eigenen Server des Nutzers), **optional**, Zweck: App-Funktionalität; nur auf Tipp, nicht im Hintergrund. Für die WLAN-Namen wird der Standort nur auf dem Gerät genutzt. |
| Persönliche Daten, Finanzen, Nachrichten, Fotos, Kontakte, Web-Verlauf | nein |

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
