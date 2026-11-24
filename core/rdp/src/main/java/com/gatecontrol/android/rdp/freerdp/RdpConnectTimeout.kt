package com.gatecontrol.android.rdp.freerdp

/**
 * The session window's own connect timeout: FreeRDP may hang without reporting anything (a closed port
 * that drops packets, a PC that never answers). When neither a first picture nor an error came within
 * [MILLIS], the window shows "Verbindung fehlgeschlagen". The clock does not run while a dialog waits
 * for the person (certificate check, credentials), and starts again afterwards.
 */
object RdpConnectTimeout {
    const val MILLIS = 30_000L

    /** A dialog of the session waits for the person: no timeout meanwhile. */
    fun waitsForPerson(event: RdpSessionEvent): Boolean =
        event is RdpSessionEvent.VerifyCertificate ||
            event is RdpSessionEvent.VerifyChangedCertificate ||
            event is RdpSessionEvent.AuthenticationRequired

    /** Should the timeout run now? Only while still connecting (no picture, no failure) and nobody is asked. */
    fun shouldRun(hasPicture: Boolean, failed: Boolean, event: RdpSessionEvent): Boolean =
        !hasPicture && !failed && !waitsForPerson(event)
}
