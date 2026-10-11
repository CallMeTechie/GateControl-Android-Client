package com.gatecontrol.android.ui.notifications

import com.gatecontrol.android.notify.PushSettings

/** What the bell on the start screen shows. */
data class BellState(
    /** No notifications arrive: push is off in the app or Android blocks them. The bell leads to the settings. */
    val pushOff: Boolean = false,
    /** Push is on in the app, but Android blocks GateControl's notifications. */
    val blocked: Boolean = false,
    /** Show the "notifications are off" hint below the header. */
    val showHint: Boolean = false,
)

/**
 * [settings] null = not loaded yet (nothing is shown until it is).
 * The hint appears while push was never switched on, or while it is on but
 * Android blocks the app's notifications. Someone who switched push off on
 * purpose only sees the bell change, not the hint.
 */
fun bellState(settings: PushSettings?, hintDismissed: Boolean, systemAllowed: Boolean): BellState {
    if (settings == null) return BellState()
    val blocked = settings.enabled && !systemAllowed
    val pushOff = !settings.enabled || blocked
    val showHint = !hintDismissed && (blocked || (!settings.enabled && !settings.everEnabled))
    return BellState(pushOff = pushOff, blocked = blocked, showHint = showHint)
}
