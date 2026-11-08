package com.gatecontrol.android.drive.device

import com.gatecontrol.android.drive.device.DeviceFilesLogic.Category
import com.gatecontrol.android.drive.device.DeviceFilesLogic.Filter
import com.gatecontrol.android.drive.device.DeviceFilesLogic.Hit
import com.gatecontrol.android.drive.device.DeviceFilesLogic.InDrive
import com.gatecontrol.android.drive.device.DeviceFilesLogic.Part
import com.gatecontrol.android.drive.device.DeviceFilesLogic.Sort
import com.gatecontrol.android.drive.device.DeviceFilesLogic.Type
import com.gatecontrol.android.drive.device.DeviceFilesLogic.VolumeKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** "Auf dem Gerät": kinds and categories, sorting and filtering, the "In Drive" rule, ZIP safety, places, the bar. */
class DeviceFilesLogicTest {

    private fun file(name: String, size: Long = 1, modified: Long = 0, mime: String? = null) =
        DeviceEntry(path = "/storage/emulated/0/Download/$name", name = name, isDir = false, size = size, modified = modified, mime = mime)

    private fun dir(name: String, modified: Long = 0) =
        DeviceEntry(path = "/storage/emulated/0/Download/$name", name = name, isDir = true, size = 0, modified = modified)

    // ─── kinds and categories ───────────────────────────────────────

    @Test
    fun `kinds by the name first, then by the MIME type`() {
        assertEquals(Type.PDF, DeviceFilesLogic.typeOf("Rechnung.PDF", null))
        assertEquals(Type.IMAGE, DeviceFilesLogic.typeOf("IMG_1.heic", "application/octet-stream"))
        assertEquals(Type.VIDEO, DeviceFilesLogic.typeOf("clip.ts", "text/vnd.trolltech.linguist"), "a .ts video is no TypeScript file")
        assertEquals(Type.AUDIO, DeviceFilesLogic.typeOf("Sprachmemo.m4a", null))
        assertEquals(Type.DOCUMENT, DeviceFilesLogic.typeOf("Mietvertrag.docx", null))
        assertEquals(Type.ARCHIVE, DeviceFilesLogic.typeOf("Fotos.zip", null))
        assertEquals(Type.APK, DeviceFilesLogic.typeOf("App.apk", null))
        assertEquals(Type.IMAGE, DeviceFilesLogic.typeOf("Scan", "image/jpeg"))
        assertEquals(Type.PDF, DeviceFilesLogic.typeOf("Brief", "application/pdf"))
        assertEquals(Type.DOCUMENT, DeviceFilesLogic.typeOf("Notiz", "text/plain; charset=utf-8"))
        assertEquals(Type.OTHER, DeviceFilesLogic.typeOf("daten.bin", "application/octet-stream"))
        assertEquals(Type.FOLDER, DeviceFilesLogic.typeOf("Download", null, isDir = true))
    }

    @Test
    fun `categories of the start page`() {
        assertEquals(Category.IMAGES, DeviceFilesLogic.categoryOf(Type.IMAGE))
        assertEquals(Category.VIDEOS, DeviceFilesLogic.categoryOf(Type.VIDEO))
        assertEquals(Category.AUDIO, DeviceFilesLogic.categoryOf(Type.AUDIO))
        assertEquals(Category.DOCUMENTS, DeviceFilesLogic.categoryOf(Type.PDF))
        assertEquals(Category.DOCUMENTS, DeviceFilesLogic.categoryOf(Type.DOCUMENT))
        assertEquals(Category.ARCHIVES, DeviceFilesLogic.categoryOf(Type.ARCHIVE))
        assertEquals(Category.ARCHIVES, DeviceFilesLogic.categoryOf(Type.APK))
        assertNull(DeviceFilesLogic.categoryOf(Type.OTHER))
        assertNull(DeviceFilesLogic.categoryOf(Type.FOLDER))
        assertEquals(Category.ARCHIVES, Category.of("archives"))
        assertNull(Category.of("nope"))
    }

    @Test
    fun `download is a place, hidden and app folders stay out`() {
        assertTrue(DeviceFilesLogic.isDownload("Download/"))
        assertTrue(DeviceFilesLogic.isDownload("Download/Rechnungen/"))
        assertFalse(DeviceFilesLogic.isDownload("Documents/Download/"))
        assertFalse(DeviceFilesLogic.isDownload(null))
        assertTrue(DeviceFilesLogic.hidden("/storage/emulated/0/.thumbnails/a.jpg"))
        assertTrue(DeviceFilesLogic.hidden("/storage/emulated/0/Android/data/x/file.txt"))
        assertTrue(DeviceFilesLogic.hidden("/storage/emulated/0/Android/media/com.whatsapp/a.jpg"))
        assertFalse(DeviceFilesLogic.hidden("/storage/emulated/0/Download/a.pdf"))
        assertFalse(DeviceFilesLogic.hidden("/storage/emulated/0/Android/Notes/a.txt"), "only the apps' own folders")
    }

    // ─── sort and filter ────────────────────────────────────────────

    @Test
    fun `folders first, then by the order chosen`() {
        val list = listOf(file("b.pdf", size = 10, modified = 3), dir("Zeug", 9), file("A.jpg", size = 30, modified = 1), dir("alt", 1), file("c.zip", size = 20, modified = 2))
        assertEquals(listOf("alt", "Zeug", "A.jpg", "b.pdf", "c.zip"), DeviceFilesLogic.view(list, Sort.NAME, Filter.ALL).map { it.name })
        assertEquals(listOf("Zeug", "alt", "b.pdf", "c.zip", "A.jpg"), DeviceFilesLogic.view(list, Sort.NEWEST, Filter.ALL).map { it.name })
        assertEquals(listOf("alt", "Zeug", "A.jpg", "c.zip", "b.pdf"), DeviceFilesLogic.view(list, Sort.SIZE, Filter.ALL).map { it.name })
    }

    @Test
    fun `a filter shows its kind only, no folders`() {
        val list = listOf(file("b.pdf"), dir("Zeug"), file("A.jpg"), file("c.zip"), file("d.apk"))
        assertEquals(listOf("b.pdf"), DeviceFilesLogic.view(list, Sort.NAME, Filter.PDF).map { it.name })
        assertEquals(listOf("c.zip", "d.apk"), DeviceFilesLogic.view(list, Sort.NAME, Filter.ARCHIVES).map { it.name })
        assertEquals(listOf(Filter.ALL, Filter.PDF, Filter.IMAGES, Filter.ARCHIVES), DeviceFilesLogic.filters(list))
        assertEquals(listOf(Filter.ALL), DeviceFilesLogic.filters(listOf(dir("nur Ordner"))))
    }

    // ─── "In Drive" ─────────────────────────────────────────────────

    @Test
    fun `in Drive with the same name and size`() {
        val hits = listOf(Hit("Rechnung_1.pdf", 240_000, false), Hit("Rechnung_1.pdf.bak", 240_000, false))
        assertEquals(InDrive.IN_DRIVE, DeviceFilesLogic.inDrive("Rechnung_1.pdf", 240_000, hits))
        assertEquals(InDrive.IN_DRIVE, DeviceFilesLogic.inDrive("rechnung_1.PDF", 240_000, hits), "case does not matter")
    }

    @Test
    fun `only here when Drive answered completely without a match`() {
        assertEquals(InDrive.ONLY_HERE, DeviceFilesLogic.inDrive("Rechnung_1.pdf", 240_001, listOf(Hit("Rechnung_1.pdf", 240_000, false))), "another size: another file")
        assertEquals(InDrive.ONLY_HERE, DeviceFilesLogic.inDrive("Rechnung_1.pdf", 240_000, emptyList()))
        assertEquals(InDrive.ONLY_HERE, DeviceFilesLogic.inDrive("Fotos", 0, listOf(Hit("Fotos", 0, true))), "a folder of that name is no file")
    }

    @Test
    fun `nothing is said when it cannot be known`() {
        assertEquals(InDrive.UNKNOWN, DeviceFilesLogic.inDrive("a.pdf", 1, null), "no answer (offline, error)")
        assertEquals(InDrive.UNKNOWN, DeviceFilesLogic.inDrive("x", 1, emptyList()), "too short to search")
        assertEquals(InDrive.UNKNOWN, DeviceFilesLogic.inDrive("a".repeat(101), 1, emptyList()), "too long to search")
        val full = List(DeviceFilesLogic.SEARCH_MAX) { Hit("IMG_$it.jpg", 5, false) }
        assertEquals(InDrive.UNKNOWN, DeviceFilesLogic.inDrive("IMG.jpg", 1, full), "a full page may hide the match")
        assertEquals(InDrive.IN_DRIVE, DeviceFilesLogic.inDrive("IMG_3.jpg", 5, full), "a match in a full page still counts")
    }

    // ─── ZIP ────────────────────────────────────────────────────────

    @Test
    fun `zip entries never leave the folder`() {
        assertEquals("a/b.txt", DeviceFilesLogic.zipTarget("a/b.txt"))
        assertEquals("a/b.txt", DeviceFilesLogic.zipTarget("./a//b.txt"))
        assertEquals("a/b.txt", DeviceFilesLogic.zipTarget("a\\b.txt"))
        assertNull(DeviceFilesLogic.zipTarget("../evil.sh"))
        assertNull(DeviceFilesLogic.zipTarget("a/../../evil.sh"))
        assertNull(DeviceFilesLogic.zipTarget("..\\evil.sh"))
        assertNull(DeviceFilesLogic.zipTarget("/etc/passwd"))
        assertNull(DeviceFilesLogic.zipTarget("C:/Windows/x.dll"))
        assertNull(DeviceFilesLogic.zipTarget("a/\u0000b"))
        assertNull(DeviceFilesLogic.zipTarget(""))
        assertNull(DeviceFilesLogic.zipTarget("./"))
    }

    @Test
    fun `inside is a real child, not a sibling with the same start`() {
        assertTrue(DeviceFilesLogic.inside("/storage/emulated/0/Download/Fotos", "/storage/emulated/0/Download/Fotos/a.jpg"))
        assertFalse(DeviceFilesLogic.inside("/storage/emulated/0/Download/Fotos", "/storage/emulated/0/Download/Fotos2/a.jpg"))
        assertFalse(DeviceFilesLogic.inside("/storage/emulated/0/Download/Fotos", "/storage/emulated/0/Download/Fotos"))
        assertTrue(DeviceFilesLogic.intoItself("/a/b", "/a/b"))
        assertTrue(DeviceFilesLogic.intoItself("/a/b", "/a/b/c"))
        assertFalse(DeviceFilesLogic.intoItself("/a/b", "/a/bc"))
    }

    @Test
    fun `zip budget - limit, free space and ratio`() {
        val gb = 1024L * 1024 * 1024
        assertEquals(DeviceFilesLogic.ZIP_MAX_BYTES, DeviceFilesLogic.zipBudget(archiveSize = 1 * gb, free = 100 * gb))
        assertEquals(5 * gb - DeviceFilesLogic.ZIP_RESERVE, DeviceFilesLogic.zipBudget(archiveSize = 1 * gb, free = 5 * gb))
        assertEquals(10_000L * DeviceFilesLogic.ZIP_MAX_RATIO, DeviceFilesLogic.zipBudget(archiveSize = 10_000, free = 100 * gb), "a ZIP bomb")
        assertEquals(0, DeviceFilesLogic.zipBudget(archiveSize = 1 * gb, free = 0))
    }

    @Test
    fun `free names and the folder of a zip`() {
        assertEquals("Fotos_Urlaub", DeviceFilesLogic.zipFolder("Fotos_Urlaub.zip"))
        assertEquals("zip", DeviceFilesLogic.zipFolder("zip"))
        val taken = setOf("Fotos", "Fotos (2)", "a.pdf")
        assertEquals("Fotos (3)", DeviceFilesLogic.freeName("Fotos") { it in taken })
        assertEquals("a (2).pdf", DeviceFilesLogic.freeName("a.pdf") { it in taken })
        assertEquals("neu.pdf", DeviceFilesLogic.freeName("neu.pdf") { it in taken })
    }

    // ─── places and the bar ─────────────────────────────────────────

    @Test
    fun `volume kinds and the path`() {
        assertEquals(VolumeKind.INTERNAL, DeviceFilesLogic.volumeKind(true, "Interner gemeinsamer Speicher"))
        assertEquals(VolumeKind.USB, DeviceFilesLogic.volumeKind(false, "SanDisk USB-Laufwerk"))
        assertEquals(VolumeKind.SD, DeviceFilesLogic.volumeKind(false, "SD-Karte von SanDisk"))
        assertEquals(
            listOf("Download" to "/storage/emulated/0/Download", "Rechnungen" to "/storage/emulated/0/Download/Rechnungen"),
            DeviceFilesLogic.crumbs("/storage/emulated/0", "/storage/emulated/0/Download/Rechnungen/"),
        )
        assertEquals(emptyList<Pair<String, String>>(), DeviceFilesLogic.crumbs("/storage/emulated/0", "/storage/emulated/0"))
        assertEquals(emptyList<Pair<String, String>>(), DeviceFilesLogic.crumbs("/storage/emulated/0", "/storage/ABCD-1234/x"))
    }

    @Test
    fun `the bar never shows more than is used`() {
        val bar = DeviceFilesLogic.bar(total = 100, used = 60, media = 30, documents = 10, audio = 5)
        assertEquals(listOf(Part.MEDIA to 0.30f, Part.DOCUMENTS to 0.10f, Part.AUDIO to 0.05f, Part.OTHER to 0.15f), bar)
        val over = DeviceFilesLogic.bar(total = 100, used = 40, media = 50, documents = 10, audio = 0)
        assertEquals(0.40f, over.sumOf { it.second.toDouble() }.toFloat(), 0.0001f)
        assertEquals(0.40f, over.first().second, 0.0001f)
        assertEquals(emptyList<Pair<Part, Float>>(), DeviceFilesLogic.bar(total = 0, used = 0, media = 0, documents = 0, audio = 0))
    }
}
