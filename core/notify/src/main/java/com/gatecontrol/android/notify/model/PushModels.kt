package com.gatecontrol.android.notify.model

/**
 * Domain models of the notification center. Plain fields only: the inbox
 * cache stores them as JSON (Gson, field names), so renaming a field drops
 * the cached inbox once — never anything else.
 */

/** Priority of a message (contract: info | normal | high | critical). */
enum class PushPriority(val wire: String) {
    INFO("info"),
    NORMAL("normal"),
    HIGH("high"),
    CRITICAL("critical");

    companion object {
        fun fromWire(value: String?): PushPriority =
            entries.firstOrNull { it.wire == value } ?: NORMAL
    }
}

/** Action types of the fixed list in the contract. */
object PushActionType {
    const val OPEN_APP_ROUTE = "open_app_route"
    const val OPEN_PORTAL = "open_portal"
    const val MUTE_1H = "mute_1h"
    const val DONE = "done"
    const val ACK = "ack"

    val ALL = setOf(OPEN_APP_ROUTE, OPEN_PORTAL, MUTE_1H, DONE, ACK)

    /** Actions that only acknowledge (no UI): run from the notification without opening the app. */
    val BACKGROUND = setOf(MUTE_1H, DONE, ACK)
}

/** A button of a message (`data.actions[]`). */
data class PushAction(
    val id: String,
    val label: String,
    val type: String,
    /** App route (open_app_route) or portal path (open_portal). */
    val target: String? = null,
)

/** Delivery state as far as this device knows it. */
object PushState {
    const val DELIVERED = "delivered"
    const val READ = "read"
    const val DISMISSED = "dismissed"
}

/** One notification as shown in the inbox, the shade and the detail screen. */
data class PushMessage(
    /** Delivery sequence (SSE id) — what acks refer to. */
    val seq: Long,
    /** Notification id — what `read`/`revoke` events refer to. */
    val id: Long,
    val eventId: String,
    val topic: String,
    val priority: String,
    val title: String,
    val body: String,
    /** ISO-8601 UTC, without milliseconds. */
    val createdAt: String?,
    val expiresAt: String?,
    val collapseKey: String?,
    val silent: Boolean,
    /** Deep link (`data.route`), an app route. */
    val route: String?,
    val actions: List<PushAction>,
    /** delivered | read | dismissed (see [PushState]). */
    val state: String = PushState.DELIVERED,
    /** Local time (epoch ms) the device got it; 0 = from the server inbox. */
    val receivedAt: Long = 0L,
    /** direct | tunnel — how the stream was connected when it arrived (null = inbox). */
    val via: String? = null,
) {
    val priorityLevel: PushPriority get() = PushPriority.fromWire(priority)
    val isUnread: Boolean get() = state == PushState.DELIVERED
}

/** A topic the device may get (`hello.topics`). */
data class PushTopic(
    val id: String,
    val label: String,
) {
    val isPlugin: Boolean get() = id.startsWith("plugin:")

    /** `admin_notice` (manual messages, tests) cannot be muted. */
    val isLocked: Boolean get() = id == ADMIN_NOTICE

    companion object {
        const val SECURITY = "security"
        const val DEVICES = "devices"
        const val SERVICES = "services"
        const val SYSTEM = "system"
        const val ADMIN_NOTICE = "admin_notice"
        val CORE = listOf(SECURITY, DEVICES, SERVICES, SYSTEM, ADMIN_NOTICE)
    }
}
