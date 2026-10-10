# Vendored WG-config golden fixtures

These fixtures are a vendored copy of
`gatecontrol-config-hash/spec/wg-config/fixtures`, synced via
`scripts/sync-wg-fixtures.sh`. Do NOT edit directly.

The `.fixtures-hash` file is asserted by `WgConfigValidatorTest`; a
mismatch means the vendored copy was locally corrupted.

NOTE: this hash only detects local corruption of the vendored copy — it
does NOT detect a forgotten re-sync after the canonical fixtures change
(the copy + hash would both be stale-but-consistent). The only real guard
against a forgotten sync is the release checklist
(see `gatecontrol-config-hash/spec/wg-config/SYNC.md`).

- Integrity hash: `314336eb25d68050d061f9a978dae0042b5c17573c19d211d0ab8cad6e6ba18a`
- Last synced (UTC): 2026-05-31
