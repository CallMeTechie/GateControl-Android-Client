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

enum class RdpProgress(val step: Int) {
    VPN_CHECK(1),
    TCP_CHECK(2),
    CREDENTIALS(3),
    CLIENT_LAUNCH(4),
    SESSION_START(5),
    COMPLETE(6)
}

data class RdpCredentials(
    val username: String,
    val password: String?,
    val domain: String?
)
