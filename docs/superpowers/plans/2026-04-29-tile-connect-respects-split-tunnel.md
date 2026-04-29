# Tile connect path now respects split-tunnel settings

**Date:** 2026-04-29

## Symptom

Android Auto (wireless) intermittently failed to connect to the head unit
even though the user had added the Android Auto package to the split-tunnel
app exception list. Wired Android Auto worked. Connecting Android Auto
*before* starting the VPN also worked.

## Root cause

Two distinct connect paths existed:

* `VpnViewModel.connect()` — used when the user taps the connect button on
  the VPN screen. Reads admin preset → falls back to local DataStore →
  calls `tunnelManager.connect(config, splitTunnelConfig)`. Also calls
  `apiClientProvider.preResolveDns(host)` before the tunnel comes up so
  API requests survive the DNS-leak gap (system DNS becomes 10.8.0.1, which
  is unreachable from the excluded GateControl app itself).
* `MainActivity.handleTileAction()` — used when the user taps the Quick
  Settings tile. Called the legacy `tunnelManager.connect(config)` overload
  with no split-tunnel arguments, which collapses to `mode = "off"` in
  `TunnelManager.connectInternal`. Side effect: no `excludeApplications()`
  call, no DNS pre-resolve.

User logs (`gatecontrol-export 2.log`, 12:47:53 and 13:14:00) confirmed both
tile connects logged `Connecting tunnel with split-tunnel mode: off` —
the user's exclude list was silently ignored.

The `HTTP 401` at the top of the log is the same DNS-leak gap: the tile
path skipped `preResolveDns()`, so the first API call after tunnel-up
failed before the cache could warm.

## Fix

Extracted the shared connect logic into a new `TunnelConnector` singleton
(`app/src/main/java/com/gatecontrol/android/service/TunnelConnector.kt`).
It centralises:

* WireGuard config retrieval and empty-config guard
* Server hostname pre-resolution
* Admin split-tunnel preset fetch (with persistence) → local DataStore fallback
* `tunnelManager.connect(config, splitTunnelConfig)` invocation
* Best-effort device hostname report (internal-DNS feature)

`MainActivity` now injects `TunnelConnector` and calls
`connectWithUserSettings()` from the tile path. `VpnViewModel.connect()`
is unchanged in this commit — its existing tests keep passing — and can
be migrated to the same connector in a follow-up without behavioural
change.

## Out of scope

The user explicitly declined an IP exception for the Wi-Fi Direct subnet
(`192.168.49.0/24`). The remaining Wi-Fi-Direct race that can occur when
the VPN comes up *after* Android Auto is already paired is therefore
unaddressed and will need a different approach (e.g. delaying connect
until the p2p network callback fires) if it persists after this fix.

## Files

* New: `app/src/main/java/com/gatecontrol/android/service/TunnelConnector.kt`
* Edit: `app/src/main/java/com/gatecontrol/android/MainActivity.kt`
