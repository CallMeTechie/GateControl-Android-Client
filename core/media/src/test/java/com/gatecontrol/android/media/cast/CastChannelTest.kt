package com.gatecontrol.android.media.cast

import com.gatecontrol.android.media.server.MediaCastApi
import com.gatecontrol.android.media.server.MediaServerError
import com.gatecontrol.android.media.server.MediaServerResult
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** „Auf TV/PC abspielen“ through the plugin: sending, the deliveries (event and silent push), the filter, once only. */
class CastChannelTest {

    private val session = "a1b2c3d4e5f6a1b2c3d4e5f6"
    private val play = CastMessage.Play(session, 0, 1_764_000_000_000, CastSourceRef("jf-1"), CastItem("item-1", "Kobaltnacht"), 2_530_000)
    private val pause = CastMessage.Command(session, 1, 1_764_000_001_000, CastCmd.PAUSE)
    private val playing = CastMessage.State(session, 1, 1_764_000_002_000, CastPhase.PLAYING, 2_531_000, 6_480_000, device = "Wohnzimmer-TV")

    /** `data` of the plugin: `{ kind, from, fromName, to, session, body }`. */
    private fun data(m: CastMessage, from: String = "17", to: String = "42", kind: String = "media.cast", session: String = m.session): JsonElement =
        JsonObject().apply {
            addProperty("kind", kind)
            addProperty("from", from)
            addProperty("fromName", "Pixel 8")
            addProperty("to", to)
            addProperty("session", session)
            add("body", CastWire.toJson(m))
        }

    private class FakeApi : MediaCastApi {
        val sent = ArrayList<Pair<String, CastMessage>>()
        var answer: MediaServerResult<Unit> = MediaServerResult.Ok(Unit)
        var targets: MediaServerResult<CastTargetList> = MediaServerResult.Ok(CastTargetList(null, emptyList()))
        override suspend fun castTargets() = targets
        override suspend fun castMessage(to: String, message: CastMessage): MediaServerResult<Unit> {
            sent += to to message
            return answer
        }
    }

    // ─── the plugin's JSON ──────────────────────────────────────────

    @Test
    fun `a delivery is read, the device ids as text also when they are numbers`() {
        val e = CastEnvelopes.parse(data(play))!!
        assertEquals("17", e.from)
        assertEquals("42", e.to)
        assertEquals("Pixel 8", e.fromName)
        assertEquals(play, e.message)
        assertEquals("17|$session|0", e.key)
        val numbers = JsonParser.parseString("""{"kind":"media.cast","from":17,"to":42,"session":"$session","body":${CastWire.encode(pause)}}""")
        assertEquals("42", CastEnvelopes.parse(numbers)!!.to)
    }

    @Test
    fun `other kinds, other sessions in the body, broken ids and bodies are no delivery`() {
        assertNull(CastEnvelopes.parse(data(play, kind = "media.revoke")))
        assertNull(CastEnvelopes.parse(data(play, session = "b1b2c3d4e5f6a1b2c3d4e5f6")))
        assertNull(CastEnvelopes.parse(data(play, from = "../x")))
        assertNull(CastEnvelopes.parse(data(play, from = "42", to = "42")))
        assertNull(CastEnvelopes.parse(JsonParser.parseString("""{"kind":"media.cast","from":"1","to":"2","session":"$session","body":{"v":2}}""")))
        assertNull(CastEnvelopes.parse(JsonParser.parseString("[]")))
        assertNull(CastEnvelopes.parse(null))
    }

    @Test
    fun `the request to the plugin is to and body`() {
        val r = CastEnvelopes.request("42", play)
        assertEquals("42", r.get("to").asString)
        assertEquals(play, CastWire.parse(r.get("body")))
    }

    @Test
    fun `the targets - TVs and PCs, online first, then by name`() {
        val o = JsonParser.parseString(
            """{"ok":true,"v":1,"devices":[
            {"id":"7","name":"Laptop","kind":"pc","clientType":"pro","online":false,"lastSeen":null},
            {"id":8,"name":"Wohnzimmer-TV","kind":"tv","clientType":"android-tv","online":true},
            {"id":"9","name":"Büro-PC","kind":"pc","online":true},
            {"id":"10","name":"Handy","kind":"phone","online":true},
            {"id":"bad id","name":"X","kind":"tv","online":true},
            {"id":"11","kind":"tv","online":false}]}""",
        ).asJsonObject
        assertEquals(
            listOf(
                CastTarget("9", "Büro-PC", CastTarget.Kind.PC, true),
                CastTarget("8", "Wohnzimmer-TV", CastTarget.Kind.TV, true),
                CastTarget("11", "11", CastTarget.Kind.TV, false),
                CastTarget("7", "Laptop", CastTarget.Kind.PC, false),
            ),
            CastEnvelopes.targets(o),
        )
        assertNull(CastEnvelopes.targets(JsonObject()))
    }

    // ─── the filter and the duplicates ──────────────────────────────

    @Test
    fun `a silent push is ours and tells the own id, the event only counts for it`() {
        val learned = ArrayList<String>()
        val inbox = CastInbox()
        val start = CastEnvelopes.parse(data(play))!!
        // not known yet: an event with a start is never taken (the start comes as a silent push too)
        assertEquals(CastInbox.Verdict.NOT_MINE, inbox.take(start, push = false))
        assertEquals(CastInbox.Verdict.TAKE, inbox.take(start, push = true, onSelf = { learned += it }))
        assertEquals(listOf("42"), learned)
        assertEquals("42", inbox.self)
        // the same start once more (the event after the push): once only
        assertEquals(CastInbox.Verdict.DUPLICATE, inbox.take(start, push = false))
        // a key for another TV of the same person: not ours
        assertEquals(CastInbox.Verdict.NOT_MINE, inbox.take(CastEnvelopes.parse(data(pause, to = "43"))!!, push = false))
        assertEquals(CastInbox.Verdict.TAKE, inbox.take(CastEnvelopes.parse(data(pause))!!, push = false))
        assertEquals(CastInbox.Verdict.DUPLICATE, inbox.take(CastEnvelopes.parse(data(pause))!!, push = true))
        // the same seq from another sender is another message
        assertEquals(CastInbox.Verdict.TAKE, inbox.take(CastEnvelopes.parse(data(pause, from = "18"))!!, push = false))
    }

    @Test
    fun `without the own id an event counts only for a session this device runs`() {
        val inbox = CastInbox()
        val state = CastEnvelopes.parse(data(playing, from = "42", to = "17"))!!
        assertEquals(CastInbox.Verdict.NOT_MINE, inbox.take(state, push = false, mine = { false }))
        assertEquals(CastInbox.Verdict.TAKE, inbox.take(state, push = false, mine = { it == session }))
        assertNull(inbox.self)
        // a known id wins over the session
        val known = CastInbox("99")
        assertEquals(CastInbox.Verdict.NOT_MINE, known.take(state, push = false, mine = { true }))
        // a push with another `to` (the device got a new token): the new id
        assertEquals(CastInbox.Verdict.TAKE, known.take(state, push = true))
        assertEquals("17", known.self)
    }

    @Test
    fun `the memory of messages is bounded`() {
        val inbox = CastInbox("42", capacity = 3)
        val first = CastEnvelopes.parse(data(pause))!!
        assertEquals(CastInbox.Verdict.TAKE, inbox.take(first, push = false))
        (2L..4L).forEach { seq -> assertEquals(CastInbox.Verdict.TAKE, inbox.take(CastEnvelopes.parse(data(pause.copy(seq = seq)))!!, push = false)) }
        // long gone from the memory: taken again (the receiver's seq rule drops it then)
        assertEquals(CastInbox.Verdict.TAKE, inbox.take(first, push = false))
    }

    // ─── the channel ────────────────────────────────────────────────

    @Test
    fun `receive hands each message for this device once, the learned id is kept`() = runTest(UnconfinedTestDispatcher()) {
        var saved: String? = null
        val channel = MediaCastChannel(FakeApi(), { null }, { saved = it })
        val got = ArrayList<CastEnvelope>()
        val job = launch { channel.incoming.toList(got) }
        assertTrue(channel.receive(data(play), push = true))
        assertFalse(channel.receive(data(play), push = false))
        assertFalse(channel.receive(data(pause, to = "43"), push = false))
        assertTrue(channel.receive(data(pause), push = false))
        assertFalse(channel.receive(JsonParser.parseString("""{"kind":"media.revoke","sourceIds":null}"""), push = true))
        assertEquals(listOf(play, pause), got.map { it.message })
        assertEquals("42", saved)
        assertEquals("42", channel.self)
        channel.clear()
        assertNull(channel.self)
        job.cancel()
    }

    @Test
    fun `the controller's own session counts before it knows its id`() = runTest(UnconfinedTestDispatcher()) {
        val channel = MediaCastChannel(FakeApi(), { null }, {})
        assertFalse(channel.receive(data(playing, from = "42", to = "17"), push = false))
        channel.own(session)
        val next = launch { channel.incoming.first() }
        assertTrue(channel.receive(data(playing.copy(seq = 2), from = "42", to = "17"), push = false))
        next.join()
        channel.release(session)
        assertFalse(channel.receive(data(playing.copy(seq = 3), from = "42", to = "17"), push = false))
    }

    @Test
    fun `send - to and body, too big made smaller, the plugin's refusals`() = runTest {
        val api = FakeApi()
        val channel = MediaCastChannel(api, { "17" }, {})
        assertEquals(CastSendResult.OK, channel.send("42", play))
        assertEquals("42" to play, api.sent.single())
        val tracks = (1..40).map { CastTrack("a:$it", "Tonspur $it mit einem sehr langen Namen, der Platz braucht") }
        val big = playing.copy(audio = tracks, subtitles = tracks.map { it.copy(id = "t:" + it.id.drop(2)) })
        assertEquals(CastSendResult.OK, channel.send("17", big))
        assertTrue(CastWire.bytes(api.sent.last().second) <= CastProtocol.MAX_BYTES)
        api.answer = MediaServerResult.Refused(MediaServerError(429, "rate_limited"))
        assertEquals(CastSendResult.RATE_LIMITED, channel.send("42", pause))
        api.answer = MediaServerResult.Refused(MediaServerError(413, "too_large"))
        assertEquals(CastSendResult.TOO_LARGE, channel.send("42", pause))
        api.answer = MediaServerResult.Refused(MediaServerError(0, MediaServerError.OFFLINE))
        assertEquals(CastSendResult.OFFLINE, channel.send("42", pause))
        api.answer = MediaServerResult.Refused(MediaServerError(403, "forbidden"))
        assertEquals(CastSendResult.REFUSED, channel.send("42", pause))
    }

    // ─── the own id from the plugin (1.4.0) ─────────────────────────

    @Test
    fun `deviceId - text or number, missing or null is not told`() {
        assertEquals("11", CastEnvelopes.self(JsonParser.parseString("""{"ok":true,"deviceId":"11"}""").asJsonObject))
        assertEquals("11", CastEnvelopes.self(JsonParser.parseString("""{"ok":true,"deviceId":11}""").asJsonObject))
        assertNull(CastEnvelopes.self(JsonParser.parseString("""{"ok":true,"deviceId":null}""").asJsonObject))
        assertNull(CastEnvelopes.self(JsonParser.parseString("""{"ok":true}""").asJsonObject))
        assertNull(CastEnvelopes.self(JsonParser.parseString("""{"ok":true,"deviceId":"bad id"}""").asJsonObject))
        val list = CastEnvelopes.targetList(JsonParser.parseString("""{"ok":true,"v":1,"deviceId":"11","devices":[{"id":"8","name":"TV","kind":"tv","online":true}]}""").asJsonObject)!!
        assertEquals("11", list.self)
        assertEquals(listOf(CastTarget("8", "TV", CastTarget.Kind.TV, true)), list.devices)
        assertNull(CastEnvelopes.targetList(JsonParser.parseString("""{"ok":true,"v":1,"devices":[]}""").asJsonObject)!!.self)
    }

    @Test
    fun `the own id told by the plugin counts at once, before any silent push`() = runTest(UnconfinedTestDispatcher()) {
        val saved = ArrayList<String>()
        val api = FakeApi()
        val channel = MediaCastChannel(api, { null }, { saved += it })
        // without the id an event for another session is not taken
        assertFalse(channel.receive(data(pause), push = false))
        api.targets = MediaServerResult.Ok(CastTargetList("42", listOf(CastTarget("17", "Handy", CastTarget.Kind.TV, true))))
        assertEquals(listOf(CastTarget("17", "Handy", CastTarget.Kind.TV, true)), (channel.targets() as MediaServerResult.Ok).value)
        assertEquals("42", channel.self)
        assertEquals(listOf("42"), saved)
        // the same again, or none: nothing new is saved
        channel.learnSelf("42")
        channel.learnSelf(null)
        assertEquals(listOf("42"), saved)
        val next = launch { channel.incoming.first() }
        assertTrue(channel.receive(data(pause.copy(seq = 9)), push = false))
        next.join()
        assertFalse(channel.receive(data(pause.copy(seq = 10), to = "43"), push = false))
        // an older plugin: no id in the list, the first silent push tells it as before
        val old = MediaCastChannel(FakeApi(), { null }, {})
        old.targets()
        assertNull(old.self)
        assertTrue(old.receive(data(play), push = true))
        assertEquals("42", old.self)
    }

    @Test
    fun `the inbox learns the id once`() {
        val learned = ArrayList<String>()
        val inbox = CastInbox()
        assertTrue(inbox.learn("11") { learned += it })
        assertFalse(inbox.learn("11") { learned += it })
        assertFalse(inbox.learn(null) { learned += it })
        assertFalse(inbox.learn("bad id") { learned += it })
        assertTrue(inbox.learn("12") { learned += it })
        assertEquals(listOf("11", "12"), learned)
        assertEquals("12", inbox.self)
    }
}
