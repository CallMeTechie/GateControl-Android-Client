package com.gatecontrol.android.chat

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt

/** Pure helpers of the Messenger 1.4 cards (polls, events, arrival, actions). Unit-tested. */
object ChatRichLogic {

    /** Share of [count] in [total] as 0…1. */
    fun share(count: Int, total: Int): Float = if (total <= 0) 0f else (count.toFloat() / total).coerceIn(0f, 1f)

    /** The option ids after tapping [optionId]: multi toggles, single picks (or withdraws when picked). */
    fun vote(poll: ChatPoll, optionId: String): List<String> = when {
        poll.multi -> if (optionId in poll.myVotes) poll.myVotes - optionId else poll.myVotes + optionId
        optionId in poll.myVotes -> emptyList()
        else -> listOf(optionId)
    }

    /** Members who have not voted (only known when the poll is not anonymous). */
    fun nonVoters(poll: ChatPoll, memberIds: List<Long>, myId: Long?): List<Long>? {
        if (poll.anonymous) return null
        val voted = poll.options.flatMap { it.voters.orEmpty() }.toSet()
        return memberIds.filter { it !in voted && it != myId }
    }

    /** May the caller close the poll (creator or group admin)? */
    fun canClose(poll: ChatPoll, myId: Long?, admin: Boolean, mine: Boolean): Boolean =
        !poll.closed && (admin || mine || (myId != null && poll.creatorId == myId))

    /** May the caller remind the others (the creator only)? */
    fun canNudge(poll: ChatPoll, myId: Long?, mine: Boolean): Boolean =
        !poll.closed && (mine || (myId != null && poll.creatorId == myId))

    /** Whole days from today to the event's day (negative: past). */
    fun daysUntil(startsAt: String, today: LocalDate, zone: ZoneId): Long? {
        val start = ChatTimeline.epochMs(startsAt) ?: return null
        val day = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
        return Duration.between(today.atStartOfDay(), day.atStartOfDay()).toDays()
    }

    /** Minutes until [etaAt] (≥ 0). */
    fun minutesUntil(etaAt: String?, nowMs: Long): Int? {
        val eta = ChatTimeline.epochMs(etaAt) ?: return null
        return ((eta - nowMs) / 60_000.0).roundToInt().coerceAtLeast(0)
    }

    /** "850 m", "3,1 km" (German) / "3.1 km". */
    fun distance(meters: Int?, locale: Locale): String? {
        val m = meters ?: return null
        return if (m < 1000) "$m m" else String.format(locale, "%.1f km", m / 1000.0)
    }

    /** May an event offer "Ankunft teilen": departure hint on, not cancelled, starting within 3 h (or running). */
    fun offerArrival(event: ChatEventInfo, nowMs: Long): Boolean {
        if (event.cancelled || event.place == null) return false
        val start = ChatTimeline.epochMs(event.startsAt) ?: return false
        val end = ChatTimeline.epochMs(event.endsAt) ?: (start + 3_600_000L)
        return nowMs in (start - 3 * 3_600_000L)..end
    }

    /** May the caller edit or cancel the event (creator or admin)? */
    fun canManageEvent(event: ChatEventInfo, myId: Long?, admin: Boolean, mine: Boolean): Boolean =
        !event.cancelled && (admin || mine || (myId != null && event.creatorId == myId))

    /** The answer after tapping [answer]: the same again withdraws (null). */
    fun rsvp(current: String?, answer: String): String? = if (current == answer) null else answer

    /** The upcoming event to pin above the chat: the server's `nextEvent`, unless dismissed or past. */
    fun showNextEvent(next: ChatNextEvent?, dismissed: Long?, nowMs: Long): Boolean {
        if (next == null || next.messageId == dismissed) return false
        val start = ChatTimeline.epochMs(next.startsAt) ?: return true
        return start + 3_600_000L > nowMs
    }

    /** Pinned messages in the order of [pinned] that are in the cache ([messages]). */
    fun pinnedMessages(pinned: List<Long>, messages: List<ChatMessage>): List<ChatMessage> {
        val byId = messages.associateBy { it.id }
        return pinned.mapNotNull { byId[it] }.filter { !it.deleted }
    }

    /** A home action is shown in the attach sheet unless hidden on this device. */
    fun visibleActions(actions: List<ChatHomeAction>, hidden: Set<String>): List<ChatHomeAction> = actions.filter { it.id !in hidden }
}
