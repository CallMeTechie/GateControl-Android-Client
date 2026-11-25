package com.gatecontrol.android.media.offline

import com.gatecontrol.android.drive.vault.DriveVaultCrypto
import com.gatecontrol.android.familie.emergency.CacheCipher
import com.gatecontrol.android.media.MediaStream
import com.gatecontrol.android.media.MediaTitle
import com.gatecontrol.android.media.MediaVersion
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import javax.crypto.AEADBadTagException

/** The downloads on the phone: the encrypted index, the folders, the files' own keys, what a broken index leaves. */
class MediaOfflineStoreTest {
    @TempDir
    lateinit var dir: File

    /** Stands in for the Keystore: reversible, and refuses what it did not write. */
    private class FakeCipher(private val tag: Byte = 0x5A) : CacheCipher {
        override fun encrypt(plain: ByteArray): ByteArray = byteArrayOf(tag) + plain.map { (it.toInt() xor 0x33).toByte() }
        override fun decrypt(blob: ByteArray): ByteArray {
            if (blob.isEmpty() || blob[0] != tag) throw AEADBadTagException()
            return blob.drop(1).map { (it.toInt() xor 0x33).toByte() }.toByteArray()
        }
    }

    private val movie = MediaTitle("m1", "jf", MediaTitle.Kind.MOVIE, "Kobaltnacht", year = 2025, runtimeMs = 6_480_000, positionMs = 2_530_000, playedAt = 10, posterId = "m1", posterTag = "p1")
    private val version = MediaVersion("v1", container = "mkv", streams = listOf(MediaStream(1, MediaStream.Type.AUDIO, codec = "aac", language = "ger", channels = 2)))

    @Test
    fun `the index is encrypted, read again with the title, the key and the progress`() {
        val store = MediaOfflineStore(dir, FakeCipher(), now = { 1_000 })
        val a = store.add(movie, OfflineQuality.P1080)
        assertEquals(32, a.key.size)
        assertEquals(2_530_000, a.positionMs)
        store.update(a.id) { it.copy(state = OfflineState.READY, version = version, subtitles = listOf(OfflineSubtitle(3, "ger", "Deutsch")), size = 42) }
        val raw = File(dir, MediaOfflineStore.INDEX).readBytes()
        assertFalse(String(raw, Charsets.ISO_8859_1).contains("Kobaltnacht"), "the index is not in plain text")

        val again = MediaOfflineStore(dir, FakeCipher())
        val b = again.get(a.id)!!
        assertEquals(movie, b.title)
        assertEquals(OfflineQuality.P1080, b.quality)
        assertEquals(OfflineState.READY, b.state)
        assertEquals(version.streams, b.version?.streams)
        assertEquals("Deutsch", b.subtitles.single().label)
        assertArrayEquals(a.key, b.key)
        assertEquals(42, again.used())
        // the same title again stays as it is
        assertEquals(OfflineState.READY, again.add(movie, OfflineQuality.P720).state)
    }

    @Test
    fun `a download cut off when the app went waits again, a failed one is asked again`() {
        val store = MediaOfflineStore(dir, FakeCipher())
        val a = store.add(movie, OfflineQuality.P720)
        store.update(a.id) { it.copy(state = OfflineState.LOADING, loaded = 5) }
        assertEquals(OfflineState.QUEUED, MediaOfflineStore(dir, FakeCipher()).get(a.id)?.state)
        store.update(a.id) { it.copy(state = OfflineState.FAILED, failure = OfflineFailure.LIMIT) }
        val again = store.add(movie, OfflineQuality.ORIGINAL)
        assertEquals(OfflineState.QUEUED, again.state)
        assertNull(again.failure)
        assertEquals(OfflineQuality.ORIGINAL, again.quality)
    }

    @Test
    fun `the files are encrypted with the title's key and go with it`() {
        val store = MediaOfflineStore(dir, FakeCipher())
        val a = store.add(movie, OfflineQuality.P1080)
        val plain = ByteArray(3 * DriveVaultCrypto.CHUNK + 77) { (it % 251).toByte() }
        store.file(a.id, MediaOfflineLogic.VIDEO).also { it.parentFile?.mkdirs() }.outputStream().use { out ->
            DriveVaultCrypto.ContentWriter.start(a.key, a.id, 1, out, MediaOfflineLogic.PREFIX).use { w ->
                w.write(plain)
                w.finish()
            }
        }
        store.writeSmall(a.id, MediaOfflineStore.POSTER, byteArrayOf(1, 2, 3))
        assertArrayEquals(byteArrayOf(1, 2, 3), store.readSmall(a.id, MediaOfflineStore.POSTER))
        val (file, content) = store.open(a.id, MediaOfflineLogic.VIDEO)!!
        assertEquals(plain.size.toLong(), content.plainSize)
        val (off, len) = content.cipherRange(3)
        val bytes = file.readBytes()
        assertArrayEquals(plain.copyOfRange(3 * DriveVaultCrypto.CHUNK, plain.size), content.decryptChunk(3, bytes, off.toInt(), len))
        content.close()
        assertFalse(bytes.asList().windowed(64, 4096).any { it == plain.copyOf(64).asList() }, "no plain text on the disk")

        store.remove(a.id)
        assertNull(store.get(a.id))
        assertFalse(store.folder(a.id).exists())
        assertNull(store.open(a.id, MediaOfflineLogic.VIDEO))
    }

    @Test
    fun `an index of another key counts as none, its folders go`() {
        val store = MediaOfflineStore(dir, FakeCipher())
        val a = store.add(movie, OfflineQuality.P1080)
        store.writeSmall(a.id, MediaOfflineStore.POSTER, byteArrayOf(9))
        assertTrue(store.folder(a.id).exists())
        val other = MediaOfflineStore(dir, FakeCipher(tag = 0x11))
        assertTrue(other.items.value.isEmpty())
        assertFalse(store.folder(a.id).exists())
        assertFalse(File(dir, MediaOfflineStore.INDEX).exists())
    }

    @Test
    fun `a removed source takes its downloads along`() {
        val store = MediaOfflineStore(dir, FakeCipher())
        store.add(movie, OfflineQuality.P1080)
        val other = store.add(movie.copy(id = "m2", sourceId = "jf2"), OfflineQuality.P1080)
        store.removeSource("jf")
        assertEquals(listOf(other.id), store.items.value.map { it.id })
        assertNotNull(store.find("jf2", "m2"))
    }
}
