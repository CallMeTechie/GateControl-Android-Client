package com.gatecontrol.android.quickactions

import com.gatecontrol.android.door.DOOR_PLUGIN

/**
 * The way to the door screen "Tür & Kamera" for people without a door quick action (pure,
 * unit-tested; docs/DOOR.md "Ways in"). The door plugin has no tab: its screen opens from door
 * pushes and from a long press on its quick actions. Someone who may only see cameras (home
 * cameras of plugin 1.6.0, or Ring cameras without a lock) has no such action — the Start tab
 * then shows the row "Tür & Kamera" with "Kameras ansehen" below its tiles.
 */
object DoorEntry {

    /**
     * Show the row? [doorListed]: the door plugin is listed for this person (it answers the apps
     * only when there is something to see); [page]: null on Start (not on the pages beside it);
     * [loaded]: the quick actions came from the server at least once (no flicker for people with
     * locks while they load); [actions]: every quick action of this person.
     */
    fun show(doorListed: Boolean, page: String?, loaded: Boolean, actions: List<QuickActionItem>): Boolean =
        doorListed && page == null && loaded && actions.none { it.plugin == DOOR_PLUGIN }
}
