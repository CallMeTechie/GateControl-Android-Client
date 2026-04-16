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
    val scopes: List<String>
)

data class PermissionFlags(
    val services: Boolean,
    val traffic: Boolean,
    val dns: Boolean,
    val rdp: Boolean
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
    val hasAuth: Boolean
)

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
    val status: RdpRouteStatus?
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

data class HeartbeatRequest(
    val peerId: Int,
    val connected: Boolean,
    val rxBytes: Long,
    val txBytes: Long,
    val uptime: Long,
    val hostname: String
)

data class StatusRequest(
    val peerId: Int,
    val status: String,
    val timestamp: String
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

data class SimpleResponse(
    val ok: Boolean,
    val error: String? = null
)
