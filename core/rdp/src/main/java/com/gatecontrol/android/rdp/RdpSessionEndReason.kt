package com.gatecontrol.android.rdp

/**
 * The end reason the session window reports ([RdpManager.endServerSession]) — from how far it got:
 * a timeout, a failure, closed before the first picture (cancelled), or a normal end.
 */
object RdpSessionEndReason {
    fun of(hasPicture: Boolean, failed: Boolean, timedOut: Boolean): String = when {
        timedOut -> RdpManager.END_CONNECT_TIMEOUT
        failed -> RdpManager.END_CONNECT_FAILED
        !hasPicture -> RdpManager.END_USER_CANCEL
        else -> RdpManager.END_USER_DISCONNECT
    }
}
