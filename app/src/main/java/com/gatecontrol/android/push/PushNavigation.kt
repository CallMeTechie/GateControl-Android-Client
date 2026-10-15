package com.gatecontrol.android.push

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where a tapped notification (or one of its actions) wants to go in the app. */
sealed interface PushNavTarget {
    data object Inbox : PushNavTarget
    data class Detail(val id: Long) : PushNavTarget
    data object Vpn : PushNavTarget
    data object Services : PushNavTarget

    /**
     * A screen of the Familie tab ([dest] = FamilieDest key). Without the
     * tab (plugin gone meanwhile) the message [messageId] opens instead.
     */
    data class Familie(val dest: String, val messageId: Long? = null) : PushNavTarget

    /**
     * A screen of the door tab ([dest] = DoorDest key: a ring's detail or
     * the tab). Without the tab (plugin gone) the message [messageId] opens.
     */
    data class Door(val dest: String, val messageId: Long? = null) : PushNavTarget

    companion object {
        /** App route of the Familie plugin (`plg-gatecontrol-familie`). */
        const val FAMILIE_ROUTE = "plg-gatecontrol-familie"

        /** App route of the door plugin (`plg-gatecontrol-door`). */
        const val DOOR_ROUTE = "plg-gatecontrol-door"

        /**
         * App route of the contract → screen. `gateways` and other plugin
         * routes (`plg-<id>`) have no screen in the app: the message itself
         * opens. The Familie and door plugins have their own tabs.
         */
        fun forRoute(route: String?, messageId: Long?, doorDest: String? = null): PushNavTarget = when (route) {
            "vpn" -> Vpn
            "services" -> Services
            "inbox" -> Inbox
            FAMILIE_ROUTE -> Familie("home", messageId?.takeIf { it > 0 })
            DOOR_ROUTE -> Door(doorDest ?: "home", messageId?.takeIf { it > 0 })
            else -> if (messageId != null && messageId > 0) Detail(messageId) else Inbox
        }
    }
}

/**
 * Hand-over from the non-exported [PushOpenActivity] to the navigation:
 * the exported MainActivity deliberately reads no intent extras.
 */
object PushNavigation {
    private val _pending = MutableStateFlow<PushNavTarget?>(null)
    val pending: StateFlow<PushNavTarget?> = _pending.asStateFlow()

    fun request(target: PushNavTarget) {
        _pending.value = target
    }

    fun consume(target: PushNavTarget) {
        _pending.compareAndSet(target, null)
    }
}
