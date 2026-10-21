package com.gatecontrol.android.quickactions

import com.gatecontrol.android.logbook.SKODA_PLUGIN
import com.gatecontrol.android.push.PushNavTarget
import com.gatecontrol.android.push.PushNavigation

/**
 * Quick actions that open an app screen instead of running (`opens`, core
 * 1.189+; pure, unit-tested). Only screens the app knows count: the
 * logbook of the vehicles plugin (`gatecontrol-skoda`, screen `logbook`,
 * ref = vehicle id). Any other `opens` is ignored and the action runs as
 * before.
 */
object QuickActionScreens {

    const val LOGBOOK = "logbook"

    /** Where a tap on [item] leads, null = run it. */
    fun target(item: QuickActionItem): PushNavTarget? = when {
        item.plugin == SKODA_PLUGIN && item.opensScreen == LOGBOOK ->
            PushNavTarget.Logbook(item.opensRef?.takeIf { r -> r.length <= 15 && r.all(Char::isDigit) }?.toLongOrNull()?.takeIf { it > 0 })
        else -> null
    }

    fun opensScreen(item: QuickActionItem): Boolean = target(item) != null

    /**
     * Hands the screen of [item] to the navigation ([PushNavigation], picked
     * up by the app's navigation once it shows); false = a normal action.
     */
    fun open(item: QuickActionItem): Boolean {
        val target = target(item) ?: return false
        PushNavigation.request(target)
        return true
    }
}
