# GateControl Android Client

WireGuard VPN Client for Android with integrated RDP management (Pro license).

## Features

- WireGuard VPN tunnel management
- Kill-Switch (Always-on VPN)
- Split-Tunneling
- Auto-Connect / Auto-Reconnect
- Traffic monitoring & bandwidth stats
- DNS Leak Test
- RDP session management (Pro)
- Wake-on-LAN (Pro)
- E2EE credential delivery (Pro)
- Multi-language (DE/EN)
- Dark/Light theme

## Client policies from the server

Admins can define client policies on the GateControl server (Settings → Client-Richtlinien, globally, per peer group or per peer). The app loads them from `GET /api/v1/client/policy` and refreshes them when a heartbeat or permissions answer carries a new `policyVersion`. The last known policy is stored and also applies offline. If the server is unreachable, the cached policy stays in force. A policy that was never fetched means no restriction.

What the app enforces:

| Policy | Android client |
|---|---|
| Auto-connect `required` | "Connect automatically" forced on and locked. The app connects on boot, on app start and when the policy arrives (only once VPN consent has been granted) |
| Auto-connect `always_on` | Same as `required`, plus no disconnect from the app or the Quick Settings tile |
| Autostart `required` / `forbidden` | Maps to connecting on boot: forced on or off |
| Split-tunnel modes | Only the allowed modes can be selected. A stored mode that is no longer allowed is clamped when connecting (full tunnel if allowed). A locked server preset keeps priority |
| Lock settings | Auto-connect, split-tunnel mode, networks and apps are locked. Theme and language stay free |
| Lock server | Server change and config import are hidden and refused |

Locked settings show "Vom Administrator festgelegt" / "Set by your administrator".

**Android limits:** an app cannot switch on the system **Always-on VPN** or **Block connections without VPN** (Android's real kill switch); only the user or an MDM / device owner can. If the policy requires a kill switch or always-on, the app shows a prominent hint with a button to the system VPN settings, on the main screen and in the settings. Enrollment through an external setup link is still possible under "lock server", because it needs a fresh setup code from the admin anyway.

The policy is applied on the device. It is a management convenience and **not a security boundary** against the device owner.

## License

MIT
