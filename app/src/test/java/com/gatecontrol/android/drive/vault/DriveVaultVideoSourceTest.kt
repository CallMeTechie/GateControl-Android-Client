package com.gatecontrol.android.drive.vault

import android.app.Application
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.random.Random

/**
 * The video source of the vault viewer (Drive plugin 1.22): it plays from the ciphertext in the cache, decrypting one
 * chunk at a time in memory — positions across chunk borders, a length, the end; a closed content stops it; nothing in
 * plain text is ever written next to the ciphertext.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DriveVaultVideoSourceTest {
    private val vaultId = "BBBBBBBBBBBBBBBBBBBBBB"

    private fun read(src: DriveVaultVideoSource, spec: DataSpec): ByteArray {
        src.open(spec)
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(777)
        while (true) {
            val n = src.read(buf, 0, buf.size)
            if (n == C.RESULT_END_OF_INPUT) break
            out.write(buf, 0, n)
        }
        src.close()
        return out.toByteArray()
    }

    @Test
    fun `plays by positions from the ciphertext, never writes plaintext`() {
        val dir = Files.createTempDirectory("dvv").toFile()
        try {
            val tk = DriveVaultCrypto.newVaultKey()
            val marker = "Geheimes Video 4711".toByteArray()
            val plain = marker + Random(11).nextBytes(3 * 4096 + 123)
            val cipher = File(dir, "view.gcdv")
            cipher.outputStream().use { DriveVaultCrypto.encryptContent(tk, vaultId, 1, ByteArrayInputStream(plain), plain.size.toLong(), it, chunkSize = 4096) }
            val content = DriveVaultCrypto.openContent({ tk }, vaultId, cipher.readBytes().copyOf(DriveVaultCrypto.HEADER), cipher.length())
            val uri = Uri.parse("gcvault://video")
            val src = DriveVaultVideoSource(cipher, content)
            assertArrayEquals(plain, read(src, DataSpec(uri)))
            for ((pos, len) in listOf(0L to 10L, 4090L to 20L, 8191L to 2L, plain.size - 5L to 5L)) {
                assertArrayEquals("$pos+$len", plain.copyOfRange(pos.toInt(), (pos + len).toInt()), read(src, DataSpec.Builder().setUri(uri).setPosition(pos).setLength(len).build()))
            }
            assertArrayEquals(plain.copyOfRange(9000, plain.size), read(src, DataSpec.Builder().setUri(uri).setPosition(9000).build()))
            // only the ciphertext lies there — no plaintext anywhere in the folder
            val files = dir.walkTopDown().filter { it.isFile }.toList()
            assertEquals(listOf(cipher), files)
            assertFalse(String(cipher.readBytes(), Charsets.ISO_8859_1).contains(String(marker, Charsets.ISO_8859_1)))
            // the viewer closed (or the vault locked): the content is closed, the source stops
            content.close()
            val stopped = try { read(src, DataSpec(uri)); false } catch (e: IOException) { true }
            assertTrue(stopped)
        } finally {
            dir.deleteRecursively()
        }
    }
}
