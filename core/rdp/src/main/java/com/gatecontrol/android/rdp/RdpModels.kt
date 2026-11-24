package com.gatecontrol.android.rdp

data class RdpSession(
    val routeId: Int,
    val sessionId: Int,
    val host: String,
    val startTime: Long,
    val isExternal: Boolean,
    val active: Boolean = true
)

enum class CredentialMode { FULL, USER_ONLY, NONE }

/** The steps of [RdpManager.connect] in their order (the sheet shows them by [step]). */
enum class RdpProgress(val step: Int) {
    VPN_CHECK(1),
    TCP_CHECK(2),
    /** GET /connect runs: for a PC registered with Fernzugriff the server waits for the PC to open access. */
    HOST_ACCESS(3),
    CREDENTIALS(4),
    SESSION_START(5),
    CLIENT_LAUNCH(6),
    SERVICE_START(7),
    COMPLETE(8)
}

data class RdpCredentials(
    val username: String,
    val password: String?,
    val domain: String?
)
