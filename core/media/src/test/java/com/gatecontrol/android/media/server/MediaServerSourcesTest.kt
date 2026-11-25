package com.gatecontrol.android.media.server

import com.gatecontrol.android.media.MediaHub
import com.gatecontrol.android.media.MediaResult
import com.gatecontrol.android.media.MediaRoute
import com.gatecontrol.android.media.MediaSecrets
import com.gatecontrol.android.media.MediaSource
import com.gatecontrol.android.media.MediaSourceKind
import com.gatecontrol.android.media.MediaSourceStore
import com.google.gson.JsonParser
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The sources from the server on the device: cache, passwords, sign-in, revocation, plugin off, offline limits. */
class MediaServerSourcesTest {

    private class MapSecrets : MediaSecrets {
        val map = HashMap<String, String>()
        override fun get(key: String): String? = map[key]
        override fun put(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    private class FakeApi : MediaServerApi {
        var list: MediaServerResult<MediaServerList> = MediaServerResult.Refused(MediaServerError(0, MediaServerError.OFFLINE))
        var tab: Boolean? = true
        val since = ArrayList<String?>()
        var secret: MediaServerResult<MediaShareSecret> = MediaServerResult.Ok(MediaShareSecret("medien-lesen", "pw3", 3))
        var secretCalls = 0
        val checked = ArrayList<Pair<String, Boolean>>()
        val reports = ArrayList<String>()
        var deleted = 0

        override suspend fun sources(since: String?): MediaServerResult<MediaServerList> {
            this.since += since
            return list
        }

        override suspend fun secret(sourceId: String): MediaServerResult<MediaShareSecret> {
            secretCalls++
            return secret
        }

        override suspend fun pair(sourceId: String, code: String) = MediaServerResult.Ok(Unit)
        override suspend fun paired(sourceId: String, jellyfinDeviceId: String) = MediaServerResult.Ok(Unit)
        override suspend fun deleteDevice(sourceId: String?): MediaServerResult<Unit> {
            deleted++
            return MediaServerResult.Ok(Unit)
        }

        override suspend fun checked(sourceId: String, ok: Boolean, problem: String?): MediaServerResult<Unit> {
            checked += sourceId to ok
            return MediaServerResult.Ok(Unit)
        }

        override suspend fun report(sourceId: String, way: String, ok: Boolean, problem: String?): MediaServerResult<Unit> {
            reports += "$sourceId:$way:$ok:$problem"
            return MediaServerResult.Ok(Unit)
        }

        override suspend fun tab(): Boolean? = tab
    }

    private val jf = MediaServerSource(
        id = "jf-1", kind = MediaServerSource.Kind.JELLYFIN, name = "DS918+", serverId = "4f1ca9", homeAddress = "http://192.168.1.20:8096",
        userId = "a71e", userName = "mila", deviceId = "gc-7Hq2mXk4Rb9tLp0sVw3N", deviceName = "GateControl · Pixel von Mila",
    )
    private val sh = MediaServerSource(id = "sh-1", kind = MediaServerSource.Kind.SHARE, name = "Filme", smb = "smb://192.168.1.20/filme", shareUser = "medien-lesen", secretRev = 3)

    private fun ok(vararg s: MediaServerSource, rev: String = "r1", media: Boolean = true) =
        MediaServerResult.Ok(MediaServerList(rev = rev, at = null, media = media, maxOfflineDays = 90, offlineHintDays = 30, sources = s.toList()))

    private class World(scope: TestScope, var clock: Long = 1_000_000_000L) {
        val secrets = MapSecrets()
        val store = MediaSourceStore(secrets)
        val api = FakeApi()
        var route = MediaRoute.HOME
        val hub = mockk<MediaHub>(relaxed = true).also { h ->
            every { h.store } returns store
            every { h.route(any()) } answers { route }
            coEvery { h.tryShare(any()) } returns MediaResult.Ok(Unit)
        }
        val pair = mockk<MediaAutoPair>(relaxed = true).also { p ->
            coEvery { p.pair(any()) } answers {
                val s = firstArg<MediaSource>()
                store.setAccess(s.id, "tok", "a71e", "mila")
                MediaPairEnd.Paired
            }
        }
        val sources = MediaServerSources(hub, api, { clock }, pair, scope)
    }

    @Test
    fun `the list is merged and kept, the share's password fetched and SMB checked, Jellyfin signed in`() = runTest {
        val w = World(this)
        w.api.list = ok(jf, sh)
        w.sources.refresh(force = true)
        advanceUntilIdle()
        val s = w.sources.state.value
        assertTrue(s.visible)
        assertTrue(s.known)
        assertEquals("r1", s.rev)
        assertEquals(listOf(null), w.api.since, "the first time without since")
        val list = w.store.sources.value
        assertEquals("tok", list.first { it.kind == MediaSourceKind.JELLYFIN }.token)
        val smb = list.first { it.kind == MediaSourceKind.SMB }
        assertEquals("pw3", smb.password)
        assertEquals(3, smb.secretRev)
        assertEquals(listOf("sh-1" to true), w.api.checked)

        // the cache: a new store over the same secrets has the list and the state
        val again = MediaServerSources(w.hub, w.api, { w.clock }, w.pair, this)
        assertEquals("r1", again.state.value.rev)
        assertTrue(again.state.value.visible)

        // the next time with since; the password is not asked again
        w.api.list = MediaServerResult.Ok(MediaServerList(rev = "r1", at = null, unchanged = true))
        w.clock += 60_000
        w.sources.refresh(force = true)
        advanceUntilIdle()
        assertEquals("r1", w.api.since.last())
        assertEquals(1, w.api.secretCalls)
    }

    @Test
    fun `away without the VPN the sign-in waits, nothing goes out`() = runTest {
        val w = World(this)
        w.route = MediaRoute.BLOCKED
        w.api.list = ok(jf)
        w.sources.refresh(force = true)
        advanceUntilIdle()
        coVerify(exactly = 0) { w.pair.pair(any()) }
        assertEquals(MediaPairEnd.BLOCKED, w.sources.state.value.pairProblems["jf-1"])
    }

    @Test
    fun `revoked - access, password and downloads go, then the list again`() = runTest {
        val w = World(this)
        w.api.list = ok(jf, sh)
        w.sources.refresh(force = true)
        advanceUntilIdle()
        // still allowed only the share
        w.api.list = ok(sh, rev = "r2")
        w.clock += 10_000
        assertTrue(w.sources.onSignal(JsonParser.parseString("""{"kind":"media.revoke","sourceIds":["jf-1"],"reason":"right"}""")))
        advanceUntilIdle()
        assertFalse(w.store.sources.value.any { it.remoteId == "jf-1" })
        assertTrue("jf-1" in w.store.gone.value, "its downloads go")
        assertTrue(w.store.sources.value.any { it.remoteId == "sh-1" })
        assertFalse(w.sources.onSignal(JsonParser.parseString("""{"kind":"media.cast"}""")))

        // all sources (null)
        w.sources.revoke(MediaRevoke(null, "device_removed"))
        assertTrue(w.store.sources.value.none { it.managed })
    }

    @Test
    fun `no access to a share's password - the share goes`() = runTest {
        val w = World(this)
        w.api.secret = MediaServerResult.Refused(MediaServerError(403, "no_access"))
        w.api.list = ok(sh)
        w.sources.refresh(force = true)
        advanceUntilIdle()
        assertTrue(w.store.sources.value.isEmpty())
        assertEquals(setOf("sh-1"), w.store.gone.value)
    }

    @Test
    fun `plugin off - hidden at once, deleted only after 24 hours`() = runTest {
        val w = World(this)
        w.api.list = ok(jf)
        w.sources.refresh(force = true)
        advanceUntilIdle()
        w.api.list = MediaServerResult.Refused(MediaServerError(404, "not_found", "plugin_unavailable"))
        w.clock += 60_000
        w.sources.refresh(force = true)
        assertFalse(w.sources.state.value.visible)
        assertEquals(1, w.store.sources.value.size, "kept for now")
        w.clock += 23 * 3_600_000L
        w.sources.refresh(force = true)
        assertEquals(1, w.store.sources.value.size)
        w.clock += 2 * 3_600_000L
        w.sources.refresh(force = true)
        assertTrue(w.store.sources.value.isEmpty())
        assertTrue("jf-1" in w.store.gone.value)
        // back again: shown
        w.api.list = ok(jf, rev = "r5")
        w.sources.refresh(force = true)
        assertTrue(w.sources.state.value.visible)
    }

    @Test
    fun `without contact - a hint after 30 days, the NAS passwords go after 90`() = runTest {
        val w = World(this)
        w.api.list = ok(sh)
        w.sources.refresh(force = true)
        advanceUntilIdle()
        assertEquals("pw3", w.store.sources.value.first().password)
        val contact = w.sources.state.value.lastContact
        w.api.list = MediaServerResult.Refused(MediaServerError(0, MediaServerError.OFFLINE))
        w.clock = contact + 31 * MediaServerState.DAY_MS
        w.sources.refresh(force = true)
        assertTrue(w.sources.state.value.offlineHint(w.clock))
        assertEquals("pw3", w.store.sources.value.first().password)
        w.clock = contact + 91 * MediaServerState.DAY_MS
        w.sources.refresh(force = true)
        assertNull(w.store.sources.value.first().password)
        assertEquals(1, w.store.sources.value.size, "the list stays, only the password goes")
    }

    @Test
    fun `the device's own sources - taken over, the rest removed after the sheet`() = runTest {
        val w = World(this)
        w.store.replaceAll(
            listOf(
                MediaSource("uuid-1", MediaSourceKind.JELLYFIN, "DS918+", "http://192.168.1.20:8096", token = "old", userId = "u0", serverId = "4F1CA9"),
                MediaSource("uuid-3", MediaSourceKind.WEBDAV, "", "https://192.168.1.30/privat"),
            ),
        )
        w.api.list = ok(jf)
        w.sources.refresh(force = true)
        advanceUntilIdle()
        coVerify { w.pair.signOutOld(match { it.id == "uuid-1" }) }
        val m = w.sources.state.value.migration
        assertEquals(listOf("uuid-1", "uuid-3"), m.map { it.id })
        assertEquals("DS918+", m[0].takenAs)
        assertNull(m[1].takenAs)
        // a second answer keeps what the sheet says
        w.clock += 60_000
        w.api.list = ok(jf, rev = "r2")
        w.sources.refresh(force = true)
        assertEquals(2, w.sources.state.value.migration.size)
        w.sources.acknowledgeMigration()
        assertTrue(w.sources.state.value.migration.isEmpty())
        assertEquals(listOf("uuid-1"), w.store.sources.value.map { it.id })
        assertTrue(w.store.sources.value.single().managed)
    }

    @Test
    fun `the way to a source is told to the plugin only when it changed`() = runTest {
        val w = World(this)
        w.api.list = ok(jf)
        w.sources.refresh(force = true)
        advanceUntilIdle()
        val s = w.store.sources.value.single()
        val listener = io.mockk.slot<(MediaSource, MediaRoute, Boolean) -> Unit>()
        io.mockk.verify { w.hub.reachListener = capture(listener) }
        listener.captured(s, MediaRoute.VPN, false)
        listener.captured(s, MediaRoute.VPN, false)
        listener.captured(s, MediaRoute.VPN, true)
        listener.captured(s, MediaRoute.BLOCKED, false)
        advanceUntilIdle()
        assertEquals(listOf("jf-1:away:false:tunnel_unreachable", "jf-1:away:true:null"), w.api.reports)
    }
}
