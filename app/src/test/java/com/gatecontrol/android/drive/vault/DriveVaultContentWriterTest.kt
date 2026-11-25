package com.gatecontrol.android.drive.vault

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.random.Random

/**
 * The content format written as the bytes come (a video converted while it is downloaded): bit for bit what
 * [DriveVaultCrypto.encryptContent] writes for the same salt, read back by [DriveVaultCrypto.decryptContent] and chunk
 * by chunk; going on after the whole chunks of a cut-off file.
 */
class DriveVaultContentWriterTest {
    private val tk = ByteArray(32) { it.toByte() }
    private val salt = ByteArray(16) { (it * 7).toByte() }
    private val cs = 1024
    private val prefix = "gcmedia-v1"

    private fun streamed(plain: ByteArray, pieces: Int = 333): ByteArray {
        val out = ByteArrayOutputStream()
        DriveVaultCrypto.ContentWriter.start(tk, "v1", 1, out, prefix, cs, salt).use { w ->
            var at = 0
            while (at < plain.size) {
                val n = minOf(pieces, plain.size - at)
                w.write(plain, at, n)
                at += n
            }
            w.finish()
        }
        return out.toByteArray()
    }

    private fun known(plain: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        DriveVaultCrypto.encryptContent(tk, "v1", 1, ByteArrayInputStream(plain), plain.size.toLong(), out, cs, salt, prefix)
        return out.toByteArray()
    }

    private fun decrypt(cipher: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        DriveVaultCrypto.decryptContent({ tk }, "v1", ByteArrayInputStream(cipher), out, prefix)
        return out.toByteArray()
    }

    @Test
    fun `the same bytes as with a known size, for empty, short, whole and longer contents`() {
        for (size in listOf(0, 1, cs - 1, cs, 2 * cs, 2 * cs + 5, 7 * cs + 999)) {
            val plain = Random(size).nextBytes(size)
            val c = streamed(plain)
            assertArrayEquals(known(plain), c, "size $size")
            assertArrayEquals(plain, decrypt(c), "size $size")
            assertEquals(DriveVaultCrypto.cipherSize(size.toLong(), cs), c.size.toLong())
        }
    }

    @Test
    fun `read chunk by chunk as the player reads it`() {
        val plain = Random(3).nextBytes(5 * cs + 17)
        val c = streamed(plain, pieces = 4096)
        val content = DriveVaultCrypto.openContent({ tk }, "v1", c.copyOf(DriveVaultCrypto.HEADER), c.size.toLong(), prefix)
        val back = ByteArrayOutputStream()
        for (i in 0 until content.chunks) {
            val (off, len) = content.cipherRange(i)
            back.write(content.decryptChunk(i, c, off.toInt(), len))
        }
        assertArrayEquals(plain, back.toByteArray())
        assertEquals(plain.size.toLong(), content.plainSize)
    }

    @Test
    fun `goes on after the whole chunks of a cut-off file`() {
        val plain = Random(9).nextBytes(6 * cs + 100)
        val full = streamed(plain)
        // cut in the middle of chunk 4: three whole chunks survive
        val cut = full.copyOf(DriveVaultCrypto.HEADER + 3 * (cs + DriveVaultCrypto.TAG) + 200)
        val chunks = DriveVaultCrypto.ContentWriter.chunksIn(cut.size.toLong(), cs)
        assertEquals(3, chunks)
        val kept = cut.copyOf((DriveVaultCrypto.HEADER + chunks * (cs + DriveVaultCrypto.TAG)).toInt())
        val out = ByteArrayOutputStream().apply { write(kept) }
        DriveVaultCrypto.ContentWriter.resume(tk, "v1", kept.copyOf(DriveVaultCrypto.HEADER), chunks, out, prefix).use { w ->
            assertEquals(3L * cs, w.written)
            w.write(plain, (chunks * cs).toInt(), plain.size - (chunks * cs).toInt())
            w.finish()
        }
        assertArrayEquals(full, out.toByteArray())
        assertArrayEquals(plain, decrypt(out.toByteArray()))
    }

    @Test
    fun `nothing after finish, and a cut-off file without its last chunk is refused`() {
        val out = ByteArrayOutputStream()
        val w = DriveVaultCrypto.ContentWriter.start(tk, "v1", 1, out, prefix, cs, salt)
        w.write(ByteArray(3 * cs))
        w.finish()
        assertThrows(IllegalStateException::class.java) { w.write(ByteArray(1)) }
        val unfinished = ByteArrayOutputStream()
        DriveVaultCrypto.ContentWriter.start(tk, "v1", 1, unfinished, prefix, cs, salt).use { it.write(ByteArray(3 * cs)) }
        // two chunks went out, none of them the last one
        assertEquals("decrypt", assertThrows(DriveVaultCrypto.DriveVaultException::class.java) { decrypt(unfinished.toByteArray()) }.code)
        assertEquals("invalid", assertThrows(DriveVaultCrypto.DriveVaultException::class.java) { DriveVaultCrypto.ContentWriter.resume(tk, "v1", ByteArray(29), 0, out) }.code)
    }
}
