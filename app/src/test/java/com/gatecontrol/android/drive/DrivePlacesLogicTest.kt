package com.gatecontrol.android.drive

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** Drive 1.16: the map's bundles, a place's days, the search's suggestions and marks, the people filter, naming. */
class DrivePlacesLogicTest {

    private val zone = ZoneId.of("Europe/Berlin")

    private fun node(id: Long, taken: String?, district: String? = null, people: List<Long>? = null) = DNode(
        id, "mine:7", 2, "p$id.jpg", false, 10, "image/jpeg", null, 1, null, null, null,
        takenAt = taken,
        geo = district?.let { DGeo(53.5, 10.0, DPlace("geonames:1", "Hamburg", it)) },
        people = people,
    )

    private fun person(id: Long, name: String?, count: Int, hidden: Boolean = false) = DPerson(id, name, count, hidden, null, null)

    // ─── the map ────────────────────────────────────────────────────

    @Test
    fun `clamp keeps Mercator's latitudes and takes a box over the date line as the whole width`() {
        assertEquals(DBbox(-10.0, -85.0, 10.0, 85.0), DrivePlacesLogic.clamp(DBbox(-10.0, -89.0, 10.0, 89.0)))
        assertEquals(DBbox(-180.0, 10.0, 180.0, 20.0), DrivePlacesLogic.clamp(DBbox(170.0, 10.0, -170.0, 20.0)))
        assertEquals(DBbox(-180.0, 10.0, 180.0, 20.0), DrivePlacesLogic.clamp(DBbox(-200.0, 10.0, 200.0, 20.0)))
    }

    @Test
    fun `padded grows by half on every side`() {
        val p = DrivePlacesLogic.padded(DBbox(10.0, 50.0, 12.0, 52.0))
        assertEquals(DBbox(9.0, 49.0, 13.0, 53.0), p)
    }

    @Test
    fun `bundles load again only for another zoom or when the view leaves what was loaded`() {
        val view = DrivePlacesLogic.View(DBbox(10.0, 50.0, 12.0, 52.0), 8)
        assertTrue(DrivePlacesLogic.needsLoad(null, view))
        val loaded = DrivePlacesLogic.Loaded(DrivePlacesLogic.padded(view.bbox), 8)
        assertFalse(DrivePlacesLogic.needsLoad(loaded, view))
        // a little to the east: still inside the padded box
        assertFalse(DrivePlacesLogic.needsLoad(loaded, view.copy(bbox = DBbox(10.5, 50.0, 12.5, 52.0))))
        // far to the east: out of it
        assertTrue(DrivePlacesLogic.needsLoad(loaded, view.copy(bbox = DBbox(12.0, 50.0, 14.0, 52.0))))
        // zoomed in: the bundles split, load again
        assertTrue(DrivePlacesLogic.needsLoad(loaded, view.copy(zoom = 9)))
    }

    @Test
    fun `around spans the points with a margin, none without points`() {
        assertNull(DrivePlacesLogic.around(emptyList()))
        val b = DrivePlacesLogic.around(listOf(50.0 to 6.0, 54.0 to 10.0))!!
        assertEquals(5.4, b.west, 1e-9)
        assertEquals(49.4, b.south, 1e-9)
        assertEquals(10.6, b.east, 1e-9)
        assertEquals(54.6, b.north, 1e-9)
        // one point: a small box, not a line
        val one = DrivePlacesLogic.around(listOf(50.0 to 6.0))!!
        assertTrue(one.east > one.west && one.north > one.south)
    }

    // ─── days and time ──────────────────────────────────────────────

    @Test
    fun `byDay groups as the photos come, each day with its most frequent district`() {
        val items = listOf(
            node(1, "2026-03-14T15:00:00Z", "Landungsbrücken"),
            node(2, "2026-03-14T09:00:00Z", "Landungsbrücken"),
            node(3, "2026-03-14T08:00:00Z", "Altona"),
            node(4, "2026-03-13T20:00:00Z", "HafenCity"),
            node(5, null),
        )
        val days = DrivePlacesLogic.byDay(items, zone)
        assertEquals(listOf(LocalDate.of(2026, 3, 14), LocalDate.of(2026, 3, 13), null), days.map { it.date })
        assertEquals(listOf(3, 1, 1), days.map { it.items.size })
        assertEquals(listOf("Landungsbrücken", "HafenCity", null), days.map { it.district })
        assertEquals(2, DrivePlacesLogic.days(items, zone))
        // just after midnight in Berlin is the next day
        assertEquals(LocalDate.of(2026, 3, 15), DrivePlacesLogic.dayOf(node(9, "2026-03-14T23:30:00Z"), zone))
    }

    @Test
    fun `span names a month, months of a year, or years`() {
        val de = Locale.GERMAN
        assertEquals("Juli 2025", DrivePlacesLogic.span("2025-07-03T10:00:00Z", "2025-07-20T10:00:00Z", de, zone))
        assertEquals("März – Juli 2025", DrivePlacesLogic.span("2025-03-03T10:00:00Z", "2025-07-20T10:00:00Z", de, zone))
        assertEquals("2014 – 2026", DrivePlacesLogic.span("2014-05-01T10:00:00Z", "2026-03-01T10:00:00Z", de, zone))
        assertEquals("August 2023", DrivePlacesLogic.span("2023-08-10", null, de, zone))
        assertNull(DrivePlacesLogic.span(null, null, de, zone))
        assertEquals("2016 – 2026", DrivePlacesLogic.years(2026, 2016))
        assertEquals("2026", DrivePlacesLogic.years(2026, 2026))
        assertEquals("2020", DrivePlacesLogic.years(null, 2020))
        assertNull(DrivePlacesLogic.years(null, null))
    }

    // ─── people ─────────────────────────────────────────────────────

    @Test
    fun `peopleCounts counts who is on the photos, most first, and toggle filters`() {
        val items = listOf(node(1, null, people = listOf(7, 8)), node(2, null, people = listOf(8)), node(3, null))
        assertEquals(listOf(8L to 2, 7L to 1), DrivePlacesLogic.peopleCounts(items))
        assertEquals(listOf(7L), DrivePlacesLogic.toggle(emptyList(), 7))
        assertEquals(listOf(8L), DrivePlacesLogic.toggle(listOf(7, 8), 7))
    }

    @Test
    fun `groups order named, unnamed and hidden people, the bar asks for the biggest unnamed`() {
        val people = listOf(
            person(1, "Tim", 120), person(2, null, 41), person(3, "Lea", 812), person(4, null, 64),
            person(5, "Fremder", 3, hidden = true), person(6, "anna", 120),
        )
        val g = DrivePlacesLogic.groups(people)
        assertEquals(listOf(3L, 6L, 1L), g.named.map { it.id })
        assertEquals(listOf(4L, 2L), g.unnamed.map { it.id })
        assertEquals(listOf(5L), g.hidden.map { it.id })
        val (bar, who) = DrivePlacesLogic.bar(people, max = 2)
        assertEquals(listOf(3L, 6L), bar.map { it.id })
        assertEquals(4L, who?.id)
    }

    @Test
    fun `initials of one or two words`() {
        assertEquals("OG", DrivePlacesLogic.initials("Oma Gerda"))
        assertEquals("L", DrivePlacesLogic.initials("lea"))
        assertEquals("ÄB", DrivePlacesLogic.initials("Ärmel Bauer Clara"))
        assertEquals("?", DrivePlacesLogic.initials(null))
    }

    // ─── search ─────────────────────────────────────────────────────

    @Test
    fun `suggestions list people, places, then what is in the photos`() {
        val s = DSuggest(
            people = listOf(DPersonCount(7, "Mama", 530)),
            places = listOf(DSuggestPlace("geonames:2911298", "Hamburg", "St. Pauli, Altona", 214)),
            labels = listOf(DSuggestLabel("Beach", "Strand", 340)),
        )
        val list = DrivePlacesLogic.suggestions(s)
        assertEquals(listOf(DrivePlacesLogic.Suggestion.Kind.PERSON, DrivePlacesLogic.Suggestion.Kind.PLACE, DrivePlacesLogic.Suggestion.Kind.LABEL), list.map { it.kind })
        assertEquals(listOf("7", "geonames:2911298", "Beach"), list.map { it.id })
        assertEquals(listOf("Mama", "Hamburg", "Strand"), list.map { it.name })
        assertTrue(DrivePlacesLogic.suggestions(null).isEmpty())
    }

    @Test
    fun `complete replaces the word being typed`() {
        assertEquals("mama Strand", DrivePlacesLogic.complete("mama str", "Strand"))
        assertEquals("mama Strand", DrivePlacesLogic.complete("mama ", "Strand"))
        assertEquals("Strand", DrivePlacesLogic.complete("", "Strand"))
    }

    @Test
    fun `marks become chips and their chip's cross takes their words out`() {
        val marks = DrivePlacesLogic.marks(DSearchMarks(listOf(7L to "Mama"), listOf("geonames:1" to "Hamburg")))
        assertEquals(listOf(DrivePlacesLogic.Mark(7, null, "Mama"), DrivePlacesLogic.Mark(null, "geonames:1", "Hamburg")), marks)
        assertEquals("hamburg strand", DrivePlacesLogic.withoutMark("mama hamburg strand", marks[0]))
        assertEquals("Mama strand", DrivePlacesLogic.withoutMark("Mama Hambúrg strand", marks[1]))
        assertEquals("koln", DrivePlacesLogic.fold(" Köln "))
    }

    // ─── naming ─────────────────────────────────────────────────────

    @Test
    fun `name options come from family first, each name once, matched at a word's start`() {
        val family = listOf(
            DrivePlacesLogic.NameOption("family", "3", "Oma Gerda", null),
            DrivePlacesLogic.NameOption("family", "4", "Opa Heinz", null),
        )
        val contacts = listOf(
            DrivePlacesLogic.NameOption("contact", "10", "Klaus Kramer", "Privat"),
            DrivePlacesLogic.NameOption("contact", "11", "oma gerda", null),
            DrivePlacesLogic.NameOption("contact", "12", "Lea Lorenz", "Elternbeirat"),
        )
        val named = mapOf("oma gerda" to 96)
        val all = DrivePlacesLogic.nameOptions("", family, contacts, named)
        assertEquals(listOf("Oma Gerda", "Opa Heinz", "Klaus Kramer"), all.map { it.name })
        assertEquals(96, all[0].namedCount)
        assertNull(all[1].namedCount)
        assertEquals(listOf("Oma Gerda"), DrivePlacesLogic.nameOptions("ger", family, contacts, named).map { it.name })
        assertEquals(listOf("Lea Lorenz"), DrivePlacesLogic.nameOptions("lor", family, contacts, named).map { it.name })
        assertTrue(DrivePlacesLogic.nameOptions("erda", family, contacts, named).isEmpty())
    }
}
