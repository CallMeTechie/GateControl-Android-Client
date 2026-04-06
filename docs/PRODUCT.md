# GateControl VPN Client for Android

**Professionelles VPN- und Remote-Desktop-Management für Ihre selbst gehostete Infrastruktur.**

---

## Was ist GateControl?

GateControl VPN Client ist ein nativer Android-Client, der sich nahtlos mit Ihrem selbst gehosteten GateControl-Server verbindet. Die App vereint WireGuard-VPN-Tunnel, Echtzeit-Bandbreitenmonitoring, Kill-Switch, Split-Tunneling und Remote-Desktop-Verwaltung in einer einzigen, professionell gestalteten Oberfläche.

Kein Abonnement. Keine Cloud-Zwischenstelle. Keine Datenerfassung. Nur eine sichere, direkte Verbindung zu Ihrer eigenen Infrastruktur.

---

## Warum GateControl?

| Eigenschaft | GateControl | Kommerzielle VPN-Apps |
|---|---|---|
| **Infrastruktur** | Vollständig selbst gehostet | Cloud-basiert, Drittanbieter |
| **Datenhoheit** | 100 % bei Ihnen | Daten auf fremden Servern |
| **Kosten** | Einmalig (Pro-Lizenz optional) | Monatliche Abonnements |
| **Telemetrie** | Keine | Nutzungsdaten, Analytics |
| **Werbung** | Keine | Häufig in Gratis-Varianten |
| **Anpassbarkeit** | Vollständig konfigurierbar | Beschränkt auf Anbieter-Optionen |
| **RDP-Integration** | Nativ integriert (Pro) | Separate App erforderlich |

---

## Zielgruppe

- **IT-Administratoren**, die VPN-Zugang für Teams und Geräte verwalten
- **Unternehmen**, die sichere Remote-Zugänge zu internen Ressourcen benötigen
- **Technisch versierte Nutzer**, die ihre VPN-Infrastruktur vollständig kontrollieren möchten
- **Managed-Service-Provider**, die VPN als Dienstleistung anbieten

---

## Kernfunktionen im Überblick

### WireGuard VPN-Tunnel
Verbinden und trennen mit einem Fingertipp. Die App nutzt die offizielle WireGuard-Android-Bibliothek und bietet damit identische Performance und Sicherheit wie der originale WireGuard-Client — mit einer deutlich benutzerfreundlicheren Oberfläche und nahtloser Integration in das GateControl-Ökosystem.

### Echtzeit-Monitoring
Ein animierter Verbindungsring zeigt den aktuellen Status auf einen Blick. Der Live-Bandbreitengraph visualisiert Upload- und Download-Geschwindigkeit in Echtzeit. Detaillierte Statistiken umfassen empfangene und gesendete Daten, Verbindungszeit und den letzten WireGuard-Handshake.

### Kill-Switch
Blockiert jeglichen Datenverkehr außerhalb des VPN-Tunnels. Die Implementierung nutzt die native Android Always-on-VPN-Integration auf Systemebene — kein Root erforderlich, keine Drittanbieter-Workarounds.

### Split-Tunneling
Leiten Sie nur bestimmten Datenverkehr durch den VPN-Tunnel. Konfigurierbar über IP-basierte Routen (z. B. `10.0.0.0/8`) oder App-basierte Ausschlüsse (einzelne Apps direkt ins Internet schicken). Die gesamte Konfiguration erfolgt komfortabel innerhalb der App.

### Quick Settings Tile
VPN mit einer Wischgeste aus dem Android-Benachrichtigungs-Schatten aktivieren oder deaktivieren. Der Verbindungsstatus ist jederzeit im Quick-Panel sichtbar — ohne die App öffnen zu müssen.

### Remote Desktop (Pro)
Verbinden Sie sich mit Windows-Desktops hinter dem VPN. Wake-on-LAN weckt ausgeschaltete Hosts direkt aus der App. Zugangsdaten werden Ende-zu-Ende-verschlüsselt (ECDH P-256 + AES-256-GCM) übertragen und niemals unverschlüsselt auf dem Gerät gespeichert.

### Automatische Einrichtung
Neues Gerät in Sekunden konfigurieren — per QR-Code-Scan, Deep-Link (`gatecontrol://setup`) oder manuelle Eingabe von Server-URL und API-Token. Die App registriert sich automatisch als Peer am GateControl-Server und bezieht die vollständige WireGuard-Konfiguration.

---

## Lizenzmodell

| Funktion | Community | Pro |
|---|---|---|
| WireGuard VPN-Tunnel | Ja | Ja |
| Kill-Switch | Ja | Ja |
| Split-Tunneling | Ja | Ja |
| Bandwidth-Monitoring | Ja | Ja |
| Quick Settings Tile | Ja | Ja |
| Auto-Connect | Ja | Ja |
| QR-Code-Setup | Ja | Ja |
| Datenverbrauchsstatistiken | -- | Ja |
| DNS-Leak-Test | -- | Ja |
| Server-Managed Services | -- | Ja |
| RDP-Sitzungsverwaltung | -- | Ja |
| Wake-on-LAN | -- | Ja |
| E2EE-Credential-Delivery | -- | Ja |

Die Lizenz wird serverseitig verwaltet. Es gibt keine In-App-Käufe — Pro-Funktionen werden automatisch freigeschaltet, sobald der GateControl-Server eine gültige Pro-Lizenz bereitstellt.

---

## Technische Eckdaten

| Eigenschaft | Wert |
|---|---|
| **Plattform** | Android 12 (API 31) oder neuer |
| **Architektur** | Kotlin, Jetpack Compose, Clean Architecture |
| **VPN-Protokoll** | WireGuard (offizielle Android-Bibliothek) |
| **Verschlüsselung (VPN)** | ChaCha20-Poly1305, Curve25519, BLAKE2s |
| **Verschlüsselung (Credentials)** | ECDH P-256 + HKDF-SHA256 + AES-256-GCM |
| **Lokale Speicherung** | EncryptedSharedPreferences (AES-256-SIV/GCM) |
| **Sprachen** | Deutsch, Englisch |
| **Design** | Material Design 3, Dark + Light Theme |
| **App-Größe** | ca. 35 MB (APK) |
| **Abhängigkeiten** | Keine Cloud-Services, keine Telemetrie-SDKs |

---

## Systemvoraussetzungen

- Android 12 (API 31) oder neuer
- Ein selbst gehosteter GateControl-Server
- Internetzugang zur Ersteinrichtung
- Pro-Lizenz am Server für RDP-Funktionen (optional)
