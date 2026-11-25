package com.gatecontrol.android.media

import com.gatecontrol.android.media.cast.CastCmd
import com.gatecontrol.android.media.cast.CastMessage
import com.gatecontrol.android.media.cast.CastPhase
import com.gatecontrol.android.media.cast.CastRemotePhase
import com.gatecontrol.android.media.cast.CastTarget
import com.gatecontrol.android.media.cast.CastWire
import com.gatecontrol.android.media.cast.MediaCastChannel
import com.gatecontrol.android.media.server.MediaCastApi
import com.gatecontrol.android.media.server.MediaServerError
import com.gatecontrol.android.media.server.MediaServerResult
import com.gatecontrol.android.media.server.MediaServerSources
import com.gatecontrol.android.media.server.MediaServerState
import com.google.gson.JsonObject
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The phone's remote through the plugin: offered only when allowed and a TV is there, the start, the answers. */
class PhoneCastTest {

    private val tv = CastTarget("42", "Wohnzimmer-TV", CastTarget.Kind.TV, online = true)
    private val managed = MediaSource("j1", MediaSourceKind.JELLYFIN, "DS918+", "http://192.168.1.20:8096", token = "secret-token", managed = true, remoteId = "jf-1")
    private val alone = MediaSource("l1", MediaSourceKind.JELLYFIN, "Alt", "http://192.168.1.30:8096", token = "t")
    private val film = MediaTitle("item-1", "j1", MediaTitle.Kind.MOVIE, "Kobaltnacht", runtimeMs = 6_480_000)

    private class Api : MediaCastApi {
        val sent = ArrayList<Pair<String, CastMessage>>()
        var targets: MediaServerResult<List<CastTarget>> = MediaServerResult.Ok(emptyList())
        var answer: MediaServerResult<Unit> = MediaServerResult.Ok(Unit)
        override suspend fun castTargets() = targets
        override suspend fun castMessage(to: String, message: CastMessage): MediaServerResult<Unit> {
            sent += to to message
            return answer
        }
    }

    private inner class World(scope: TestScope, cast: Boolean = true) {
        val api = Api()
        val channel = MediaCastChannel(api, { null }, {})
        val state = MutableStateFlow(MediaServerState(known = true, tab = true, cast = cast))
        val servers = mockk<MediaServerSources>(relaxed = true).also { every { it.state } returns state }
        val store = mockk<MediaSourceStore>(relaxed = true).also { s ->
            every { s.source(any()) } answers { listOf(managed, alone).firstOrNull { it.id == firstArg() } }
        }
        val hub = mockk<MediaHub>(relaxed = true).also { h ->
            every { h.store } returns store
            every { h.info } returns MediaClientInfo("GateControl", "Pixel 8", "dev", "1")
        }
        val cast = PhoneCast(hub, servers, channel, scope).also { it.clock = { 1_000_000L } }
    }

    private fun stateData(m: CastMessage.State, from: String = "42", to: String = "17") = JsonObject().apply {
        addProperty("kind", "media.cast")
        addProperty("from", from)
        addProperty("to", to)
        addProperty("session", m.session)
        add("body", CastWire.toJson(m))
    }

    @Test
    fun `offered only while the plugin allows it and an own TV or PC is there`() = runTest(UnconfinedTestDispatcher()) {
        val w = World(TestScope(UnconfinedTestDispatcher(testScheduler)))
        assertFalse(w.cast.available.value)
        w.api.targets = MediaServerResult.Ok(listOf(tv))
        assertEquals(listOf(tv) to null, w.cast.targets())
        assertTrue(w.cast.available.value)
        // switched off in the plugin
        w.state.value = w.state.value.copy(cast = false)
        assertFalse(w.cast.available.value)
        assertEquals(emptyList<CastTarget>() to CastListProblem.NOT_ALLOWED, w.cast.targets())
        // only titles of a source of the plugin
        assertTrue(w.cast.canCast(film))
        assertFalse(w.cast.canCast(film.copy(sourceId = "l1")))
    }

    @Test
    fun `the start goes to the plugin with its source id, the TV's answers move the remote`() = runTest(UnconfinedTestDispatcher()) {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        val w = World(scope)
        assertNull(w.cast.start(tv, film, 2_530_000, audio = 1, subtitle = -1))
        val (to, play) = w.api.sent.single()
        assertEquals("42", to)
        play as CastMessage.Play
        assertEquals("jf-1", play.source.id)
        assertEquals(0, play.seq)
        assertEquals("Pixel 8", play.from)
        assertFalse(CastWire.encode(play).contains("secret"))
        val s = w.cast.state.value!!
        assertEquals(CastRemotePhase.CONNECTING, s.phase)

        // the TV answers as an event: this phone does not know its id yet, its own session counts
        w.channel.receive(stateData(CastMessage.State(s.session, 1, 5, CastPhase.PLAYING, 2_531_000, 6_480_000)), push = false)
        assertEquals(CastRemotePhase.PLAYING, w.cast.state.value!!.phase)
        // the same once more (silent push): no change; another session's state: none either
        w.channel.receive(stateData(CastMessage.State(s.session, 1, 5, CastPhase.PAUSED, 0, 0)), push = true)
        assertEquals(CastRemotePhase.PLAYING, w.cast.state.value!!.phase)
        w.channel.receive(stateData(CastMessage.State("zzzzzzzzzzzzzzzzzzzzzzzz", 2, 5, CastPhase.STOPPED)), push = false)
        assertEquals(CastRemotePhase.PLAYING, w.cast.state.value!!.phase)

        // a key: to the TV with the next seq
        w.cast.command(CastCmd.PAUSE)
        val (_, pause) = w.api.sent.last()
        assertEquals(CastCmd.PAUSE, (pause as CastMessage.Command).cmd)
        assertEquals(1, pause.seq)
        assertEquals(CastRemotePhase.PAUSED, w.cast.state.value!!.phase)
        scope.cancel()
    }

    @Test
    fun `too many messages - the start says so and nothing runs`() = runTest(UnconfinedTestDispatcher()) {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        val w = World(scope)
        w.api.answer = MediaServerResult.Refused(MediaServerError(429, "rate_limited"))
        assertEquals(CastStartProblem.RATE_LIMITED, w.cast.start(tv, film, 0, null, null))
        assertNull(w.cast.state.value)
        w.api.answer = MediaServerResult.Refused(MediaServerError(403, "no_access"))
        assertEquals(CastStartProblem.FAILED, w.cast.start(tv, film, 0, null, null))
        // a source of this device alone cannot be cast
        assertEquals(CastStartProblem.FAILED, w.cast.start(tv, film.copy(sourceId = "l1"), 0, null, null))
        scope.cancel()
    }

    private fun TestScope.cancel() = coroutineContext[kotlinx.coroutines.Job]?.cancel()
}
