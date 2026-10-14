package com.gatecontrol.android.familie.push

import com.gatecontrol.android.familie.FAMILIE_PLUGIN
import com.gatecontrol.android.familie.FamilieDest
import com.gatecontrol.android.familie.FamilieJson.dbl
import com.gatecontrol.android.familie.FamilieJson.int
import com.gatecontrol.android.familie.FamilieJson.obj
import com.gatecontrol.android.familie.FamilieJson.str
import com.gatecontrol.android.familie.LocateMode
import com.gatecontrol.android.notify.model.PushMessage
import com.google.gson.JsonParser

/** The `data.familie` context of a Familie push message (contract B3). */
sealed interface FamiliePushEvent {
    data class LocateRequest(val requestId: Int, val mode: LocateMode, val from: String?) : FamiliePushEvent

    /** Answer, decline, failure or expiry of a request (to the parent who asked). */
    data class LocateUpdate(val type: String, val requestId: Int?, val childId: Int?) : FamiliePushEvent

    data class TaskDue(val taskId: Int, val occurrence: String?) : FamiliePushEvent

    /** Someone did a task (mode "first"): the others' reminder for it goes away. */
    data class TaskDone(val taskId: Int, val occurrence: String?) : FamiliePushEvent

    data class ShoppingGoing(val listId: String?) : FamiliePushEvent

    data class DateReminder(val eventId: String?, val date: String?) : FamiliePushEvent

    data class Arrival(val userId: Int?, val label: String?) : FamiliePushEvent

    data class Sos(
        val sosId: Int,
        val userId: Int,
        val lat: Double?,
        val lon: Double?,
        val accuracy: Double?,
    ) : FamiliePushEvent

    data class PlaceConsent(val placeId: Int) : FamiliePushEvent

    data class PlaceTell(val placeId: Int?, val userId: Int?) : FamiliePushEvent

    /** Any other Familie message (e.g. the parent's SOS acknowledgement); routed by its topic. */
    data class Other(val type: String?, val topicKey: String?) : FamiliePushEvent
}

/**
 * Reads Familie messages from the push stream and decides where they lead.
 * Pure (no Android), so the routing is unit-tested.
 */
object FamiliePush {

    private const val TOPIC_PREFIX = "plugin:$FAMILIE_PLUGIN:"
    const val ROUTE = "plg-$FAMILIE_PLUGIN"

    /** Topic key (`locate`, `sos`, …) of a Familie message, else null. */
    fun topicKey(topic: String): String? = topic.takeIf { it.startsWith(TOPIC_PREFIX) }?.removePrefix(TOPIC_PREFIX)

    fun isFamilie(message: PushMessage): Boolean =
        topicKey(message.topic) != null || message.route == ROUTE

    /** The message's Familie context; null for messages of other senders. */
    fun parse(message: PushMessage): FamiliePushEvent? {
        if (!isFamilie(message)) return null
        val topicKey = topicKey(message.topic)
        val data = message.dataJson?.let { raw ->
            runCatching { JsonParser.parseString(raw).takeIf { it.isJsonObject }?.asJsonObject }.getOrNull()
        }
        val f = data?.obj("familie") ?: return FamiliePushEvent.Other(null, topicKey)
        val type = f.str("type")?.take(40)
        return when (type) {
            "locate_request" -> {
                val id = f.int("requestId") ?: return FamiliePushEvent.Other(type, topicKey)
                val mode = LocateMode.fromWire(f.str("mode")) ?: LocateMode.CONFIRM
                FamiliePushEvent.LocateRequest(id, mode, f.str("from")?.take(60))
            }
            "locate_result", "locate_declined", "locate_failed", "locate_expired" ->
                FamiliePushEvent.LocateUpdate(type, f.int("requestId"), f.int("childId"))
            "task_due" -> f.int("taskId")?.let { FamiliePushEvent.TaskDue(it, f.str("occurrence")?.take(10)) }
                ?: FamiliePushEvent.Other(type, topicKey)
            "task_done" -> f.int("taskId")?.let { FamiliePushEvent.TaskDone(it, f.str("occurrence")?.take(10)) }
                ?: FamiliePushEvent.Other(type, topicKey)
            "shopping_going" -> FamiliePushEvent.ShoppingGoing(f.str("listId")?.takeIf { it.length <= 64 })
            "date" -> FamiliePushEvent.DateReminder(f.str("eventId")?.take(40), f.str("date")?.take(10))
            "arrival" -> FamiliePushEvent.Arrival(f.int("userId"), f.str("label")?.take(60))
            "sos" -> {
                val sosId = f.int("sosId") ?: return FamiliePushEvent.Other(type, topicKey)
                val userId = f.int("userId") ?: return FamiliePushEvent.Other(type, topicKey)
                val lat = f.dbl("lat")?.takeIf { it in -90.0..90.0 }
                val lon = f.dbl("lon")?.takeIf { it in -180.0..180.0 }
                FamiliePushEvent.Sos(sosId, userId, lat.takeIf { lon != null }, lon.takeIf { lat != null }, f.dbl("accuracy")?.takeIf { it >= 0 })
            }
            "place_consent" -> f.int("placeId")?.let { FamiliePushEvent.PlaceConsent(it) } ?: FamiliePushEvent.Other(type, topicKey)
            "place_tell" -> FamiliePushEvent.PlaceTell(f.int("placeId"), f.int("userId"))
            else -> {
                // Locate updates of plugin versions that name them differently still carry the request.
                if (topicKey == "locate" && f.int("requestId") != null) {
                    FamiliePushEvent.LocateUpdate(type ?: "locate", f.int("requestId"), f.int("childId"))
                } else {
                    FamiliePushEvent.Other(type, topicKey)
                }
            }
        }
    }

    /** Screen of the Familie tab a tap on the message opens. */
    fun destination(event: FamiliePushEvent): FamilieDest = when (event) {
        is FamiliePushEvent.LocateRequest -> FamilieDest.Child
        is FamiliePushEvent.LocateUpdate -> event.childId?.let { FamilieDest.Locate(it, event.requestId) } ?: FamilieDest.Home
        is FamiliePushEvent.TaskDue -> FamilieDest.Tasks
        is FamiliePushEvent.TaskDone -> FamilieDest.Tasks
        is FamiliePushEvent.ShoppingGoing -> FamilieDest.Shopping(event.listId)
        is FamiliePushEvent.DateReminder -> FamilieDest.Dates
        is FamiliePushEvent.Arrival -> FamilieDest.Home
        is FamiliePushEvent.Sos -> FamilieDest.Sos(event.sosId, event.userId, event.lat, event.lon, event.accuracy)
        is FamiliePushEvent.PlaceConsent -> FamilieDest.Places
        is FamiliePushEvent.PlaceTell -> FamilieDest.Places
        is FamiliePushEvent.Other -> when (event.topicKey) {
            "shopping" -> FamilieDest.Shopping()
            "tasks" -> FamilieDest.Tasks
            "dates" -> FamilieDest.Dates
            "places" -> FamilieDest.Places
            // To a child: the parent saw the SOS / asked for the location.
            "sos", "locate" -> FamilieDest.Child
            else -> FamilieDest.Home
        }
    }

    /** Notification buttons of a Familie message (instead of the generic ones). */
    enum class Action { SEND_LOCATION, DECLINE_LOCATION, TASK_DONE, TASK_SNOOZE, PLACE_ACCEPT, PLACE_DECLINE, SOS_SEEN }

    fun actions(event: FamiliePushEvent): List<Action> = when (event) {
        is FamiliePushEvent.LocateRequest ->
            if (event.mode == LocateMode.CONFIRM) listOf(Action.SEND_LOCATION, Action.DECLINE_LOCATION) else emptyList()
        is FamiliePushEvent.TaskDue -> listOf(Action.TASK_DONE, Action.TASK_SNOOZE)
        is FamiliePushEvent.PlaceConsent -> listOf(Action.PLACE_ACCEPT, Action.PLACE_DECLINE)
        is FamiliePushEvent.Sos -> listOf(Action.SOS_SEEN)
        else -> emptyList()
    }

    /**
     * Collapse key of the reminder of a task occurrence (`task:<id>:<date>`,
     * contract B3): a `task_done` for it removes that notification.
     */
    fun reminderCollapseKey(taskId: Int, occurrence: String?): String? =
        occurrence?.takeIf { Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(it) }?.let { "task:$taskId:$it" }

    /** Geofences may have changed: places were created, consented to or triggered. */
    fun touchesPlaces(event: FamiliePushEvent, topicKey: String?): Boolean =
        event is FamiliePushEvent.PlaceConsent || event is FamiliePushEvent.PlaceTell || topicKey == "places"

    /** The parent's acknowledgement of a child's SOS (any SOS-topic message that is not an SOS itself). */
    fun isSosAck(event: FamiliePushEvent, topicKey: String?): Boolean =
        topicKey == "sos" && event !is FamiliePushEvent.Sos
}
