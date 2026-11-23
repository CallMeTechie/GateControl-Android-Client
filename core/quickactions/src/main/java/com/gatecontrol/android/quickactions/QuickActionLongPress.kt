package com.gatecontrol.android.quickactions

import com.gatecontrol.android.door.DOOR_PLUGIN

/**
 * What a long press on a quick action opens (pure, unit-tested): a quick
 * action of the door plugin opens the door screen "Tür & Kamera" (the
 * plugin has no tab, docs/DOOR.md); other plugins' actions have no long
 * press.
 */
object QuickActionLongPress {

    enum class Target { DOOR }

    /** [doorListed]: the door plugin is still listed (else its stored pins open nothing). */
    fun target(item: QuickActionItem, doorListed: Boolean): Target? = when {
        item.plugin == DOOR_PLUGIN && doorListed -> Target.DOOR
        else -> null
    }
}
