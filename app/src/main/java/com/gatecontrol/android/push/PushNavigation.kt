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

    /** RDP hosts: the Remote segment of the Netzwerk tab (until 1.35 an own tab). */
    data object Remote : PushNavTarget

    /**
     * A screen of the Familie tab ([dest] = FamilieDest key). Without the
     * tab (plugin gone meanwhile) the message [messageId] opens instead.
     */
    data class Familie(val dest: String, val messageId: Long? = null) : PushNavTarget

    /**
     * A door screen ([dest] = DoorDest key: a ring's detail, else the door
     * screen "Tür & Kamera" — no tab). Without the plugin (gone meanwhile)
     * the message [messageId] opens.
     */
    data class Door(val dest: String, val messageId: Long? = null) : PushNavTarget

    /**
     * The tab "Chats" (messenger plugin), in [conversationId] when given.
     * Without the tab (plugin gone meanwhile) the start screen opens.
     */
    data class Chat(
        val conversationId: Long? = null,
        /** Messenger 1.4: the message to show (poll, event, reminder, …). */
        val messageId: Long? = null,
        /** Messenger 1.4: `arrival` = offer to share the arrival (departure hint). */
        val open: String? = null,
    ) : PushNavTarget

    /**
     * Shared from another app ("Teilen → GateControl"): pick a chat for
     * [ChatShareHandover]'s content — or straight into [conversationId]
     * (a sharing shortcut of that chat).
     */
    data class ChatShare(val conversationId: Long? = null) : PushNavTarget

    /**
     * The logbook ("Fahrtenbuch") of the vehicles plugin, of [vehicleId]
     * (null = the first own vehicle): a quick action with `opens` (start-tab
     * widget or button, home-screen widget, shortcut, Quick Settings tile).
     */
    data class Logbook(val vehicleId: Long? = null) : PushNavTarget

    /** GateDrop: the screen "Geteilt" on [tab] (received | sent | links). */
    data class Shared(val tab: String = "received") : PushNavTarget

    companion object {
        /** App route of the Familie plugin (`plg-gatecontrol-familie`). */
        const val FAMILIE_ROUTE = "plg-gatecontrol-familie"

        /** App route of GateDrop (`plg-gatecontrol-gatedrop`). */
        const val GATEDROP_ROUTE = "plg-gatecontrol-gatedrop"

        /** App route of the door plugin (`plg-gatecontrol-door`). */
        const val DOOR_ROUTE = "plg-gatecontrol-door"

        /**
         * App route of the contract → screen (`rdp`/`remote`: the Remote
         * segment of the Netzwerk tab). `gateways` and other plugin
         * routes (`plg-<id>`) have no screen in the app: the message itself
         * opens. The Familie plugin has its own tab, the door plugin its
         * door screen (no tab).
         */
        fun forRoute(route: String?, messageId: Long?, doorDest: String? = null): PushNavTarget = when (route) {
            "vpn" -> Vpn
            "services" -> Services
            "rdp", "remote" -> Remote
            "inbox" -> Inbox
            FAMILIE_ROUTE -> Familie("home", messageId?.takeIf { it > 0 })
            DOOR_ROUTE -> Door(doorDest ?: "home", messageId?.takeIf { it > 0 })
            com.gatecontrol.android.chat.MESSENGER_ROUTE -> Chat()
            // GateDrop (deliveries, "Link geladen"): the screen "Geteilt".
            GATEDROP_ROUTE -> Shared()
            else -> if (messageId != null && messageId > 0) Detail(messageId) else Inbox
        }
    }
}

/**
 * Hand-over from the non-exported [PushOpenActivity] (and
 * QuickActionActivity, for quick actions that open a screen) to the
 * navigation: the exported MainActivity deliberately reads no intent extras.
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
