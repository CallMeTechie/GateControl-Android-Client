package com.gatecontrol.android.drive.faces

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** Drive 1.16: SFace loaded when switched on — sources in order, wrong bytes thrown away, resumed, progress. */
class DriveFaceModelFetcherTest {

    @TempDir
    lateinit var dir: File

    private val good = ByteArray(300_000) { (it * 31 % 251).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(good).joinToString("") { "%02x".format(it) }

    /** A server: per URL the bytes it sends (null: unreachable), an optional break after so many bytes. */
    private class Server(val files: Map<String, ByteArray?>, val breakAfter: Map<String, Int> = emptyMap()) {
        val asked = ArrayList<Pair<String, Long>>()

        fun open(url: String, offset: Long): DriveFaceModelFetcher.Response? {
            asked += url to offset
            val bytes = files[url] ?: return null
            if (bytes.isEmpty()) return response(404, ByteArray(0))
            val from = offset.toInt().coerceAtMost(bytes.size)
            val body = bytes.copyOfRange(from, bytes.size)
            val cut = breakAfter[url]
            val stream: InputStream = if (cut == null) ByteArrayInputStream(body) else Breaking(body, cut)
            return response(if (offset > 0) 206 else 200, ByteArray(0), stream)
        }

        private fun response(code: Int, bytes: ByteArray, stream: InputStream = ByteArrayInputStream(bytes)) = object : DriveFaceModelFetcher.Response {
            override val code = code
            override val body = stream
            override fun close() = Unit
        }
    }

    /** Sends [cut] bytes, then the connection breaks. */
    private class Breaking(val bytes: ByteArray, val cut: Int) : InputStream() {
        var pos = 0
        override fun read(): Int = if (pos >= cut) throw IOException("reset") else if (pos >= bytes.size) -1 else bytes[pos++].toInt() and 0xFF
    }

    private fun fetcher(server: Server) = DriveFaceModelFetcher(dir, "m.onnx", sha, good.size.toLong(), server::open)

    @Test
    fun `the first source with the right bytes wins, earlier ones that fail are skipped in order`() {
        val server = Server(mapOf("a" to ByteArray(0), "b" to null, "c" to good, "d" to good))
        val r = fetcher(server).fetch(listOf("a", "b", "c", "d"))
        assertTrue(r is DriveFaceModelFetcher.Result.Done)
        assertEquals(listOf("a", "b", "c"), server.asked.map { it.first })
        assertArrayEquals(good, File(dir, "m.onnx").readBytes())
        assertFalse(File(dir, "m.onnx.part").exists())
    }

    @Test
    fun `wrong bytes are thrown away and the next source tried`() {
        val bad = good.copyOf().also { it[1000] = (it[1000] + 1).toByte() }
        val server = Server(mapOf("a" to bad, "b" to good))
        val f = fetcher(server)
        assertTrue(f.fetch(listOf("a", "b")) is DriveFaceModelFetcher.Result.Done)
        assertTrue(f.ready())
        // only wrong bytes: nothing stays, the reason is the hash
        val dir2 = File(dir, "x")
        val only = DriveFaceModelFetcher(dir2, "m.onnx", sha, good.size.toLong(), Server(mapOf("a" to bad, "b" to good.copyOf(good.size - 1)))::open)
        assertEquals(DriveFaceModelFetcher.Result.Failed("hash"), only.fetch(listOf("a", "b")))
        assertFalse(File(dir2, "m.onnx").exists())
        assertFalse(File(dir2, "m.onnx.part").exists())
    }

    @Test
    fun `a broken download goes on from where it stopped at the same source`() {
        val server = Server(mapOf("a" to good), breakAfter = mapOf("a" to 120_000))
        val f = fetcher(server)
        assertEquals(DriveFaceModelFetcher.Result.Failed("offline"), f.fetch(listOf("a")))
        assertFalse(f.ready())
        val part = File(dir, "m.onnx.part").length()
        assertTrue(part in 1..120_000)
        val resumed = Server(mapOf("a" to good))
        assertTrue(fetcher(resumed).fetch(listOf("a")) is DriveFaceModelFetcher.Result.Done)
        assertEquals(listOf("a" to part), resumed.asked)
        assertArrayEquals(good, File(dir, "m.onnx").readBytes())
    }

    @Test
    fun `a part of another source is not continued`() {
        fetcher(Server(mapOf("a" to good), breakAfter = mapOf("a" to 50_000))).fetch(listOf("a"))
        val other = Server(mapOf("b" to good))
        assertTrue(fetcher(other).fetch(listOf("b")) is DriveFaceModelFetcher.Result.Done)
        assertEquals(listOf("b" to 0L), other.asked)
    }

    @Test
    fun `progress counts up to the size, nothing is fetched when the file is there, delete removes all`() {
        val seen = ArrayList<Long>()
        val f = fetcher(Server(mapOf("a" to good)))
        f.fetch(listOf("a"), progress = { done, total -> assertEquals(good.size.toLong(), total); seen += done })
        assertEquals(0L, seen.first())
        assertEquals(good.size.toLong(), seen.last())
        assertTrue(seen.zipWithNext().all { (a, b) -> b >= a })
        val again = Server(mapOf("a" to good))
        assertTrue(fetcher(again).fetch(listOf("a")) is DriveFaceModelFetcher.Result.Done)
        assertTrue(again.asked.isEmpty())
        f.delete()
        assertFalse(f.ready())
        assertFalse(File(dir, "m.onnx").exists())
    }

    @Test
    fun `cancelled stops and keeps the part`() {
        var blocks = 0
        val r = fetcher(Server(mapOf("a" to good))).fetch(listOf("a"), cancelled = { blocks++ > 2 })
        assertEquals(DriveFaceModelFetcher.Result.Failed("cancelled"), r)
        assertTrue(File(dir, "m.onnx.part").exists())
    }

    @Test
    fun `sources start with this version's release`() {
        val s = DriveFaceModel.sources("1.75.0")
        assertEquals("https://github.com/CallMeTechie/GateControl-Android-Client/releases/download/v1.75.0/face_recognition_sface_2021dec.onnx", s[0])
        assertTrue(s[1].startsWith("https://media.githubusercontent.com/media/opencv/opencv_zoo/"))
        assertTrue(s[2].startsWith("https://huggingface.co/opencv/face_recognition_sface/"))
    }
}
