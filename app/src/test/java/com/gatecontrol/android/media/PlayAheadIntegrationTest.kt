package com.gatecontrol.android.media

import android.app.Application
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.gatecontrol.android.media.player.PlayAhead
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * „Beim Abspielen mitladen“ for real (Media3's cache on Robolectric): the player reads the whole file through the
 * space while the loader goes ahead, every byte comes once from the server, nothing readable is left on the disk, and
 * it is all gone after the playback.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PlayAheadIntegrationTest {

    private val root: File = Files.createTempDirectory("ahead").toFile()
    private val url = "https://media.example.test/Videos/m1/stream?static=true&mediaSourceId=v1&playSessionId=a"
    private val file = Random(7).nextBytes(6 * (1 shl 20) + 12_345)
    private val served = AtomicLong()

    /** A server with ranges: `Content-Range` as Jellyfin answers. */
    private inner class Server : BaseDataSource(true) {
        private var at = 0
        private var left = 0L
        private var uri: Uri? = null
        private var headers: Map<String, List<String>> = emptyMap()

        override fun open(dataSpec: DataSpec): Long {
            uri = dataSpec.uri
            transferInitializing(dataSpec)
            at = dataSpec.position.toInt()
            val rest = file.size - at
            left = if (dataSpec.length == C.LENGTH_UNSET.toLong()) rest.toLong() else minOf(dataSpec.length, rest.toLong())
            headers = mapOf("content-range" to listOf("bytes $at-${at + left - 1}/${file.size}"))
            transferStarted(dataSpec)
            return left
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (left == 0L) return C.RESULT_END_OF_INPUT
            val n = minOf(length.toLong(), left, 16_384).toInt()
            System.arraycopy(file, at, buffer, offset, n)
            at += n
            left -= n
            served.addAndGet(n.toLong())
            bytesTransferred(n)
            // a slow line: the loader is ahead while the player reads
            Thread.sleep(0, 200_000)
            return n
        }

        override fun getUri(): Uri? = uri

        override fun getResponseHeaders(): Map<String, List<String>> = headers

        override fun close() {
            if (uri != null) transferEnded()
            uri = null
        }
    }

    private val upstream = DataSource.Factory { Server() }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun readAll(ahead: PlayAhead, from: Long = 0): ByteArray {
        val out = ByteArrayOutputStream()
        val ds = ahead.factory.createDataSource()
        ds.open(DataSpec.Builder().setUri(Uri.parse(url)).setPosition(from).build())
        val buf = ByteArray(32_768)
        while (true) {
            val n = ds.read(buf, 0, buf.size)
            if (n == C.RESULT_END_OF_INPUT) break
            out.write(buf, 0, n)
        }
        ds.close()
        return out.toByteArray()
    }

    private fun waitFor(what: () -> Boolean) {
        val until = System.currentTimeMillis() + 20_000
        while (!what() && System.currentTimeMillis() < until) Thread.sleep(20)
        assertTrue(what())
    }

    @Test
    fun `the player reads the file through the space, each byte comes once, then it is gone`() {
        val ahead = PlayAhead(root, upstream, limitBytes = 64L shl 20)
        ahead.follow(url)
        assertArrayEquals(file, readAll(ahead))
        waitFor { ahead.state().complete }
        assertEquals(file.size.toLong(), ahead.state().totalBytes)
        // the loader and the player never fetched the same piece twice
        assertEquals(file.size.toLong(), served.get())
        // read again (jumped back): all from the device
        assertArrayEquals(file.copyOfRange(1_000_000, file.size), readAll(ahead, 1_000_000))
        assertEquals(file.size.toLong(), served.get())
        // nothing readable on the disk: the pieces are encrypted
        val pieces = root.walkTopDown().filter { it.isFile && it.name.endsWith(".exo") }.toList()
        assertTrue(pieces.isNotEmpty())
        val probe = file.copyOfRange(2_000_000, 2_000_064)
        assertFalse(pieces.any { p -> p.readBytes().let { b -> indexOf(b, probe) >= 0 } })
        ahead.release()
        waitFor { root.listFiles().isNullOrEmpty() }
    }

    @Test
    fun `loaded ahead on its own before the player reads, the next title drops the one before`() {
        val ahead = PlayAhead(root, upstream, limitBytes = 64L shl 20)
        ahead.follow(url)
        // the player opens the start (as on prepare), then waits
        val ds = ahead.factory.createDataSource()
        ds.open(DataSpec.Builder().setUri(Uri.parse(url)).build())
        ds.read(ByteArray(1024), 0, 1024)
        // while the player holds its piece open (paused), the rest is loaded behind it, nothing twice
        waitFor { ahead.state().complete }
        assertTrue(served.get() <= file.size.toLong())
        assertTrue(ahead.state().aheadBytes >= file.size - PlayAhead.PLAYER_PIECE)
        ds.close()
        ahead.follow("https://media.example.test/Videos/m2/stream?static=true&mediaSourceId=v2")
        waitFor { ahead.state().totalBytes == 0L }
        ahead.release()
        waitFor { root.listFiles().isNullOrEmpty() }
    }

    @Test
    fun `not followed - straight through`() {
        val ahead = PlayAhead(root, upstream, limitBytes = 64L shl 20)
        assertArrayEquals(file, readAll(ahead))
        assertFalse(ahead.following)
        ahead.release()
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
