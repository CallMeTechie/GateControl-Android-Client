package com.gatecontrol.android.drive.vault

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import kotlin.random.Random

/**
 * The viewer of vault files (Drive plugin 1.22): what it shows, random access to a vault file's chunks (the video
 * source reads them one at a time), the plaintext buffer that never grows by copies, and what the viewer holds being
 * released on close and on lock (DriveVaultHeld).
 */
class DriveVaultViewTest {
    private val vaultId = "AAAAAAAAAAAAAAAAAAAAAA"

    @Test
    fun `kinds, limits and types`() {
        val k = listOf("a.JPG", "b.heic", "c.pdf", "d.md", "e.txt", "f.mp4", "g.docx", "h.zip", "noext").map(DriveVaultView::kindOf)
        assertEquals(
            listOf(DriveVaultView.Kind.IMAGE, DriveVaultView.Kind.IMAGE, DriveVaultView.Kind.PDF, DriveVaultView.Kind.MARKDOWN, DriveVaultView.Kind.TEXT,
                DriveVaultView.Kind.VIDEO, DriveVaultView.Kind.OFFICE, DriveVaultView.Kind.OTHER, DriveVaultView.Kind.OTHER),
            k,
        )
        assertTrue(DriveVaultView.tooLarge(DriveVaultView.Kind.IMAGE, 65L * 1_048_576))
        assertFalse(DriveVaultView.tooLarge(DriveVaultView.Kind.PDF, 10L * 1_048_576))
        assertFalse(DriveVaultView.tooLarge(DriveVaultView.Kind.OFFICE, Long.MAX_VALUE), "office: a note, not a size")
        assertEquals(64L, DriveVaultView.limitMb(DriveVaultView.Kind.IMAGE))
        assertEquals("application/pdf", DriveVaultView.mimeOf("x.PDF"))
        assertEquals("application/octet-stream", DriveVaultView.mimeOf("x.exe"))
    }

    @Test
    fun `markdown as blocks and spans - HTML of the file stays text`() {
        val b = DriveVaultView.markdown("# Titel\n\nmit **fett** und `code` <script>x</script>\n\n- eins\n- [x] zwei\n1. erst\n\n```\n<b>roh</b>\n```\n> Zitat\n---")
        assertEquals(DriveVaultView.Block.Heading(1, "Titel"), b[0])
        assertEquals(DriveVaultView.Block.Paragraph("mit **fett** und `code` <script>x</script>"), b[1])
        assertEquals(DriveVaultView.Block.Item("•", "eins"), b[2])
        assertEquals(DriveVaultView.Block.Item("•", "☑ zwei"), b[3])
        assertEquals(DriveVaultView.Block.Item("1.", "erst"), b[4])
        assertEquals(DriveVaultView.Block.Code("<b>roh</b>"), b[5])
        assertEquals(DriveVaultView.Block.Quote("Zitat"), b[6])
        assertEquals(DriveVaultView.Block.Rule, b[7])
        assertEquals(
            listOf(DriveVaultView.Span.Plain("mit "), DriveVaultView.Span.Bold("fett"), DriveVaultView.Span.Plain(" und "), DriveVaultView.Span.Mono("code"), DriveVaultView.Span.Plain(" <script>x</script>")),
            DriveVaultView.spans("mit **fett** und `code` <script>x</script>"),
        )
    }

    private fun encrypt(tk: ByteArray, plain: ByteArray, chunk: Int): ByteArray {
        val out = ByteArrayOutputStream()
        DriveVaultCrypto.encryptContent(tk, vaultId, 3, ByteArrayInputStream(plain), plain.size.toLong(), out, chunkSize = chunk)
        return out.toByteArray()
    }

    @Test
    fun `random access to the chunks equals the whole, changed, swapped and closed chunks refused`() {
        val tk = DriveVaultCrypto.newVaultKey()
        val plain = Random(7).nextBytes(3 * 1024 + 100)
        val ct = encrypt(tk, plain, 1024)
        val c = DriveVaultCrypto.openContent({ v -> if (v == 3) tk else null }, vaultId, ct.copyOf(DriveVaultCrypto.HEADER), ct.size.toLong())
        assertEquals(4L, c.chunks)
        assertEquals(plain.size.toLong(), c.plainSize)
        val parts = (c.chunks - 1 downTo 0).map { i ->
            val (off, len) = c.cipherRange(i)
            i to c.decryptChunk(i, ct, off.toInt(), len)
        }.sortedBy { it.first }.map { it.second }
        assertArrayEquals(plain, parts.reduce { a, b -> a + b })
        assertEquals(1L, c.chunkOf(1500))
        assertEquals(3L, c.chunkOf(plain.size + 10L))
        val (o1, l1) = c.cipherRange(1)
        val bad = ct.copyOfRange(o1.toInt(), o1.toInt() + l1).also { it[3] = (it[3].toInt() xor 1).toByte() }
        assertEquals("decrypt", assertThrows(DriveVaultCrypto.DriveVaultException::class.java) { c.decryptChunk(1, bad) }.code)
        val (o2, l2) = c.cipherRange(2)
        assertEquals("decrypt", assertThrows(DriveVaultCrypto.DriveVaultException::class.java) { c.decryptChunk(1, ct, o2.toInt(), l2) }.code, "the index is bound")
        assertEquals("decrypt", assertThrows(DriveVaultCrypto.DriveVaultException::class.java) {
            DriveVaultCrypto.openContent({ null }, vaultId, ct.copyOf(DriveVaultCrypto.HEADER), ct.size.toLong())
        }.code)
        assertEquals("invalid", assertThrows(DriveVaultCrypto.DriveVaultException::class.java) {
            DriveVaultCrypto.openContent({ tk }, vaultId, ct.copyOf(DriveVaultCrypto.HEADER), DriveVaultCrypto.HEADER + 3L * 1040 + 5)
        }.code)
        c.close()
        assertEquals("invalid", assertThrows(DriveVaultCrypto.DriveVaultException::class.java) { c.decryptChunk(0, ct, DriveVaultCrypto.HEADER, 1040) }.code)
        assertEquals(plain.size.toLong(), DriveVaultCrypto.plainSizeOf(encrypt(tk, plain, DriveVaultCrypto.CHUNK).size.toLong()))
    }

    @Test
    fun `the plaintext buffer has the exact size, a limit and is overwritten`() {
        val tk = DriveVaultCrypto.newVaultKey()
        val plain = Random(3).nextBytes(5000)
        val buf = PlainBuffer(5000, 10_000)
        DriveVaultCrypto.decryptContent({ tk }, vaultId, ByteArrayInputStream(encrypt(tk, plain, 1024)), buf)
        val got = buf.bytes()
        assertArrayEquals(plain, got)
        buf.wipe()
        assertTrue(got.all { it == 0.toByte() }, "wipe overwrites the bytes handed out")
        val small = PlainBuffer(16, 100)
        assertThrows(DriveVaultTooLarge::class.java) {
            DriveVaultCrypto.decryptContent({ tk }, vaultId, ByteArrayInputStream(encrypt(tk, plain, 1024)), small)
        }
    }

    @Test
    fun `what the viewer holds goes on close in reverse and what comes later goes at once`() {
        val order = mutableListOf<String>()
        val h = DriveVaultHeld()
        assertTrue(h.add(Closeable { order += "bitmap" }))
        assertTrue(h.add(Closeable { order += "pdf" }))
        h.close()
        assertEquals(listOf("pdf", "bitmap"), order)
        assertFalse(h.add(Closeable { order += "late" }))
        assertEquals(listOf("pdf", "bitmap", "late"), order)
        h.close()
        assertEquals(3, order.size, "closing twice releases nothing twice")
    }
}
