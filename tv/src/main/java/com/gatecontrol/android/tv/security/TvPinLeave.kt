package com.gatecontrol.android.tv.security

/** What leaving the front ([android.app.Activity.onStop]) does to the functions the PIN opened. */
enum class TvLeave {
    /** Locked at once: the screen saver, the photo frame, another app, the Home button. */
    LOCK,

    /** Kept: GateControl's own viewer or player opened over it. */
    KEEP,

    /**
     * Kept for a moment: a text field had the focus — the dictation of Fire OS (Alexa) takes the front while one holds
     * the microphone button. Coming back within [TvPinLeave.DICTATION_GRACE_MS] keeps it open, later locks it.
     */
    WAIT,
}

/**
 * Stage 3d known problem 1: dictating sent the app to the background and [TvPinUnlocks.lockAll] locked Nachrichten
 * again in the middle of an answer. Now: while a text field has the keyboard's focus the PIN waits for the return; the
 * screen saver and the screen going off always lock (their own broadcast), the photo frame too. Pure, unit-tested.
 */
object TvPinLeave {
    /** Long enough for a sentence of dictation, short enough that a TV left alone locks. */
    const val DICTATION_GRACE_MS = 3 * 60_000L

    fun onStop(inApp: Boolean, typing: Boolean): TvLeave = when {
        inApp -> TvLeave.KEEP
        typing -> TvLeave.WAIT
        else -> TvLeave.LOCK
    }

    /** Back in front after [TvLeave.WAIT] at [leftAt] (elapsed realtime): lock when it took longer than the grace. */
    fun lockOnReturn(leftAt: Long?, now: Long): Boolean = leftAt != null && now - leftAt > DICTATION_GRACE_MS
}
