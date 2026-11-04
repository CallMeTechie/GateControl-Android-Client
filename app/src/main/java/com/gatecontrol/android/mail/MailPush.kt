package com.gatecontrol.android.mail

import com.gatecontrol.android.notify.model.PushMessage
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Where a tap on a Mail push leads (pure, unit-tested). Topic `mail` (title = sender, text = subject) carries
 * `data.mail = { messageId, accountId }` and opens the mail; `mail_failed` carries `{ outboxId }` and opens the
 * outbox. Their channel is called "Mail" whatever the server names it.
 */
object MailPush {
    private const val TOPIC_PREFIX = "plugin:$MAIL_PLUGIN:"
    const val TOPIC_MAIL = TOPIC_PREFIX + "mail"
    const val TOPIC_FAILED = TOPIC_PREFIX + "mail_failed"
    /** Since Mail 1.2.0: a reminder (`data.calendar = { eventId, occurrence }`) and an invitation (`{ invitationId }`). */
    const val TOPIC_REMINDER = TOPIC_PREFIX + "calendar_reminder"
    const val TOPIC_INVITE = TOPIC_PREFIX + "calendar_invite"
    /**
     * Since Mail 1.9.0: a calendar shared with the person (`data.calendar = { sharedCalendarId }`), a booking request
     * to decide (`data.resources = { bookingId, resourceId }`), the answer to the person's own booking (also
     * `data.calendar = { eventId }`).
     */
    const val TOPIC_SHARE = TOPIC_PREFIX + "calendar_share"
    const val TOPIC_RES_REQUEST = TOPIC_PREFIX + "resource_request"
    const val TOPIC_RES_BOOKING = TOPIC_PREFIX + "resource_booking"

    fun isMailMessage(message: PushMessage): Boolean = message.topic.startsWith(TOPIC_PREFIX)

    /** Whether a notification channel (by its topic) is Mail's own: shown as "Mail". */
    fun isMailTopic(topic: String): Boolean = topic == TOPIC_MAIL

    /** Whether a channel (by its topic) is the calendar's: shown as "Kalender". */
    fun isCalendarTopic(topic: String): Boolean = topic == TOPIC_REMINDER || topic == TOPIC_INVITE || topic == TOPIC_SHARE || topic == TOPIC_RES_REQUEST || topic == TOPIC_RES_BOOKING

    /**
     * `message:<id>` | `outbox:<id>` | `outbox` | `event:<id>[:<occurrence>]` | `invitation:<id>` | `request:<bookingId>`
     * | `booking:<bookingId>` | `shared[:<calendarId>]`; null for other messages.
     */
    fun destination(message: PushMessage): String? {
        if (!isMailMessage(message)) return null
        val data = message.dataJson?.let { raw -> runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull() }
        val mail = data?.get("mail")?.takeIf { it.isJsonObject }?.asJsonObject
        val calendar = data?.get("calendar")?.takeIf { it.isJsonObject }?.asJsonObject
        val resources = data?.get("resources")?.takeIf { it.isJsonObject }?.asJsonObject
        return when (message.topic) {
            TOPIC_MAIL -> mail?.id("messageId")?.let { "message:$it" }
            TOPIC_FAILED -> mail?.id("outboxId")?.let { "outbox:$it" } ?: "outbox"
            TOPIC_REMINDER -> calendar?.id("eventId")?.let { id ->
                val occ = calendar.get("occurrence")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { OCCURRENCE.matches(it) }
                "event:$id" + (occ?.let { ":$it" } ?: "")
            }
            TOPIC_INVITE -> calendar?.id("invitationId")?.let { "invitation:$it" }
            // the requests (tab „Offen“) at its card
            TOPIC_RES_REQUEST -> resources?.id("bookingId")?.let { "request:$it" } ?: "request"
            // the answer: the own event, else the own bookings
            TOPIC_RES_BOOKING -> calendar?.id("eventId")?.let { "event:$it" } ?: resources?.id("bookingId")?.let { "booking:$it" } ?: "booking"
            TOPIC_SHARE -> "shared" + (calendar?.id("sharedCalendarId")?.let { ":$it" } ?: "")
            else -> null
        }
    }

    private val OCCURRENCE = Regex("^(\\d{1,15}|\\d{4}-\\d{2}-\\d{2})$")

    /** A destination of [destination] → the event and its occurrence, or null. */
    fun eventOf(dest: String): Pair<Long, String?>? {
        if (!dest.startsWith("event:")) return null
        val rest = dest.removePrefix("event:")
        val id = rest.substringBefore(':').toLongOrNull()?.takeIf { it > 0 } ?: return null
        val occ = rest.substringAfter(':', "").takeIf { it.isNotEmpty() && OCCURRENCE.matches(it) }
        return id to occ
    }

    /** A destination of [destination] → the invitation's id, or null. */
    fun invitationOf(dest: String): Long? = dest.takeIf { it.startsWith("invitation:") }?.removePrefix("invitation:")?.toLongOrNull()?.takeIf { it > 0 }

    private fun JsonObject.id(key: String): Long? =
        get(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asString.trim().toLong() }.getOrNull() }?.takeIf { it > 0 }

    /** A destination of [destination] → the requests to open: (tab `open` | `mine`, the booking to show) or null. */
    fun bookingsOf(dest: String): Pair<String, Long?>? = when {
        dest == "request" -> "open" to null
        dest.startsWith("request:") -> "open" to dest.removePrefix("request:").toLongOrNull()?.takeIf { it > 0 }
        dest == "booking" -> "mine" to null
        dest.startsWith("booking:") -> "mine" to dest.removePrefix("booking:").toLongOrNull()?.takeIf { it > 0 }
        else -> null
    }

    /** Whether a destination opens „Mit mir geteilt“. */
    fun isShared(dest: String): Boolean = dest == "shared" || dest.startsWith("shared:")

    /** A destination of [destination] → the mail's id, or null. */
    fun messageOf(dest: String): Long? = dest.takeIf { it.startsWith("message:") }?.removePrefix("message:")?.toLongOrNull()?.takeIf { it > 0 }

    /** Whether a destination opens the outbox. */
    fun isOutbox(dest: String): Boolean = dest == "outbox" || dest.startsWith("outbox:")
}
