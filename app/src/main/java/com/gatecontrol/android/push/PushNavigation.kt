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

    companion object {
        /**
         * App route of the contract → screen. `gateways` and plugin routes
         * (`plg-<id>`) have no screen in the app: the message itself opens.
         */
        fun forRoute(route: String?, messageId: Long?): PushNavTarget = when (route) {
            "vpn" -> Vpn
            "services" -> Services
            "inbox" -> Inbox
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
