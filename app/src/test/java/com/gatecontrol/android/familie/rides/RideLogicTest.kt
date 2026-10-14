package com.gatecontrol.android.familie.rides

import com.gatecontrol.android.familie.FamilieMe
import com.gatecontrol.android.familie.FamiliePlace
import com.gatecontrol.android.familie.FamilieRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class RideLogicTest {

    private val anna = FamilieMe(3, "Anna", FamilieRole.PARENT, null, null)
    private val marc = FamilieMe(4, "Marc", FamilieRole.PARENT, null, null)
    private val tim = FamilieMe(5, "Tim", FamilieRole.CHILD, 9, null)
    private val jonas = FamilieMe(6, "Jonas", FamilieRole.CHILD, 19, null)

    private fun ride(
        id: String = "12",
        date: String = "2026-10-14",
        time: String = "16:00",
        status: String = Ride.STATUS_OPEN,
        driver: RideDriver? = null,
        declined: List<RidePerson> = emptyList(),
        askedAt: String? = null,
        remind: Int = 20,
        lat: Double? = null,
        lon: Double? = null,
    ) = Ride(
        id = id, seriesId = null, date = date, time = time, title = "Tim vom Training abholen", place = "Sporthalle Nord",
        lat = lat, lon = lon, childIds = listOf(5), status = status, driver = driver, requestedBy = RidePerson(3, "Anna"),
        declined = declined, askedAt = askedAt, remindMinutes = remind, note = "",
    )

    @Test
    fun `parents and adults drive, children ask`() {
        val open = ride()
        assertTrue(RideLogic.canTake(open, anna))
        assertTrue(RideLogic.canTake(open, jonas), "an adult member may take a ride")
        assertFalse(RideLogic.canTake(open, tim))
        assertFalse(RideLogic.canTake(ride(status = Ride.STATUS_TAKEN), anna))
        assertFalse(RideLogic.canTake(ride(status = Ride.STATUS_CANCELLED), anna))
    }

    @Test
    fun `can't once per parent, give back only as the driver`() {
        val declined = ride(declined = listOf(RidePerson(4, "Marc")))
        assertTrue(RideLogic.canDecline(declined, anna))
        assertFalse(RideLogic.canDecline(declined, marc), "Marc said so already")
        assertFalse(RideLogic.canDecline(ride(), jonas), "only parents decline")

        val taken = ride(status = Ride.STATUS_TAKEN, driver = RideDriver.Member(3, "Anna"))
        assertTrue(RideLogic.canRelease(taken, anna))
        assertFalse(RideLogic.canRelease(taken, marc))
        assertFalse(RideLogic.canRelease(ride(status = Ride.STATUS_TAKEN, driver = RideDriver.Label("Bus")), anna))

        assertTrue(RideLogic.canEdit(anna))
        assertFalse(RideLogic.canEdit(jonas))

        // A child cancels its own one-off request, nothing else.
        val request = ride().copy(requestedBy = RidePerson(5, "Tim"))
        assertTrue(RideLogic.canCancelOwn(request, tim))
        assertFalse(RideLogic.canCancelOwn(ride(), tim), "asked for by Anna")
        assertFalse(RideLogic.canCancelOwn(request.copy(seriesId = 3), tim), "no series")
        assertFalse(RideLogic.canCancelOwn(request.copy(status = Ride.STATUS_CANCELLED), tim))
        assertFalse(RideLogic.canCancelOwn(request, anna), "parents use the edit form")
    }

    @Test
    fun `asking again waits five minutes`() {
        val now = 1_000_000_000L
        assertTrue(RideLogic.canAsk(ride(), now, null))
        assertFalse(RideLogic.canAsk(ride(), now, now - 60_000))
        assertTrue(RideLogic.canAsk(ride(), now, now - RideLogic.ASK_PAUSE_MS))
        assertFalse(RideLogic.canAsk(ride(status = Ride.STATUS_TAKEN), now, null))
    }

    @Test
    fun `asked or soon open rides and my next rides go to the top`() {
        val now = LocalDateTime.parse("2026-10-14T12:00")
        val rides = listOf(
            ride(id = "1", date = "2026-10-18", time = "10:00"),
            ride(id = "2", date = "2026-10-16", time = "08:00", askedAt = "2026-10-14T10:00:00Z"),
            ride(id = "3", date = "2026-10-14", time = "16:00"),
            ride(id = "4", date = "2026-10-14", time = "17:00", status = Ride.STATUS_TAKEN, driver = RideDriver.Member(3, "Anna")),
            ride(id = "5", date = "2026-10-14", time = "17:30", status = Ride.STATUS_TAKEN, driver = RideDriver.Member(4, "Marc")),
            ride(id = "6", date = "2026-10-14", time = "09:00"),
            ride(id = "7", date = "2026-10-14", time = "18:00", status = Ride.STATUS_CANCELLED),
        )
        val s = RideLogic.sections(rides, anna, now)
        assertEquals(listOf("3", "4", "2"), s.attention.map { it.id })
        assertEquals(listOf("6", "5", "7", "1"), s.week.map { it.id })
        assertEquals(listOf("3", "5", "2"), RideLogic.sections(rides, marc, now).attention.map { it.id }, "Marc sees his own ride on top")
    }

    @Test
    fun `the driver's reminder time`() {
        assertEquals("15:40", RideLogic.reminderTime(ride()))
        assertEquals("23:50", RideLogic.reminderTime(ride(time = "00:10", remind = 20)))
        assertNull(RideLogic.reminderTime(ride(remind = 0)))
    }

    @Test
    fun `the place reminder after taking a ride`() {
        assertNull(RideLogic.placeReminder(ride(), anna, "Tim einsammeln"), "no coordinates, no reminder")
        val d = RideLogic.placeReminder(ride(lat = 52.5, lon = 13.4), anna, "Tim einsammeln")!!
        assertEquals("Sporthalle Nord", d.title)
        assertEquals("Tim einsammeln", d.note)
        assertEquals(150, d.radius)
        assertEquals(FamiliePlace.TRIGGER_ENTER, d.trigger)
        assertEquals(FamiliePlace.ACTION_NOTE, d.action)
        assertEquals("once", d.repeat)
        assertEquals(3, d.targetUserId)
    }
}
