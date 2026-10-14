package com.gatecontrol.android.familie.rides

import com.gatecontrol.android.familie.FamilieMe
import com.gatecontrol.android.familie.FamiliePlace
import com.gatecontrol.android.familie.PlaceDraft
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Pure decisions of the rides screen (who may do what, which rides go to
 * the top), unit-tested. The server checks everything again.
 */
object RideLogic {

    /** Adults count from 18 (the plugin's ADULT_AGE). */
    private const val ADULT_AGE = 18

    /** "Wer kann …?" at most every 5 minutes per ride (the server answers too_soon otherwise). */
    const val ASK_PAUSE_MS = 5 * 60_000L

    /** A ride this close comes to the top even when nobody asked yet. */
    private const val SOON_HOURS = 24L

    /** The place reminder after "Ich mache es" (contract: enter, 150 m, once). */
    const val PLACE_RADIUS = 150

    private val HM = DateTimeFormatter.ofPattern("HH:mm")

    /** Parents and adult members may drive ("Ich mache es"). */
    fun canDrive(me: FamilieMe): Boolean = me.isParent || (me.isChild && (me.age ?: 0) >= ADULT_AGE)

    fun canTake(ride: Ride, me: FamilieMe): Boolean = ride.isOpen && canDrive(me)

    /** "Kann nicht": parents, once per ride. */
    fun canDecline(ride: Ride, me: FamilieMe): Boolean =
        ride.isOpen && me.isParent && ride.declined.none { it.userId == me.userId }

    /** "Rückgängig": the driver gives the ride back. */
    fun canRelease(ride: Ride, me: FamilieMe): Boolean = ride.isTaken && ride.driverId == me.userId

    fun canEdit(me: FamilieMe): Boolean = me.isParent

    /** A child cancels its own one-off "Bitte abholen" (parents cancel through the edit form). */
    fun canCancelOwn(ride: Ride, me: FamilieMe): Boolean =
        !me.isParent && !ride.isCancelled && !ride.isSeries && ride.requestedBy?.userId == me.userId

    /** "Wer kann …?" again: open, and the last ask is at least 5 minutes ago. */
    fun canAsk(ride: Ride, nowMs: Long, askedAtMs: Long?): Boolean =
        ride.isOpen && (askedAtMs == null || nowMs - askedAtMs >= ASK_PAUSE_MS)

    fun start(ride: Ride): LocalDateTime? = runCatching {
        LocalDateTime.of(LocalDate.parse(ride.date), LocalTime.parse(ride.time))
    }.getOrNull()

    /** When the driver gets reminded (`HH:MM`), null without a reminder. */
    fun reminderTime(ride: Ride): String? {
        if (ride.remindMinutes <= 0) return null
        val t = runCatching { LocalTime.parse(ride.time) }.getOrNull() ?: return null
        return HM.format(t.minusMinutes(ride.remindMinutes.toLong()))
    }

    /** The screen's two parts: what needs someone ("Offen" + my rides soon) and the rest of the week. */
    data class Sections(val attention: List<Ride>, val week: List<Ride>)

    fun sections(rides: List<Ride>, me: FamilieMe, now: LocalDateTime): Sections {
        val sorted = rides.sortedWith(compareBy({ it.date }, { it.time }, { it.id }))
        val soon = now.plusHours(SOON_HOURS)
        val attention = sorted.filter { r ->
            val at = start(r) ?: return@filter false
            if (at.isBefore(now.minusHours(1))) return@filter false
            when {
                r.isOpen -> r.askedAt != null || !at.isAfter(soon)
                r.isTaken -> r.driverId == me.userId && !at.isAfter(soon)
                else -> false
            }
        }
        val ids = attention.map { it.id }.toSet()
        return Sections(attention, sorted.filter { it.id !in ids })
    }

    /**
     * "Orts-Erinnerung an der Halle anlegen" after taking a ride: a 1.0 place
     * reminder for myself (arrive, 150 m, note, once). Null without coordinates.
     */
    fun placeReminder(ride: Ride, me: FamilieMe, note: String): PlaceDraft? {
        val lat = ride.lat ?: return null
        val lon = ride.lon ?: return null
        return PlaceDraft(
            title = ride.place.ifBlank { ride.title }.trim().take(80),
            note = note.trim().take(300),
            lat = lat,
            lon = lon,
            radius = PLACE_RADIUS,
            trigger = FamiliePlace.TRIGGER_ENTER,
            action = FamiliePlace.ACTION_NOTE,
            repeat = "once",
            targetUserId = me.userId,
        )
    }
}
