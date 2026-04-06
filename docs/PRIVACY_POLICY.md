# GateControl VPN Client — Privacy Policy

## Overview

GateControl VPN Client is a self-hosted VPN management app. It connects exclusively to **your own GateControl server**. We do not operate any cloud infrastructure, relay servers, or data collection endpoints.

---

## Data Collection

**We do not collect, store, or transmit any personal data.**

Specifically, the app does **not**:

- Send analytics or telemetry to any third party
- Track your location, browsing activity, or app usage
- Display advertisements
- Share data with advertising networks or data brokers
- Store personal data on any server we control

---

## Network Communication

All network traffic is directed exclusively to the GateControl server URL that **you configure during setup**. The app communicates with:

- **Your GateControl server** — for VPN configuration, license validation, and service management
- **Your WireGuard endpoint** — for encrypted VPN tunnel traffic

No data is sent to any other destination.

---

## Permissions

The app requests the following Android permissions:

| Permission | Purpose |
|---|---|
| **INTERNET** | Required to establish the VPN connection to your server |
| **CAMERA** | Used solely for scanning QR codes during initial server setup. Camera access is only activated when you explicitly open the QR scanner. No images or video are stored or transmitted. |
| **VPN Service** | Required by Android to create the WireGuard VPN tunnel |
| **Boot Completed** | Optional auto-connect on device startup (user-configurable) |
| **Notifications** | To display VPN connection status |

---

## Data Storage

All configuration data (server URL, API token, WireGuard keys) is stored **locally on your device** using Android's EncryptedSharedPreferences (AES-256). No credentials are stored in plain text.

RDP credentials (Pro feature) use end-to-end encryption (ECDH + AES-256-GCM) and are never stored unencrypted on the device.

---

## Third-Party Services

The app uses **Google ML Kit** (on-device barcode scanning) for QR code recognition. ML Kit runs entirely on-device and does not transmit any data to Google servers.

No other third-party SDKs or services are included.

---

## Children's Privacy

This app is intended for IT administrators and technical users. It is not directed at children under the age of 13 and does not knowingly collect information from children.

---

## Contact

For privacy-related inquiries, please open an issue on our [GitHub repository](https://github.com/CallMeTechie/GateControl-Android-Client) or contact the server administrator of your GateControl instance.

---

*Last updated: April 6, 2026*
