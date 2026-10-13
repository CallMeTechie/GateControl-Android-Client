package com.gatecontrol.android.network

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
 * Reply to /client/home-network. [sameLan] is true only for a request
 * through the VPN tunnel whose WireGuard endpoint lies inside the server's
 * LAN; a direct request always gets false (a reverse proxy or SNAT router
 * can make internet clients look like LAN sources). Missing on servers
 * that predate it.
 */
data class HomeNetworkResponse(
    val ok: Boolean = false,
    val lan: List<String>? = null,
    @SerializedName("same_lan") val sameLan: Boolean? = null,
    /** "tunnel" or "direct": how the request reached the server. */
    val via: String? = null,
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
)

data class QuickActionsResponse(
    val ok: Boolean = false,
    val actions: List<QuickAction>? = null,
)

/** Answer of `POST /client/actions/{plugin}/{id}`; [state] = the switch's new state. */
data class QuickActionRunResponse(
    val ok: Boolean = false,
    val message: String? = null,
    val state: String? = null,
    val error: String? = null,
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
