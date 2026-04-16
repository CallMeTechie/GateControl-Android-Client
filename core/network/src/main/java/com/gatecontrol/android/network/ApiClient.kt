package com.gatecontrol.android.network

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface ApiClient {

    @GET("api/v1/client/ping")
    suspend fun ping(): PingResponse

    @GET("api/v1/client/permissions")
    suspend fun getPermissions(): PermissionsResponse

    @POST("api/v1/client/register")
    suspend fun register(@Body request: RegisterRequest): RegisterResponse

    @GET("api/v1/client/config")
    suspend fun getConfig(@Query("peerId") peerId: Int): ConfigResponse

    @GET("api/v1/client/config/check")
    suspend fun checkConfigUpdate(
        @Query("peerId") peerId: Int,
        @Query("hash") hash: String? = null
    ): ConfigCheckResponse

    @POST("api/v1/client/heartbeat")
    suspend fun sendHeartbeat(@Body request: HeartbeatRequest): SimpleResponse

    @POST("api/v1/client/peer/hostname")
    suspend fun reportHostname(@Body request: HostnameReportRequest): HostnameReportResponse

    @POST("api/v1/client/status")
    suspend fun reportStatus(@Body request: StatusRequest): SimpleResponse

    @GET("api/v1/client/peer-info")
    suspend fun getPeerInfo(@Query("peerId") peerId: Int): PeerInfoResponse

    @GET("api/v1/client/traffic")
    suspend fun getTraffic(@Query("peerId") peerId: Int): TrafficResponse

    @GET("api/v1/client/services")
    suspend fun getServices(): ServicesResponse

    @GET("api/v1/client/dns-check")
    suspend fun dnsCheck(): DnsCheckResponse

    @GET("api/v1/client/split-tunnel")
    suspend fun getSplitTunnelPreset(): SplitTunnelPresetResponse

    @GET("api/v1/client/rdp")
    suspend fun getRdpRoutes(): RdpRoutesResponse

    @GET("api/v1/client/rdp/{id}/connect")
    suspend fun getRdpConnection(
        @Path("id") routeId: Int,
        @Query("ecdhPublicKey") ecdhPublicKey: String? = null
    ): RdpConnectResponse

    @POST("api/v1/client/rdp/{id}/session")
    suspend fun startRdpSession(@Path("id") routeId: Int): RdpSessionResponse

    @PATCH("api/v1/client/rdp/{id}/session")
    suspend fun rdpHeartbeat(
        @Path("id") routeId: Int,
        @Body request: RdpHeartbeatRequest
    ): SimpleResponse

    @HTTP(method = "DELETE", path = "api/v1/client/rdp/{id}/session", hasBody = true)
    suspend fun endRdpSession(
        @Path("id") routeId: Int,
        @Body request: RdpEndSessionRequest
    ): RdpSessionResponse

    @POST("api/v1/rdp/{id}/sessions/disconnect-all")
    suspend fun disconnectAllSessions(@Path("id") routeId: Int): SimpleResponse

    @POST("api/v1/rdp/{id}/wol")
    suspend fun sendWol(@Path("id") routeId: Int): WolResponse

    @GET("api/v1/client/rdp/{id}/status")
    suspend fun getRdpRouteStatus(@Path("id") routeId: Int): RdpRouteStatusResponse

    @GET("api/v1/client/update/check")
    suspend fun checkUpdate(
        @Query("version") version: String,
        @Query("platform") platform: String = "android",
        @Query("client") client: String = "gatecontrol"
    ): UpdateCheckResponse
}
