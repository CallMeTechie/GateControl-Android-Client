package com.gatecontrol.android.familie.emergency

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * The encrypted offline copy of the emergency card. On the phone the key
 * lives in the Android Keystore; here the same AES-GCM code runs with a
 * software key.
 */
class EmergencyCacheTest {

    @TempDir
    lateinit var dir: File

    private fun key(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private val data = JsonParser.parseString(
        """{"ok":true,"members":[{"userId":5,"name":"Tim","allergies":"Erdnüsse (schwer)"}],"contacts":[],"updatedAt":"2026-10-14T12:00:00Z"}""",
    ).asJsonObject

    private fun file() = File(File(dir, "familie"), "emergency.bin")

    @Test
    fun `round trip for the same login`() {
        val k = key()
        val cache = EmergencyCache(file(), AesGcmCipher { k })
        assertTrue(cache.write("owner-a", data, 1_760_443_200_000))
        val entry = cache.read("owner-a")!!
        assertEquals(data, entry.data)
        assertEquals(1_760_443_200_000, entry.savedAtMs)

        // A new instance (app restart) with the same key reads it too.
        assertEquals(data, EmergencyCache(file(), AesGcmCipher { k }).read("owner-a")?.data)
    }

    @Test
    fun `nothing readable is on disk`() {
        val cache = EmergencyCache(file(), AesGcmCipher(::key))
        cache.write("owner-a", data, 1L)
        val bytes = file().readBytes()
        val text = String(bytes, Charsets.ISO_8859_1)
        assertFalse(text.contains("Tim"), "names are encrypted")
        assertFalse(text.contains("Erdn"), "health data is encrypted")
        assertFalse(text.contains("owner-a"))
        assertFalse(File(file().parentFile, "emergency.bin.tmp").exists(), "no temp file left")

        // Each write uses a fresh IV: the same data never gives the same bytes.
        val k = key()
        val c = EmergencyCache(file(), AesGcmCipher { k })
        c.write("owner-a", data, 1L)
        val first = file().readBytes()
        c.write("owner-a", data, 1L)
        assertNotEquals(first.toList(), file().readBytes().toList())
    }

    @Test
    fun `another login, another key or a changed file count as no copy and remove it`() {
        val k = key()
        val cache = EmergencyCache(file(), AesGcmCipher { k })

        cache.write("owner-a", data, 1L)
        assertNull(cache.read("owner-b"))
        assertFalse(file().exists(), "a copy of another login is removed")

        cache.write("owner-a", data, 1L)
        assertNull(EmergencyCache(file(), AesGcmCipher(::key)).read("owner-a"), "a lost key")
        assertFalse(file().exists())

        cache.write("owner-a", data, 1L)
        val bytes = file().readBytes()
        bytes[bytes.size - 3] = (bytes[bytes.size - 3].toInt() xor 0x01).toByte()
        file().writeBytes(bytes)
        assertNull(cache.read("owner-a"), "GCM refuses a changed file")
        assertFalse(file().exists())

        file().writeBytes(byteArrayOf(1, 2, 3))
        assertNull(cache.read("owner-a"), "not our format")
    }

    @Test
    fun `no file, a failing cipher, clear`() {
        val k = key()
        val cache = EmergencyCache(file(), AesGcmCipher { k })
        assertNull(cache.read("owner-a"))
        assertFalse(cache.exists())

        val broken = EmergencyCache(file(), object : CacheCipher {
            override fun encrypt(plain: ByteArray): ByteArray = throw IllegalStateException("keystore")
            override fun decrypt(blob: ByteArray): ByteArray = throw IllegalStateException("keystore")
        })
        assertFalse(broken.write("owner-a", data, 1L), "never written in plain text")
        assertFalse(file().exists())

        cache.write("owner-a", data, 1L)
        assertTrue(cache.exists())
        cache.clear()
        assertFalse(cache.exists())
        assertNull(cache.read("owner-a"))
    }
}
