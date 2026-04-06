# GateControl Android Client — Einrichtungsanleitung

Dieses Dokument beschreibt die Ersteinrichtung des GateControl VPN Client auf einem Android-Gerät.

---

## Voraussetzungen

- Android 12 (API 31) oder neuer
- Ein laufender GateControl-Server mit gültiger Konfiguration
- **Für den vollen Funktionsumfang:** API-Token vom GateControl-Server
- **Alternativ:** WireGuard-Konfigurationsdatei (`.conf`) oder QR-Code

---

## Installation

### Google Play Store
1. Suchen Sie im Play Store nach **GateControl VPN Client**
2. Tippen Sie auf **Installieren**
3. Starten Sie die App nach der Installation

### Direkte APK-Installation
1. Laden Sie die aktuelle APK von der [GitHub Releases](https://github.com/CallMeTechie/GateControl-Android-Client/releases)-Seite herunter
2. Erlauben Sie die Installation aus unbekannten Quellen (wird beim ersten Mal abgefragt)
3. Tippen Sie auf die heruntergeladene `.apk`-Datei und installieren Sie die App

---

## Einrichtungsmodi

### Modus A: API-Token (empfohlen)

Bietet den **vollen Funktionsumfang**: VPN, RDP, Services, Datenverbrauch, DNS-Leak-Test, automatische Konfigurationsaktualisierung.

**Schritt 1 — Server-URL und Token eingeben**

1. Starten Sie die App — der Setup-Bildschirm wird automatisch angezeigt
2. Tippen Sie auf **Manuell eingeben**
3. Geben Sie die Server-URL ein (z. B. `https://gate.example.com`)
4. Geben Sie den API-Token ein (beginnt mit `gc_`)
5. Tippen Sie auf **Verbindung testen** um die Erreichbarkeit zu prüfen
6. Tippen Sie auf **Speichern & Registrieren**

**Schritt 2 — VPN-Berechtigung erteilen**

1. Android fragt nach der VPN-Berechtigung — tippen Sie auf **Erlauben**
2. Optional: Aktivieren Sie **Always-on VPN** in den Android-Systemeinstellungen

**Schritt 3 — Verbinden**

1. Tippen Sie auf dem Hauptbildschirm auf **Verbinden**
2. Der Verbindungsring wird grün, sobald der Tunnel steht

### Modus B: QR-Code-Scan

Ideal für schnelle Einrichtung ohne manuelle Eingabe von URLs und Tokens.

1. Starten Sie die App
2. Tippen Sie auf **QR-Code scannen**
3. Erlauben Sie den Kamerazugriff (wird nur für den QR-Scanner verwendet)
4. Scannen Sie den QR-Code, der vom GateControl-Server oder Administrator bereitgestellt wird
5. Die App registriert sich automatisch und bezieht die VPN-Konfiguration

### Modus C: Deep-Link

Für automatisierte Provisionierung per URL (z. B. per E-Mail oder MDM).

1. Öffnen Sie den bereitgestellten Link im Format:
   ```
   gatecontrol://setup?url=https://gate.example.com&token=gc_XXXXXXXX
   ```
2. Die App startet automatisch und übernimmt die Konfiguration
3. Bestätigen Sie die Registrierung

### Modus D: WireGuard-Konfigurationsdatei

Für Nutzer ohne GateControl-Server-API — standardmäßiger WireGuard-Import.

1. Starten Sie die App
2. Tippen Sie auf **Konfigurationsdatei importieren**
3. Wählen Sie die `.conf`-Datei aus dem Dateisystem
4. Die App importiert die WireGuard-Konfiguration direkt

> **Hinweis:** Im Konfigurationsdatei-Modus sind serverseitige Funktionen (RDP, Services, Datenverbrauch, DNS-Leak-Test) nicht verfügbar. Nur der VPN-Tunnel wird konfiguriert.

---

## Nach der Einrichtung

### Auto-Connect aktivieren
1. Öffnen Sie **Einstellungen** (untere Navigationsleiste)
2. Aktivieren Sie **Automatisch verbinden**
3. Die App verbindet sich künftig beim Start automatisch mit dem VPN

### Kill-Switch einrichten
1. Auf dem VPN-Bildschirm: Aktivieren Sie den **Kill-Switch**-Schalter
2. Alternativ: Öffnen Sie die **Android-Systemeinstellungen > Netzwerk & Internet > VPN > GateControl > Einstellungen**
3. Aktivieren Sie **Immer aktiv (Always-on VPN)** und **Verbindungen ohne VPN blockieren**

### Quick Settings Tile hinzufügen
1. Wischen Sie vom oberen Bildschirmrand nach unten, um die Quick Settings zu öffnen
2. Tippen Sie auf das Stift-Symbol (Bearbeiten)
3. Suchen Sie **GateControl VPN** und ziehen Sie die Kachel in den sichtbaren Bereich
4. Nun können Sie den VPN-Tunnel mit einem Tipp auf die Kachel ein-/ausschalten

---

## Fehlerbehebung

| Problem | Lösung |
|---|---|
| **Verbindung schlägt fehl** | Server-URL und API-Token prüfen. Ist der GateControl-Server erreichbar? |
| **VPN trennt sich immer wieder** | Kill-Switch + Always-on-VPN aktivieren. Prüfen Sie die Mobilfunk-/WLAN-Stabilität. |
| **QR-Code wird nicht erkannt** | Kamera-Berechtigung prüfen. QR-Code im Vollbildmodus anzeigen. |
| **RDP-Tab nicht sichtbar** | RDP erfordert eine Pro-Lizenz am GateControl-Server. |
| **DNS-Leak erkannt** | Kill-Switch aktivieren. Split-Tunneling-Regeln prüfen. |
| **App startet nicht nach Boot** | Auto-Connect aktivieren. Battery-Optimization für GateControl deaktivieren. |
