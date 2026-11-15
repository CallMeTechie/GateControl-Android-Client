package com.gatecontrol.android.chat

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Files and pictures over a bridge (Messenger 1.15.0, apps-contract stage 4): the feature words, `bridge.supports` and
 * `bridge.mediaTypes` (as server/bridgechats.js convInfo sends them), the placeholder's states, a contact's picture,
 * what may be sent (no WebM voice message, a video only as MP4), the refusals `423 scan_pending` (sent again, same
 * clientId) and `409 bridge_media_type` (not), and a caption that cannot be edited over there.
 */
class ChatBridgeMediaTest {

    private fun json(s: String): JsonObject = JsonParser.parseString(s).asJsonObject

    private val mediaTypes = """{"image":["image/jpeg","image/png","image/webp"],"video":["video/mp4"],"voice":["audio/ogg","audio/mp4","audio/aac","audio/mpeg"],"file":null}"""

    private fun conv(supports: String = """["text","reply","image","video","voice","file"]""", types: String? = mediaTypes) = ChatJson.conversation(
        json(
            """{"id":31,"kind":"direct","title":"Kim Muster","peer":null,"avatarMediaId":"0123456789abcdef0123456789abcdef","members":[{"userId":7,"name":"Anna"}],
                "me":{"role":"member","notify":"all"},"bridge":{"network":"whatsapp","label":"WhatsApp","state":"ok","closed":false,"supports":$supports
                ${types?.let { ""","mediaTypes":$it""" } ?: ""}},"can":{"send":true}}""",
        ),
    )!!

    private fun msg(bridge: String, kind: String = "text", text: String? = null) = ChatJson.message(
        json("""{"id":93,"conversationId":31,"senderId":null,"kind":"$kind","mine":false,"createdAt":"2026-11-15T09:58:00.000Z","text":${text?.let { "\"$it\"" }},"reactions":[],"bridge":$bridge}"""),
    )!!

    private fun ph(kind: String, state: String?, reason: String? = null, extra: String = "") =
        """{"sender":{"contactId":12,"name":"Kim Muster","avatarMediaId":"fedcba9876543210fedcba9876543210"},"remote":false,"state":null,"reason":null,
            "placeholder":{"kind":"$kind","name":"grill.jpg","state":${state?.let { "\"$it\"" }},"reason":${reason?.let { "\"$it\"" }}$extra}}"""

    @Test
    fun `the feature words are known - no update hint for them`() {
        val f = ChatFeatures(tokens = setOf("bridge_chats", "bridge_media", "bridge_new_chat"))
        assertTrue(ChatFeatureHint.unknown(f).isEmpty())
        assertEquals("bridge_media", ChatBridgeChat.FEATURE_MEDIA)
        assertEquals("bridge_new_chat", ChatBridgeChat.FEATURE_NEW_CHAT)
    }

    @Test
    fun `supports and mediaTypes - read, stored and read again`() {
        val c = conv()
        val b = c.bridge!!
        assertTrue("file" in b.supports && "voice" in b.supports)
        assertEquals(listOf("video/mp4"), b.mediaTypes!!["video"])
        assertTrue(b.mediaTypes!!.containsKey("file"))
        assertNull(b.mediaTypes!!["file"], "null: any type")
        assertEquals("0123456789abcdef0123456789abcdef", c.avatarMediaId, "the contact's picture as the chat's")
        assertEquals(c, ChatJson.conversation(ChatSerializer.conversation(c)))
        // a server of stage 3 names none
        val old = conv(supports = """["text","reply"]""", types = null)
        assertNull(old.bridge!!.mediaTypes)
        assertEquals(old, ChatJson.conversation(ChatSerializer.conversation(old)))
        // what is no type is left out, an unknown kind too
        val odd = conv(types = """{"image":["IMAGE/JPEG","no type",3],"sticker":["image/webp"],"voice":"audio/ogg"}""")
        assertEquals(mapOf("image" to listOf("image/jpeg")), odd.bridge!!.mediaTypes)
    }

    @Test
    fun `what may go - pictures, voice in AAC-in-MP4 but never WebM, a video only as MP4, any file`() {
        val c = conv()
        assertTrue(ChatBridgeChat.attach(c))
        assertTrue(ChatBridgeChat.mediaOk(c, ChatMessage.KIND_IMAGE, PREPARED_IMAGE_MIME), "the app's pictures are WebP")
        assertTrue(ChatBridgeChat.mediaOk(c, ChatMessage.KIND_IMAGE, "image/jpeg; charset=x"))
        assertFalse(ChatBridgeChat.mediaOk(c, ChatMessage.KIND_IMAGE, "image/gif"))
        assertTrue(ChatBridgeChat.voice(c, VoiceRecorder.MIME), "the recorder's audio/mp4")
        assertFalse(ChatBridgeChat.voice(c, "audio/webm"))
        assertTrue(ChatBridgeChat.mediaOk(c, ChatMessage.KIND_FILE, "video/mp4"))
        assertFalse(ChatBridgeChat.mediaOk(c, ChatMessage.KIND_FILE, "video/webm"))
        assertTrue(ChatBridgeChat.mediaOk(c, ChatMessage.KIND_FILE, "application/x-anything"))
        assertTrue(ChatBridgeChat.mediaOk(c, ChatMessage.KIND_FILE, "image/gif"), "a picture as a file is a file")
        assertEquals("video", ChatBridgeChat.outKind(ChatMessage.KIND_FILE, "VIDEO/MP4"))
    }

    @Test
    fun `a chat of stage 3 takes text only - no paper clip, no microphone`() {
        val c = conv(supports = """["text","reply","reaction"]""", types = null)
        assertFalse(ChatBridgeChat.attach(c))
        assertFalse(ChatBridgeChat.voice(c, VoiceRecorder.MIME))
        assertFalse(ChatBridgeChat.mediaOk(c, ChatMessage.KIND_FILE, "application/pdf"))
        // a chat inside GateControl takes everything
        assertTrue(ChatBridgeChat.attach(null))
        assertTrue(ChatBridgeChat.mediaOk(null, ChatMessage.KIND_VOICE, "audio/webm"))
    }

    @Test
    fun `placeholders - loading, failed with the reason, a location with its place, an older server`() {
        val loading = msg(ph("image", "loading")).bridge!!
        assertTrue(loading.placeholder!!.loading)
        assertEquals("fedcba9876543210fedcba9876543210", loading.senderAvatarMediaId)
        val infected = msg(ph("file", "failed", "infected")).bridge!!.placeholder!!
        assertTrue(infected.failed)
        assertEquals("infected", infected.reason)
        for (r in listOf("too_large", "type", "quota", "gone", "unavailable")) assertEquals(r, msg(ph("video", "failed", r)).bridge!!.placeholder!!.reason)
        // a reason this app does not know still says the file did not come
        assertEquals("unavailable", msg(ph("video", "failed", "eaten")).bridge!!.placeholder!!.reason)
        val geo = msg(ph("location", null, extra = ""","geo":{"lat":52.52,"lon":13.405}"""), text = "Brandenburger Tor").bridge!!.placeholder!!
        assertTrue(geo.geo)
        assertEquals(52.52, geo.lat)
        assertNull(geo.state)
        assertFalse(msg(ph("location", null, extra = ""","geo":{"lat":95,"lon":13}""")).bridge!!.placeholder!!.geo, "no place outside the globe")
        val old = msg("""{"sender":{"contactId":12,"name":"Kim"},"remote":false,"state":null,"reason":null,"placeholder":{"kind":"image","name":"a.jpg"}}""").bridge!!
        assertNull(old.placeholder!!.state)
        assertNull(old.senderAvatarMediaId)
        // stored and read again
        for (m in listOf(msg(ph("image", "loading")), msg(ph("file", "failed", "gone")), msg(ph("location", null, extra = ""","geo":{"lat":1.5,"lon":2.5}""")))) {
            assertEquals(m, ChatJson.message(ChatSerializer.message(m)))
        }
    }

    @Test
    fun `a caption is not edited over there, a text is`() {
        val own = """{"sender":null,"remote":false,"state":"sent","reason":null,"placeholder":null}"""
        assertTrue(ChatBridgeChat.editable(msg(own, "text", "Hallo")))
        assertFalse(ChatBridgeChat.editable(msg(own, "image", "Unterschrift")))
    }

    @Test
    fun `423 scan_pending - sent again in a few seconds, 409 bridge_media_type - never`() {
        val pending = ChatOutboxLogic.step(ChatResult.Failed("scan_pending", 423), 1, hadUpload = true, nowMs = 1_000L)
        assertInstanceOf(OutboxStep.Retry::class.java, pending)
        assertEquals(1_000L + ChatOutboxLogic.SCAN_WAIT_MS, (pending as OutboxStep.Retry).atMs)
        val later = ChatOutboxLogic.step(ChatResult.Failed("scan_pending", 423, retryAfterS = 20), 1, hadUpload = true, nowMs = 0L)
        assertEquals(20_000L, (later as OutboxStep.Retry).atMs)
        assertEquals(OutboxStep.GiveUp("scan_pending"), ChatOutboxLogic.step(ChatResult.Failed("scan_pending", 423), ChatOutboxLogic.MAX_ATTEMPTS, true, 0L))
        assertEquals(OutboxStep.GiveUp("bridge_media_type"), ChatOutboxLogic.step(ChatResult.Failed("bridge_media_type", 409, "voice"), 1, true, 0L))
    }

    @Test
    fun `retryAfterS of an error answer`() {
        assertEquals(42, ChatJson.errorRetryAfter(json("""{"ok":false,"code":"rate_limited","retryAfterS":41.2}""")))
        assertNull(ChatJson.errorRetryAfter(json("""{"ok":false,"code":"rate_limited","retryAfterS":"soon"}""")))
        assertEquals(86_400, ChatJson.errorRetryAfter(json("""{"retryAfterS":1e9}""")))
    }
}
