package com.gatecontrol.android.tv.input

import java.util.concurrent.ConcurrentHashMap

/**
 * Whether a text field of GateControl TV has the focus right now (the system keyboard and its dictation are on it).
 * The activity asks before it locks what the TV PIN opened ([com.gatecontrol.android.tv.security.TvPinLeave]).
 */
object TvTyping {
    private val focused = ConcurrentHashMap.newKeySet<Any>()

    val active: Boolean get() = focused.isNotEmpty()

    /** Field [key] got ([has]) or lost the focus; a field that leaves the screen calls it with false. */
    fun focus(key: Any, has: Boolean) {
        if (has) focused.add(key) else focused.remove(key)
    }

    /** Tests. */
    internal fun reset() = focused.clear()
}
