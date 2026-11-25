package com.gatecontrol.android.media.server

import com.gatecontrol.android.media.MediaHub
import com.gatecontrol.android.media.MediaResult
import com.gatecontrol.android.media.MediaRoute
import com.gatecontrol.android.media.MediaSource
import com.gatecontrol.android.media.MediaSourceKind
import com.gatecontrol.android.media.MediaSourceStore
import com.gatecontrol.android.media.jellyfin.JellyfinAuth
import com.gatecontrol.android.media.jellyfin.JellyfinQuickConnect
import com.gatecontrol.android.media.jellyfin.JellyfinSignIn
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Ablauf A: Quick Connect started here, the code confirmed by the plugin, the device's own access, no code shown. */
class MediaAutoPairTest {

    private val source = MediaSource(
        "jf-1", MediaSourceKind.JELLYFIN, "DS918+", "http://192.168.1.20:8096", managed = true, remoteId = "jf-1",
        deviceId = "gc-7Hq2mXk4Rb9tLp0sVw3N", deviceName = "GateControl · Pixel von Mila", linkedUserId = "a71e",
    )

    private class Api(vararg answers: MediaServerResult<Unit>) : MediaServerApi {
        private val queue = ArrayDeque(answers.toList())
        val codes = ArrayList<String>()
        val paired = ArrayList<String>()
        override suspend fun sources(since: String?): MediaServerResult<MediaServerList> = error("unused")
        override suspend fun secret(sourceId: String): MediaServerResult<MediaShareSecret> = error("unused")
        override suspend fun pair(sourceId: String, code: String): MediaServerResult<Unit> {
            codes += code
            return queue.removeFirstOrNull() ?: MediaServerResult.Ok(Unit)
        }
        override suspend fun paired(sourceId: String, jellyfinDeviceId: String): MediaServerResult<Unit> {
            paired += jellyfinDeviceId
            return MediaServerResult.Ok(Unit)
        }
        override suspend fun deleteDevice(sourceId: String?) = MediaServerResult.Ok(Unit)
        override suspend fun checked(sourceId: String, ok: Boolean, problem: String?) = MediaServerResult.Ok(Unit)
        override suspend fun report(sourceId: String, way: String, ok: Boolean, problem: String?) = MediaServerResult.Ok(Unit)
        override suspend fun tab(): Boolean? = true
    }

    private val store = mockk<MediaSourceStore>(relaxed = true)
    private val signIn = mockk<JellyfinSignIn>()
    private val hub = mockk<MediaHub>(relaxed = true) {
        every { store } returns this@MediaAutoPairTest.store
        every { signIn(any()) } returns signIn
        every { route(any()) } returns MediaRoute.VPN
    }

    @Test
    fun `signed in without a code on the screen - Initiate, the plugin confirms, the own access`() = runTest {
        val q = JellyfinQuickConnect("482913", "sec")
        coEvery { signIn.quickConnect() } returns MediaResult.Ok(q)
        coEvery { signIn.confirmed(q) } returnsMany listOf(MediaResult.Ok(false), MediaResult.Ok(true))
        coEvery { signIn.signIn(q) } returns MediaResult.Ok(JellyfinAuth("tok", "a71e", "mila", "4f1ca9"))
        val api = Api()
        assertEquals(MediaPairEnd.Paired, MediaAutoPair(hub, api, pollMs = 1).pair(source))
        assertEquals(listOf("482913"), api.codes)
        verify { store.setAccess("jf-1", "tok", "a71e", "mila") }
        assertEquals(listOf("gc-7Hq2mXk4Rb9tLp0sVw3N"), api.paired)
    }

    @Test
    fun `a code that ran out - a new one once, then the plugin's code`() = runTest {
        val q1 = JellyfinQuickConnect("111111", "s1")
        val q2 = JellyfinQuickConnect("222222", "s2")
        val q3 = JellyfinQuickConnect("333333", "s3")
        coEvery { signIn.quickConnect() } returnsMany listOf(MediaResult.Ok(q1), MediaResult.Ok(q2), MediaResult.Ok(q3))
        val invalid = MediaServerResult.Refused(MediaServerError(422, "code_invalid"))
        val api = Api(invalid, invalid)
        assertEquals(MediaPairEnd.Problem("code_invalid"), MediaAutoPair(hub, api, pollMs = 1).pair(source))
        assertEquals(listOf("111111", "222222"), api.codes)
    }

    @Test
    fun `Quick Connect off, no mapping, offline, away without VPN`() = runTest {
        coEvery { signIn.quickConnect() } returns MediaResult.Unauthorized
        assertEquals(MediaPairEnd.Problem("quick_connect_off"), MediaAutoPair(hub, Api(), pollMs = 1).pair(source))
        coEvery { signIn.quickConnect() } returns MediaResult.Ok(JellyfinQuickConnect("1", "s"))
        assertEquals(MediaPairEnd.Problem("no_mapping"), MediaAutoPair(hub, Api(MediaServerResult.Refused(MediaServerError(409, "no_mapping"))), pollMs = 1).pair(source))
        assertEquals(MediaPairEnd.Problem("no_mapping"), MediaAutoPair(hub, Api(), pollMs = 1).pair(source.copy(linkedUserId = null)))
        coEvery { signIn.quickConnect() } returns MediaResult.Offline
        assertEquals(MediaPairEnd.Problem(MediaPairEnd.OFFLINE), MediaAutoPair(hub, Api(), pollMs = 1).pair(source))
        every { hub.route(any()) } returns MediaRoute.BLOCKED
        assertEquals(MediaPairEnd.Problem(MediaPairEnd.BLOCKED), MediaAutoPair(hub, Api(), pollMs = 1).pair(source))
        coVerify(exactly = 0) { store.setAccess(any(), any(), any(), any()) }
    }

    @Test
    fun `not confirmed within the minute`() = runTest {
        val q = JellyfinQuickConnect("482913", "sec")
        coEvery { signIn.quickConnect() } returns MediaResult.Ok(q)
        coEvery { signIn.confirmed(q) } returns MediaResult.Ok(false)
        assertEquals(MediaPairEnd.Problem(MediaPairEnd.FAILED), MediaAutoPair(hub, Api(), pollMs = 1, waitMs = 20).pair(source))
    }
}
