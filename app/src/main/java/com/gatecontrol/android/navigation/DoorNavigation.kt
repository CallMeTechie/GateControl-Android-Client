package com.gatecontrol.android.navigation

import com.gatecontrol.android.door.DoorDest

/** One door screen to open: the door screen (with [selected] beside its list on wide windows) or a page on top. */
sealed interface DoorStep {
    data class Main(val selected: DoorDest? = null) : DoorStep
    data class Page(val dest: DoorDest) : DoorStep
}

/** Where door pushes lead (pure, unit-tested; docs/DOOR.md "Push routing"). */
internal object DoorNavigation {

    /**
     * Screens that show [dest], in order. Phones: the door screen, and the
     * history or a ring's detail on top of it (back leads to the door
     * screen, then to where the person was). Wide windows: the door screen
     * with [dest] beside its list.
     */
    fun steps(dest: DoorDest, twoPane: Boolean): List<DoorStep> = when {
        twoPane -> listOf(DoorStep.Main(dest.takeIf { it != DoorDest.Home }))
        dest == DoorDest.Home -> listOf(DoorStep.Main())
        else -> listOf(DoorStep.Main(), DoorStep.Page(dest))
    }

    /**
     * Screens a tapped door push ([destKey] = DoorDest key) opens; null when
     * the plugin is not listed (any more): then the message itself opens.
     */
    fun forPush(destKey: String, pluginListed: Boolean, twoPane: Boolean): List<DoorStep>? {
        if (!pluginListed) return null
        return steps(DoorDest.parse(destKey) ?: DoorDest.Home, twoPane)
    }
}
