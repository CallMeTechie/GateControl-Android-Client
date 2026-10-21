package com.gatecontrol.android.push.routes

import com.gatecontrol.android.network.ApiClient
import com.gatecontrol.android.notify.PushEndpoint
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import retrofit2.Response
import java.io.IOException

/** `GET/PUT /api/v1/client/push/routes` (core 1.191): answer, body, refusals, older servers. */
class NotifyRoutesJsonTest {

    private fun json(s: String): JsonObject = JsonParser.parseString(s).asJsonObject

    private val answer = """
        {"ok":true,"sink":true,"canFamily":true,"groups":[
          {"source":"core","label":"GateControl","topics":[
            {"id":"security","label":"Sicherheit","dest":"own","locked":false,"familySetBy":null},
            {"id":"devices","label":"Geräte & Dienste","dest":"inbox","locked":false,"familySetBy":null}]},
          {"source":"plugin:gatecontrol-schule","label":"Schule","topics":[
            {"id":"plugin:gatecontrol-schule:messages","label":"Nachrichten der Schule","dest":"family","locked":false,"familySetBy":"Marc"},
            {"id":"plugin:gatecontrol-schule:changes","label":"Stundenplan-Änderungen","dest":"own","locked":false,"familySetBy":null},
            {"id":"plugin:gatecontrol-schule:absences","label":"Fehlzeiten","dest":"weird","locked":false}]},
          {"source":"plugin:empty","label":"Leer","topics":[]},
          {"source":"https://evil","label":"X","topics":[{"id":"x","label":"X","dest":"inbox"}]}
        ]}
    """.trimIndent()

    @Test
    fun `the answer is read per source and topic`() {
        val r = NotifyRoutesJson.parse(json(answer))!!
        assertTrue(r.sink)
        assertTrue(r.canFamily)
        // Groups without topics or with a bad source are dropped.
        assertEquals(listOf("core", "plugin:gatecontrol-schule"), r.groups.map { it.source })
        assertEquals("Schule", r.groups[1].label)
        val msgs = r.topic("plugin:gatecontrol-schule:messages")!!
        assertEquals(NotifyDest.FAMILY, msgs.dest)
        assertEquals("Marc", msgs.familySetBy)
        assertEquals("Nachrichten der Schule", msgs.label)
        assertEquals(NotifyDest.OWN, r.topic("plugin:gatecontrol-schule:changes")!!.dest)
        // An unknown destination is GateControl.
        assertEquals(NotifyDest.INBOX, r.topic("plugin:gatecontrol-schule:absences")!!.dest)
        // security always stays in GateControl, whatever the server says.
        val sec = r.topic("security")!!
        assertTrue(sec.locked)
        assertEquals(NotifyDest.INBOX, sec.dest)
        assertEquals("Schule", r.groupOf("plugin:gatecontrol-schule:changes")?.label)
    }

    @Test
    fun `broken answers are null, missing flags false`() {
        assertNull(NotifyRoutesJson.parse(json("""{"ok":true}""")))
        assertNull(NotifyRoutesJson.parse("not json"))
        assertNull(NotifyRoutesJson.parse(null as String?))
        val r = NotifyRoutesJson.parse(json("""{"groups":[]}"""))!!
        assertFalse(r.sink)
        assertFalse(r.canFamily)
        assertTrue(r.groups.isEmpty())
        // Duplicate topic ids: the first wins.
        val dup = NotifyRoutesJson.parse(
            json("""{"sink":true,"groups":[{"source":"core","label":"GC","topics":[{"id":"devices","label":"A","dest":"own"},{"id":"devices","label":"B","dest":"inbox"}]}]}"""),
        )!!
        assertEquals(listOf("A"), dup.groups.single().topics.map { it.label })
    }

    @Test
    fun `choices follow sink, rights and locks`() {
        val r = NotifyRoutesJson.parse(json(answer))!!
        val devices = r.topic("devices")!!
        assertEquals(listOf(NotifyDest.INBOX, NotifyDest.FAMILY, NotifyDest.OWN), r.options(devices))
        assertTrue(r.editable(devices))
        // Not an adult: no family chat to choose …
        val child = r.copy(canFamily = false)
        assertEquals(listOf(NotifyDest.INBOX, NotifyDest.OWN), child.options(devices))
        // … and one an adult set stays (shown, not changeable).
        val msgs = child.topic("plugin:gatecontrol-schule:messages")!!
        assertEquals(listOf(NotifyDest.INBOX, NotifyDest.FAMILY, NotifyDest.OWN), child.options(msgs))
        assertFalse(child.editable(msgs))
        // Locked: GateControl only.
        assertEquals(listOf(NotifyDest.INBOX), r.options(r.topic("security")!!))
        assertFalse(r.editable(r.topic("security")!!))
        // Without the messenger everything stays in GateControl.
        val noSink = r.copy(sink = false)
        val changes = noSink.topic("plugin:gatecontrol-schule:changes")!!
        assertEquals(NotifyDest.INBOX, noSink.effective(changes))
        assertEquals(listOf(NotifyDest.INBOX), noSink.options(changes))
        assertFalse(noSink.editable(changes))
    }

    @Test
    fun `a change is shown before the answer`() {
        val r = NotifyRoutesJson.parse(json(answer))!!
        val next = r.with("plugin:gatecontrol-schule:messages", NotifyDest.OWN)
        val t = next.topic("plugin:gatecontrol-schule:messages")!!
        assertEquals(NotifyDest.OWN, t.dest)
        assertNull(t.familySetBy)
        assertEquals(r.topic("devices"), next.topic("devices"))
    }

    @Test
    fun `body and refusals`() {
        assertEquals(json("""{"topic":"devices","dest":"family"}"""), NotifyRoutesJson.body("devices", NotifyDest.FAMILY))
        assertEquals("family_not_allowed", NotifyRoutesRepository.refusal(403, """{"ok":false,"error":"family_not_allowed"}"""))
        assertEquals("forbidden", NotifyRoutesRepository.refusal(403, null))
        assertEquals("locked", NotifyRoutesRepository.refusal(409, """{"error":"locked"}"""))
        assertEquals("no_sink", NotifyRoutesRepository.refusal(409, """{"code":"no_sink"}"""))
        assertEquals("locked", NotifyRoutesRepository.refusal(409, "<html>"))
        assertEquals("unsupported", NotifyRoutesRepository.refusal(404, null))
        assertEquals("failed", NotifyRoutesRepository.refusal(500, """{"error":"Some <b>HTML</b>"}"""))
    }

    @Test
    fun `the chat of a change to own is read, missing or broken is null`() {
        val created = NotifyRoutesJson.chat(
            json("""{"ok":true,"chat":{"source":"plugin:gatecontrol-schule","label":"Schule","conversationId":"42","created":true}}"""),
        )
        assertEquals(NotifyRouteChat("plugin:gatecontrol-schule", "Schule", 42L, created = true), created)
        val existing = NotifyRoutesJson.chat(json("""{"chat":{"source":"core","label":"GateControl","conversationId":7,"created":false}}"""))!!
        assertFalse(existing.created)
        assertEquals(7L, existing.conversationId)
        // Older servers (1.191), inbox / family, a failure: no chat.
        assertNull(NotifyRoutesJson.chat(json("""{"ok":true}""")))
        assertNull(NotifyRoutesJson.chat(json("""{"ok":true,"chat":null}""")))
        assertNull(NotifyRoutesJson.chat(json("""{"chat":"Schule"}""")))
        assertNull(NotifyRoutesJson.chat(null))
        // No label: the source's name; neither: nothing to name.
        assertEquals("gatecontrol-schule", NotifyRoutesJson.chat(json("""{"chat":{"source":"plugin:gatecontrol-schule","conversationId":"3"}}"""))!!.label)
        assertNull(NotifyRoutesJson.chat(json("""{"chat":{"source":"https://evil","conversationId":"3"}}""")))
        // Untrusted: a bad id is dropped, control characters go, a missing flag is false.
        val odd = NotifyRoutesJson.chat(json("""{"chat":{"source":"x y","label":"Sch\u0007ule","conversationId":"1; drop","created":"yes"}}"""))!!
        assertNull(odd.source)
        assertEquals("Sch ule", odd.label)
        assertNull(odd.conversationId)
        assertFalse(odd.created)
        assertNull(NotifyRoutesJson.chat(json("""{"chat":{"label":"S","conversationId":"-4"}}"""))!!.conversationId)
        assertNull(NotifyRoutesJson.chat(json("""{"chat":{"label":"S","conversationId":"0"}}"""))!!.conversationId)
    }

    // ─── repository ─────────────────────────────────────────────────

    private val api: ApiClient = mockk()
    private val endpoint: PushEndpoint = mockk {
        every { serverUrl() } returns "https://gc.example.com"
        every { api(any()) } returns this@NotifyRoutesJsonTest.api
    }

    private fun error(code: Int, body: String) = Response.error<JsonObject>(code, body.toResponseBody("application/json".toMediaType()))

    @Test
    fun `an older server hides the page`() = runBlocking {
        coEvery { api.getPushRoutes() } returns error(404, """{"error":"not_found"}""")
        val repo = NotifyRoutesRepository(endpoint)
        assertNull(repo.supported.value)
        assertEquals(RoutesLoad.Unsupported, repo.load())
        assertEquals(false, repo.supported.value)
    }

    @Test
    fun `a newer server shows it, a failure says nothing yet`() = runBlocking {
        coEvery { api.getPushRoutes() } throws IOException("down")
        val repo = NotifyRoutesRepository(endpoint)
        repo.probe()
        assertNull(repo.supported.value)
        coEvery { api.getPushRoutes() } returns Response.success(json(answer))
        repo.probe()
        assertEquals(true, repo.supported.value)
        assertTrue(repo.load() is RoutesLoad.Ok)
    }

    @Test
    fun `a change is sent and its refusal named`() = runBlocking {
        val body = slot<JsonObject>()
        coEvery { api.putPushRoutes(capture(body)) } returns Response.success(json(answer))
        val repo = NotifyRoutesRepository(endpoint)
        val ok = repo.set("plugin:gatecontrol-schule:changes", NotifyDest.OWN)
        assertEquals(json("""{"topic":"plugin:gatecontrol-schule:changes","dest":"own"}"""), body.captured)
        assertTrue(ok is RoutesSave.Ok && ok.routes != null)
        // `{ ok:true }` only: saved, the screen reloads.
        coEvery { api.putPushRoutes(any()) } returns Response.success(json("""{"ok":true}"""))
        assertEquals(RoutesSave.Ok(null), repo.set("devices", NotifyDest.OWN))
        // 1.192: the own chat it made sure of — only read for a change to own.
        val withChat = """{"ok":true,"chat":{"source":"plugin:gatecontrol-schule","label":"Schule","conversationId":"42","created":true}}"""
        coEvery { api.putPushRoutes(any()) } returns Response.success(json(withChat))
        assertEquals(
            RoutesSave.Ok(null, NotifyRouteChat("plugin:gatecontrol-schule", "Schule", 42L, created = true)),
            repo.set("plugin:gatecontrol-schule:changes", NotifyDest.OWN),
        )
        assertEquals(RoutesSave.Ok(null), repo.set("plugin:gatecontrol-schule:changes", NotifyDest.INBOX))
        coEvery { api.putPushRoutes(any()) } returns error(403, """{"ok":false,"error":"family_not_allowed"}""")
        assertEquals(RoutesSave.Refused("family_not_allowed"), repo.set("devices", NotifyDest.FAMILY))
        coEvery { api.putPushRoutes(any()) } returns error(409, """{"ok":false,"error":"no_sink"}""")
        assertEquals(RoutesSave.Refused("no_sink"), repo.set("devices", NotifyDest.OWN))
        coEvery { api.putPushRoutes(any()) } throws IOException("down")
        assertEquals(RoutesSave.Refused("network"), repo.set("devices", NotifyDest.OWN))
    }
}
