package com.gatecontrol.android.media.jellyfin

import com.gatecontrol.android.media.MediaClientInfo
import com.gatecontrol.android.media.MediaPlay
import com.gatecontrol.android.media.MediaProgress
import com.gatecontrol.android.media.MediaQuality
import com.gatecontrol.android.media.MediaResult
import com.gatecontrol.android.media.MediaSource
import com.gatecontrol.android.media.MediaSourceKind
import com.gatecontrol.android.media.MediaTitle
import com.gatecontrol.android.media.PlayMethod
import com.google.gson.JsonParser
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** The Jellyfin client against a fake server: the rows, the details, how a title is played and the progress. */
class JellyfinLibraryTest {

    private lateinit var server: MockWebServer
    private lateinit var library: JellyfinLibrary
    private val info = MediaClientInfo("GateControl TV", "Fire TV", "dev-1234", "1.144.1")
    private val http = OkHttpClient()

    @BeforeEach
    fun setUp() {
        server = MockWebServer().apply { start() }
        library = library(JellyfinDeviceCaps(passthrough = setOf("dts")))
    }

    private fun library(caps: JellyfinDeviceCaps) = JellyfinLibrary(
        MediaSource("s1", MediaSourceKind.JELLYFIN, "DS918+", server.url("/").toString().trimEnd('/'), userId = "u1", token = "tok-secret"),
        http,
        info,
        caps = { caps },
    )

    @AfterEach
    fun tearDown() = server.shutdown()

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private val movie = """
        {"Id":"m1","Name":"Kobaltnacht","Type":"Movie","ProductionYear":2025,"RunTimeTicks":64800000000,
         "UserData":{"PlaybackPositionTicks":25300000000,"Played":false},"ImageTags":{"Primary":"p1"},
         "BackdropImageTags":["b1"],"Genres":["Drama"],"OfficialRating":"FSK-12","DateCreated":"2026-11-20T18:00:00.0000000Z"}
    """.trimIndent()

    private val episode = """
        {"Id":"e4","Name":"Folge 4","Type":"Episode","SeriesId":"s9","SeriesName":"Station Eisvogel","SeasonId":"se2",
         "ParentIndexNumber":2,"IndexNumber":4,"RunTimeTicks":27000000000,"UserData":{"PlaybackPositionTicks":16200000000},
         "SeriesPrimaryImageTag":"sp","ParentBackdropItemId":"s9","ParentBackdropImageTags":["sb"]}
    """.trimIndent()

    @Test
    fun `Weiterschauen with the access and the person`() = runTest {
        server.enqueue(json("""{"Items":[$movie,$episode,{"Id":"x","Name":"Album","Type":"MusicAlbum"}],"TotalRecordCount":3}"""))
        val r = library.resume(12)
        val items = (r as MediaResult.Ok).value
        assertEquals(listOf("m1", "e4"), items.map { it.id })
        val m = items[0]
        assertEquals(MediaTitle.Kind.MOVIE, m.kind)
        assertEquals(2025, m.year)
        assertEquals(6_480_000L, m.runtimeMs)
        assertEquals(2_530_000L, m.positionMs)
        assertTrue(m.resumable)
        assertEquals(3_950_000L, m.leftMs)
        assertEquals("m1" to "p1", m.posterId to m.posterTag)
        assertEquals("FSK-12", m.rating)
        assertNotNull(m.addedAt)
        // an episode shows its series' poster and backdrop
        val e = items[1]
        assertEquals("s9" to "sp", e.posterId to e.posterTag)
        assertEquals("s9" to "sb", e.backdropId to e.backdropTag)
        assertEquals(2 to 4, e.season to e.episode)

        val req = server.takeRequest()
        assertEquals("/UserItems/Resume", req.requestUrl!!.encodedPath)
        assertEquals("u1", req.requestUrl!!.queryParameter("userId"))
        val auth = req.getHeader("Authorization")!!
        assertTrue(auth.startsWith("MediaBrowser "), auth)
        assertTrue(auth.contains("Client=\"GateControl TV\""), auth)
        assertTrue(auth.contains("DeviceId=\"dev-1234\""), auth)
        assertTrue(auth.contains("Token=\"tok-secret\""), auth)
        // the token never in the URL
        assertFalse(req.requestUrl.toString().contains("tok-secret"))
    }

    @Test
    fun `Filme A-Z in pages, Zuletzt hinzugefuegt as a bare list`() = runTest {
        server.enqueue(json("""{"Items":[$movie],"TotalRecordCount":412,"StartIndex":40}"""))
        val page = (library.movies(40, 1) as MediaResult.Ok).value
        assertEquals(412, page.total)
        assertEquals(41, page.next)
        val req = server.takeRequest().requestUrl!!
        assertEquals("/Items", req.encodedPath)
        assertEquals("Movie", req.queryParameter("includeItemTypes"))
        assertEquals("SortName", req.queryParameter("sortBy"))
        assertEquals("40", req.queryParameter("startIndex"))

        server.enqueue(json("[$movie]"))
        assertEquals(1, (library.latest(16) as MediaResult.Ok).value.size)
        assertEquals("Movie,Series", server.takeRequest().requestUrl!!.queryParameter("includeItemTypes"))
    }

    @Test
    fun `seasons and episodes of a series`() = runTest {
        server.enqueue(json("""{"Items":[{"Id":"se2","Name":"Staffel 2","Type":"Season","IndexNumber":2,"SeriesId":"s9"}]}"""))
        assertEquals("Staffel 2", (library.seasons("s9") as MediaResult.Ok).value.single().name)
        assertEquals("/Shows/s9/Seasons", server.takeRequest().requestUrl!!.encodedPath)
        server.enqueue(json("""{"Items":[$episode]}"""))
        assertEquals("e4", (library.episodes("s9", "se2") as MediaResult.Ok).value.single().id)
        val r = server.takeRequest().requestUrl!!
        assertEquals("/Shows/s9/Episodes", r.encodedPath)
        assertEquals("se2", r.queryParameter("seasonId"))
    }

    private val version = """
        {"Id":"v1","Container":"mkv","Bitrate":52000000,"RunTimeTicks":64800000000,"SupportsDirectPlay":true,
         "SupportsDirectStream":true,"SupportsTranscoding":true,"DefaultAudioStreamIndex":1,
         "MediaStreams":[
           {"Index":0,"Type":"Video","Codec":"hevc","Width":3840,"Height":2160,"VideoRange":"HDR","VideoRangeType":"HDR10"},
           {"Index":1,"Type":"Audio","Codec":"dts","Language":"ger","Channels":6,"IsDefault":true},
           {"Index":2,"Type":"Audio","Codec":"truehd","Language":"eng","Channels":8},
           {"Index":3,"Type":"Subtitle","Codec":"subrip","Language":"ger","IsTextSubtitleStream":true,"IsExternal":true,
            "DeliveryMethod":"External","DeliveryUrl":"/Videos/m1/v1/Subtitles/3/0/Stream.srt"},
           {"Index":4,"Type":"Subtitle","Codec":"PGSSUB","Language":"eng","IsTextSubtitleStream":false}
         ]}
    """.trimIndent()

    @Test
    fun `the detail page with the file's streams`() = runTest {
        server.enqueue(json(movie.dropLast(1) + ""","Overview":"Eine Funkerin …","MediaSources":[$version]}"""))
        val d = (library.detail("m1") as MediaResult.Ok).value
        assertEquals("Eine Funkerin …", d.title.overview)
        val v = d.version!!
        assertEquals("mkv", v.container)
        assertEquals(52_000_000L, v.bitrate)
        assertEquals("4K HDR10", MediaQuality.videoLabel(v.video))
        assertEquals(listOf(1, 2), v.audio.map { it.index })
        assertTrue(v.subtitles[1].picture)
        assertEquals("pgssub", v.subtitles[1].codec)
        assertEquals("/Items/m1", server.takeRequest().requestUrl!!.encodedPath)
    }

    @Test
    fun `at home the file itself, with the device profile and the start`() = runTest {
        server.enqueue(json("""{"MediaSources":[$version],"PlaySessionId":"ps1"}"""))
        val plan = (library.play(MediaPlay("m1", startMs = 2_530_000, subtitleIndex = 3)) as MediaResult.Ok).value
        assertEquals(PlayMethod.DIRECT_PLAY, plan.method)
        assertFalse(plan.converted)
        assertFalse(plan.hls)
        assertTrue(plan.url.contains("/Videos/m1/stream?static=true&mediaSourceId=v1&playSessionId=ps1"), plan.url)
        assertFalse(plan.url.contains("tok-secret"))
        assertEquals(1, plan.audioIndex)
        assertEquals(3, plan.subtitleIndex)
        // the external SRT comes beside the video
        val side = plan.sideSubtitles.single()
        assertEquals("application/x-subrip", side.mime)
        assertEquals(server.url("/Videos/m1/v1/Subtitles/3/0/Stream.srt").toString(), side.url)

        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/Items/m1/PlaybackInfo", req.requestUrl!!.encodedPath)
        val body = JsonParser.parseString(req.body.readUtf8()).asJsonObject
        assertEquals(MediaQuality.ORIGINAL_BPS, body.get("MaxStreamingBitrate").asLong)
        assertEquals(25_300_000_000L, body.get("StartTimeTicks").asLong)
        assertEquals(3, body.get("SubtitleStreamIndex").asInt)
        val profile = body.getAsJsonObject("DeviceProfile")
        val direct = profile.getAsJsonArray("DirectPlayProfiles").first().asJsonObject
        assertTrue(direct.get("AudioCodec").asString.split(',').contains("dts"))
        assertEquals("hls", profile.getAsJsonArray("TranscodingProfiles").first().asJsonObject.get("Protocol").asString)
    }

    @Test
    fun `away the NAS converts at the bit rate asked for`() = runTest {
        val converting = version.replace("\"SupportsDirectPlay\":true", "\"SupportsDirectPlay\":false")
            .replace(
                "\"DefaultAudioStreamIndex\":1,",
                "\"DefaultAudioStreamIndex\":1,\"TranscodingSubProtocol\":\"hls\"," +
                    "\"TranscodingUrl\":\"/videos/m1/master.m3u8?MediaSourceId=v1&VideoBitrate=7616000&TranscodeReasons=ContainerBitrateExceedsLimit\",",
            )
        server.enqueue(json("""{"MediaSources":[$converting],"PlaySessionId":"ps2"}"""))
        val plan = (library.play(MediaPlay("m1", maxBitrate = 8_000_000)) as MediaResult.Ok).value
        assertEquals(PlayMethod.TRANSCODE, plan.method)
        assertTrue(plan.converted)
        assertTrue(plan.hls)
        assertEquals(8_000_000L, plan.bitrate)
        assertEquals(server.url("/videos/m1/master.m3u8").toString(), plan.url.substringBefore('?'))
        assertEquals(listOf("ContainerBitrateExceedsLimit"), plan.version.transcodeReasons)
        val body = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
        assertEquals(8_000_000L, body.get("MaxStreamingBitrate").asLong)
        assertEquals(8_000_000L, body.getAsJsonObject("DeviceProfile").get("MaxStreamingBitrate").asLong)
    }

    @Test
    fun `a refused playback and an unknown title`() = runTest {
        server.enqueue(json("""{"MediaSources":[],"ErrorCode":"NoCompatibleStream"}"""))
        assertTrue(library.play(MediaPlay("m1")) is MediaResult.Failed)
        server.enqueue(json("{}", code = 404))
        assertEquals(MediaResult.NotFound, library.detail("zz"))
    }

    @Test
    fun `the progress goes to Jellyfin`() = runTest {
        server.enqueue(json("""{"MediaSources":[$version],"PlaySessionId":"ps1"}"""))
        val plan = (library.play(MediaPlay("m1")) as MediaResult.Ok).value
        server.takeRequest()
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setResponseCode(204))
        library.started(MediaProgress(plan, 0, paused = false))
        library.progress(MediaProgress(plan, 42_000, paused = true))
        library.stopped(MediaProgress(plan, 60_000, paused = false))
        val started = server.takeRequest()
        assertEquals("/Sessions/Playing", started.requestUrl!!.encodedPath)
        val progress = server.takeRequest()
        assertEquals("/Sessions/Playing/Progress", progress.requestUrl!!.encodedPath)
        val p = JsonParser.parseString(progress.body.readUtf8()).asJsonObject
        assertEquals(420_000_000L, p.get("PositionTicks").asLong)
        assertTrue(p.get("IsPaused").asBoolean)
        assertEquals("DirectPlay", p.get("PlayMethod").asString)
        assertEquals("ps1", p.get("PlaySessionId").asString)
        assertEquals("v1", p.get("MediaSourceId").asString)
        val stopped = server.takeRequest()
        assertEquals("/Sessions/Playing/Stopped", stopped.requestUrl!!.encodedPath)
        assertFalse(JsonParser.parseString(stopped.body.readUtf8()).asJsonObject.get("Failed").asBoolean)
    }

    @Test
    fun `seen and not seen`() = runTest {
        server.enqueue(json("""{"Played":true}"""))
        assertTrue(library.setPlayed("m1", true) is MediaResult.Ok)
        val r = server.takeRequest()
        assertEquals("POST", r.method)
        assertEquals("/UserPlayedItems/m1", r.requestUrl!!.encodedPath)
        server.enqueue(json("""{"Played":false}"""))
        library.setPlayed("m1", false)
        assertEquals("DELETE", server.takeRequest().method)
    }

    @Test
    fun `signed out, unreachable`() = runTest {
        server.enqueue(json("{}", code = 401))
        assertEquals(MediaResult.Unauthorized, library.resume(12))
        server.shutdown()
        assertEquals(MediaResult.Offline, library.resume(12))
    }

    @Test
    fun `the bandwidth from the server`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(ByteArray(200_000))))
        val bps = library.measure()
        assertNotNull(bps)
        assertTrue(bps!! > 0)
        val r = server.takeRequest().requestUrl!!
        assertEquals("/Playback/BitrateTest", r.encodedPath)
        assertEquals(JellyfinLibrary.MEASURE_BYTES.toString(), r.queryParameter("size"))
        server.enqueue(json("{}", code = 500))
        assertNull(library.measure())
    }

    @Test
    fun `a server's URLs stay on the server`() {
        val c = JellyfinClient("http://192.168.1.20:8096/jellyfin", http, info, "tok")
        assertEquals("http://192.168.1.20:8096/jellyfin/videos/m1/master.m3u8?a=1", c.absolute("/videos/m1/master.m3u8?a=1"))
        assertEquals("http://192.168.1.20:8096/jellyfin/videos/m1/master.m3u8", c.absolute("/jellyfin/videos/m1/master.m3u8"))
        // another host: only its path and query, the access never leaves for it
        assertEquals("http://192.168.1.20:8096/jellyfin/x/y?z=1", c.absolute("http://media.example.test/x/y?z=1"))
    }

    @Test
    fun `pictures by tag, none without one`() {
        val t = MediaTitle("m1", "s1", MediaTitle.Kind.MOVIE, "Kobaltnacht", posterId = "m1", posterTag = "p1")
        val url = library.imageUrl(t, backdrop = false, maxHeight = 300)!!
        assertTrue(url.contains("/Items/m1/Images/Primary?maxHeight=300&quality=85&tag=p1"), url)
        assertNull(library.imageUrl(t, backdrop = true, maxHeight = 540))
        assertTrue(library.headers().getValue("Authorization").contains("Token=\"tok-secret\""))
    }

    @Test
    fun `Geraet anmelden - another device's Quick Connect code confirmed with this access`() = runTest {
        server.enqueue(json("true"))
        server.enqueue(json("true"))
        assertEquals(JellyfinAuthorize.CONFIRMED, library.authorize(" 731 205 "))
        assertEquals("/QuickConnect/Enabled", server.takeRequest().path)
        val r = server.takeRequest()
        assertEquals("POST", r.method)
        assertEquals("/QuickConnect/Authorize?code=731205&userId=u1", r.path)
        assertTrue(r.getHeader("Authorization")!!.contains("Token=\"tok-secret\""))

        // a code nobody is waiting with
        server.enqueue(json("true"))
        server.enqueue(json("{}", code = 404))
        assertEquals(JellyfinAuthorize.UNKNOWN_CODE, library.authorize("000000"))
        server.takeRequest()
        server.takeRequest()

        // Quick Connect switched off on the server: nothing sent
        server.enqueue(json("false"))
        assertEquals(JellyfinAuthorize.DISABLED, library.authorize("731205"))
        server.takeRequest()
        assertEquals(JellyfinAuthorize.UNKNOWN_CODE, library.authorize("  "))
        assertEquals(5, server.requestCount)

        // this device's access is gone
        server.enqueue(json("true"))
        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(JellyfinAuthorize.SIGNED_OUT, library.authorize("731205"))
    }

    @Test
    fun `a phone without Dolby decoders - AC3 converted to AAC, never passed through`() {
        val phone = com.gatecontrol.android.media.MediaDeviceCaps.fromDecoders(listOf("video/avc", "video/hevc", "audio/mp4a-latm"), hdrScreen = false, name = "GateControl")
        assertFalse("ac3" in JellyfinProfile.directAudio(phone))
        assertFalse("eac3" in JellyfinProfile.directAudio(phone))
        assertEquals(listOf("aac"), JellyfinProfile.convertedAudio(phone))
        val profile = JellyfinProfile.build(phone, 8_000_000)
        assertEquals("GateControl", profile.get("Name").asString)
        assertEquals("aac", profile.getAsJsonArray("TranscodingProfiles")[0].asJsonObject.get("AudioCodec").asString)
        assertFalse(profile.getAsJsonArray("DirectPlayProfiles")[0].asJsonObject.get("AudioCodec").asString.contains("ac3"))
        // the TV stick decodes Dolby itself, DTS goes to the receiver
        val tv = JellyfinDeviceCaps(passthrough = setOf("dts"))
        assertEquals(listOf("aac", "ac3", "eac3", "dts"), JellyfinProfile.convertedAudio(tv))
        assertTrue("eac3" in JellyfinProfile.directAudio(tv))
    }

    @Test
    fun `downloads - the user's permission, the original, the converted file, a subtitle, progress watched offline`() = runTest {
        server.enqueue(json("""{"Name":"Mila","Policy":{"EnableContentDownloading":false}}"""))
        assertEquals(MediaResult.Ok(false), library.canDownload())
        assertEquals("/Users/Me", server.takeRequest().requestUrl!!.encodedPath)
        // an old server that does not say: allowed
        server.enqueue(json("""{"Name":"Mila"}"""))
        assertEquals(MediaResult.Ok(true), library.canDownload())
        server.takeRequest()

        val original = library.downloadUrl("m1", "v1").toHttpUrl()
        assertEquals("/Items/m1/Download", original.encodedPath)
        assertEquals("v1", original.queryParameter("mediaSourceId"))
        val converted = library.convertedUrl("m1", "v1", 8_000_000, 1920, 1080, audioIndex = 2, burnSubtitle = 5).toHttpUrl()
        assertEquals("/Videos/m1/stream.ts", converted.encodedPath)
        assertEquals("8000000", converted.queryParameter("videoBitRate"))
        assertEquals("1080", converted.queryParameter("maxHeight"))
        assertEquals("2", converted.queryParameter("audioStreamIndex"))
        assertEquals("5", converted.queryParameter("subtitleStreamIndex"))
        assertEquals("Encode", converted.queryParameter("subtitleMethod"))
        assertEquals("dev-1234", converted.queryParameter("deviceId"))
        assertFalse(converted.toString().contains("tok-secret"), "the access goes in the header, never in the URL")
        assertTrue(library.subtitleUrl("m1", "v1", 3).endsWith("/Videos/m1/v1/Subtitles/3/Stream.vtt"))

        server.enqueue(MockResponse().setResponseCode(204))
        assertEquals(MediaResult.Ok(Unit), library.saveUserData("m1", positionMs = 2_530_000, played = false, playedAt = 1_764_000_000_000))
        val r = server.takeRequest()
        assertEquals("/UserItems/m1/UserData", r.requestUrl!!.encodedPath)
        assertEquals("u1", r.requestUrl!!.queryParameter("userId"))
        val body = JsonParser.parseString(r.body.readUtf8()).asJsonObject
        assertEquals(25_300_000_000, body.get("PlaybackPositionTicks").asLong)
        assertFalse(body.get("Played").asBoolean)
        assertEquals("2025-11-24T16:00:00Z", body.get("LastPlayedDate").asString)
    }

    @Test
    fun `a version's size is read`() = runTest {
        server.enqueue(json("""{"Id":"m1","Name":"Kobaltnacht","Type":"Movie","MediaSources":[{"Id":"v1","Container":"mkv","Size":3100000000}]}"""))
        val d = library.detail("m1").valueOrNull()!!
        assertEquals(3_100_000_000, d.version?.size)
    }
}
