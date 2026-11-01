package com.gatecontrol.android.drive

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The pages of the Drive tab, sorting by file type (photos and videos are files like any other). */
class DriveLogicTabsTest {

    private fun file(id: Long, name: String, mime: String?, vault: String? = null) =
        DNode(id, "mine:7", 1, name, false, 10, mime, null, 1, null, null, null, vaultId = vault)

    private fun dir(id: Long, name: String, vault: String? = null) =
        DNode(id, "mine:7", 1, name, true, 0, null, null, 1, null, null, null, vaultId = vault)

    @Test
    fun `the pages of the Drive tab - Uebersicht, Dateien, Fotos`() {
        assertEquals(listOf(DriveLogic.Tab.OVERVIEW, DriveLogic.Tab.FILES), DriveLogic.tabs(hasPhotos = false))
        assertEquals(listOf(DriveLogic.Tab.OVERVIEW, DriveLogic.Tab.FILES, DriveLogic.Tab.PHOTOS), DriveLogic.tabs(hasPhotos = true))
        assertEquals(2, DriveLogic.tabCount(hasPhotos = false))
        assertEquals(3, DriveLogic.tabCount(hasPhotos = true))
        // Drive opens on the overview, with or without photos
        assertEquals(0, DriveLogic.pageOf(DriveLogic.Tab.OVERVIEW, hasPhotos = true))
        assertEquals(0, DriveLogic.pageOf(DriveLogic.Tab.OVERVIEW, hasPhotos = false))
        // the files keep their page when the photos come (me loads later)
        assertEquals(DriveLogic.pageOf(DriveLogic.Tab.FILES, false), DriveLogic.pageOf(DriveLogic.Tab.FILES, true))
        assertEquals(2, DriveLogic.pageOf(DriveLogic.Tab.PHOTOS, hasPhotos = true))
        // the photos without the Photos page: the files
        assertEquals(1, DriveLogic.pageOf(DriveLogic.Tab.PHOTOS, hasPhotos = false))
        assertEquals(DriveLogic.Tab.PHOTOS, DriveLogic.tabAt(2, hasPhotos = true))
        // a page beyond the last (the photos went away): the last
        assertEquals(DriveLogic.Tab.FILES, DriveLogic.tabAt(2, hasPhotos = false))
        assertEquals(DriveLogic.Tab.OVERVIEW, DriveLogic.tabAt(-1, hasPhotos = false))
        // swiping: not while items are ticked
        assertTrue(DriveLogic.tabSwipe(selecting = false))
        assertFalse(DriveLogic.tabSwipe(selecting = true))
        // round trip page → tab → page
        for (photos in listOf(false, true)) {
            for (page in 0 until DriveLogic.tabCount(photos)) assertEquals(page, DriveLogic.pageOf(DriveLogic.tabAt(page, photos), photos))
        }
    }

    @Test
    fun `sort by file type - folders first, then the groups, extension, name`() {
        val items = listOf(
            file(1, "z.zip", "application/zip"),
            file(2, "Brief.docx", null),
            dir(3, "b-Ordner"),
            file(4, "Liste.xlsx", null),
            file(5, "notes.txt", "text/plain"),
            file(6, "Rechnung.pdf", "application/pdf"),
            file(7, "Folien.pptx", null),
            file(8, "Song.mp3", "audio/mpeg"),
            file(9, "data.bin", "application/octet-stream"),
            dir(10, "A-Ordner"),
            file(11, "alt.doc", null),
            file(12, "Antwort.docx", null),
            file(13, "readme.md", null),
            file(14, "Urlaub.mp4", "video/mp4"),
            file(15, "IMG_2.jpg", "image/jpeg"),
            file(16, "IMG_1.heic", "image/heic"),
            file(17, "Clip.mov", null),
            file(18, "IMG_3.JPG", null),
        )
        val up = DriveLogic.sortByType(items, desc = false)
        // folders, documents, spreadsheets, presentations, PDF, pictures, videos, text, archives, audio, the rest
        assertEquals(
            listOf(
                "A-Ordner", "b-Ordner",
                "alt.doc", "Antwort.docx", "Brief.docx",
                "Liste.xlsx",
                "Folien.pptx",
                "Rechnung.pdf",
                "IMG_1.heic", "IMG_2.jpg", "IMG_3.JPG",
                "Clip.mov", "Urlaub.mp4",
                "readme.md", "notes.txt",
                "z.zip",
                "Song.mp3",
                "data.bin",
            ),
            up.map { it.name },
        )
        // descending: the order turned around, the folders still first
        val down = DriveLogic.sortByType(items, desc = true)
        assertEquals(listOf("b-Ordner", "A-Ordner"), down.take(2).map { it.name })
        assertEquals(up.drop(2).reversed(), down.drop(2))
        // the other sorts stay the server's
        assertEquals(items, DriveLogic.ordered(items, DriveLogic.FolderSort("mtime", true)))
        assertEquals(up, DriveLogic.ordered(items, DriveLogic.FolderSort("type", false)))
    }

    @Test
    fun `pictures and videos are groups of their own`() {
        val pdf = DriveLogic.typeGroup(file(1, "a.pdf", "application/pdf"))
        val image = DriveLogic.typeGroup(file(2, "a.png", "image/png"))
        val video = DriveLogic.typeGroup(file(3, "a.mp4", "video/mp4"))
        val text = DriveLogic.typeGroup(file(4, "a.txt", "text/plain"))
        assertTrue(pdf < image)
        assertTrue(image < video)
        assertTrue(video < text)
        assertFalse(image == video)
        // the rest last
        assertTrue(DriveLogic.typeGroup(file(5, "a.bin", "application/octet-stream")) > DriveLogic.typeGroup(file(6, "a.mp3", "audio/mpeg")))
    }

    @Test
    fun `the sort choice and its direction`() {
        assertEquals(DriveLogic.FolderSort("name", false), DriveLogic.sortOf(null, null))
        assertEquals(DriveLogic.FolderSort("name", false), DriveLogic.sortOf("bogus", null))
        assertEquals(DriveLogic.FolderSort("mtime", true), DriveLogic.sortOf("mtime", null))
        assertEquals(DriveLogic.FolderSort("size", true), DriveLogic.sortOf("size", null))
        assertEquals(DriveLogic.FolderSort("type", false), DriveLogic.sortOf("type", null))
        assertEquals(DriveLogic.FolderSort("type", true), DriveLogic.sortOf("type", true))
        assertEquals("name", DriveLogic.serverSort("type"))
        assertEquals("size", DriveLogic.serverSort("size"))
    }

    @Test
    fun `a free name in a vault folder`() {
        assertEquals("a.pdf", DriveLogic.freeName("a.pdf", listOf("b.pdf")))
        assertEquals("a (2).pdf", DriveLogic.freeName("a.pdf", listOf("A.PDF")))
        assertEquals("a (3).pdf", DriveLogic.freeName("a.pdf", listOf("a.pdf", "a (2).pdf")))
        assertEquals("Notiz (2)", DriveLogic.freeName("Notiz", listOf("notiz")))
    }
}

/** `PATCH areas/<id> { docAi }` and the area's setting. */
class DriveJsonDocAiTest {
    private fun json(s: String) = com.google.gson.JsonParser.parseString(s).asJsonObject

    @Test
    fun `docAi of an area, its body and its answer`() {
        assertEquals(true, DriveJson.area(json("""{"id":"mine:7","rootId":1,"docAi":true}"""))!!.docAi)
        assertEquals(null, DriveJson.area(json("""{"id":"mine:7","rootId":1}"""))!!.docAi)
        assertEquals("""{"docAi":false}""", DriveJson.docAiBody(false).toString())
        assertEquals(false, DriveJson.areaDocAi(json("""{"ok":true,"area":{"id":"mine:7","docAi":false}}""")))
        assertEquals(null, DriveJson.areaDocAi(json("""{"ok":true}""")))
    }
}
