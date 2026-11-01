package com.gatecontrol.android.drive

import com.gatecontrol.android.drive.DriveOverviewLogic.Section
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId

/** Drive 1.18: `GET overview`, the overview of an older Drive, which sections show, the labels. */
class DriveOverviewTest {

    private fun json(s: String) = JsonParser.parseString(s).asJsonObject
    private val utc = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 11, 1)

    private fun node(id: Long, name: String, mime: String? = null, takenAt: String? = null, dir: Boolean = false) =
        DNode(id, "mine:7", 1, name, dir, 10, mime, null, 1, null, null, null, takenAt = takenAt)

    /** An answer as the contract describes it. */
    private val answer = """
        {"ok":true,
         "todo":{"deadlines":[
             {"id":1,"kind":"payment","date":"2026-11-04","label":"Rechnung Stadtwerke zahlen","node":{"id":11,"areaId":"family:1","name":"Rechnung.pdf"}},
             {"id":2,"kind":"cancellation","date":"2026-11-13","label":"Kfz-Versicherung kündigen"},
             {"id":3,"kind":"bogus","date":"2026-11-14"},
             {"id":4,"kind":"payment","date":"2026-11-15"}],
           "deadlinesTotal":2,"review":12,"toFile":3,"toFileHint":" Familie › Versicherungen "},
         "recent":[
             {"id":21,"areaId":"family:1","name":"Steuerbescheid 2025.pdf","mime":"application/pdf","openedAt":"2026-10-31T09:00:00Z","areaLabel":"Familie"},
             {"id":22,"areaId":"mine:7","name":"Urlaub.jpg","mime":"image/jpeg"},
             {"id":23,"areaId":"mine:7","name":"Ordner","isDir":true},
             {"id":24,"areaId":"mine:7","name":"Haushaltsbuch.xlsx","changedAt":"2026-10-29T09:00:00Z"}],
         "memories":[
             {"yearsAgo":2,"date":"2024-11-01","cover":{"id":31,"areaId":"mine:7","name":"Strand.jpg","takenAt":"2024-11-01T10:00:00Z"},"count":30},
             {"yearsAgo":1,"date":"2025-11-01","count":2}],
         "people":[{"id":41,"name":"Lea","count":30},{"id":42,"name":"Mama","count":20},{"id":43,"name":null,"count":9},{"id":44,"name":"Weg","hidden":true}],
         "unnamed":4,
         "places":{"top":[{"key":"geonames:2886242","name":"Köln","count":1629},{"key":"geonames:2911298","name":"Hamburg","count":214},{"key":"bad key!","name":"x"}],
                   "cluster":{"lat":50.94,"lon":6.96,"count":3871},"total":3871},
         "activity":[
             {"at":"2026-11-01T08:00:00Z","user":{"id":2,"name":"Mama"},"kind":"added_photos","count":14,"area":{"id":"family:1","label":"Familie"},"folder":{"id":51,"path":"Familie/Urlaub 2026"},"sample":{"id":52,"areaId":"family:1","name":"IMG.jpg"}},
             {"at":"2026-10-31T08:00:00Z","user":{"id":3,"name":"Papa"},"kind":"added_files","count":1,"area":{"id":"family:1","label":"Familie"},"folder":null,"sample":{"id":53,"areaId":"family:1","name":"Zeugnis Lea.pdf"}},
             {"kind":"deleted","count":1}],
         "storage":{"used":212000000000,"total":1000000000000,"target":"NAS"}}
    """.trimIndent()

    @Test
    fun `GET overview is taken as the contract says, the rest is dropped`() {
        val o = DriveOverviewJson.overview(json(answer))!!
        assertFalse(o.assembled)
        val t = o.todo!!
        assertEquals(listOf(1L, 2L, 4L), t.deadlines.map { it.id })
        // the total is never below what came
        assertEquals(3, t.deadlinesTotal)
        assertEquals(12, t.review)
        assertEquals(3, t.toFile)
        assertEquals("Familie › Versicherungen", t.toFileHint)
        // no folders, photos or videos in "Zuletzt geöffnet"
        assertEquals(listOf("Steuerbescheid 2025.pdf", "Haushaltsbuch.xlsx"), o.recent.map { it.node.name })
        assertEquals("Familie", o.recent[0].areaLabel)
        assertEquals("2026-10-29T09:00:00Z", o.recent[1].changedAt)
        // a memory without a cover (or items) is left out
        assertEquals(1, o.memories.size)
        assertEquals(30, o.memories[0].count)
        val p = o.people!!
        assertEquals(listOf("Lea", "Mama"), p.people.map { it.name })
        assertEquals(4, p.unnamed)
        assertEquals(43L, p.firstUnnamed)
        assertEquals(listOf("Köln", "Hamburg"), o.places!!.top.map { it.name })
        assertEquals(3871, o.places!!.cluster!!.count)
        assertEquals(2, o.activity.size)
        assertEquals("Familie/Urlaub 2026", o.activity[0].folderPath)
        assertEquals(DOverviewStorage(212_000_000_000, 1_000_000_000_000, "NAS"), o.storage)
    }

    @Test
    fun `nested answer, people as an object, wrong parts empty`() {
        val o = DriveOverviewJson.overview(json("""{"overview":{"people":{"people":[{"id":1,"name":"Tim"}],"unnamed":0},"places":{"top":[]},"storage":{"total":5}}}"""))!!
        assertEquals(listOf("Tim"), o.people!!.people.map { it.name })
        assertEquals(0, o.people!!.unnamed)
        assertNull(o.todo)
        assertFalse(DriveOverviewLogic.hasPlaces(o.places))
        assertNull(o.storage)
        val empty = DriveOverviewJson.overview(json("{}"))!!
        assertEquals(DOverview(), empty)
        // only the search
        assertEquals(listOf(Section.SEARCH), DriveOverviewLogic.sections(empty, null))
    }

    @Test
    fun `sections show in the mockup order, an empty one stays out`() {
        val o = DriveOverviewJson.overview(json(answer))!!
        val saved = DriveOverviewLogic.Backup.Saved(1L)
        assertEquals(
            listOf(Section.SEARCH, Section.STATUS, Section.TODO, Section.RECENT, Section.MEMORIES, Section.PEOPLE, Section.PLACES, Section.ACTIVITY),
            DriveOverviewLogic.sections(o, saved),
        )
        // nothing to do, no recent files, no memories, no activity, no storage and the backup off
        val bare = o.copy(todo = DTodo(emptyList(), 0, 0, null, null), recent = emptyList(), memories = emptyList(), activity = emptyList(), storage = null)
        assertEquals(listOf(Section.SEARCH, Section.PEOPLE, Section.PLACES), DriveOverviewLogic.sections(bare, null))
        // the backup alone gives the status row
        assertEquals(listOf(Section.SEARCH, Section.STATUS, Section.PEOPLE, Section.PLACES), DriveOverviewLogic.sections(bare, DriveOverviewLogic.Backup.Waiting))
        // people: "Wer ist das?" alone still shows, nobody at all does not
        assertTrue(DriveOverviewLogic.hasPeople(DOverviewPeople(emptyList(), 2)))
        assertFalse(DriveOverviewLogic.hasPeople(DOverviewPeople(emptyList(), 0)))
        // "Zum Ablegen" alone is something to do
        assertTrue(DriveOverviewLogic.hasTodo(DTodo(emptyList(), 0, 0, 3, null)))
        assertFalse(DriveOverviewLogic.hasTodo(DTodo(emptyList(), 0, 0, 0, null)))
        assertEquals(5, DriveOverviewLogic.todoCount(o.todo))
        assertEquals(0, DriveOverviewLogic.todoCount(null))
        // still loading: only the search
        assertEquals(listOf(Section.SEARCH), DriveOverviewLogic.sections(null, saved))
    }

    @Test
    fun `labels - Heute vor 2 Jahren, Vor einem Jahr`() {
        val two = DMemoryCard(2, "2024-11-01", node(1, "a.jpg", takenAt = "2024-11-01T10:00:00Z"), 30)
        assertEquals(DriveOverviewLogic.MemoryLabel(2, today = true), DriveOverviewLogic.memoryLabel(two, today, utc))
        // a day beside it (the plugin takes ± 1 day)
        val one = DMemoryCard(1, "2025-11-01", node(2, "b.jpg", takenAt = "2025-10-31T20:00:00Z"), 2)
        assertEquals(DriveOverviewLogic.MemoryLabel(1, today = false), DriveOverviewLogic.memoryLabel(one, today, utc))
        // no time on the cover: the group's date; no yearsAgo: from the date
        val dated = DMemoryCard(0, "2023-11-01", node(3, "c.jpg"), 1)
        assertEquals(DriveOverviewLogic.MemoryLabel(3, today = true), DriveOverviewLogic.memoryLabel(dated, today, utc))
        // nothing to go by: "An diesem Tag"
        assertEquals(0, DriveOverviewLogic.memoryLabel(DMemoryCard(0, null, node(4, "d.jpg"), 1), today, utc).years)
    }

    @Test
    fun `the photo backup as a line`() {
        assertNull(DriveOverviewLogic.backup(enabled = false, running = true, done = 1, total = 2, lastRunAt = 5, error = null))
        assertEquals(DriveOverviewLogic.Backup.Running(1, 2), DriveOverviewLogic.backup(true, true, 1, 2, 5, "x"))
        assertEquals(DriveOverviewLogic.Backup.Stopped("no_permission"), DriveOverviewLogic.backup(true, false, 0, 0, 5, "no_permission"))
        assertEquals(DriveOverviewLogic.Backup.Saved(5), DriveOverviewLogic.backup(true, false, 0, 0, 5, null))
        assertEquals(DriveOverviewLogic.Backup.Waiting, DriveOverviewLogic.backup(true, false, 0, 0, 0, null))
    }

    @Test
    fun `without overview the overview is built from the other endpoints`() {
        val d = { id: Long, date: String -> DDeadline(id, "payment", date, null, null, node = node(id, "f$id.pdf")) }
        val o = DriveOverviewLogic.assemble(
            today = today,
            deadlines = listOf(d(2, "2026-11-20"), d(1, "2026-10-01"), d(3, "2026-11-02"), d(5, "2026-11-01"), d(6, "2026-12-24")),
            classes = DPhotoClasses(work = 1, private = 2, review = 5),
            memories = listOf(DMemories(1, "2025-11-01", listOf(node(9, "a.jpg"), node(10, "b.jpg"))), DMemories(2, null, emptyList())),
            people = DPeople(
                listOf(
                    DPerson(1, "Mama", 5, false, null, null),
                    DPerson(2, null, 9, false, null, null),
                    DPerson(3, null, 20, false, null, null),
                    DPerson(4, "X", 99, true, null, null),
                ),
                null,
                null,
            ),
            places = listOf(
                DPlaceSummary("b", "Hamburg", null, "DE", 2, 53.5, 10.0, null, null, null),
                DPlaceSummary("a", "Köln", null, "DE", 7, 50.9, 6.9, null, null, null),
            ),
            storage = DOverviewStorage(100, 1000, "NAS"),
        )
        assertTrue(o.assembled)
        // from today on, by date; at most three, the total of all
        assertEquals(listOf(5L, 3L, 2L), o.todo!!.deadlines.map { it.id })
        assertEquals(4, o.todo!!.deadlinesTotal)
        assertEquals(5, o.todo!!.review)
        assertNull(o.todo!!.toFile)
        assertEquals(listOf(1 to 2), o.memories.map { it.yearsAgo to it.count })
        assertEquals(9L, o.memories[0].cover.id)
        assertEquals(listOf("Mama"), o.people!!.people.map { it.name })
        assertEquals(2, o.people!!.unnamed)
        assertEquals(3L, o.people!!.firstUnnamed)
        assertEquals(listOf("Köln", "Hamburg"), o.places!!.top.map { it.name })
        assertEquals(9, o.places!!.total)
        assertEquals(9, DriveOverviewLogic.mapCluster(o.places!!)!!.count)
        // "Zuletzt geöffnet" and "Neu in …" cannot be built: they stay out
        assertEquals(
            listOf(Section.SEARCH, Section.STATUS, Section.TODO, Section.MEMORIES, Section.PEOPLE, Section.PLACES),
            DriveOverviewLogic.sections(o, null),
        )
        // features off: their parts are missing
        val none = DriveOverviewLogic.assemble(today, null, null, null, null, null, null)
        assertNull(none.todo)
        assertNull(none.people)
        assertNull(none.places)
        assertEquals(listOf(Section.SEARCH), DriveOverviewLogic.sections(none, null))
        // the review count alone is something to do
        val review = DriveOverviewLogic.assemble(today, null, DPhotoClasses(0, 0, 4), null, null, null, null)
        assertEquals(listOf(Section.SEARCH, Section.TODO), DriveOverviewLogic.sections(review, null))
    }

    @Test
    fun `the storage of Meine Dateien and how full it is`() {
        val areas = listOf(
            DArea("family:1", "family", "Familie", "write", "member", 2, false, DStorage(9, null, null, null, null)),
            DArea("mine:7", "mine", "Meine Dateien", "manage", "owner", 1, false, DStorage(250, 1000, null, "NAS", null)),
        )
        val s = DriveOverviewLogic.storageOf(areas)!!
        assertEquals(DOverviewStorage(250, 1000, "NAS"), s)
        assertEquals(0.25f, DriveOverviewLogic.fill(s)!!, 0.0001f)
        assertNull(DriveOverviewLogic.fill(s.copy(total = null)))
        assertNull(DriveOverviewLogic.storageOf(areas.take(1)))
    }

    @Test
    fun `the small map - a region around the bubble, else around the places`() {
        val one = DOverviewPlaces(listOf(DPlaceTop("k", "Köln", 7)), DCluster(50.94, 6.96, 3871, null, null), 3871)
        val b = DriveOverviewLogic.mapBox(one)!!
        assertTrue(b.west < 6.96 && b.east > 6.96 && b.south < 50.94 && b.north > 50.94)
        // a region, not a street
        assertTrue(b.east - b.west >= 4.0)
        val two = DOverviewPlaces(listOf(DPlaceTop("a", "Köln", 7, 50.9, 6.9), DPlaceTop("b", "Hamburg", 2, 53.5, 10.0)), null, 9)
        val bb = DriveOverviewLogic.mapBox(two)!!
        assertTrue(bb.west < 6.9 && bb.east > 10.0 && bb.south < 50.9 && bb.north > 53.5)
        assertEquals(DCluster(50.9, 6.9, 9, null, null), DriveOverviewLogic.mapCluster(two))
        assertNull(DriveOverviewLogic.mapBox(DOverviewPlaces(listOf(DPlaceTop("k", "x", 1)), null, 1)))
    }

    @Test
    fun `small helpers`() {
        assertTrue(DriveOverviewLogic.isMedia(node(1, "a.JPG")))
        assertTrue(DriveOverviewLogic.isMedia(node(2, "clip", mime = "video/mp4")))
        assertFalse(DriveOverviewLogic.isMedia(node(3, "a.pdf")))
        assertEquals("Urlaub 2026", DriveOverviewLogic.folderName("Familie/Urlaub 2026/"))
        assertNull(DriveOverviewLogic.folderName(""))
        assertNotNull(DriveOverviewLogic.millis("2026-11-01T08:00:00Z"))
        assertNull(DriveOverviewLogic.millis("gestern"))
    }
}
