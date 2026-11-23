package com.gatecontrol.android.drive

import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.network.ApiClient
import com.gatecontrol.android.network.ApiClientProvider
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import retrofit2.Response

/**
 * Drive 1.31 (`framePhotos`): single photos for the photo frame of the GateControl TV — the answers of
 * `GET|POST|DELETE frame/photos` and the calls themselves (ids in parts, the sums, `403 forbidden`).
 */
class DriveFramePhotosTest {

    private fun json(s: String): JsonObject = JsonParser.parseString(s).asJsonObject

    // ─── the answers ────────────────────────────────────────────────

    @Test
    fun `the feature comes with me`() {
        val me = DriveJson.me(json("""{"me":{"id":7},"features":["photos","framePhotos"]}"""))!!
        assertTrue(me.has(DMe.FEATURE_FRAME_PHOTOS))
    }

    @Test
    fun `GET frame photos gives the shared ids and the count`() {
        val r = DriveJson.framePhotos(json("""{"ok":true,"album":{"id":"a"},"ids":[12,"11",-3,"x",10],"count":40}"""))!!
        assertEquals(listOf(12L, 11L, 10L), r.ids)
        assertEquals(40, r.count)
        assertEquals(0, r.added)
        assertTrue(r.skipped.isEmpty())
        // without the album (nothing shared yet) and without a count: the ids counted
        val none = DriveJson.framePhotos(json("""{"ok":true,"album":null,"ids":[]}"""))!!
        assertEquals(emptyList<Long>(), none.ids)
        assertEquals(0, none.count)
    }

    @Test
    fun `POST frame photos gives what was added and skipped with the reason`() {
        val r = DriveJson.framePhotos(
            json(
                """{"ok":true,"album":{"id":"a"},"added":2,"ids":[1,2],"count":2,
                "skipped":[{"id":3,"reason":"not_image"},{"id":4,"reason":"vault"},{"id":5},{"reason":"child"},"x"]}""",
            ),
        )!!
        assertEquals(2, r.added)
        assertEquals(listOf(DFrameSkip(3, "not_image"), DFrameSkip(4, "vault"), DFrameSkip(5, "failed")), r.skipped)
    }

    @Test
    fun `DELETE frame photos gives what was removed, an answer without ids is none`() {
        assertEquals(3, DriveJson.framePhotos(json("""{"ok":true,"album":null,"removed":3,"ids":[],"count":0}"""))!!.removed)
        assertNull(DriveJson.framePhotos(json("""{"ok":true,"count":3}""")))
    }

    // ─── the calls ──────────────────────────────────────────────────

    private val client = mockk<ApiClient>()

    private fun api(): DriveApi {
        val setup = mockk<SetupRepository> {
            every { getServerUrl() } returns "https://gc.example.test"
            every { isConfigured() } returns true
        }
        val provider = mockk<ApiClientProvider> { every { getClient(any()) } returns client }
        val settings = mockk<SettingsRepository> { every { getLocale() } returns flowOf("de") }
        return DriveApi(setup, provider, settings, mockk(relaxed = true))
    }

    private fun ids(o: JsonObject): List<Long> = o.getAsJsonArray("ids").map { it.asLong }

    @Test
    fun `the state asks with ids in parts of 100 and keeps only the ones asked for`() = runTest {
        val queries = mutableListOf<Map<String, String>>()
        coEvery { client.pluginGet(DRIVE_PLUGIN, "frame/photos", capture(queries)) } answers {
            val asked = thirdArg<Map<String, String>>()["ids"]!!.split(',').map { it.toLong() }
            // the server names the shared ones among them (here: the even ones) — and one it was not asked about
            Response.success(json("""{"ok":true,"ids":${asked.filter { it % 2 == 0L } + 9999L},"count":5}"""))
        }
        val r = api().framePhotoState((1L..150L).toList() + 2L)
        assertEquals((1L..150L).filter { it % 2 == 0L }.toSet(), (r as DResult.Ok).value)
        assertEquals(2, queries.size)
        assertEquals(100, queries[0]["ids"]!!.split(',').size)
        assertTrue(queries[0]["ids"]!!.startsWith("1,2,3,"))
        assertEquals(50, queries[1]["ids"]!!.split(',').size)
        assertEquals("de", queries[0]["lang"])
        // nothing to ask: no call
        assertEquals(emptySet<Long>(), (api().framePhotoState(emptyList()) as DResult.Ok).value)
        assertEquals(2, queries.size)
    }

    @Test
    fun `sharing posts the ids in parts of 500 and sums up the answers`() = runTest {
        val bodies = mutableListOf<JsonObject>()
        coEvery { client.pluginPost(DRIVE_PLUGIN, "frame/photos", capture(bodies), any()) } answers {
            val n = ids(thirdArg()).size
            Response.success(
                json("""{"ok":true,"album":{"id":"a"},"added":${n - 1},"ids":[1],"count":${n - 1},"skipped":[{"id":${n + 10000},"reason":"blocked"}]}"""),
            )
        }
        val r = (api().shareFramePhotos((1L..700L).toList()) as DResult.Ok).value
        assertEquals(listOf(500, 200), bodies.map { ids(it).size })
        assertEquals(499 + 199, r.added)
        assertEquals(listOf(DFrameSkip(10500, "blocked"), DFrameSkip(10200, "blocked")), r.skipped)
    }

    @Test
    fun `removing sends DELETE with the ids in the body`() = runTest {
        val body = slot<JsonObject>()
        coEvery { client.pluginDeleteWithBody(DRIVE_PLUGIN, "frame/photos", capture(body), any()) } returns
            Response.success(json("""{"ok":true,"album":{"id":"a"},"removed":2,"ids":[],"count":0}"""))
        val r = (api().unshareFramePhotos(listOf(5L, 6L, 5L)) as DResult.Ok).value
        assertEquals(listOf(5L, 6L), ids(body.captured))
        assertEquals(2, r.removed)
        coVerify(exactly = 0) { client.pluginPost(any(), any(), any(), any()) }
    }

    @Test
    fun `403 forbidden is a refusal the app names`() = runTest {
        coEvery { client.pluginPost(DRIVE_PLUGIN, "frame/photos", any(), any()) } returns
            Response.error(403, """{"ok":false,"code":"forbidden","field":"frame"}""".toResponseBody("application/json".toMediaType()))
        val r = api().shareFramePhotos(listOf(1L))
        assertEquals("forbidden", (r as DResult.Failed).code)
        assertEquals(403, r.http)
        assertTrue(DriveFrameLogic.forbidden(r))
        // nothing to send: no call at all
        assertTrue(api().shareFramePhotos(emptyList()) is DResult.Failed)
    }
}
