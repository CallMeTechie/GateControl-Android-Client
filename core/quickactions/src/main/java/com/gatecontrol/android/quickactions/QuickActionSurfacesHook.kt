package com.gatecontrol.android.quickactions

/**
 * What else shows the pinned quick actions besides the start screen: on the phone app shortcuts, Quick Settings tiles
 * and the home-screen widget (`QuickActionSurfaces`), on the TV nothing. Called after every change of the pins, off
 * the main thread.
 */
fun interface QuickActionSurfacesHook {
    suspend fun pinsChanged(pins: List<QuickActionItem>)

    companion object {
        /** No other surfaces (TV, tests). */
        val NONE = QuickActionSurfacesHook { }
    }
}
