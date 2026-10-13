package com.gatecontrol.android.network

import com.google.gson.JsonObject
import retrofit2.Response
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.HTTP
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

interface ApiClient {

    @GET("api/v1/client/ping")
    suspend fun ping(): PingResponse

    /** Ping with an explicit, not yet stored token (side-effect free connection test). */
    @GET("api/v1/client/ping")
    suspend fun pingWithToken(@Header("X-API-Token") token: String): PingResponse

    @GET("api/v1/client/permissions")
    suspend fun getPermissions(): PermissionsResponse

    @POST("api/v1/client/register")
    suspend fun register(@Body request: RegisterRequest): RegisterResponse

    /** Public: trades a one-shot setup code for a peer-bound token + config. */
    @POST("api/v1/client/enroll")
    suspend fun enroll(@Body request: EnrollRequest): EnrollResponse

    @GET("api/v1/client/config")
    suspend fun getConfig(@Query("peerId") peerId: Int): ConfigResponse

    @GET("api/v1/client/config/check")
    suspend fun checkConfigUpdate(
        @Query("peerId") peerId: Int,
        @Query("hash") hash: String? = null
    ): ConfigCheckResponse

    @POST("api/v1/client/heartbeat")
    suspend fun sendHeartbeat(@Body request: HeartbeatRequest): HeartbeatResponse

    @POST("api/v1/client/peer/hostname")
    suspend fun reportHostname(@Body request: HostnameReportRequest): HostnameReportResponse

    /**
     * Upload a redacted support bundle (gzip JSON, see [SupportBundleUploader]).
     * Server: gatecontrol docs/feature-support-bundle.md.
     */
    @POST("api/v1/client/support-bundle")
    suspend fun uploadSupportBundle(
        @Query("peerId") peerId: Int,
        @Body body: RequestBody,
    ): SupportBundleUploadResponse

    /**
     * Mints a one-time automatic login link for the portal. Use
     * [getPortalLink], which validates the answer and never throws.
     */
    @POST("api/v1/client/portal-link")
    suspend fun requestPortalLink(@Body request: PortalLinkRequest = PortalLinkRequest()): Response<PortalLinkResponse>

    @GET("api/v1/client/peer-info")
    suspend fun getPeerInfo(@Query("peerId") peerId: Int): PeerInfoResponse

    @GET("api/v1/client/traffic")
    suspend fun getTraffic(@Query("peerId") peerId: Int): TrafficResponse

    @GET("api/v1/client/services")
    suspend fun getServices(): ServicesResponse

    /**
     * Address to open a service, with a one-time automatic login when the
     * route allows it. Use [getServiceLink], which validates the answer and
     * never throws.
     */
    @POST("api/v1/client/services/{id}/open")
    suspend fun requestServiceLink(@Path("id") id: Int): Response<ServiceOpenResponse>

    /** Quick actions of the plugins for this device's user (404 on older servers). */
    @GET("api/v1/client/actions")
    suspend fun getQuickActions(@Query("lang") lang: String? = null): Response<QuickActionsResponse>

    @POST("api/v1/client/actions/{plugin}/{id}")
    suspend fun runQuickAction(
        @Path("plugin") plugin: String,
        @Path("id") id: String,
        @Query("lang") lang: String? = null,
    ): Response<QuickActionRunResponse>

    /** Location sharing: may this device send, last stored position (404 on older servers). */
    @GET("api/v1/client/location")
    suspend fun getLocationStatus(): Response<LocationStatusResponse>

    /** Sends one position, only after the user tapped the satellite button. */
    @POST("api/v1/client/location")
    suspend fun reportLocation(@Body request: LocationReportRequest): Response<LocationReportResponse>

    /** Deletes every position the server keeps for this device. */
    @HTTP(method = "DELETE", path = "api/v1/client/location")
    suspend fun deleteLocations(): Response<LocationDeleteResponse>

    @GET("api/v1/client/dns-check")
    suspend fun dnsCheck(): DnsCheckResponse

    // ─── Speed test against the own server (diagnosis) ──────────────
    // Run on ApiClientProvider.speedTestClient (longer timeouts), see
    // SpeedTestClient. 404 on servers without the endpoints.

    @GET("api/v1/client/speedtest")
    suspend fun getSpeedTestInfo(): Response<SpeedTestInfoResponse>

    /** [bytes] of filler as application/octet-stream; read it as a stream. */
    @Streaming
    @GET("api/v1/client/speedtest/download")
    suspend fun speedTestDownload(@Query("bytes") bytes: Long): Response<ResponseBody>

    @POST("api/v1/client/speedtest/upload")
    suspend fun speedTestUpload(@Body body: RequestBody): Response<SpeedTestUploadResponse>

    @GET("api/v1/client/split-tunnel")
    suspend fun getSplitTunnelPreset(): SplitTunnelPresetResponse

    /**
     * Is this device in the server's own LAN ("Heimnetz")? The server decides
     * (`same_lan`), and only for requests through the tunnel. Raw
     * [Response]: 404 means an older server without the endpoint. Use
     * [probeHomeNetwork], which classifies and never throws.
     */
    @GET("api/v1/client/home-network")
    suspend fun getHomeNetwork(): Response<HomeNetworkResponse>

    /**
     * Effective client policy. With the cached version as If-None-Match the
     * server answers 304 when nothing changed (hence the raw [Response]).
     */
    @GET("api/v1/client/policy")
    suspend fun getClientPolicy(@Header("If-None-Match") ifNoneMatch: String? = null): Response<ClientPolicyResponse>

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

    // ─── Notification center (push) ─────────────────────────────────
    // The stream itself (GET api/v1/client/push) runs on its own client,
    // see ApiClientProvider.streamingClient and core/notify.

    @POST("api/v1/client/push/ack")
    suspend fun pushAck(@Body request: PushAckRequest): SimpleResponse

    @GET("api/v1/client/push/inbox")
    suspend fun pushInbox(
        @Query("limit") limit: Int = 100,
        @Query("before") before: Long? = null,
    ): PushInboxResponse

    /**
     * Stored device preferences plus the person's quiet hours (read only).
     * Raw JSON: whether `quiet` is missing (older server) or null (none set)
     * matters, see core/notify PushEventParser.quietInfo.
     */
    @GET("api/v1/client/push/prefs")
    suspend fun getPushPrefs(): JsonObject

    @PUT("api/v1/client/push/prefs")
    suspend fun pushPrefs(@Body request: PushPrefsRequest): SimpleResponse

    @POST("api/v1/client/push/test")
    suspend fun pushTest(): PushTestResponse

    @GET("api/v1/pihole/summary")
    suspend fun getPiholeSummary(): PiholeSummaryResponse

    @GET("api/v1/pihole/history")
    suspend fun getPiholeHistory(): PiholeHistoryResponse

    @GET("api/v1/pihole/top-domains")
    suspend fun getPiholeTopDomains(): PiholeTopDomainsResponse

    @GET("api/v1/pihole/top-clients")
    suspend fun getPiholeTopClients(): PiholeTopClientsResponse

    @GET("api/v1/pihole/query-types")
    suspend fun getPiholeQueryTypes(): PiholeQueryTypesResponse

    @GET("api/v1/pihole/health")
    suspend fun getPiholeHealth(): PiholeHealthResponse

    @POST("api/v1/pihole/blocking")
    suspend fun setPiholeBlocking(@Body request: PiholeBlockingRequest): SimpleResponse
}
