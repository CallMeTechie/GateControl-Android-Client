package com.gatecontrol.android.network

import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName

// --- Responses ---

data class PingResponse(
    val ok: Boolean,
    val version: String,
    val timestamp: String
)

data class PermissionsResponse(
    val ok: Boolean,
    val permissions: PermissionFlags,
    val scopes: List<String>,
    @SerializedName("portalUrl") val portalUrl: String? = null,
    @SerializedName("autoOpenPortal") val autoOpenPortal: Boolean = false,
    /** Client policy version (see [ClientPolicyResponse]); null on old servers. */
    @SerializedName("policyVersion") val policyVersion: String? = null,
)

data class PermissionFlags(
    val services: Boolean,
    val traffic: Boolean,
    val dns: Boolean,
    val rdp: Boolean,
    val pihole: Boolean = false,
    val piholeControl: Boolean = false
)

data class RegisterResponse(
    val ok: Boolean,
    val peerId: Int,
    val peerName: String?,
    val config: String?,
    val hash: String?
)

data class ConfigResponse(
    val ok: Boolean,
    val config: String,
    val hash: String,
    val peerName: String?
)

data class ConfigCheckResponse(
    val ok: Boolean,
    val updated: Boolean,
    val config: String?,
    val hash: String?
)

data class PeerInfoResponse(
    val ok: Boolean,
    val peer: PeerInfo
)

data class PeerInfo(
    val id: Int,
    val name: String,
    val enabled: Boolean,
    @SerializedName("expiresAt") val expiresAt: String?,
    @SerializedName("createdAt") val createdAt: String?
)

data class TrafficResponse(
    val ok: Boolean,
    val traffic: TrafficStats
)

data class TrafficStats(
    val total: TrafficPeriod,
    @SerializedName("last24h") val last24h: TrafficPeriod,
    @SerializedName("last7d") val last7d: TrafficPeriod,
    @SerializedName("last30d") val last30d: TrafficPeriod
)

data class TrafficPeriod(
    val rx: Long,
    val tx: Long
)

data class ServicesResponse(
    val ok: Boolean,
    val services: List<VpnService>
)

data class VpnService(
    val id: Int,
    val name: String,
    val domain: String,
    val url: String,
    val hasAuth: Boolean,
    /** Route monitoring: "up" | "down"; null when not monitored or an older server. */
    val status: String? = null,
    /** "route" (GateControl login page) | "basic" | "none"; null on older servers. */
    val auth: String? = null,
    /** The route allows the automatic login from the apps ([ServiceOpenResponse]). */
    val appLogin: Boolean? = null,
)

/**
 * Answer of `POST /client/services/{id}/open`: [url] is a one-time login
 * link when [autoLogin], else the route's address.
 */
data class ServiceOpenResponse(
    val ok: Boolean = false,
    @SerializedName("url") val url: String? = null,
    @SerializedName("autoLogin") val autoLogin: Boolean? = null,
    @SerializedName("expiresIn") val expiresIn: Int? = null,
) {
    // The ticket must not end up in a log line through an accidental toString().
    override fun toString(): String = "ServiceOpenResponse(ok=$ok, url=${if (url == null) "null" else "[REDACTED]"}, autoLogin=$autoLogin)"
}

data class DnsCheckResponse(
    val ok: Boolean,
    val vpnSubnet: String,
    val vpnDns: String,
    val gatewayIp: String
)

data class UpdateCheckResponse(
    val ok: Boolean,
    val available: Boolean,
    val version: String?,
    val downloadUrl: String?,
    val fileName: String?,
    val fileSize: Long?,
    val releaseNotes: String?
)

// --- Split Tunnel ---

data class SplitTunnelNetwork(
    val cidr: String,
    val label: String
)

data class SplitTunnelPresetResponse(
    val ok: Boolean,
    val mode: String,
    val networks: List<SplitTunnelNetwork>,
    val locked: Boolean,
    val source: String
)

// --- RDP ---

data class RdpRoutesResponse(
    val ok: Boolean,
    val routes: List<RdpRoute>
)

data class RdpRoute(
    val id: Int,
    val name: String,
    val host: String,
    val port: Int,
    @SerializedName("external_hostname") val externalHostname: String?,
    @SerializedName("external_port") val externalPort: Int?,
    @SerializedName("access_mode") val accessMode: String,
    @SerializedName("credential_mode") val credentialMode: String,
    val domain: String?,
    @SerializedName("resolution_mode") val resolutionMode: String?,
    @SerializedName("resolution_width") val resolutionWidth: Int?,
    @SerializedName("resolution_height") val resolutionHeight: Int?,
    @SerializedName("multi_monitor") val multiMonitor: Boolean?,
    @SerializedName("color_depth") val colorDepth: Int?,
    @SerializedName("redirect_clipboard") val redirectClipboard: Boolean?,
    @SerializedName("redirect_printers") val redirectPrinters: Boolean?,
    @SerializedName("redirect_drives") val redirectDrives: Boolean?,
    @SerializedName("audio_mode") val audioMode: String?,
    @SerializedName("network_profile") val networkProfile: String?,
    @SerializedName("session_timeout") val sessionTimeout: Int?,
    @SerializedName("admin_session") val adminSession: Boolean?,
    @SerializedName("wol_enabled") val wolEnabled: Boolean?,
    @SerializedName("maintenance_enabled") val maintenanceEnabled: Boolean?,
    val status: RdpRouteStatus?,
    @SerializedName("connect_address") val connectAddress: String? = null,
    @SerializedName("connect_port") val connectPort: Int? = null
)

data class RdpRouteStatus(
    val online: Boolean,
    val lastCheck: String?
)

data class RdpConnectResponse(
    val ok: Boolean,
    val connection: RdpConnection
)

data class RdpConnection(
    val id: Int,
    val name: String,
    val host: String,
    val port: Int,
    @SerializedName("credential_mode") val credentialMode: String,
    val domain: String?,
    @SerializedName("credentials_e2ee") val credentialsE2ee: E2eePayload?
)

data class E2eePayload(
    val data: String,
    val iv: String,
    val authTag: String,
    val serverPublicKey: String
)

data class RdpSessionResponse(
    val ok: Boolean,
    val session: RdpSession?
)

data class RdpSession(
    val id: Int,
    @SerializedName("route_id") val routeId: Int,
    @SerializedName("started_at") val startedAt: String,
    @SerializedName("ended_at") val endedAt: String?
)

// --- Requests ---

data class RegisterRequest(
    val hostname: String,
    val platform: String,
    val clientVersion: String,
    val peerId: Int? = null
)

data class EnrollRequest(
    val code: String,
    val hostname: String,
    val platform: String,
    val clientVersion: String,
    /** Device fingerprint for machine binding (also sent as X-Machine-Fingerprint). */
    val fingerprint: String,
)

data class EnrollResponse(
    val ok: Boolean,
    val token: String? = null,
    val peerId: Int? = null,
    val peerName: String? = null,
    val config: String? = null,
    val hash: String? = null,
    val scopes: List<String>? = null,
    val error: String? = null,
)

data class HeartbeatRequest(
    val peerId: Int,
    val connected: Boolean,
    val rxBytes: Long,
    val txBytes: Long,
    val uptime: Long,
    val hostname: String
)

data class HostnameReportRequest(
    val hostname: String
)

data class HostnameReportResponse(
    val ok: Boolean,
    val assigned: String? = null,
    val changed: Boolean? = null,
    val error: String? = null
)

data class RdpHeartbeatRequest(
    val sessionId: Int
)

data class RdpEndSessionRequest(
    val sessionId: Int,
    val endReason: String
)

data class WolResponse(
    val ok: Boolean,
    val error: String? = null
)

data class RdpRouteStatusResponse(
    val ok: Boolean,
    val status: RdpRouteStatus?
)

/** Reply to /client/heartbeat; peerEnabled=false means the admin disabled this peer. */
data class HeartbeatResponse(
    val ok: Boolean,
    val peerEnabled: Boolean? = null,
    /** Client policy version; differs from the cached one → refetch the policy. */
    val policyVersion: String? = null,
    /** An admin asked this device for a support bundle (the user is asked first). */
    val supportBundleRequested: Boolean? = null,
    /** When the admin asked (server time, UTC) — a new value means a new request. */
    val supportBundleRequestedAt: String? = null,
)

/** Reply to /client/policy: the effective client policy for this token's peer. */
data class ClientPolicyResponse(
    val ok: Boolean,
    val version: String? = null,
    val managed: Boolean? = null,
    val policy: ClientPolicyPayload? = null,
)

data class ClientPolicyPayload(
    val killSwitch: String? = null,
    val autoConnect: String? = null,
    val autostart: String? = null,
    val splitTunnelModes: List<String>? = null,
    val splitTunnelLocked: Boolean? = null,
    val lockSettings: Boolean? = null,
    val lockServer: Boolean? = null,
)

/**
 * Reply to /client/home-network. [sameLan] is true when the device is in
 * the server's own LAN (only for a request through the VPN tunnel whose
 * WireGuard endpoint lies inside that LAN) or, from core 1.167 on, at the
 * site of one of its gateways (same public address as the gateway and a
 * `local` address inside a LAN the gateway routes; also for direct
 * requests). Missing on servers that predate it.
 */
data class HomeNetworkResponse(
    val ok: Boolean = false,
    val lan: List<String>? = null,
    @SerializedName("same_lan") val sameLan: Boolean? = null,
    /** "tunnel" or "direct": how the request reached the server. */
    val via: String? = null,
    /**
     * Core 1.167+: `null` or `{type: "server" | "gateway", gatewayId?, name?}`.
     * Kept raw on purpose: an explicit `null` (a server that knows gateway
     * sites) must stay distinguishable from a missing key (an older one),
     * see [parseHomeSite].
     */
    val site: JsonElement? = null,
)

data class SupportBundleUploadResponse(
    val ok: Boolean,
    val bundle: SupportBundleInfo? = null,
    val error: String? = null,
)

data class SupportBundleInfo(
    val id: Long,
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("size_bytes") val sizeBytes: Long = 0,
)

data class SimpleResponse(
    val ok: Boolean,
    val error: String? = null
)

// ─── Pi-hole (Phase 2) ───────────────────────────────────────────────

data class PiholeSummaryResponse(
    val ok: Boolean,
    val data: PiholeSummary? = null
)

data class PiholeSummary(
    val queries: PiholeQueries? = null,
    val gravity: Long? = null,
    val clients: PiholeClients? = null,
    val blocking: PiholeBlocking? = null,
    val attribution: String? = null,
    @SerializedName("lastSyncAt") val lastSyncAt: Long? = null
)

data class PiholeQueries(
    val total: Long = 0,
    val blocked: Long = 0,
    val percent: Double = 0.0
)

data class PiholeClients(
    val active: Int = 0
)

data class PiholeBlocking(
    val state: String = "unknown",
    val timer: Long? = null
)

data class PiholeHistoryResponse(
    val ok: Boolean,
    val data: List<PiholeHistoryPoint>? = null
)

data class PiholeHistoryPoint(
    val t: Long,
    val allowed: Long,
    val blocked: Long
)

data class PiholeTopDomainsResponse(
    val ok: Boolean,
    val data: List<PiholeTopDomain>? = null
)

data class PiholeTopDomain(
    val domain: String,
    val count: Long
)

data class PiholeTopClientsResponse(
    val ok: Boolean,
    val data: List<PiholeTopClient>? = null
)

data class PiholeTopClient(
    val ip: String,
    val count: Long,
    val peerId: Int? = null,
    val peerName: String? = null
)

data class PiholeQueryTypesResponse(
    val ok: Boolean,
    val data: Map<String, Long>? = null
)

data class PiholeHealthResponse(
    val ok: Boolean,
    val data: PiholeHealth? = null
)

data class PiholeHealth(
    val instances: List<PiholeInstance>? = null,
    val attribution: String? = null,
    @SerializedName("lastSyncAt") val lastSyncAt: Long? = null
)

data class PiholeInstance(
    val id: String,
    val connected: Boolean,
    val error: String? = null
)

data class PiholeBlockingRequest(
    val enabled: Boolean,
    val timer: Int? = null
)

/**
 * One-time portal login link (`POST /client/portal-link`). [url] carries a
 * single-use ticket: never log, store or report it.
 */
data class PortalLinkResponse(
    val ok: Boolean = false,
    @SerializedName("url") val url: String? = null,
    @SerializedName("expiresIn") val expiresIn: Int? = null,
) {
    // The ticket must not end up in a log line through an accidental toString().
    override fun toString(): String = "PortalLinkResponse(ok=$ok, url=${if (url == null) "null" else "[REDACTED]"}, expiresIn=$expiresIn)"
}

/**
 * Body of `POST /client/portal-link`. [next] is the portal tab to land on
 * after the automatic login (`/portal`, `/portal#<tab>`, `/#<tab>`); the
 * server checks it and falls back to `/portal`. Older servers ignore it.
 * Null is left out of the JSON (`{}`).
 */
data class PortalLinkRequest(
    @SerializedName("next") val next: String? = null,
)

/**
 * A plugin "quick action" (`GET /client/actions`): a button, or a switch
 * with [state] "on"/"off". [confirm]: ask before running.
 */
data class QuickAction(
    @SerializedName("plugin") val plugin: String? = null,
    @SerializedName("pluginName") val pluginName: String? = null,
    @SerializedName("id") val id: String? = null,
    @SerializedName("title") val title: String? = null,
    @SerializedName("subtitle") val subtitle: String? = null,
    @SerializedName("icon") val icon: String? = null,
    @SerializedName("kind") val kind: String? = null,
    @SerializedName("state") val state: String? = null,
    @SerializedName("confirm") val confirm: Boolean? = null,
    /** Extra information and controls (core 1.171+), null = the action is a plain button/switch. */
    @SerializedName("widget") val widget: QuickActionWidget? = null,
    /** The thing the action belongs to (core 1.172+); actions of one device share its id. */
    @SerializedName("device") val device: QuickActionDevice? = null,
    /** Switches only (core 1.172+): icon and tone per state, e.g. a red open lock while off. */
    @SerializedName("look") val look: QuickActionLook? = null,
    /** Switches only (core 1.172+): ask first only before switching to this state ("on" | "off"). */
    @SerializedName("confirmWhen") val confirmWhen: String? = null,
)

data class QuickActionDevice(
    @SerializedName("id") val id: String? = null,
    @SerializedName("title") val title: String? = null,
    @SerializedName("subtitle") val subtitle: String? = null,
    @SerializedName("icon") val icon: String? = null,
)

data class QuickActionLook(
    @SerializedName("on") val on: QuickActionLookState? = null,
    @SerializedName("off") val off: QuickActionLookState? = null,
)

/** [tone]: good | warn | crit | neutral. */
data class QuickActionLookState(
    @SerializedName("icon") val icon: String? = null,
    @SerializedName("tone") val tone: String? = null,
)

/**
 * The widget side of a quick action: a reading ([value] as a JSON string or
 * number, [unit], named [label]), a [status] line, a [state]
 * on|off|good|warn|crit, small extra [readings] and up to four [controls].
 * [power] false: the action carrying it is not the device's power switch.
 */
data class QuickActionWidget(
    @SerializedName("value") val value: JsonElement? = null,
    @SerializedName("unit") val unit: String? = null,
    @SerializedName("status") val status: String? = null,
    @SerializedName("state") val state: String? = null,
    @SerializedName("controls") val controls: List<QuickActionControl>? = null,
    @SerializedName("label") val label: String? = null,
    @SerializedName("readings") val readings: List<QuickActionReading>? = null,
    @SerializedName("power") val power: Boolean? = null,
)

/** A small value of a widget or tile: icon and/or value with unit, coloured by [state]. */
data class QuickActionReading(
    @SerializedName("id") val id: String? = null,
    @SerializedName("label") val label: String? = null,
    @SerializedName("value") val value: JsonElement? = null,
    @SerializedName("unit") val unit: String? = null,
    @SerializedName("icon") val icon: String? = null,
    @SerializedName("state") val state: String? = null,
)

/**
 * One control of a widget: `stepper` and `slider` carry a number in
 * [min]..[max] with [step]; `choice` carries an option id in [value] (as a
 * JSON string) and its [options].
 */
data class QuickActionControl(
    @SerializedName("id") val id: String? = null,
    @SerializedName("kind") val kind: String? = null,
    @SerializedName("label") val label: String? = null,
    @SerializedName("value") val value: JsonElement? = null,
    @SerializedName("min") val min: Double? = null,
    @SerializedName("max") val max: Double? = null,
    @SerializedName("step") val step: Double? = null,
    @SerializedName("unit") val unit: String? = null,
    @SerializedName("options") val options: List<QuickActionControlOption>? = null,
)

data class QuickActionControlOption(
    @SerializedName("id") val id: String? = null,
    @SerializedName("label") val label: String? = null,
    /** SVG path (24×24) when the server sends one; the app knows common modes itself. */
    @SerializedName("icon") val icon: String? = null,
)

data class QuickActionsResponse(
    val ok: Boolean = false,
    val actions: List<QuickAction>? = null,
)

/**
 * One start tile of a plugin (`GET /api/v1/client/tiles`). [key] is stable
 * (`<pluginId>/<section>/<title>`); [value] may be a JSON string or number
 * (kept as the raw element), [state] one of on|off|good|warn|crit or null,
 * [icon] an SVG path `d` for a 24×24 viewBox.
 */
data class ClientTile(
    @SerializedName("key") val key: String? = null,
    @SerializedName("plugin") val plugin: String? = null,
    @SerializedName("pluginName") val pluginName: String? = null,
    @SerializedName("section") val section: String? = null,
    @SerializedName("title") val title: String? = null,
    @SerializedName("value") val value: com.google.gson.JsonElement? = null,
    @SerializedName("unit") val unit: String? = null,
    @SerializedName("state") val state: String? = null,
    @SerializedName("icon") val icon: String? = null,
    /** Icon values shown instead of [value] (core 1.172+), e.g. battery 78 %, a green lock. */
    @SerializedName("items") val items: List<QuickActionReading>? = null,
)

data class ClientTilesResponse(
    val ok: Boolean = false,
    val tiles: List<ClientTile>? = null,
)

/**
 * A plugin that answers the apps (`GET /api/v1/client/plugins`). [tab] is
 * null when the plugin has no own app tab (API only) or hides it from this
 * user.
 */
data class ClientPlugin(
    val id: String? = null,
    val name: String? = null,
    val version: String? = null,
    val tab: ClientPluginTab? = null,
)

/** Own app tab of a plugin: label in the requested language and an SVG path (24×24 stroke icon). */
data class ClientPluginTab(
    val label: String? = null,
    val icon: String? = null,
)

data class ClientPluginsResponse(
    val ok: Boolean = false,
    val plugins: List<ClientPlugin>? = null,
)

/**
 * Body of `POST /client/actions/{plugin}/{id}`: [source] `app` |
 * `notification`; [control] and [value] (a number, or an option id) when a
 * widget control was used (core 1.171+), both null for a plain run.
 */
data class QuickActionRunRequest(
    @SerializedName("source") val source: String = SOURCE_APP,
    @SerializedName("control") val control: String? = null,
    @SerializedName("value") val value: Any? = null,
) {
    companion object {
        const val SOURCE_APP = "app"
        const val SOURCE_NOTIFICATION = "notification"
    }
}

/**
 * Answer of `POST /client/actions/{plugin}/{id}`; [state] = the switch's new
 * state, [widget] = the widget after the run (core 1.171+, else null).
 */
data class QuickActionRunResponse(
    val ok: Boolean = false,
    val message: String? = null,
    val state: String? = null,
    val error: String? = null,
    val widget: QuickActionWidget? = null,
)

/**
 * Location sharing on tap (`/api/v1/client/location`). [allowed] = the
 * administrator lets this device's user share it; [last] = the last
 * position the server keeps for this device.
 */
data class LocationStatusResponse(
    val ok: Boolean = false,
    val allowed: Boolean = false,
    val last: LocationFix? = null,
    val keep: Int? = null,
    @SerializedName("retention_days") val retentionDays: Int? = null,
)

/** One position as the server stores it; [at] = epoch ms. */
data class LocationFix(
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val accuracy: Double? = null,
    val at: Long = 0,
)

data class LocationReportRequest(
    val lat: Double,
    val lng: Double,
    val accuracy: Double?,
    val altitude: Double?,
    val provider: String?,
    @SerializedName("captured_at") val capturedAt: Long,
)

data class LocationReportResponse(
    val ok: Boolean = false,
    val at: Long? = null,
    val error: String? = null,
)

data class LocationDeleteResponse(
    val ok: Boolean = false,
    val deleted: Int? = null,
)

/**
 * Speed test limits of the own server (`GET /client/speedtest`, 404 on
 * older servers). [viaTunnel] = this request reached the server through
 * the VPN tunnel.
 */
data class SpeedTestInfoResponse(
    val ok: Boolean = false,
    @SerializedName("maxDownloadBytes") val maxDownloadBytes: Long = 0,
    @SerializedName("maxUploadBytes") val maxUploadBytes: Long = 0,
    @SerializedName("viaTunnel") val viaTunnel: Boolean? = null,
)

/** Answer of `POST /client/speedtest/upload`: bytes received, server-side duration. */
data class SpeedTestUploadResponse(
    val ok: Boolean = false,
    val bytes: Long = 0,
    @SerializedName("serverMs") val serverMs: Long? = null,
)
