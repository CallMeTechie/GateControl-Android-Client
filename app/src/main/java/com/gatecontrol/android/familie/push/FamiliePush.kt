package com.gatecontrol.android.familie.push

import com.gatecontrol.android.familie.FAMILIE_PLUGIN
import com.gatecontrol.android.familie.FamilieDest
import com.gatecontrol.android.familie.FamilieJson
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

    /**
     * A rides message (1.1): `ride_ask`, `ride_reminder`, `ride_open`,
     * `ride_taken`, `ride_released`, `ride_unassigned`.
     */
    data class Ride(val type: String, val rideId: String?, val date: String?) : FamiliePushEvent {
        val isAsk: Boolean get() = type == FamiliePush.RIDE_ASK
    }

    /** A child turned 14 with locate mode "auto" (1.1, to the parents). */
    data class LocateAgeHint(val childId: Int?) : FamiliePushEvent

    /**
     * Taschengeld (1.2): `allowance_paid` (to the child), `allowance_convert`
     * (a child asks to convert stars; to the parents, with [requestId]),
     * `allowance_decided` (to the child) and any other `allowance_*` message.
     */
    data class Allowance(val type: String, val childId: Int?, val requestId: Int?, val stars: Int?) : FamiliePushEvent {
        val isConvert: Boolean get() = type == FamiliePush.ALLOWANCE_CONVERT
    }

    /** A presence rule's notification (1.2, "Tim ist zuhause"). */
    data class PresenceRule(val ruleId: Int?, val userId: Int?) : FamiliePushEvent

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
            RIDE_ASK, "ride_reminder", "ride_open", "ride_taken", "ride_released", "ride_unassigned" -> FamiliePushEvent.Ride(
                type,
                f.str("rideId")?.takeIf { FamilieJson.RIDE_ID_RE.matches(it) },
                f.str("date")?.takeIf { it.length == 10 },
            )
            "locate_age_hint" -> FamiliePushEvent.LocateAgeHint(f.int("childId"))
            ALLOWANCE_PAID, ALLOWANCE_CONVERT, ALLOWANCE_DECIDED -> FamiliePushEvent.Allowance(type, f.int("childId"), f.int("requestId")?.takeIf { it > 0 }, f.int("stars"))
            "presence_rule" -> FamiliePushEvent.PresenceRule(f.int("ruleId"), f.int("userId"))
            else -> {
                if (type != null && type.startsWith("allowance_")) {
                    return FamiliePushEvent.Allowance(type, f.int("childId"), f.int("requestId")?.takeIf { it > 0 }, f.int("stars"))
                }
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
        is FamiliePushEvent.Ride -> FamilieDest.Rides(event.rideId)
        is FamiliePushEvent.LocateAgeHint -> event.childId?.let { FamilieDest.Locate(it) } ?: FamilieDest.Home
        is FamiliePushEvent.Allowance -> FamilieDest.Allowance(event.childId)
        is FamiliePushEvent.PresenceRule -> FamilieDest.Presence
        is FamiliePushEvent.Other -> when (event.topicKey) {
            "shopping" -> FamilieDest.Shopping()
            "tasks" -> FamilieDest.Tasks
            "dates" -> FamilieDest.Dates
            "places" -> FamilieDest.Places
            "rides" -> FamilieDest.Rides()
            "allowance" -> FamilieDest.Allowance()
            "wishes" -> FamilieDest.Wishes()
            "expenses" -> FamilieDest.Expenses()
            "board" -> FamilieDest.Board
            "meals" -> FamilieDest.Meals()
            "presence" -> FamilieDest.Presence
            // To a child: the parent saw the SOS / asked for the location.
            "sos", "locate" -> FamilieDest.Child
            else -> FamilieDest.Home
        }
    }

    /** Notification buttons of a Familie message (instead of the generic ones). */
    enum class Action { SEND_LOCATION, DECLINE_LOCATION, TASK_DONE, TASK_SNOOZE, PLACE_ACCEPT, PLACE_DECLINE, SOS_SEEN, RIDE_TAKE, RIDE_DECLINE, ALLOWANCE_APPROVE, ALLOWANCE_DECLINE }

    const val RIDE_ASK = "ride_ask"
    const val ALLOWANCE_PAID = "allowance_paid"
    const val ALLOWANCE_CONVERT = "allowance_convert"
    const val ALLOWANCE_DECIDED = "allowance_decided"

    fun actions(event: FamiliePushEvent): List<Action> = when (event) {
        is FamiliePushEvent.LocateRequest ->
            if (event.mode == LocateMode.CONFIRM) listOf(Action.SEND_LOCATION, Action.DECLINE_LOCATION) else emptyList()
        is FamiliePushEvent.TaskDue -> listOf(Action.TASK_DONE, Action.TASK_SNOOZE)
        is FamiliePushEvent.PlaceConsent -> listOf(Action.PLACE_ACCEPT, Action.PLACE_DECLINE)
        is FamiliePushEvent.Sos -> listOf(Action.SOS_SEEN)
        // "Wer kann Tim um 16:00 abholen?" — answered from the notification.
        is FamiliePushEvent.Ride -> if (event.isAsk && event.rideId != null) listOf(Action.RIDE_TAKE, Action.RIDE_DECLINE) else emptyList()
        // "Lena möchte 10 Sterne umwandeln" — parents decide from the notification.
        is FamiliePushEvent.Allowance ->
            if (event.isConvert && event.requestId != null) listOf(Action.ALLOWANCE_APPROVE, Action.ALLOWANCE_DECLINE) else emptyList()
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
