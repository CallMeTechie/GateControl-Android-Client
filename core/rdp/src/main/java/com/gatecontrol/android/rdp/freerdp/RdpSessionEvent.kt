package com.gatecontrol.android.rdp.freerdp

/**
 * Events emitted by [GateControlUiEventListener] and observed by
 * [com.gatecontrol.android.rdp.RdpSessionActivity] via a `StateFlow`.
 *
 * All fields are primitives or immutable — the listener runs on the FreeRDP
 * network thread and the consumer is the UI thread.
 */
sealed class RdpSessionEvent {
    object Idle : RdpSessionEvent()
    object PreConnect : RdpSessionEvent()
    data class ConnectionSuccess(val instance: Long) : RdpSessionEvent()
    data class ConnectionFailure(val instance: Long, val reason: String?) : RdpSessionEvent()
    data class Disconnecting(val instance: Long) : RdpSessionEvent()
    data class Disconnected(val instance: Long) : RdpSessionEvent()
    data class GraphicsResize(val width: Int, val height: Int, val bpp: Int) : RdpSessionEvent()
    data class GraphicsUpdate(val x: Int, val y: Int, val width: Int, val height: Int) : RdpSessionEvent()
    data class SettingsChanged(val width: Int, val height: Int, val bpp: Int) : RdpSessionEvent()
    data class VerifyCertificate(
        val host: String,
        val port: Long,
        val commonName: String,
        val subject: String,
        val issuer: String,
        val fingerprint: String,
        val flags: Long,
    ) : RdpSessionEvent()
    data class VerifyChangedCertificate(
        val host: String,
        val port: Long,
        val commonName: String,
        val subject: String,
        val issuer: String,
        val fingerprint: String,
        val oldSubject: String,
        val oldIssuer: String,
        val oldFingerprint: String,
        val flags: Long,
    ) : RdpSessionEvent()
    data class AuthenticationRequired(val gateway: Boolean) : RdpSessionEvent()
}
