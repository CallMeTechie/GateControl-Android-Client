package com.gatecontrol.android.chat

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Videos and file previews (Messenger 1.3.0: `video`, `thumbMediaId` on files, `kind=video`, `features`), the update hint. */
class ChatVideoTest {

    private fun json(s: String): JsonObject = JsonParser.parseString(s).asJsonObject

    private val me = """
        "user":{"id":7,"name":"Marc"},"prefs":{},"limits":{},"serverTime":"2026-10-17T10:00:00.000Z"
    """

    private fun msg(id: Long, kind: String, mime: String?) = ChatMessage(
        id = id, conversationId = 12, senderId = 8, clientId = null, kind = kind, via = null, mine = false,
        createdAt = "2026-10-17T10:00:00.000Z", editedAt = null, deletedAt = null, expiresAt = null, deleted = false, undecryptable = false,
        text = null, replyTo = null, media = ChatMedia("%032x".format(id), mime, 100, null), meta = null, location = null,
        system = null, reactions = emptyList(), status = null,
    )

    // ─── features ───────────────────────────────────────────────────

    @Test
    fun `features as an object, further true keys are names`() {
        val p = ChatJson.profile(json("""{$me,"features":{"files":false,"bridges":false,"video_meta":true,"file_thumbs":false}}"""))!!
        assertFalse(p.features.files)
        assertTrue(p.features.videoMeta)
        assertFalse(p.features.fileThumbs)
        assertEquals(p, ChatJson.profile(ChatSerializer.profile(p)))
    }

    @Test
    fun `the preview size comes from the limits`() {
        val p = ChatJson.profile(json("""{"user":{"id":7,"name":"Marc"},"features":{},"limits":{"thumbMaxSide":480,"thumbMaxBytes":65536}}"""))!!
        assertEquals(480, p.limits.thumbMaxSide)
        assertEquals(640, ChatJson.profile(json("""{$me,"features":{}}"""))!!.limits.thumbMaxSide, "older servers: 640")
        assertEquals(p, ChatJson.profile(ChatSerializer.profile(p)))
        assertEquals(listOf(640, 480, 320), ChatPreview.thumbSides(640))
        assertEquals(listOf(480, 360, 240), ChatPreview.thumbSides(480))
    }

    @Test
    fun `older servers name neither`() {
        val p = ChatJson.profile(json("""{$me,"features":{"direct":true,"edit":true}}"""))!!
        assertFalse(p.features.videoMeta)
        assertFalse(p.features.fileThumbs)
        assertFalse(ChatPreview.wantsThumb("video/mp4", p.features))
        assertFalse(ChatPreview.wantsThumb("video/mp4", null))
    }

    // ─── messages ───────────────────────────────────────────────────

    @Test
    fun `a video file carries its length and size`() {
        val m = ChatJson.message(
            json(
                """{"id":50,"conversationId":12,"senderId":7,"kind":"file","mine":true,"createdAt":"2026-10-17T10:00:00.000Z",
                 "media":{"id":"0123456789abcdef0123456789abcdef","mime":"video/mp4","bytes":900000,"thumbId":"abcdefabcdefabcdefabcdefabcdefab"},
                 "meta":{"fileName":"clip.mp4"},"video":{"durationMs":42000,"width":1080,"height":1920},"reactions":[]}""",
            ),
        )!!
        assertTrue(m.isVideo)
        assertEquals(ChatVideo(42_000, 1080, 1920), m.video)
        assertEquals(42_000L, m.videoDurationMs)
        assertEquals("abcdefabcdefabcdefabcdefabcdefab", m.media?.thumbId)
        assertEquals(m, ChatJson.message(ChatSerializer.message(m)), "survives the cache")
        // A tombstone keeps nothing of it.
        val tomb = ChatJson.message(ChatJsonPatch.tombstone(ChatSerializer.message(m), "2026-10-17T10:05:00.000Z"))!!
        assertNull(tomb.video)
    }

    @Test
    fun `nonsense video values are dropped`() {
        val m = ChatJson.message(
            json(
                """{"id":50,"conversationId":12,"kind":"file","createdAt":"2026-10-17T10:00:00.000Z",
                 "media":{"id":"0123456789abcdef0123456789abcdef","mime":"video/webm"},"video":{"durationMs":-5,"width":0,"height":"x"}}""",
            ),
        )!!
        assertNull(m.video)
        assertTrue(m.isVideo, "still a video by its type")
        assertFalse(msg(1, ChatMessage.KIND_FILE, "application/pdf").isVideo)
        assertFalse(msg(2, ChatMessage.KIND_IMAGE, "image/webp").isVideo)
    }

    @Test
    fun `send body carries video and preview`() {
        val body = ChatJson.sendBody(
            "client-123456", ChatMessage.KIND_FILE, null, mediaId = "0123456789abcdef0123456789abcdef",
            thumbMediaId = "abcdefabcdefabcdefabcdefabcdefab", meta = ChatMeta(fileName = "clip.mp4"), video = ChatVideo(1500, 640, 360),
        )
        assertEquals("file", body.get("kind").asString)
        assertEquals("abcdefabcdefabcdefabcdefabcdefab", body.get("thumbMediaId").asString)
        assertEquals(json("""{"durationMs":1500,"width":640,"height":360}"""), body.getAsJsonObject("video"))
        assertEquals(json("""{"fileName":"clip.mp4"}"""), body.getAsJsonObject("meta"))
        assertFalse(ChatJson.sendBody("client-123456", ChatMessage.KIND_FILE, null).has("video"))
    }

    // ─── preview logic ──────────────────────────────────────────────

    @Test
    fun `the outbox keeps a video's length in meta, the body sends it as video`() {
        val meta = ChatMeta(fileName = "clip.mp4", durationMs = 42_000, w = 1080, h = 1920)
        // The outbox round trip keeps it all.
        assertEquals(meta, ChatOutboxLogic.metaOf(ChatOutboxLogic.metaJson(meta)!!))
        val (m, v) = ChatPreview.sendFields(ChatMessage.KIND_FILE, "video/mp4", meta)
        assertEquals(ChatMeta(fileName = "clip.mp4"), m)
        assertEquals(ChatVideo(42_000, 1080, 1920), v)
        // Without video_meta nothing was stored: the file name only.
        assertEquals(ChatMeta(fileName = "clip.mp4") to null, ChatPreview.sendFields(ChatMessage.KIND_FILE, "video/mp4", ChatMeta(fileName = "clip.mp4")))
        // Other files and kinds are left alone.
        val pdf = ChatMeta(fileName = "a.pdf")
        assertEquals(pdf to null, ChatPreview.sendFields(ChatMessage.KIND_FILE, "application/pdf", pdf))
        val voice = ChatMeta(durationMs = 3000, waveform = listOf(1, 2))
        assertEquals(voice to null, ChatPreview.sendFields(ChatMessage.KIND_VOICE, "audio/mp4", voice))
    }

    @Test
    fun `previews for videos and PDFs only when the server takes them`() {
        val on = ChatFeatures(tokens = setOf(ChatFeatures.FILE_THUMBS))
        assertTrue(ChatPreview.wantsThumb("video/mp4", on))
        assertTrue(ChatPreview.wantsThumb("application/pdf", on))
        assertFalse(ChatPreview.wantsThumb("application/zip", on))
        assertFalse(ChatPreview.wantsThumb("image/gif", on))
        assertFalse(ChatPreview.wantsThumb("video/mp4", ChatFeatures(tokens = setOf(ChatFeatures.VIDEO_META))))
    }

    @Test
    fun `preview sizes stay within 640 px`() {
        assertEquals(640 to 360, ChatPreview.fit(1920, 1080, 640))
        assertEquals(360 to 640, ChatPreview.fit(1080, 1920, 640))
        assertEquals(320 to 240, ChatPreview.fit(320, 240, 640), "small ones stay")
        // PDF pages (points) are rendered up to the size.
        assertEquals(452 to 640, ChatPreview.fit(595, 842, 640, upscale = true))
        assertEquals(640 to 640, ChatPreview.fit(0, 0, 640))
    }

    @Test
    fun `upright videos swap width and height`() {
        assertEquals(1080 to 1920, ChatPreview.orientedSize(1920, 1080, 90))
        assertEquals(1080 to 1920, ChatPreview.orientedSize(1920, 1080, -90))
        assertEquals(1920 to 1080, ChatPreview.orientedSize(1920, 1080, 180))
        assertEquals(1920 to 1080, ChatPreview.orientedSize(1920, 1080, null))
    }

    @Test
    fun `the frame is taken at a second, in the middle of short clips`() {
        assertEquals(1_000_000L, ChatPreview.frameTimeUs(42_000))
        assertEquals(1_000_000L, ChatPreview.frameTimeUs(null))
        assertEquals(750_000L, ChatPreview.frameTimeUs(1_500))
        assertEquals(1_000_000L, ChatPreview.frameTimeUs(0))
    }

    @Test
    fun `video fields from the file`() {
        assertEquals(ChatVideo(42_000, 1080, 1920), ChatPreview.video(42_000, 1080, 1920))
        assertEquals(ChatVideo(null, 640, null), ChatPreview.video(-1, 640, 0))
        assertNull(ChatPreview.video(null, null, null))
    }

    // ─── media overview ─────────────────────────────────────────────

    @Test
    fun `with video_meta videos get a tab of their own`() {
        val image = msg(1, ChatMessage.KIND_IMAGE, "image/webp")
        val video = msg(2, ChatMessage.KIND_FILE, "video/mp4")
        val pdf = msg(3, ChatMessage.KIND_FILE, "application/pdf")
        val all = listOf(image, video, pdf)
        assertEquals(ChatMediaLogic.Tab.entries, ChatMediaLogic.tabs(videoMeta = true))
        assertFalse(ChatMediaLogic.Tab.Videos in ChatMediaLogic.tabs(videoMeta = false))
        assertEquals("image", ChatMediaLogic.kind(ChatMediaLogic.Tab.Media, videoMeta = true))
        assertEquals("video", ChatMediaLogic.kind(ChatMediaLogic.Tab.Videos, videoMeta = true))
        assertEquals("file", ChatMediaLogic.kind(ChatMediaLogic.Tab.Files, videoMeta = true))
        assertEquals(listOf(1L), ChatMediaLogic.merge(ChatMediaLogic.Tab.Media, emptyList(), all, videoMeta = true).map { it.id })
        assertEquals(listOf(2L), ChatMediaLogic.merge(ChatMediaLogic.Tab.Videos, emptyList(), all, videoMeta = true).map { it.id })
        assertEquals(listOf(3L), ChatMediaLogic.merge(ChatMediaLogic.Tab.Files, emptyList(), all, videoMeta = true).map { it.id })
        // Without it: pictures and videos together, as before.
        assertEquals(listOf(2L, 1L), ChatMediaLogic.merge(ChatMediaLogic.Tab.Media, emptyList(), all, videoMeta = false).map { it.id })
    }

    @Test
    fun `media counts gain videos`() {
        val page = ChatJson.mediaPage(json("""{"ok":true,"hasMore":false,"counts":{"image":2,"voice":0,"file":1,"video":4},"items":[]}"""))
        assertNotNull(page)
        assertEquals(4, page!!.videos)
        assertEquals(1, page.files)
        assertNull(ChatJson.mediaPage(json("""{"ok":true,"counts":{"image":2},"items":[]}"""))!!.videos)
    }

    // ─── update hint ────────────────────────────────────────────────

    @Test
    fun `unknown features ask for a newer app`() {
        val known = ChatJson.profile(json("""{$me,"features":{"direct":true,"bridges":false,"e2e":false,"video_meta":true,"file_thumbs":true}}"""))!!.features
        assertEquals(emptySet<String>(), ChatFeatureHint.unknown(known))
        assertFalse(ChatFeatureHint.show(known, null))
        val newer = ChatJson.profile(json("""{$me,"features":{"video_meta":true,"polls":true,"stories":true,"stickers":false}}"""))!!.features
        assertEquals(setOf("polls", "stories"), ChatFeatureHint.unknown(newer), "only keys switched on count")
        assertTrue(ChatFeatureHint.show(newer, null))
        assertFalse(ChatFeatureHint.unknown(ChatFeatures(tokens = setOf("e2e", "bridges"))).isNotEmpty(), "known keys switched on are no reason")
        assertFalse(ChatFeatureHint.show(null, null))
    }

    @Test
    fun `a dismissal holds until another unknown feature turns up`() {
        val polls = ChatFeatures(tokens = setOf("stories", "polls"))
        val sig = ChatFeatureHint.signature(ChatFeatureHint.unknown(polls))
        assertEquals("polls,stories", sig, "independent of the order")
        assertFalse(ChatFeatureHint.show(polls, sig))
        assertTrue(ChatFeatureHint.show(ChatFeatures(tokens = setOf("stories", "polls", "calls")), sig))
        assertTrue(ChatFeatureHint.show(ChatFeatures(tokens = setOf("calls")), sig))
        assertNull(ChatFeatureHint.signature(emptySet()))
    }
}
