# GateControl Android Client — FAQ

Häufig gestellte Fragen zum GateControl VPN Client.

---

## Allgemein

### Was ist GateControl?
GateControl ist ein selbst gehostetes VPN- und Infrastruktur-Management-System. Der Android Client verbindet sich mit Ihrem eigenen GateControl-Server — es gibt keine Cloud-Zwischenstelle und keine Drittanbieter-Server.

### Ist GateControl kostenlos?
Die Community-Version bietet den vollen VPN-Funktionsumfang kostenlos. Pro-Funktionen (RDP, DNS-Leak-Test, erweiterte Statistiken) erfordern eine Pro-Lizenz, die serverseitig verwaltet wird.

### Brauche ich einen eigenen Server?
Ja. GateControl ist ein Self-Hosted-Produkt. Sie benötigen einen GateControl-Server, auf den sich die App verbindet. Ohne Server ist die App nicht nutzbar (Ausnahme: reiner WireGuard-Import einer `.conf`-Datei).

### Welche Android-Version wird benötigt?
Android 12 (API Level 31) oder neuer.

### In welchen Sprachen ist die App verfügbar?
Deutsch und Englisch. Die Sprache kann in den App-Einstellungen gewählt werden.

---

## VPN

### Welches VPN-Protokoll wird verwendet?
WireGuard — ein modernes, schnelles und sicheres VPN-Protokoll. Die App nutzt die offizielle WireGuard-Android-Bibliothek.

### Ist die Verbindung sicher?
Ja. WireGuard verwendet ChaCha20-Poly1305 für Verschlüsselung, Curve25519 für den Schlüsselaustausch und BLAKE2s als Hash-Funktion. Zusätzlich werden Zugangsdaten (bei RDP) Ende-zu-Ende mit AES-256-GCM verschlüsselt.

### Was passiert bei einem Verbindungsabbruch?
Die App versucht automatisch eine Wiederverbindung mit exponentiellem Backoff (2s bis 60s, maximal 10 Versuche). Bei aktiviertem Kill-Switch wird in der Zwischenzeit jeglicher Netzwerkverkehr blockiert.

### Kann ich bestimmte Apps vom VPN ausschließen?
Ja. Unter **Einstellungen > Tunnel > Split-Tunneling > Ausgeschlossene Apps** können einzelne Apps ausgewählt werden, die den VPN-Tunnel umgehen.

### Kann ich nur bestimmte Netzwerke durch den Tunnel routen?
Ja. Unter **Einstellungen > Tunnel > Split-Tunneling > IP-Routen** können CIDR-Bereiche (z. B. `10.0.0.0/8`) definiert werden.

### Wie aktiviere ich den Kill-Switch?
Auf dem VPN-Bildschirm den Kill-Switch-Schalter aktivieren. Für maximalen Schutz zusätzlich in den Android-Systemeinstellungen unter **VPN > GateControl > Immer aktiv und Verbindungen ohne VPN blockieren** aktivieren.

### Verbraucht die App viel Akku?
WireGuard ist eines der effizientesten VPN-Protokolle und hat minimalen Einfluss auf den Akkuverbrauch. Der Foreground Service sorgt dafür, dass die Verbindung nicht vom System beendet wird.

---

## Einrichtung

### Wie richte ich die App ein?
Es gibt vier Möglichkeiten:
1. **QR-Code scannen** — Schnellste Methode, vom Administrator bereitgestellt
2. **Deep-Link** — Automatische Konfiguration per `gatecontrol://setup?url=...&token=...`
3. **Manuell** — Server-URL und API-Token eingeben
4. **Konfigurationsdatei** — Standard-WireGuard `.conf`-Datei importieren

### Was ist der Unterschied zwischen API-Token und Konfigurationsdatei?
Mit einem **API-Token** registriert sich die App am Server und erhält den vollen Funktionsumfang (automatische Updates, RDP, Services, Statistiken). Eine **Konfigurationsdatei** bietet nur die grundlegende VPN-Funktion ohne serverseitige Features.

### Wo finde ich meinen API-Token?
Im GateControl-Server-Dashboard unter der Geräteverwaltung. Der Token beginnt mit `gc_`.

### Kann ich die App auf mehreren Geräten nutzen?
Ja. Jedes Gerät wird als eigener Peer am Server registriert und erhält eine individuelle WireGuard-Konfiguration.

---

## RDP (Pro)

### Was benötige ich für RDP?
- Eine Pro-Lizenz am GateControl-Server
- Einen konfigurierten RDP-Host im Server-Dashboard
- Einen externen RDP-Client auf dem Gerät (Microsoft Remote Desktop oder aFreeRDP)
- Eine aktive VPN-Verbindung

### Werden meine RDP-Zugangsdaten sicher übertragen?
Ja. Die Zugangsdaten werden per ECDH P-256 Schlüsselaustausch und AES-256-GCM Ende-zu-Ende verschlüsselt. Es wird ein Ephemeral-Schlüsselpaar pro Sitzung verwendet — kein Schlüsselmaterial wird persistent gespeichert.

### Was ist Wake-on-LAN?
Damit können ausgeschaltete Computer über das Netzwerk aufgeweckt werden. Die App sendet den WoL-Befehl über die GateControl-API und wartet, bis der Host erreichbar ist.

### Warum sehe ich den RDP-Tab nicht?
Der RDP-Tab wird nur angezeigt, wenn der GateControl-Server eine Pro-Lizenz bereitstellt und RDP-Hosts konfiguriert sind.

---

## Sicherheit & Datenschutz

### Werden meine Daten gesammelt?
Nein. Die App enthält keine Telemetrie, keine Analytics-SDKs und keine Werbung. Alle Daten bleiben auf Ihrem Gerät und Ihrem Server.

### Wohin sendet die App Daten?
Ausschließlich an die Server-URL, die Sie bei der Einrichtung angegeben haben. Keine Dritten erhalten Zugang zu Ihren Daten.

### Wie werden Daten auf dem Gerät gespeichert?
Über Androids EncryptedSharedPreferences mit AES-256-Verschlüsselung. Weder API-Token noch WireGuard-Schlüssel werden im Klartext gespeichert.

### Ist die App Open Source?
Der Quellcode ist auf [GitHub](https://github.com/CallMeTechie/GateControl-Android-Client) verfügbar.

### Was passiert auf gerooteten Geräten?
Die App zeigt eine Warnung an, dass die VPN-Sicherheit auf gerooteten Geräten beeinträchtigt sein könnte. Die Funktionalität bleibt erhalten.

---

## Fehlerbehebung

### Die App verbindet sich nicht
1. Prüfen Sie, ob der GateControl-Server erreichbar ist
2. Prüfen Sie Server-URL und API-Token in den Einstellungen
3. Nutzen Sie **Verbindung testen** in den Einstellungen
4. Prüfen Sie die Protokolle unter **Einstellungen > Protokolle anzeigen**

### DNS-Leak erkannt
1. Aktivieren Sie den Kill-Switch
2. Prüfen Sie die Split-Tunneling-Konfiguration — keine DNS-Server außerhalb des Tunnels?
3. Aktivieren Sie Always-on VPN in den Android-Systemeinstellungen

### Die App wird im Hintergrund beendet
1. Deaktivieren Sie die Akkuoptimierung für GateControl in den Android-Einstellungen
2. Aktivieren Sie Always-on VPN in den Systemeinstellungen
3. Der Foreground Service sollte die App vor dem Beenden schützen

### QR-Code wird nicht erkannt
1. Prüfen Sie die Kameraberechtigung in den Android-App-Einstellungen
2. Stellen Sie sicher, dass der QR-Code scharf und vollständig im Kamerabild sichtbar ist
3. Versuchen Sie, den QR-Code in einem hellen Umfeld zu scannen

### App stürzt ab
1. Exportieren Sie die Protokolle unter **Einstellungen > Protokolle exportieren**
2. Erstellen Sie ein Issue auf [GitHub](https://github.com/CallMeTechie/GateControl-Android-Client/issues) mit den Protokolldaten
