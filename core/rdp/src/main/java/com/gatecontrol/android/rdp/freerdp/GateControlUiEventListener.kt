package com.gatecontrol.android.rdp.freerdp

import com.freerdp.freerdpcore.services.LibFreeRDP
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Bridge between [com.freerdp.freerdpcore.services.LibFreeRDP.UIEventListener]
 * (called on the FreeRDP network thread) and our Kotlin-side `StateFlow`.
 *
 * All incoming Java calls publish to [eventFlow]. Certificate verification and
 * authentication callbacks are synchronous contracts from FreeRDP's point of
 * view — they must return an answer on the calling thread — so we delegate to
 * blocking callbacks supplied by the activity.
 *
 * @param eventFlow the flow that receives every non-blocking event
 * @param verifyCertificate blocking callback; receives the unknown-cert event
 *                          and the changed-cert event (the other param is null
 *                          depending on which case fired) and returns
 *                          `0`=reject, `1`=accept once, `2`=accept and store
 * @param authenticate blocking callback; mutates the supplied StringBuilder
 *                     username/password and returns `true` if the user supplied
 *                     credentials
 */
class GateControlUiEventListener(
    private val eventFlow: MutableStateFlow<RdpSessionEvent>,
    private val verifyCertificate: (
        unknown: RdpSessionEvent.VerifyCertificate?,
        changed: RdpSessionEvent.VerifyChangedCertificate?,
    ) -> Int,
    private val authenticate: (
        username: StringBuilder,
        password: StringBuilder,
    ) -> Boolean,
) : LibFreeRDP.UIEventListener {

    override fun OnSettingsChanged(width: Int, height: Int, bpp: Int) {
        eventFlow.value = RdpSessionEvent.SettingsChanged(width, height, bpp)
    }

    override fun OnAuthenticate(
        username: StringBuilder,
        domain: StringBuilder,
        password: StringBuilder,
    ): Boolean {
        eventFlow.value = RdpSessionEvent.AuthenticationRequired(gateway = false)
        return authenticate(username, password)
    }

    override fun OnGatewayAuthenticate(
        username: StringBuilder,
        domain: StringBuilder,
        password: StringBuilder,
    ): Boolean {
        eventFlow.value = RdpSessionEvent.AuthenticationRequired(gateway = true)
        return authenticate(username, password)
    }

    override fun OnVerifiyCertificateEx(
        host: String,
        port: Long,
        commonName: String,
        subject: String,
        issuer: String,
        fingerprint: String,
        flags: Long,
    ): Int {
        val unknown = RdpSessionEvent.VerifyCertificate(
            host, port, commonName, subject, issuer, fingerprint, flags
        )
        eventFlow.value = unknown
        return verifyCertificate(unknown, null)
    }

    override fun OnVerifyChangedCertificateEx(
        host: String,
        port: Long,
        commonName: String,
        subject: String,
        issuer: String,
        fingerprint: String,
        oldSubject: String,
        oldIssuer: String,
        oldFingerprint: String,
        flags: Long,
    ): Int {
        val changed = RdpSessionEvent.VerifyChangedCertificate(
            host, port, commonName, subject, issuer, fingerprint,
            oldSubject, oldIssuer, oldFingerprint, flags
        )
        eventFlow.value = changed
        return verifyCertificate(null, changed)
    }

    override fun OnGraphicsUpdate(x: Int, y: Int, width: Int, height: Int) {
        eventFlow.value = RdpSessionEvent.GraphicsUpdate(x, y, width, height)
    }

    override fun OnGraphicsResize(width: Int, height: Int, bpp: Int) {
        eventFlow.value = RdpSessionEvent.GraphicsResize(width, height, bpp)
    }

    override fun OnRemoteClipboardChanged(data: String?) {
        // MVP: ignore remote clipboard updates
    }
}
