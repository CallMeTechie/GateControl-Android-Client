# Diagnose-Assistent (Diagnostics)

Developer notes for the "Diagnose" screen (`settings/diagnosis`): seven
connection checks, a summary, and "Problem melden" (support bundle with a
description and the results).

## Entry points

- Settings › Hilfe › "Diagnose & Problem melden" (`SettingsScreen` → `onNavigateToDiagnosis`).
- Start screen: "Verbindung prüfen" under the error banner (`TunnelState.Error`) and
  under the connection card when the last handshake is older than 180 s
  (`VpnScreen` → `onOpenDiagnosis`).
- Route `Screen.Diagnosis` → `ui/diagnosis/DiagnosisScreen.kt`.

## Code

| File | Role |
|---|---|
| `diagnosis/DiagnosisModels.kt` | `CheckId` (ids in the bundle), `CheckStatus`, `CheckResult`, `DiagText` (late-resolved text), `DiagnosisReport` + summary rules |
| `diagnosis/DiagnosisEvaluators.kt` | Pure rules: latency, error classes, tunnel, handshake, DNS, MTU search, speed |
| `diagnosis/DiagnosisProbes.kt` | Seams: `NetworkProbe`, `ServerProbe`, `TunnelProbe`, `CommandRunner`, `SpeedProbe` |
| `diagnosis/AndroidDiagnosisProbes.kt` | Android / API implementations + Hilt bindings (`DiagnosisModule`) |
| `diagnosis/DiagnosisRunner.kt` | Runs the checks in order, per-check timeout, progress callback |
| `diagnosis/DiagnosisPayload.kt` | The `diagnosis` key of the support bundle |
| `diagnosis/DiagnosisViewModel.kt` | Run state (survives rotation), report dialog, upload |
| `core/network/SpeedTestClient.kt` | Speed test transfers (streaming, time-capped) |

Every check runs under its own timeout (`DiagnosisRunner.timeoutFor`); a check
that throws or times out gets a result of its own and the run goes on.
Checks that need the tunnel are `skipped` while it is down.

## Checks

| id | What | ok / warn / fail |
|---|---|---|
| `internet` | Network under the VPN: present and `NET_CAPABILITY_VALIDATED`; names Wi-Fi / mobile / Ethernet | fail without network or validation |
| `server` | 3× `GET api/v1/client/ping`, median (stops at the first error) | ok < 150 ms, warn < 600 ms ("a bit slow"), warn above ("slow"); fail with the error class (DNS, timeout, connect, TLS, 401/403 → token, other HTTP) |
| `tunnel` | `TunnelManager.state` | ok connected; warn off / connecting; fail on `Error` |
| `handshake` | Age of the last WireGuard handshake. Older than 180 s: one ping through the tunnel, wait 3 s, read again | ok ≤ 180 s, warn ≤ 600 s, fail beyond or never; notes when rx did not grow |
| `dns` | `LinkProperties.dnsServers` of the `TRANSPORT_VPN` network vs. `dns-check.vpnDns` | ok same; fail none / only foreign; warn extra resolver or strict private DNS (`privateDnsServerName`) |
| `mtu` | `/system/bin/ping -M do -c 1 -W 1 -s <size−28> <gatewayIp>` (gateway from `dns-check`), binary search 1280…tunnel MTU (config `MTU`, default 1420), ≤ 8 probes, ~10 s | ok full MTU; warn with the largest size; skipped without ping, gateway or any ICMP answer |
| `speed` | Server endpoints below; download ≤ min(25 MiB, max), upload ≤ min(10 MiB, max), each capped at 8 s | ok (informational); warn when both < 1 Mbit/s or nothing measured; skipped on 404 (older server), 429, or when the server check failed |

Summary: `fail` if any check failed, `warn` if any warned, else `ok`.
Skipped checks never degrade it; the screen shows "N Prüfungen übersprungen".

## Speed test endpoints (server)

- `GET api/v1/client/speedtest` → `{"ok":true,"maxDownloadBytes":int,"maxUploadBytes":int,"viaTunnel":bool}`; 404 on older servers.
- `GET api/v1/client/speedtest/download?bytes=N` → N bytes `application/octet-stream`.
- `POST api/v1/client/speedtest/upload` (octet-stream) → `{"ok":true,"bytes":int,"serverMs":int}`.
- 429 = too many measurements.

The client uses `ApiClientProvider.speedTestClient` — the normal client
(auth, machine binding, VPN-safe DNS) with longer read/write timeouts and a
40 s call timeout. The download is read as a stream (`@Streaming`), never into
memory; the clock starts after the response headers. The upload is a
fixed-length, one-shot body from one reused buffer; when the time cap hits,
the request is aborted and the rate comes from the bytes written. Rates use the
client's elapsed time; `serverMs` is only a fallback.

## Support bundle extras

"Problem melden" sends the normal support bundle (`SupportBundleCollector.collect`)
with two extra top-level keys; both pass the redactor:

```json
"description": "Seiten laden nicht",
"diagnosis": {
  "startedAt": "2026-10-13T09:00:00Z", "finishedAt": "2026-10-13T09:00:31Z",
  "summary": "warn",
  "checks": [ { "id": "server", "status": "warn", "title": "Server",
                "detail": "720 ms – langsam", "value": "720 ms", "hint": "…" } ]
}
```

`description` is trimmed, at most 2000 characters and left out when empty.
Titles, details and hints are the texts on screen, resolved in the app language
(a configuration context, as the app switches language on the Activity only);
`value` is the unlocalised technical value. Older servers validate only
`schema` and keep unknown keys.

## Tests

`app/src/test/.../diagnosis/` (rules, runner with fakes, payload / bundle,
ViewModel) and `core/network/.../SpeedTestClientTest.kt` (MockWebServer).
