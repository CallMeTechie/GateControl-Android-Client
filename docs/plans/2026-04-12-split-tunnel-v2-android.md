# Split-Tunneling v2 — Android Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Full split-tunnel UI with include/exclude mode, network presets, app picker, admin preset enforcement, and AllowedIPs complement calculation.

**Architecture:** Fetch admin preset from server on connect, merge with local user config (apps always user-controlled). New Settings UI with mode toggle, network presets + custom CIDRs, and an app picker using PackageManager. TunnelManager calculates AllowedIPs complement for exclude mode.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt DI, WireGuard Android library, DataStore

**Spec:** See docs/specs/2026-04-12-split-tunnel-v2-design.md
**Depends on:** Server Plan (API endpoint must exist)

---

## File Map

| File | Action | Responsibility |
|---|---|---|
| core/data/.../SettingsRepository.kt | Modify | New DataStore keys for split-tunnel JSON format |
| core/network/.../ApiClient.kt | Modify | Add getSplitTunnelPreset() Retrofit method |
| core/network/.../ApiModels.kt | Modify | Add SplitTunnelPreset data class |
| core/tunnel/.../TunnelManager.kt | Modify | AllowedIPs complement calculation, new buildWgConfig logic |
| core/tunnel/.../CidrComplement.kt | Create | AllowedIPs subtraction algorithm |
| app/.../ui/settings/SettingsScreen.kt | Modify | Replace old split-tunnel UI with new design |
| app/.../ui/settings/SettingsViewModel.kt | Modify | New split-tunnel state management |
| app/.../ui/settings/AppPickerSheet.kt | Create | Bottom sheet with installed app list + search |
| app/.../ui/settings/NetworkPresetsSection.kt | Create | Composable for network presets + custom CIDRs |
| app/.../util/WifiSubnetDetector.kt | Create | Auto-detect current WiFi subnet |
| app/.../ui/vpn/VpnViewModel.kt | Modify | Fetch preset from server on connect |

---

## Tasks

### Task 1: API Model + Retrofit Method

Add SplitTunnelPreset data class to ApiModels.kt and getSplitTunnelPreset() to ApiClient.kt (GET /api/v1/client/split-tunnel).

### Task 2: CIDR Complement Algorithm

Create CidrComplement.kt in core/tunnel. Given a base range (0.0.0.0/0) and a list of CIDRs to exclude, compute the complementary AllowedIPs list. This is pure math (bit manipulation on IP ranges), fully unit-testable.

### Task 3: SettingsRepository Extension

Add new DataStore keys: split_tunnel_mode (String), split_tunnel_networks (JSON String), split_tunnel_apps (JSON String), split_tunnel_admin_locked (Boolean). Add getters/setters. Migrate old format on first read.

### Task 4: WifiSubnetDetector

Create utility that reads current WiFi network info via ConnectivityManager/LinkProperties and returns the subnet CIDR (e.g. 192.168.178.0/24). Returns null if not on WiFi.

### Task 5: AppPickerSheet Composable

Bottom sheet that lists installed apps from PackageManager. Shows app icon + name, search filter, toggle for system apps. Selected apps are marked. Returns list of {package, label} on dismiss.

### Task 6: NetworkPresetsSection Composable

Composable with: preset checkboxes (Private Nets, Link-Local, WiFi Subnet auto), custom CIDR list with add/remove, admin-lock indicator. Reads/writes to SettingsViewModel state.

### Task 7: SettingsScreen Rewrite (Split-Tunnel Section)

Replace the old text-field based split-tunnel section with the new design: mode toggle, NetworkPresetsSection, app list with AppPickerSheet, admin-lock banner. Context-dependent labels based on mode.

### Task 8: SettingsViewModel Extension

State management for new split-tunnel config. Load from DataStore, save on change. Handle admin-lock state (disable network editing when locked).

### Task 9: VpnViewModel — Fetch Preset on Connect

Before connecting, call getSplitTunnelPreset(). If source != none, store admin preset in DataStore. If locked, override local network config. Merge with user's app list. Pass to TunnelManager.

### Task 10: TunnelManager — New buildWgConfig Logic

Update buildWgConfig() to handle the new data format:
- Exclude mode: calculate AllowedIPs complement (0.0.0.0/0 minus excluded CIDRs), set excludeApplications
- Include mode: AllowedIPs = defined CIDRs only, set includeApplications if apps defined
- Always add VPN subnet (10.8.0.0/24) to AllowedIPs regardless of mode

### Task 11: Push + CI + Deploy

Push, watch CI (Security + Build), fix failures. New APK release.
