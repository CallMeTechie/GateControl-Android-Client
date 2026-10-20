package com.gatecontrol.android.familie

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
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import retrofit2.Response
import java.io.IOException

class FamilieApiTest {

    private val api = mockk<ApiClient>()
    private val setup = mockk<SetupRepository> {
        every { getServerUrl() } returns "https://gc.example.com"
        every { isConfigured() } returns true
    }
    private val provider = mockk<ApiClientProvider> { every { getClient(any()) } returns api }
    private val familie = FamilieApi(setup, provider)

    private fun ok(json: String): Response<JsonObject> = Response.success(JsonParser.parseString(json).asJsonObject)

    private fun error(code: Int, json: String): Response<JsonObject> =
        Response.error(code, json.toResponseBody("application/json".toMediaType()))

    @Test
    fun `calls go through the client plugin bridge of the familie plugin`() = runBlocking {
        val path = slot<String>()
        val query = slot<Map<String, String>>()
        coEvery { api.pluginGet("gatecontrol-familie", capture(path), capture(query)) } returns
            ok("""{"ok":true,"lists":[],"items":[],"serverTime":"2026-10-14T12:00:00Z"}""")
        val r = familie.shopping(since = "2026-10-14T11:00:00Z")
        assertInstanceOf(FamilieResult.Ok::class.java, r)
        assertEquals("shopping", path.captured)
        assertEquals(mapOf("since" to "2026-10-14T11:00:00Z"), query.captured)
    }

    @Test
    fun `alltag events come from the alltag plugin`() = runBlocking {
        coEvery { api.pluginGet("gatecontrol-alltag", "app/events", any()) } returns ok("""{"ok":true,"events":[],"waste":[]}""")
        assertInstanceOf(FamilieResult.Ok::class.java, familie.alltagEvents("2026-10-14", "2026-12-13"))
        coVerify { api.pluginGet("gatecontrol-alltag", "app/events", mapOf("from" to "2026-10-14", "to" to "2026-12-13")) }
    }

    @Test
    fun `timetable and changes come from the schule plugin`() = runBlocking {
        coEvery { api.pluginGet("gatecontrol-schule", "app/timetable", any()) } returns
            ok("""{"ok":true,"updatedAt":null,"students":[{"id":3,"name":"Lena","days":[]}]}""")
        val r = familie.schoolTimetable("2026-10-19", "2026-11-01")
        assertEquals(listOf(3), (r as FamilieResult.Ok).value.timetable.students.map { it.id })
        coVerify { api.pluginGet("gatecontrol-schule", "app/timetable", mapOf("from" to "2026-10-19", "to" to "2026-11-01")) }

        coEvery { api.pluginGet("gatecontrol-schule", "app/changes", any()) } returns ok("""{"ok":true,"changes":[]}""")
        assertEquals(FamilieResult.Ok(emptyList<Any>()), familie.schoolChanges(99))
        coVerify { api.pluginGet("gatecontrol-schule", "app/changes", mapOf("days" to "30")) }

        // Not from the app / plugin missing / bad range.
        coEvery { api.pluginGet("gatecontrol-schule", "app/timetable", any()) } returns error(403, """{"ok":false,"code":"FORBIDDEN","error":"…"}""")
        assertEquals(FamilieResult.Failed("FORBIDDEN", 403), familie.schoolTimetable("2026-10-19", "2026-11-01"))
        coEvery { api.pluginGet("gatecontrol-schule", "app/timetable", any()) } returns error(404, """{"ok":false,"code":"not_found"}""")
        assertEquals(FamilieResult.Failed("not_found", 404), familie.schoolTimetable("2026-10-19", "2026-11-01"))
        coEvery { api.pluginGet("gatecontrol-schule", "app/timetable", any()) } returns ok("""{"ok":true}""")
        assertEquals(FamilieResult.Failed(FamilieApi.CODE_INVALID, 200), familie.schoolTimetable("2026-10-19", "2026-11-01"))
    }

    @Test
    fun `messages and absences come from the schule plugin, 404 from an older one`() = runBlocking {
        coEvery { api.pluginGet("gatecontrol-schule", "app/messages", any()) } returns
            ok("""{"ok":true,"messages":[{"id":"1","studentIds":[3],"subject":"Hallo"}]}""")
        val m = familie.schoolMessages()
        assertEquals(listOf("1"), (m as FamilieResult.Ok).value.items.map { it.id })
        coVerify { api.pluginGet("gatecontrol-schule", "app/messages", mapOf("days" to "30")) }

        coEvery { api.pluginGet("gatecontrol-schule", "app/absences", any()) } returns ok("""{"ok":true,"absences":[]}""")
        assertEquals(emptyList<Any>(), (familie.schoolAbsences() as FamilieResult.Ok).value.items)
        coVerify { api.pluginGet("gatecontrol-schule", "app/absences", mapOf("days" to "60")) }

        coEvery { api.pluginGet("gatecontrol-schule", "app/messages", any()) } returns error(404, """{"ok":false,"code":"not_found"}""")
        assertEquals(FamilieResult.Failed("not_found", 404), familie.schoolMessages())
        coEvery { api.pluginGet("gatecontrol-schule", "app/absences", any()) } returns ok("""{"ok":true}""")
        assertEquals(FamilieResult.Failed(FamilieApi.CODE_INVALID, 200), familie.schoolAbsences())
    }

    @Test
    fun `error answers carry the contract code`() = runBlocking {
        coEvery { api.pluginPost(any(), "locate/5", any()) } returns error(429, """{"ok":false,"code":"too_soon","error":"Bitte warten","retryAfter":42,"requestId":17}""")
        assertEquals(FamilieResult.Failed("too_soon", 429, retryAfter = 42, requestId = 17), familie.requestLocation(5))

        coEvery { api.pluginGet(any(), "me", any()) } returns error(403, """{"ok":false,"code":"not_member"}""")
        assertEquals(FamilieResult.Failed("not_member", 403), familie.me())

        coEvery { api.pluginGet(any(), "tasks", any()) } returns error(404, "<html>")
        assertEquals(FamilieResult.Failed("http_404", 404), familie.tasks())

        // 200 with ok:false is an error too.
        coEvery { api.pluginPost(any(), "tasks/4/done", any()) } returns ok("""{"ok":false,"code":"module_off"}""")
        assertEquals(FamilieResult.Failed("module_off", 200), familie.taskDone(4))
    }

    @Test
    fun `network trouble is offline, a create without the object still succeeds`() = runBlocking {
        coEvery { api.pluginGet(any(), "places", any()) } throws IOException("reset")
        assertEquals(FamilieResult.Offline, familie.places())

        coEvery { api.pluginPost(any(), "locate/5", any()) } returns ok("""{"ok":true}""")
        assertEquals(FamilieResult.Ok(Created<LocateRequest>(null)), familie.requestLocation(5))

        coEvery { api.pluginPost(any(), "sos", any()) } returns ok("""{"ok":true,"sosId":8}""")
        assertEquals(FamilieResult.Ok(Created(8)), familie.sos(null, null, null, null))
    }

    @Test
    fun `done and snooze from a reminder name the occurrence`() = runBlocking {
        val body = slot<JsonObject>()
        coEvery { api.pluginPost(any(), "tasks/4/snooze", capture(body)) } returns ok("""{"ok":true,"snoozedUntil":"2026-10-14T18:00:00Z","occurrence":"2026-10-14"}""")
        assertEquals(FamilieResult.Ok(Unit), familie.taskSnooze(4, 60, "2026-10-14"))
        assertEquals("""{"minutes":60,"occurrence":"2026-10-14"}""", body.captured.toString())
        coEvery { api.pluginPost(any(), "tasks/4/done", capture(body)) } returns ok("""{"ok":true,"alreadyDone":false,"starsBooked":1}""")
        familie.taskDone(4)
        assertEquals("{}", body.captured.toString())
    }

    @Test
    fun `answer body as the contract describes it`() = runBlocking {
        val body = slot<JsonObject>()
        coEvery { api.pluginPost(any(), "locate/requests/21/answer", capture(body)) } returns ok("""{"ok":true}""")
        familie.answer(21, 52.52, 13.405, 12.0, "2026-10-14T14:42:09Z", 54)
        assertEquals("""{"lat":52.52,"lon":13.405,"accuracy":12.0,"at":"2026-10-14T14:42:09Z","battery":54}""", body.captured.toString())
    }

    @Test
    fun `nothing is sent without a configured server`() = runBlocking {
        every { setup.isConfigured() } returns false
        assertEquals(FamilieResult.NotConfigured, familie.me())
    }

    // ─── 1.1: rides and emergency card ───────────────────────────────

    @Test
    fun `ride calls use the ride id as one path segment and series as a query`() = runBlocking {
        coEvery { api.pluginGet(any(), "rides", any()) } returns ok("""{"ok":true,"rides":[]}""")
        assertEquals(FamilieResult.Ok(emptyList<com.gatecontrol.android.familie.rides.Ride>()), familie.rides("2026-10-14", "2026-10-21"))
        coVerify { api.pluginGet("gatecontrol-familie", "rides", mapOf("from" to "2026-10-14", "to" to "2026-10-21")) }

        coEvery { api.pluginPost(any(), any(), any()) } returns ok("""{"ok":true}""")
        familie.takeRide("3:2026-10-14")
        familie.releaseRide("12")
        familie.declineRide("12")
        familie.askRide("12")
        coVerify { api.pluginPost("gatecontrol-familie", "rides/3:2026-10-14/take", any()) }
        coVerify { api.pluginPost("gatecontrol-familie", "rides/12/release", any()) }
        coVerify { api.pluginPost("gatecontrol-familie", "rides/12/decline", any()) }
        coVerify { api.pluginPost("gatecontrol-familie", "rides/12/ask", any()) }

        val driver = slot<JsonObject>()
        coEvery { api.pluginPost(any(), "rides/12/driver", capture(driver)) } returns ok("""{"ok":true}""")
        familie.setRideDriver("12", com.gatecontrol.android.familie.rides.DriverChoice.None)
        assertEquals("""{"clear":true}""", driver.captured.toString())

        coEvery { api.pluginPatch(any(), any(), any(), any()) } returns ok("""{"ok":true}""")
        coEvery { api.pluginDelete(any(), any(), any()) } returns ok("""{"ok":true}""")
        val draft = com.gatecontrol.android.familie.rides.RideDraft(date = "2026-10-14", time = "16:00", title = "Tim abholen", childIds = listOf(5))
        familie.updateRide("3:2026-10-14", draft, series = true)
        coVerify { api.pluginPatch("gatecontrol-familie", "rides/3:2026-10-14", any(), mapOf("series" to "1")) }
        familie.deleteRide("12")
        coVerify { api.pluginDelete("gatecontrol-familie", "rides/12", emptyMap()) }

        coEvery { api.pluginPost(any(), "rides/12/take", any()) } returns
            error(409, """{"ok":false,"code":"already_taken","error":"x","driver":{"userId":4,"name":"Marc"}}""")
        assertEquals(FamilieResult.Failed("already_taken", 409, detail = "Marc"), familie.takeRide("12"))

        val reopen = slot<JsonObject>()
        coEvery { api.pluginPatch(any(), "rides/3:2026-10-14", capture(reopen), any()) } returns ok("""{"ok":true}""")
        familie.reopenRide("3:2026-10-14")
        assertEquals("""{"status":"open"}""", reopen.captured.toString())

        // Anything that is not a ride id never reaches the server.
        assertEquals(FamilieResult.Failed(FamilieApi.CODE_INVALID, 0), familie.takeRide("../me"))
        assertEquals(FamilieResult.Failed(FamilieApi.CODE_INVALID, 0), familie.deleteRide("12/../x"))
    }

    @Test
    fun `emergency card is read raw for the offline copy and written with put`() = runBlocking {
        coEvery { api.pluginGet(any(), "emergency", any()) } returns ok("""{"ok":true,"members":[],"contacts":[],"updatedAt":"2026-10-14T12:00:00Z"}""")
        val fetch = (familie.emergency() as FamilieResult.Ok).value
        assertEquals("2026-10-14T12:00:00Z", fetch.raw.get("updatedAt").asString)

        val body = slot<JsonObject>()
        coEvery { api.pluginPut(any(), "emergency/members/5", capture(body)) } returns ok("""{"ok":true}""")
        val draft = com.gatecontrol.android.familie.emergency.EmergencyMemberDraft(
            bloodType = "A+", weightKg = 32.0, allergies = "Erdnüsse", medications = "", conditions = "", notes = "",
            insurance = null, contacts = emptyList(),
        )
        assertEquals(FamilieResult.Ok(Unit), familie.putEmergencyMember(5, draft))
        assertEquals("A+", body.captured.get("bloodType").asString)

        coEvery { api.pluginPut(any(), "emergency/members/6", any()) } returns
            error(400, """{"ok":false,"code":"invalid","field":"contacts.0.phone"}""")
        assertEquals(FamilieResult.Failed("invalid", 400, field = "contacts.0.phone"), familie.putEmergencyMember(6, draft))

        coEvery { api.pluginPut(any(), "emergency/contacts", any()) } returns error(403, """{"ok":false,"code":"parents_only"}""")
        assertEquals(FamilieResult.Failed("parents_only", 403), familie.putEmergencyContacts(emptyList()))
    }
}
