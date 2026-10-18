package com.gatecontrol.android.ui.chat

import android.content.Context
import com.gatecontrol.android.R
import com.gatecontrol.android.chat.ChatConversation
import com.gatecontrol.android.chat.ChatMessage
import com.gatecontrol.android.chat.ChatPerson
import com.gatecontrol.android.chat.VoiceWave
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Texts of the chat screens built from resources (names, previews, system messages, times). */
object ChatText {

    /** Display name of a person; deleted accounts get "Gelöschtes Konto". */
    fun name(ctx: Context, p: ChatPerson?): String = when {
        p == null -> ctx.getString(R.string.chat_unknown_person)
        p.name.isNullOrBlank() -> ctx.getString(R.string.chat_deleted_account)
        else -> p.name
    }

    /** Name of user [id] in [c] ("Du" for the caller). */
    fun nameOf(ctx: Context, c: ChatConversation?, id: Long?, myId: Long?): String = when {
        id == null -> ctx.getString(R.string.chat_gatecontrol)
        id == myId -> ctx.getString(R.string.chat_you)
        else -> name(ctx, c?.members?.firstOrNull { it.userId == id } ?: c?.peer?.takeIf { it.userId == id })
    }

    /** Title of a conversation: the group's title or the other person's name. */
    fun title(ctx: Context, c: ChatConversation): String = when {
        !c.title.isNullOrBlank() -> c.title
        c.isGroup -> ctx.getString(R.string.chat_group_fallback)
        else -> name(ctx, c.peer)
    }

    /** Short description of a message's content (list preview, reply quote, notification). */
    fun content(ctx: Context, m: ChatMessage, c: ChatConversation?, myId: Long?): String {
        if (m.deleted) return ctx.getString(if (m.mine) R.string.chat_deleted_by_me else R.string.chat_deleted)
        if (m.undecryptable) return ctx.getString(R.string.chat_undecryptable)
        if (m.isVideo) {
            val len = m.videoDurationMs?.takeIf { it > 0 }?.let { VoiceWave.clock(it) }
            return prefixed(ctx.getString(R.string.chat_kind_video), listOfNotNull(len, m.text?.takeIf { it.isNotBlank() }).joinToString(" · "))
        }
        return when (m.kind) {
            ChatMessage.KIND_IMAGE -> prefixed(ctx.getString(R.string.chat_kind_image), m.text)
            ChatMessage.KIND_VOICE -> ctx.getString(R.string.chat_kind_voice) + (m.meta?.durationMs?.let { " · " + VoiceWave.clock(it) } ?: "")
            ChatMessage.KIND_FILE -> prefixed(ctx.getString(R.string.chat_kind_file), m.meta?.fileName ?: m.text)
            ChatMessage.KIND_LOCATION -> prefixed(ctx.getString(R.string.chat_kind_location), m.location?.label)
            ChatMessage.KIND_SYSTEM -> system(ctx, m, c, myId)
            // Messenger 1.4 kinds.
            ChatMessage.KIND_POLL -> prefixed(ctx.getString(R.string.chat_kind_poll), m.poll?.question)
            ChatMessage.KIND_EVENT -> prefixed(ctx.getString(R.string.chat_kind_event), m.event?.title)
            ChatMessage.KIND_ARRIVAL -> prefixed(ctx.getString(R.string.chat_kind_arrival), m.arrival?.dest?.label)
            ChatMessage.KIND_ACTION -> prefixed(ctx.getString(R.string.chat_kind_action), m.action?.label)
            ChatMessage.KIND_CARD -> m.card?.let { card -> listOfNotNull(card.title, card.text?.lineSequence()?.firstOrNull()).joinToString(": ") }
                ?: m.text.orEmpty()
            else -> if (m.isUnknownKind) m.text ?: ctx.getString(R.string.chat_kind_unknown) else m.text.orEmpty()
        }
    }

    private fun prefixed(label: String, text: String?): String = if (text.isNullOrBlank()) label else "$label · $text"

    /** "Anna hat Lena hinzugefügt", … (README system events). */
    fun system(ctx: Context, m: ChatMessage, c: ChatConversation?, myId: Long?): String {
        val s = m.system ?: return m.text.orEmpty()
        val by = if (s.by == null) ctx.getString(R.string.chat_gatecontrol) else nameOf(ctx, c, s.by, myId)
        fun names(ids: List<Long>) = ids.joinToString(", ") { nameOf(ctx, c, it, myId) }
        return when (s.event) {
            "created" -> ctx.getString(R.string.chat_sys_created, by, s.title ?: "")
            "member_added" -> ctx.getString(R.string.chat_sys_added, by, names(s.userIds))
            "member_removed" -> ctx.getString(R.string.chat_sys_removed, by, names(s.userIds))
            "member_left" -> ctx.getString(R.string.chat_sys_left, nameOf(ctx, c, s.userId, myId))
            "title" -> ctx.getString(R.string.chat_sys_title, by, s.title ?: "")
            "avatar" -> ctx.getString(R.string.chat_sys_avatar, by)
            "disappearing" -> if ((s.seconds ?: 0) <= 0) ctx.getString(R.string.chat_sys_disappearing_off, by)
            else ctx.getString(R.string.chat_sys_disappearing_on, by, disappearing(ctx, s.seconds ?: 0))
            "notice" -> m.text.orEmpty()
            // The plugin that managed the group handed it over (Messenger 1.1.0).
            "released" -> ctx.getString(R.string.chat_sys_released)
            // Messenger 1.4.
            "poll_closed" -> if (s.label != null) ctx.getString(R.string.chat_sys_poll_closed, s.title.orEmpty(), s.label)
            else ctx.getString(R.string.chat_sys_poll_closed_none, s.title.orEmpty())
            "arrived" -> s.label?.let { ctx.getString(R.string.chat_sys_arrived, nameOf(ctx, c, s.userId, myId), it) }
                ?: ctx.getString(R.string.chat_sys_arrived_plain, nameOf(ctx, c, s.userId, myId))
            "action_run" -> {
                val who = nameOf(ctx, c, s.userId ?: s.by, myId)
                if (s.otherUserId != null) ctx.getString(R.string.chat_sys_action_run_as, who, s.label.orEmpty(), nameOf(ctx, c, s.otherUserId, myId))
                else ctx.getString(R.string.chat_sys_action_run, who, s.label.orEmpty())
            }
            "action_denied" -> ctx.getString(R.string.chat_sys_action_denied, by, s.label.orEmpty())
            "event_cancelled" -> ctx.getString(R.string.chat_sys_event_cancelled, by, s.title.orEmpty())
            "vote" -> ctx.getString(R.string.chat_sys_vote, nameOf(ctx, c, s.userId ?: s.by, myId), s.label.orEmpty())
            "rsvp" -> {
                val who = nameOf(ctx, c, s.userId ?: s.by, myId)
                when (s.answer) {
                    "yes" -> if ((s.guests ?: 0) > 0) ctx.getString(R.string.chat_sys_rsvp_yes_guests, who, s.guests ?: 0) else ctx.getString(R.string.chat_sys_rsvp_yes, who)
                    "maybe" -> ctx.getString(R.string.chat_sys_rsvp_maybe, who)
                    "no" -> ctx.getString(R.string.chat_sys_rsvp_no, who)
                    else -> m.text ?: ctx.getString(R.string.chat_sys_unknown)
                }
            }
            else -> m.text ?: ctx.getString(R.string.chat_sys_unknown)
        }
    }

    /** Messenger 1.4: "online" / "zuletzt online …" of a person who shares it; null otherwise. */
    fun presence(ctx: Context, p: ChatPerson?): String? = when {
        p == null || p.gone -> null
        p.online == true -> ctx.getString(R.string.chat_online)
        p.lastSeenAt != null -> {
            val day = listTime(ctx, p.lastSeenAt)
            val time = clock(ctx, p.lastSeenAt)
            ctx.getString(R.string.chat_last_seen, if (day == time) time else "$day $time")
        }
        else -> null
    }

    /** "1 Tag", "1 Woche", "30 Tage", "Aus". */
    fun disappearing(ctx: Context, seconds: Int): String = when {
        seconds <= 0 -> ctx.getString(R.string.chat_disappearing_off)
        seconds % 604_800 == 0 && seconds / 604_800 == 1 -> ctx.getString(R.string.chat_disappearing_week)
        seconds % 86_400 == 0 -> ctx.resources.getQuantityString(R.plurals.chat_days, seconds / 86_400, seconds / 86_400)
        else -> ctx.resources.getQuantityString(R.plurals.chat_hours, (seconds / 3600).coerceAtLeast(1), (seconds / 3600).coerceAtLeast(1))
    }

    private fun locale(ctx: Context): Locale = ctx.resources.configuration.locales[0] ?: Locale.getDefault()

    /** `17:44` of an ISO time. */
    fun clock(ctx: Context, iso: String?, zone: ZoneId = ZoneId.systemDefault()): String {
        val t = iso?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return ""
        return DateTimeFormatter.ofPattern("HH:mm", locale(ctx)).format(t.atZone(zone))
    }

    fun clockMs(ctx: Context, ms: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofPattern("HH:mm", locale(ctx)).format(Instant.ofEpochMilli(ms).atZone(zone))

    /** List time: today the clock, yesterday "Gestern", this week the weekday, else the date. */
    fun listTime(ctx: Context, iso: String?, zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = LocalDate.now(zone)): String {
        val t = iso?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return ""
        val d = t.atZone(zone).toLocalDate()
        return when {
            d == today -> clock(ctx, iso, zone)
            d == today.minusDays(1) -> ctx.getString(R.string.chat_yesterday)
            d.isAfter(today.minusDays(7)) -> DateTimeFormatter.ofPattern("EEE", locale(ctx)).format(d)
            else -> DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale(ctx)).format(d)
        }
    }

    /** Day separator: "Heute", "Gestern" or the date. */
    fun day(ctx: Context, date: LocalDate, today: LocalDate = LocalDate.now()): String = when (date) {
        today -> ctx.getString(R.string.chat_today)
        today.minusDays(1) -> ctx.getString(R.string.chat_yesterday)
        else -> DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale(ctx)).format(date)
    }

    /** "248 KB". */
    fun bytes(n: Long?): String {
        val b = n ?: return ""
        return when {
            b < 1024 -> "$b B"
            b < 1024 * 1024 -> "${(b + 512) / 1024} KB"
            else -> String.format(Locale.ROOT, "%.1f MB", b / 1048576.0)
        }
    }

    /** "schreibt …" / "Anna schreibt …" / "Anna und Tim schreiben …". */
    fun typing(ctx: Context, c: ChatConversation?, users: List<Long>, myId: Long?): String? {
        if (users.isEmpty()) return null
        if (c?.isGroup != true) return ctx.getString(R.string.chat_typing)
        val names = users.take(2).map { nameOf(ctx, c, it, myId) }
        return if (names.size == 1) ctx.getString(R.string.chat_typing_one, names[0])
        else ctx.getString(R.string.chat_typing_many, names[0], names[1])
    }

    /** Reason a message could not be sent (outbox `error`). */
    fun sendError(ctx: Context, code: String?): String = ctx.getString(
        when (code) {
            "offline", null -> R.string.chat_err_offline
            "readonly" -> R.string.chat_err_readonly
            "peer_unavailable" -> R.string.chat_err_peer
            "file_missing" -> R.string.chat_err_file_missing
            "media_invalid", "media_in_use", "http_415", "unsupported_type" -> R.string.chat_err_media
            "http_413", "too_large" -> R.string.chat_err_too_large
            "http_507", "quota_exceeded" -> R.string.chat_err_quota
            "not_found" -> R.string.chat_err_gone
            else -> R.string.chat_err_generic
        },
    )

    /** An action that failed (edit, delete, react, create, …). */
    fun actionError(ctx: Context, code: String?): String = ctx.getString(
        when (code) {
            "offline" -> R.string.chat_err_offline
            "edit_window" -> R.string.chat_err_window
            "not_sender" -> R.string.chat_err_not_sender
            "not_admin" -> R.string.chat_err_not_admin
            "managed" -> R.string.chat_err_managed
            "too_many" -> R.string.chat_err_too_many
            "disappearing_off" -> R.string.chat_err_disappearing_off
            "deleted" -> R.string.chat_err_deleted
            "peer_unavailable" -> R.string.chat_err_peer
            "media_invalid", "media_in_use", "http_415", "unsupported_type" -> R.string.chat_err_media
            "http_413", "too_large" -> R.string.chat_err_too_large
            "http_507", "quota_exceeded" -> R.string.chat_err_quota
            "unreadable" -> R.string.chat_err_unreadable
            else -> R.string.chat_err_generic
        },
    )
}
