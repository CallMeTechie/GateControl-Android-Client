package com.gatecontrol.android.chat

import com.gatecontrol.android.familie.PluginDirectory
import com.gatecontrol.android.mail.MCalendars
import com.gatecontrol.android.mail.MEventInput
import com.gatecontrol.android.mail.createEvent
import com.gatecontrol.android.mail.MResult
import com.gatecontrol.android.mail.MailPimJson
import com.gatecontrol.android.mail.MailPimRepository
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where „Termin anlegen“ of a message saves: a calendar of the Mail plugin the person may write to (`POST events`), or
 * Familie's calendar (the messenger's `POST messages/:id/to-calendar`). [key] is what the device remembers
 * (`mail:<id>`, `familie`).
 */
data class ChatCalendarTarget(val key: String, val name: String, val mailCalendarId: Long? = null, val color: String? = null) {
    val isMail: Boolean get() = mailCalendarId != null

    companion object {
        const val FAMILIE = "familie"
        fun mailKey(id: Long) = "mail:$id"
    }
}

/** What the form „Termin anlegen“ saves. */
data class ChatSuggestedEventDraft(val title: String, val startMs: Long, val allDay: Boolean, val targetKey: String)

object ChatCalendarChoice {
    /** One hour for a timed event (the Mail plugin needs an end). */
    const val DEFAULT_DURATION_MS = 3_600_000L

    /**
     * The calendars to pick from: the Mail plugin's own calendars that can be written to (not a subscribed ICS feed, the
     * default one first), then Familie when its calendar is on ([familie]; its name: [familieName]).
     */
    fun targets(mail: MCalendars?, familie: Boolean, familieName: String): List<ChatCalendarTarget> {
        val writable = mail?.calendars.orEmpty().filter { it.writable && it.source != "ics" }
        val first = mail?.prefs?.defaultCalendarId?.takeIf { d -> writable.any { it.id == d } } ?: writable.firstOrNull { it.isDefault }?.id
        val ordered = writable.sortedBy { if (it.id == first) 0 else 1 }
        return ordered.map { ChatCalendarTarget(ChatCalendarTarget.mailKey(it.id), it.name, it.id, it.color) } +
            listOfNotNull(ChatCalendarTarget(ChatCalendarTarget.FAMILIE, familieName).takeIf { familie })
    }

    /** The calendar the form starts with: the one picked last, else the Mail calendar (when there is one), else Familie. */
    fun default(targets: List<ChatCalendarTarget>, last: String?): ChatCalendarTarget? =
        targets.firstOrNull { it.key == last } ?: targets.firstOrNull { it.isMail } ?: targets.firstOrNull()

    /** The Mail plugin's event of [d] in calendar [calendarId] ([zone]: the device's). */
    fun mailInput(d: ChatSuggestedEventDraft, calendarId: Long, zone: ZoneId): MEventInput {
        val day = Instant.ofEpochMilli(d.startMs).atZone(zone).toLocalDate().toString()
        return MEventInput(
            calendarId = calendarId,
            title = d.title.trim(),
            allDay = d.allDay,
            start = d.startMs.takeIf { !d.allDay },
            end = (d.startMs + DEFAULT_DURATION_MS).takeIf { !d.allDay },
            startDate = day.takeIf { d.allDay },
            endDate = day.takeIf { d.allDay },
            timeZone = zone.id,
        )
    }
}

/** The calendars „Termin anlegen“ offers and saving into the Mail plugin's ones; remembers the one picked last. */
@Singleton
class ChatCalendarTargets @Inject constructor(
    private val mail: MailPimRepository,
    private val plugins: PluginDirectory,
    private val device: ChatDeviceSettings,
) {
    /** Whether the Mail plugin is there for this person. */
    val mailAvailable: Boolean get() = plugins.state.value.mail

    /** Mail's calendars (fresh, else what is cached) or null without the Mail plugin. */
    suspend fun mailCalendars(): MCalendars? {
        if (!mailAvailable) return null
        return (mail.refreshCalendars() as? MResult.Ok)?.value ?: mail.calendars.value
    }

    val last: String? get() = device.config.value.calendarTarget

    suspend fun remember(key: String) {
        if (device.config.value.calendarTarget != key) device.update { it.copy(calendarTarget = key) }
    }

    suspend fun saveMail(d: ChatSuggestedEventDraft, calendarId: Long, zone: ZoneId = ZoneId.systemDefault()): MResult<*> =
        mail.api.createEvent(MailPimJson.eventBody(ChatCalendarChoice.mailInput(d, calendarId, zone)))
}
