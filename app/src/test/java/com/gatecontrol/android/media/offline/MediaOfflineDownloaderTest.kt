package com.gatecontrol.android.media.offline

import com.gatecontrol.android.drive.vault.DriveVaultCrypto
import com.gatecontrol.android.familie.emergency.CacheCipher
import com.gatecontrol.android.media.MediaClientInfo
import com.gatecontrol.android.media.MediaSource
import com.gatecontrol.android.media.MediaSourceKind
import com.gatecontrol.android.media.MediaTitle
import com.gatecontrol.android.media.MediaTrackChoice
import com.gatecontrol.android.media.jellyfin.JellyfinLibrary
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

/**
 * Loading a download from a fake Jellyfin: the user's permission, the original with its subtitle file and poster
 * (encrypted, read back), a cut-off original going on with a range, the converted file asked as MPEG-TS, the
 * storage limit.
 */
class MediaOfflineDownloaderTest {
    @TempDir
    lateinit var dir: File

    private lateinit var server: MockWebServer
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private var allowed = true
    private val original = Random(1).nextBytes(2 * DriveVaultCrypto.CHUNK + 4321)
    private val converted = Random(2).nextBytes(DriveVaultCrypto.CHUNK + 99)
    private var ranges = true

    private class PlainCipher : CacheCipher {
        override fun encrypt(plain: ByteArray) = plain
        override fun decrypt(blob: ByteArray) = blob
    }

    private val item = """
        {"Id":"m1","Name":"Kobaltnacht","Type":"Movie","RunTimeTicks":64800000000,"ImageTags":{"Primary":"p1"},
         "MediaSources":[{"Id":"v1","Container":"mkv","Bitrate":40000000,"Size":${2 * DriveVaultCrypto.CHUNK + 4321},
          "MediaStreams":[{"Index":0,"Type":"Video","Codec":"hevc","Width":3840,"Height":2160},
                          {"Index":1,"Type":"Audio","Codec":"aac","Language":"ger","Channels":2},
                          {"Index":3,"Type":"Subtitle","Codec":"srt","Language":"ger","IsExternal":true,"IsTextSubtitleStream":true}]}]}
    """.trimIndent()

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val path = request.requestUrl!!.encodedPath
                return when {
                    path == "/Users/Me" -> MockResponse().setBody("""{"Name":"Mila","Policy":{"EnableContentDownloading":$allowed}}""")
                    path == "/Items/m1" -> MockResponse().setBody(item)
                    path == "/Items/m1/Download" -> {
                        val from = request.getHeader("Range")?.removePrefix("bytes=")?.removeSuffix("-")?.toIntOrNull()
                        if (from != null && ranges) {
                            MockResponse().setResponseCode(206).setBody(Buffer().write(original.copyOfRange(from, original.size)))
                        } else {
                            MockResponse().setBody(Buffer().write(original))
                        }
                    }
                    path == "/Videos/m1/stream.ts" -> MockResponse().setChunkedBody(Buffer().write(converted), 50_000)
                    path == "/Videos/m1/v1/Subtitles/3/Stream.vtt" -> MockResponse().setBody("WEBVTT\n\n00:00.000 --> 00:01.000\nHallo\n")
                    path.startsWith("/Items/m1/Images/") -> MockResponse().setBody(Buffer().write(byteArrayOf(7, 7, 7)))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @AfterEach
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private val source by lazy {
        MediaSource("jf", MediaSourceKind.JELLYFIN, "DS918+", server.url("/").toString().trimEnd('/'), userId = "u1", token = "tok")
    }

    private val link = object : OfflineLink {
        override fun source(id: String) = source.takeIf { it.id == id }
        override suspend fun library(source: MediaSource) = JellyfinLibrary(source, OkHttpClient(), MediaClientInfo("GateControl", "Pixel", "dev-1", "1.0"))
        override fun http(source: MediaSource) = OkHttpClient()
        override fun trackChoice(seriesId: String): MediaTrackChoice? = null
    }

    private val movie = MediaTitle("m1", "jf", MediaTitle.Kind.MOVIE, "Kobaltnacht", runtimeMs = 6_480_000)

    private fun plainOf(store: MediaOfflineStore, id: String): ByteArray {
        val (file, content) = store.open(id, MediaOfflineLogic.VIDEO)!!
        val bytes = file.readBytes()
        val out = ByteArrayOutputStream()
        for (i in 0 until content.chunks) {
            val (off, len) = content.cipherRange(i)
            out.write(content.decryptChunk(i, bytes, off.toInt(), len))
        }
        return out.toByteArray()
    }

    @Test
    fun `the original with its subtitle file and poster, encrypted on the phone`() = runBlocking {
        val store = MediaOfflineStore(dir, PlainCipher())
        val a = store.add(movie, OfflineQuality.ORIGINAL)
        val outcome = MediaOfflineDownloader(store, link, limit = { 20_000_000_000 }, free = { -1 }).runAll()
        assertEquals(MediaOfflineDownloader.Outcome.DONE, outcome)
        val done = store.get(a.id)!!
        assertEquals(OfflineState.READY, done.state)
        assertArrayEquals(original, plainOf(store, a.id))
        assertEquals(listOf(3), done.subtitles.map { it.index })
        assertTrue(String(store.readSmall(a.id, MediaOfflineLogic.subPart(3))!!).startsWith("WEBVTT"))
        assertArrayEquals(byteArrayOf(7, 7, 7), store.readSmall(a.id, MediaOfflineStore.POSTER))
        assertEquals(store.bytesOf(a.id), done.size)
        assertEquals(3, done.version?.streams?.size)
        val download = requests.first { it.requestUrl!!.encodedPath == "/Items/m1/Download" }
        assertTrue(download.getHeader("Authorization")!!.contains("Token=\"tok\""))
        assertEquals("v1", download.requestUrl!!.queryParameter("mediaSourceId"))
    }

    @Test
    fun `a cut-off original goes on after its whole chunks with a range`() = runBlocking {
        val store = MediaOfflineStore(dir, PlainCipher())
        val a = store.add(movie, OfflineQuality.ORIGINAL)
        // one whole chunk and a piece of the next were written before the Wi-Fi went
        val file = store.file(a.id, MediaOfflineLogic.VIDEO).also { it.parentFile?.mkdirs() }
        file.outputStream().use { out ->
            DriveVaultCrypto.ContentWriter.start(a.key, a.id, 1, out, MediaOfflineLogic.PREFIX).use { w -> w.write(original, 0, DriveVaultCrypto.CHUNK + 500) }
        }
        RandomAccessFile(file, "rw").use { it.setLength(it.length() + 1234) }
        MediaOfflineDownloader(store, link, limit = { 20_000_000_000 }, free = { -1 }).runAll()
        val download = requests.last { it.requestUrl!!.encodedPath == "/Items/m1/Download" }
        assertEquals("bytes=${DriveVaultCrypto.CHUNK}-", download.getHeader("Range"))
        assertEquals(OfflineState.READY, store.get(a.id)?.state)
        assertArrayEquals(original, plainOf(store, a.id))
    }

    @Test
    fun `a server without ranges sends it all again`() = runBlocking {
        ranges = false
        val store = MediaOfflineStore(dir, PlainCipher())
        val a = store.add(movie, OfflineQuality.ORIGINAL)
        store.file(a.id, MediaOfflineLogic.VIDEO).also { it.parentFile?.mkdirs() }.outputStream().use { out ->
            DriveVaultCrypto.ContentWriter.start(a.key, a.id, 1, out, MediaOfflineLogic.PREFIX).use { w -> w.write(original, 0, DriveVaultCrypto.CHUNK + 1) }
        }
        MediaOfflineDownloader(store, link, limit = { 20_000_000_000 }, free = { -1 }).runAll()
        assertArrayEquals(original, plainOf(store, a.id))
    }

    @Test
    fun `converted on the NAS - MPEG-TS at the step with the chosen sound, the text subtitles beside it`() = runBlocking {
        val store = MediaOfflineStore(dir, PlainCipher())
        val a = store.add(movie, OfflineQuality.P720)
        MediaOfflineDownloader(store, link, limit = { 20_000_000_000 }, free = { -1 }).runAll()
        val done = store.get(a.id)!!
        assertEquals(OfflineState.READY, done.state)
        assertEquals(OfflineQuality.P720, done.quality)
        assertArrayEquals(converted, plainOf(store, a.id))
        val r = requests.first { it.requestUrl!!.encodedPath == "/Videos/m1/stream.ts" }.requestUrl!!
        assertEquals("h264", r.queryParameter("videoCodec"))
        assertEquals("aac", r.queryParameter("audioCodec"))
        assertEquals("4000000", r.queryParameter("videoBitRate"))
        assertEquals("720", r.queryParameter("maxHeight"))
        assertEquals("1", r.queryParameter("audioStreamIndex"))
        assertEquals("false", r.queryParameter("static"))
        assertEquals("ts", done.version?.container)
        assertEquals(listOf(3), done.subtitles.map { it.index })
    }

    @Test
    fun `Jellyfin does not allow it, the limit is reached`() = runBlocking {
        allowed = false
        val store = MediaOfflineStore(dir, PlainCipher())
        val a = store.add(movie, OfflineQuality.ORIGINAL)
        MediaOfflineDownloader(store, link, limit = { 20_000_000_000 }, free = { -1 }).runAll()
        assertEquals(OfflineFailure.NOT_ALLOWED, store.get(a.id)?.failure)
        assertTrue(requests.none { it.requestUrl!!.encodedPath == "/Items/m1/Download" })

        allowed = true
        val b = store.add(movie, OfflineQuality.ORIGINAL)
        MediaOfflineDownloader(store, link, limit = { 1_000_000 }, free = { -1 }).runAll()
        assertEquals(OfflineState.FAILED, store.get(b.id)?.state)
        assertEquals(OfflineFailure.LIMIT, store.get(b.id)?.failure)
    }

    @Test
    fun `the server not reached - it waits and is tried again`() = runBlocking {
        val store = MediaOfflineStore(dir, PlainCipher())
        val a = store.add(movie, OfflineQuality.ORIGINAL)
        server.shutdown()
        val outcome = MediaOfflineDownloader(store, link, limit = { 20_000_000_000 }, free = { -1 }).runAll()
        assertEquals(MediaOfflineDownloader.Outcome.RETRY, outcome)
        assertEquals(OfflineState.QUEUED, store.get(a.id)?.state)
    }
}
