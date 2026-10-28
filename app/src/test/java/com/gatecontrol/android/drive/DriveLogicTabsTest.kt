package com.gatecontrol.android.drive

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Photos only in the Photos tab, the two pages of the Drive tab, sorting by file type. */
class DriveLogicTabsTest {

    private fun file(id: Long, name: String, mime: String?, vault: String? = null) =
        DNode(id, "mine:7", 1, name, false, 10, mime, null, 1, null, null, null, vaultId = vault)

    private fun dir(id: Long, name: String, vault: String? = null) =
        DNode(id, "mine:7", 1, name, true, 0, null, null, 1, null, null, null, vaultId = vault)

    private fun me(vararg features: String) =
        DMe(1, "A", "family", true, null, emptyList(), 0, null, null, emptyMap(), features = features.toSet())

    @Test
    fun `the rule of GET photos - image and video by type only`() {
        assertTrue(DriveLogic.isPhoto(file(1, "a.jpg", "image/jpeg")))
        assertTrue(DriveLogic.isPhoto(file(2, "a.mov", "video/quicktime")))
        assertTrue(DriveLogic.isPhoto(file(3, "a.heic", "IMAGE/HEIC")))
        assertTrue(DriveLogic.isPhoto(file(4, "scan", "image/png")))
        // the type counts, not the extension (photos.js: mime LIKE 'image/%' OR 'video/%')
        assertFalse(DriveLogic.isPhoto(file(5, "a.jpg", null)))
        assertFalse(DriveLogic.isPhoto(file(6, "a.jpg", "application/octet-stream")))
        assertFalse(DriveLogic.isPhoto(file(7, "a.mp3", "audio/mpeg")))
        assertFalse(DriveLogic.isPhoto(file(8, "a.pdf", "application/pdf")))
        assertFalse(DriveLogic.isPhoto(file(9, "x", "text/image/png")))
        // never a folder, never a vault's file, never one in the trash
        assertFalse(DriveLogic.isPhoto(dir(10, "Bilder")))
        assertFalse(DriveLogic.isPhoto(file(11, "a.jpg", "image/jpeg", vault = "Vt9bJm0n2QwErTyUiOpAsD")))
        assertFalse(DriveLogic.isPhoto(file(12, "a.jpg", "image/jpeg").copy(deletedAt = "2026-10-01T00:00:00Z")))
    }

    @Test
    fun `the Files tab leaves the photos out and counts them`() {
        val items = listOf(dir(1, "Urlaub"), file(2, "a.jpg", "image/jpeg"), file(3, "b.pdf", "application/pdf"), file(4, "c.mp4", "video/mp4"))
        val v = DriveLogic.filesView(items, hidePhotos = true)
        assertEquals(listOf(1L, 3L), v.items.map { it.id })
        assertEquals(2, v.photos)
        assertFalse(v.empty)
        // without the Photos tab (or in a vault): everything as before
        val all = DriveLogic.filesView(items, hidePhotos = false)
        assertEquals(items, all.items)
        assertEquals(0, all.photos)
        // a folder of photos only is not "empty"
        val onlyPhotos = DriveLogic.filesView(listOf(file(2, "a.jpg", "image/jpeg")), hidePhotos = true)
        assertTrue(onlyPhotos.items.isEmpty())
        assertFalse(onlyPhotos.empty)
        assertTrue(DriveLogic.filesView(emptyList(), hidePhotos = true).empty)
    }

    @Test
    fun `photos are hidden only with the Photos tab and never in a vault`() {
        assertTrue(DriveLogic.hidesPhotos(me(DMe.FEATURE_PHOTOS), dir(1, "Dokumente")))
        assertTrue(DriveLogic.hidesPhotos(me(DMe.FEATURE_PHOTOS), null))
        assertFalse(DriveLogic.hidesPhotos(me(), dir(1, "Dokumente")))
        assertFalse(DriveLogic.hidesPhotos(null, dir(1, "Dokumente")))
        assertFalse(DriveLogic.hidesPhotos(me(DMe.FEATURE_PHOTOS), dir(1, "0123456789abcdef0123456789abcdef", vault = "Vt9bJm0n2QwErTyUiOpAsD")))
    }

    @Test
    fun `the pages of the Drive tab`() {
        assertEquals(1, DriveLogic.tabCount(hasPhotos = false))
        assertEquals(2, DriveLogic.tabCount(hasPhotos = true))
        assertEquals(DriveLogic.TAB_PHOTOS, DriveLogic.tabOf(photos = true, hasPhotos = true))
        assertEquals(DriveLogic.TAB_FILES, DriveLogic.tabOf(photos = false, hasPhotos = true))
        // the saved "photos" without the Photos tab: the files
        assertEquals(DriveLogic.TAB_FILES, DriveLogic.tabOf(photos = true, hasPhotos = false))
        assertTrue(DriveLogic.isPhotosTab(DriveLogic.TAB_PHOTOS, hasPhotos = true))
        assertFalse(DriveLogic.isPhotosTab(DriveLogic.TAB_FILES, hasPhotos = true))
        assertFalse(DriveLogic.isPhotosTab(DriveLogic.TAB_PHOTOS, hasPhotos = false))
        // swiping: two pages, nothing ticked
        assertTrue(DriveLogic.tabSwipe(hasPhotos = true, selecting = false))
        assertFalse(DriveLogic.tabSwipe(hasPhotos = true, selecting = true))
        assertFalse(DriveLogic.tabSwipe(hasPhotos = false, selecting = false))
        // round trip page → saved choice → page
        for (page in 0 until DriveLogic.tabCount(true)) {
            assertEquals(page, DriveLogic.tabOf(DriveLogic.isPhotosTab(page, true), true))
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
        )
        val up = DriveLogic.sortByType(items, desc = false)
        assertEquals(
            listOf("A-Ordner", "b-Ordner", "alt.doc", "Antwort.docx", "Brief.docx", "Liste.xlsx", "Folien.pptx", "Rechnung.pdf", "readme.md", "notes.txt", "z.zip", "Song.mp3", "data.bin"),
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
