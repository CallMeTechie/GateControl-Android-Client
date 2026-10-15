package com.gatecontrol.android.door

import com.gatecontrol.android.door.DoorJson.id
import com.gatecontrol.android.door.DoorJson.obj
import com.gatecontrol.android.door.DoorJson.str
import com.gatecontrol.android.notify.model.PushMessage
import com.google.gson.JsonParser

/** `data.door` of a door push (README "Pushes"). */
data class DoorPushInfo(
    /** ding | motion | auto_unlocked | auto_refused | auto_relocked | auto_relock_failed | auto_ask | open_long | jammed | unreachable | battery | night | ring_broken */
    val type: String,
    val eventId: Long?,
    val cameraId: Long?,
    val lockId: Long?,
) {
    val isRing: Boolean get() = type == DING || type == MOTION

    companion object {
        const val DING = "ding"
        const val MOTION = "motion"
    }
}

/** A screen of the door tab. */
sealed interface DoorDest {
    val key: String

    data object Home : DoorDest {
        override val key = "home"
    }

    data object History : DoorDest {
        override val key = "history"
    }

    /** One ring / motion event with its picture (push "Ansehen"). */
    data class Event(val eventId: Long) : DoorDest {
        override val key get() = "event:$eventId"
    }

    companion object {
        fun parse(key: String?): DoorDest? = when {
            key == null -> null
            key == Home.key -> Home
            key == History.key -> History
            key.startsWith("event:") -> key.removePrefix("event:").takeIf { it.length in 1..15 && it.all(Char::isDigit) }
                ?.toLong()?.takeIf { it > 0 }?.let { Event(it) }
            else -> null
        }
    }
}

/** Reads door pushes and decides where a tap leads. Pure, unit-tested. */
object DoorPush {

    const val ROUTE = "plg-$DOOR_PLUGIN"
    private const val TOPIC_PREFIX = "plugin:$DOOR_PLUGIN:"
    private val TYPE_RE = Regex("^[a-z][a-z0-9_]{0,31}$")

    fun isDoorMessage(message: PushMessage): Boolean =
        message.topic.startsWith(TOPIC_PREFIX) || message.route == ROUTE

    /** `data.door` of a door message, null for other senders or without it. */
    fun parse(message: PushMessage): DoorPushInfo? {
        if (!isDoorMessage(message)) return null
        val data = message.dataJson ?: return null
        val door = runCatching { JsonParser.parseString(data).asJsonObject.obj("door") }.getOrNull() ?: return null
        val type = door.str("type")?.takeIf { TYPE_RE.matches(it) } ?: return null
        return DoorPushInfo(type, door.id("eventId"), door.id("cameraId"), door.id("lockId"))
    }

    /** Where a tap on a door message (or its "Ansehen") leads: the ring's detail, else the tab. */
    fun destination(message: PushMessage): DoorDest? {
        if (!isDoorMessage(message)) return null
        val info = parse(message)
        val eventId = info?.eventId
        return if (info != null && info.isRing && eventId != null) DoorDest.Event(eventId) else DoorDest.Home
    }
}
