package com.gatecontrol.android.media.share

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.Charset

/** SRT files beside a video in Windows-1252 / ISO-8859-1 / UTF-16: their umlauts reach the player as UTF-8. */
class SubtitleTextTest {
    private val srt = "1\r\n00:00:01,000 --> 00:00:03,000\r\nGrüße aus Köln – „schön“ €\r\n"

    @Test
    fun `UTF-8 stays as it is, also with its byte order mark`() {
        val utf8 = srt.toByteArray(Charsets.UTF_8)
        assertTrue(SubtitleText.validUtf8(utf8))
        assertArrayEquals(utf8, SubtitleText.toUtf8(utf8))
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + utf8
        assertArrayEquals(bom, SubtitleText.toUtf8(bom))
    }

    @Test
    fun `Windows-1252 becomes UTF-8 with the umlauts right`() {
        val cp1252 = srt.toByteArray(Charset.forName("windows-1252"))
        assertFalse(SubtitleText.validUtf8(cp1252))
        assertEquals(srt, String(SubtitleText.toUtf8(cp1252), Charsets.UTF_8))
    }

    @Test
    fun `ISO-8859-1 umlauts are the same in Windows-1252`() {
        val text = "Mädchen, Größe, Übung"
        assertEquals(text, String(SubtitleText.toUtf8(text.toByteArray(Charsets.ISO_8859_1)), Charsets.UTF_8))
    }

    @Test
    fun `UTF-16 with its byte order mark`() {
        assertEquals(srt, String(SubtitleText.toUtf8(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + srt.toByteArray(Charsets.UTF_16LE)), Charsets.UTF_8))
        assertEquals(srt, String(SubtitleText.toUtf8(byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + srt.toByteArray(Charsets.UTF_16BE)), Charsets.UTF_8))
    }

    @Test
    fun `only text subtitle files`() {
        assertTrue(SubtitleText.isSubtitle("Filme/Kupferstadt.de.SRT"))
        assertTrue(SubtitleText.isSubtitle("a/b.ass"))
        assertFalse(SubtitleText.isSubtitle("Filme/Kupferstadt.mkv"))
        assertFalse(SubtitleText.isSubtitle("Filme/Kupferstadt.sub"), "VobSub .sub is a picture subtitle")
    }
}
