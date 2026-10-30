package com.gatecontrol.android.drive

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Drive 1.14 (D5): the searchable PDF made on the phone. */
class DriveSearchablePdfTest {

    /** A JPEG start: SOI, an APP0 segment, then SOF0 with [h] × [w] and [c] components. */
    private fun jpeg(w: Int, h: Int, c: Int = 3, sof: Int = 0xC0): ByteArray {
        val app0 = byteArrayOf(0xFF.toByte(), 0xE0.toByte(), 0, 6, 'J'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte())
        val sofSeg = byteArrayOf(
            0xFF.toByte(), sof.toByte(), 0, 11, 8,
            (h shr 8).toByte(), h.toByte(), (w shr 8).toByte(), w.toByte(), c.toByte(), 1, 0x11, 0,
        )
        return byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + app0 + sofSeg + byteArrayOf(0xFF.toByte(), 0xD9.toByte())
    }

    @Test
    fun `the JPEG's size and colours`() {
        assertEquals(DriveSearchablePdf.JpegInfo(1240, 1754, 3), DriveSearchablePdf.jpegInfo(jpeg(1240, 1754)))
        assertEquals(DriveSearchablePdf.JpegInfo(800, 600, 1), DriveSearchablePdf.jpegInfo(jpeg(800, 600, c = 1, sof = 0xC2)))
        // a DHT (C4) is no frame
        assertNull(DriveSearchablePdf.jpegInfo(jpeg(800, 600, sof = 0xC4)))
        assertNull(DriveSearchablePdf.jpegInfo(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())))
        assertNull(DriveSearchablePdf.jpegInfo(jpeg(800, 600, c = 2)))
    }

    @Test
    fun `page size - A4 wide, landscape A4 long`() {
        val (w, h) = DriveSearchablePdf.pageSize(DriveSearchablePdf.JpegInfo(1000, 2000, 3))
        assertEquals(595.28, w, 0.01)
        assertEquals(1190.56, h, 0.01)
        assertEquals(841.89, DriveSearchablePdf.pageSize(DriveSearchablePdf.JpegInfo(2000, 1000, 3)).first, 0.01)
    }

    @Test
    fun `text is escaped in WinAnsi`() {
        assertEquals("a\\(b\\)\\\\", String(DriveSearchablePdf.escape("a(b)\\"), Charsets.US_ASCII))
        assertEquals("Z\\344hler \\200", String(DriveSearchablePdf.escape("Zähler €"), Charsets.US_ASCII))
        assertEquals("? ", String(DriveSearchablePdf.escape("漢\n"), Charsets.US_ASCII))
    }

    @Test
    fun `the page draws the picture, then the lines invisibly where they stand`() {
        val p = DriveSearchablePdf.Page(10, DriveSearchablePdf.JpegInfo(1000, 1000, 3), 500, 500, listOf(DriveKi.OcrLine("Rechnung", 50, 100, 250, 120), DriveKi.OcrLine(" ", 0, 0, 1, 1)))
        val c = String(DriveSearchablePdf.content(p, 600.0, 600.0) { it.length * 0.5 }, Charsets.US_ASCII)
        assertTrue(c.startsWith("q 600 0 0 600 0 0 cm /Im0 Do Q\n"), c)
        assertTrue(c.contains("BT 3 Tr"), c)
        // line height 20 px → 24 pt, font 20.4; width 240 pt for 8 × 0.5 × 20.4 = 81.6 → 294.12 %
        assertTrue(c.contains("/F1 20.4 Tf 294.12 Tz 1 0 0 1 60 460.8 Tm (Rechnung) Tj"), c)
        assertEquals(1, Regex("Tj").findAll(c).count())
        // nothing read: only the picture
        val empty = String(DriveSearchablePdf.content(p.copy(lines = emptyList()), 600.0, 600.0), Charsets.US_ASCII)
        assertTrue(!empty.contains("BT"))
    }

    @Test
    fun `a whole PDF - objects, the pictures as they are, a cross-reference table that points right`() {
        val pics = listOf(jpeg(100, 200) + ByteArray(50) { 7 }, jpeg(300, 100) + ByteArray(20) { 9 })
        val pages = pics.map { DriveSearchablePdf.Page(it.size.toLong(), DriveSearchablePdf.jpegInfo(it)!!, 100, 200, listOf(DriveKi.OcrLine("Hallo Welt", 10, 10, 90, 30))) }
        val out = ByteArrayOutputStream()
        DriveSearchablePdf.write(out, pages, open = { ByteArrayInputStream(pics[it]) })
        val bytes = out.toByteArray()
        val text = String(bytes, Charsets.ISO_8859_1)
        assertTrue(text.startsWith("%PDF-1.4\n"))
        assertTrue(text.trimEnd().endsWith("%%EOF"))
        assertTrue(text.contains("/Count 2"))
        assertTrue(text.contains("/Width 300 /Height 100 /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${pics[1].size}"))
        assertTrue(text.contains("/MediaBox [0 0 841.89 280.63]"), text.take(2000))
        // the pictures go in unchanged
        assertTrue(text.contains(String(pics[0], Charsets.ISO_8859_1)))
        // xref: every offset starts its object
        val startxref = Regex("startxref\n(\\d+)").find(text)!!.groupValues[1].toInt()
        assertTrue(text.substring(startxref).startsWith("xref\n0 11\n"))
        val offsets = Regex("(\\d{10}) 00000 n ").findAll(text.substring(startxref)).map { it.groupValues[1].toInt() }.toList()
        assertEquals(10, offsets.size)
        offsets.forEachIndexed { i, o -> assertTrue(text.substring(o).startsWith("${i + 1} 0 obj"), "object ${i + 1}") }
    }

    @Test
    fun `a picture shorter than announced is refused`() {
        val pic = jpeg(10, 10)
        val page = DriveSearchablePdf.Page(pic.size + 100L, DriveSearchablePdf.jpegInfo(pic)!!, 10, 10, emptyList())
        assertThrows(IllegalArgumentException::class.java) {
            DriveSearchablePdf.write(ByteArrayOutputStream(), listOf(page), open = { ByteArrayInputStream(pic) })
        }
    }
}
