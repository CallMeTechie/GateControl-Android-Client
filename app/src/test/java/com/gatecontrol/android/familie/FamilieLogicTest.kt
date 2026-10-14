package com.gatecontrol.android.familie

import com.gatecontrol.android.familie.locate.FamiliePermissions
import com.gatecontrol.android.familie.locate.FamiliePermissions.AnswerPlan
import com.gatecontrol.android.familie.locate.FamiliePermissions.GeofenceReadiness
import com.gatecontrol.android.familie.locate.FamiliePermissions.SendPath
import com.gatecontrol.android.familie.places.GeofenceRegistrar
import com.gatecontrol.android.network.ClientPlugin
import com.gatecontrol.android.network.ClientPluginTab
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FamilieLogicTest {

    // ─── Tab visibility (C1) ─────────────────────────────────────────

    @Test
    fun `the tab shows only for the familie plugin with a tab`() {
        val withTab = PluginAvailability.from(
            listOf(
                ClientPlugin("gatecontrol-familie", "Familie", "1.0.0", ClientPluginTab("Familie", "M3 11l9-7 9 7")),
                ClientPlugin("gatecontrol-alltag", "Alltag", "1.2.0", null),
            ),
        )
        assertTrue(withTab.familieTab)
        assertTrue(withTab.familieApi)
        assertTrue(withTab.alltag)
        assertEquals("M3 11l9-7 9 7", withTab.familieIcon)

        // appVisible false: listed (API usable) but no tab.
        val hidden = PluginAvailability.from(listOf(ClientPlugin("gatecontrol-familie", "Familie", "1.0.0", null)))
        assertFalse(hidden.familieTab)
        assertTrue(hidden.familieApi)
        assertFalse(hidden.alltag)

        assertEquals(PluginAvailability.NONE, PluginAvailability.from(emptyList()))
        assertEquals(PluginAvailability.NONE, PluginAvailability.from(listOf(ClientPlugin("gatecontrol-skoda", "Skoda", "1", ClientPluginTab("x", null)))))
    }

    @Test
    fun `a tab icon that is not a plain svg path is ignored`() {
        val evil = PluginAvailability.from(listOf(ClientPlugin("gatecontrol-familie", tab = ClientPluginTab("F", "<script>alert(1)</script>"))))
        assertTrue(evil.familieTab)
        assertNull(evil.familieIcon)
    }

    @Test
    fun `cached data belongs to one server and token`() {
        val a = PluginDirectory.ownerKey("https://gc.example.com", "t1")
        assertEquals(a, PluginDirectory.ownerKey("https://gc.example.com", "t1"))
        assertNotEquals(a, PluginDirectory.ownerKey("https://gc.example.com", "t2"))
        assertNotEquals(a, PluginDirectory.ownerKey("https://other.example.com", "t1"))
        assertEquals("", PluginDirectory.ownerKey("", "t1"))
    }

    // ─── Permissions (C3, C4) ────────────────────────────────────────

    @Test
    fun `answering a locate request in the background needs all-the-time location`() {
        assertEquals(AnswerPlan.Fail(LocateFailCode.NO_PERMISSION), FamiliePermissions.answerPlan(foreground = false, background = false, appVisible = true, locationOn = true))
        assertEquals(AnswerPlan.Fail(LocateFailCode.NO_PERMISSION), FamiliePermissions.answerPlan(foreground = true, background = false, appVisible = false, locationOn = true))
        assertEquals(AnswerPlan.Locate, FamiliePermissions.answerPlan(foreground = true, background = false, appVisible = true, locationOn = true))
        assertEquals(AnswerPlan.Locate, FamiliePermissions.answerPlan(foreground = true, background = true, appVisible = false, locationOn = true))
        assertEquals(AnswerPlan.Fail(LocateFailCode.LOCATION_OFF), FamiliePermissions.answerPlan(foreground = true, background = true, appVisible = false, locationOn = false))
    }

    @Test
    fun `the send button runs where locating is allowed`() {
        assertEquals(SendPath.BACKGROUND, FamiliePermissions.sendPath(foreground = true, background = true))
        assertEquals(SendPath.SCREEN, FamiliePermissions.sendPath(foreground = true, background = false))
        assertEquals(SendPath.APP, FamiliePermissions.sendPath(foreground = false, background = false))
    }

    @Test
    fun `only children in automatic mode are asked for background location`() {
        assertTrue(FamiliePermissions.childNeedsBackground(LocateMode.AUTO, foreground = true, background = false))
        assertTrue(FamiliePermissions.childNeedsBackground(LocateMode.AUTO, foreground = false, background = false))
        assertFalse(FamiliePermissions.childNeedsBackground(LocateMode.AUTO, foreground = true, background = true))
        assertFalse(FamiliePermissions.childNeedsBackground(LocateMode.CONFIRM, foreground = true, background = false))
        assertFalse(FamiliePermissions.childNeedsBackground(null, foreground = false, background = false))
    }

    @Test
    fun `geofences need play services, precise and background location`() {
        assertEquals(GeofenceReadiness.UNAVAILABLE, FamiliePermissions.geofenceReadiness(playServices = false, fine = true, background = true))
        assertEquals(GeofenceReadiness.NEED_LOCATION, FamiliePermissions.geofenceReadiness(playServices = true, fine = false, background = true))
        assertEquals(GeofenceReadiness.NEED_BACKGROUND, FamiliePermissions.geofenceReadiness(playServices = true, fine = true, background = false))
        assertEquals(GeofenceReadiness.READY, FamiliePermissions.geofenceReadiness(playServices = true, fine = true, background = true))
    }

    // ─── Geofences (C4) ──────────────────────────────────────────────

    private fun place(id: Int, owner: Int, target: Int, active: Boolean = true, consent: String = FamiliePlace.CONSENT_ACCEPTED) = FamiliePlace(
        id = id, ownerId = owner, targetUserId = target, title = "P$id", note = "", lat = 52.5, lon = 13.4, radius = 150,
        trigger = FamiliePlace.TRIGGER_ENTER, action = FamiliePlace.ACTION_NOTE, listId = null, tellUserIds = emptyList(),
        repeat = "always", active = active, consent = consent,
    )

    @Test
    fun `this phone watches only active accepted places targeting me`() {
        val places = listOf(
            place(1, owner = 3, target = 3),
            place(2, owner = 5, target = 3, consent = FamiliePlace.CONSENT_PENDING),
            place(3, owner = 3, target = 5),
            place(4, owner = 3, target = 3, active = false),
            place(5, owner = 5, target = 3),
        )
        assertEquals(listOf(1, 5), GeofenceRegistrar.select(places, meId = 3).map { it.id })
        assertEquals(42, GeofenceRegistrar.placeIdOf(GeofenceRegistrar.requestId(42)))
        assertNull(GeofenceRegistrar.placeIdOf("other-7"))
        assertEquals(GeofenceRegistrar.MAX_FENCES, GeofenceRegistrar.select((1..150).map { place(it, 3, 3) }, 3).size)
    }

    // ─── Destinations (C3 deep links) ────────────────────────────────

    @Test
    fun `destinations survive the round trip through their key`() {
        val all = listOf(
            FamilieDest.Home, FamilieDest.Shopping(), FamilieDest.Shopping("6f1c-uuid"), FamilieDest.Tasks, FamilieDest.Dates,
            FamilieDest.Locate(5), FamilieDest.Locate(5, 21), FamilieDest.Sos(8, 5, 52.52, 13.405, 12.0), FamilieDest.Sos(9, 5),
            FamilieDest.Child, FamilieDest.Places, FamilieDest.PlaceEdit(), FamilieDest.PlaceEdit(3),
        )
        all.forEach { assertEquals(it, FamilieDest.parse(it.key), it.key) }
    }

    @Test
    fun `broken destinations are rejected`() {
        listOf(null, "", "nope", "locate/x", "shopping/a/b", "shopping/bad id", "sos/1", "place", "place/x").forEach {
            assertNull(FamilieDest.parse(it), it.toString())
        }
        assertEquals(FamilieDest.Sos(1, 2, null, null, null), FamilieDest.parse("sos/1/2/95/13/-"), "an impossible position is dropped")
    }

    // ─── Agenda (dates + Alltag) ─────────────────────────────────────

    @Test
    fun `agenda merges both calendars by day and time within the range`() {
        val own = listOf(
            FamilieEvent("1", "Elternabend", "2026-10-14", "19:00", null, false, "none", emptyList(), "event", null, ""),
            FamilieEvent("member:5", "Tim", "2026-10-20", null, null, true, "yearly", listOf(5), "birthday", null, ""),
            FamilieEvent("2", "Zu spät", "2027-01-01", null, null, true, "none", emptyList(), "event", null, ""),
        )
        val alltag = AlltagPayload(
            events = listOf(AlltagEvent("a1", "Zahnarzt", "2026-10-14", "08:30", "09:00", false, "Familie", "#0B7A55")),
            waste = listOf(AlltagWaste("2026-10-14", listOf("Gelbe Tonne"), null)),
        )
        val agenda = Agenda.merge(own, alltag, "2026-10-14", "2026-12-13")
        assertEquals(
            listOf("Gelbe Tonne", "Zahnarzt", "Elternabend", "Tim"),
            agenda.map {
                when (it) {
                    is AgendaEntry.Own -> it.event.title
                    is AgendaEntry.Alltag -> it.event.title
                    is AgendaEntry.Waste -> it.waste.kinds.single()
                }
            },
        )
        assertEquals(2, Agenda.merge(own, null, "2026-10-14", "2026-12-13").size)
    }

    @Test
    fun `api paths accept ids and fixed words only`() {
        assertEquals("locate/requests/12/answer", FamilieApi.path("locate", "requests", 12, "answer"))
        assertEquals("dates/member:5", FamilieApi.path("dates", "member:5"))
        assertNull(FamilieApi.path("shopping", "lists", "../admin", "going"))
        assertNull(FamilieApi.path("places", "1?x=y"))
        assertNull(FamilieApi.path("shopping", "lists", "..", "going"))
    }
}
