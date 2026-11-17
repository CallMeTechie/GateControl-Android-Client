package com.gatecontrol.android.chat

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
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import retrofit2.Response
import java.io.File

/**
 * „Verknüpfte Dienste“ (Messenger 1.12.0/1.13.0): the feature word, the answers of `bridges/…` as the plugin sends
 * them (server/bridges.js), what a row says, the error codes, the paths — and that number and code stay out of
 * logs.
 */
class ChatBridgesTest {

    private fun json(s: String): JsonObject = JsonParser.parseString(s).asJsonObject

    private val me = """"ok":true,"user":{"id":7,"name":"Mia"},"prefs":{},"limits":{}"""

    private fun net(
        state: String = ChatBridges.NET_RUNNING,
        allowed: Boolean = true,
        consent: Boolean = true,
        outdated: Boolean = false,
        vararg logins: ChatBridgeLogin,
    ) = ChatBridgeNetwork(ChatBridges.WHATSAPP, state, allowed, if (allowed) null else ChatBridges.LOCK_CHILD, consent, outdated, logins = logins.toList())

    private fun login(state: String, id: String = "wa1", since: String? = "2026-11-02T08:00:00.000Z") = ChatBridgeLogin(id, "+49 1512 0000000", state, since)

    // ─── the feature word and the update hint ───────────────────────

    @Test
    fun `linked_services is a feature this app knows - the update hint stays away`() {
        // GET me of Messenger 1.13.0 with a bridge switched on (server/api.js me())
        val f = ChatJson.profile(
            json(
                """{$me,"features":{"direct":true,"groups":true,"voice":true,"files":true,"location":true,"reactions":true,"edit":true,"search":true,
                "disappearing":true,"bridges":false,"e2e":false,"video_meta":true,"file_thumbs":true,"scan":true,"status":true,"stories":true,
                "story_duration":true,"polls":true,"events":true,"arrival":true,"msg_tools":true,"privacy2":true,"actions":true,
                "action_requests":true,"grant_off":true,"notices":true,"notice_routes":true,"linked_services":true}}""",
            ),
        )!!.features
        assertTrue(f.linkedServices)
        assertEquals(emptySet<String>(), ChatFeatureHint.unknown(f), "every word of Messenger 1.13.0 is known")
        assertFalse(ChatFeatureHint.show(f, null), "no „neuere App-Version“ without a newer app")
    }

    @Test
    fun `without the word there is no entry - and a word of a newer messenger still asks for an update`() {
        val off = ChatJson.profile(json("""{$me,"features":{"direct":true,"bridges":false}}"""))!!.features
        assertFalse(off.linkedServices, "bridges off or not offered: the key is absent")
        assertFalse(ChatJson.profile(json("""{$me,"features":{"linked_services":false}}"""))!!.features.linkedServices)
        // `bridge_chats` (1.14.0), `bridge_media` and `bridge_new_chat` (1.15.0) are known; a later word is not
        assertEquals(emptySet<String>(), ChatFeatureHint.unknown(ChatJson.profile(json("""{$me,"features":{"linked_services":true,"bridge_chats":true,"bridge_media":true,"bridge_new_chat":true}}"""))!!.features))
        val newer = ChatJson.profile(json("""{$me,"features":{"linked_services":true,"bridge_chats":true,"bridge_future":true}}"""))!!.features
        assertEquals(setOf("bridge_future"), ChatFeatureHint.unknown(newer))
        assertTrue(ChatFeatureHint.show(newer, null))
        // dismissed for the old word: the hint is gone now, nothing is left to dismiss
        assertFalse(ChatFeatureHint.show(ChatFeatures(tokens = setOf(ChatBridges.FEATURE)), "linked_services"))
        assertNull(ChatFeatureHint.signature(ChatFeatureHint.unknown(ChatFeatures(tokens = setOf(ChatBridges.FEATURE)))))
    }

    // ─── GET bridges ────────────────────────────────────────────────

    @Test
    fun `GET bridges - the services with the own logins`() {
        val info = ChatBridgesJson.info(
            json(
                """{"ok":true,"bridges":{"available":true,"location":"server","networks":[
                {"id":"whatsapp","state":"running","allowed":true,"lock":null,"consent":"2026-11-01T10:00:00.000Z","outdated":false,
                 "version":"0.2610.0","wantVersion":"0.2610.0",
                 "logins":[{"id":"4915120000000","name":"+49 1512 0000000","state":"connected","since":"2026-11-02T08:00:00.000Z"},
                           {"id":"bad id","name":"x","state":"connected"},
                           {"id":"old","name":"","state":"something_new","since":"nonsense"}]},
                {"id":"icq","state":"running","allowed":true,"logins":[]}]}}""",
            ),
        )!!
        assertTrue(info.available)
        assertTrue(info.onServer)
        assertEquals(listOf("whatsapp"), info.networks.map { it.id }, "a service this app has no page for is left out")
        val wa = info.networks.single()
        assertTrue(wa.consent)
        assertTrue(wa.allowed)
        assertEquals(listOf("4915120000000", "old"), wa.logins.map { it.id }, "a login whose id is no id is dropped")
        assertEquals(ChatBridges.LOGIN_UNKNOWN, wa.logins[1].state, "a state this app does not know")
        assertNull(wa.logins[1].since)
        assertEquals("4915120000000", ChatBridges.mainLogin(wa)!!.id)
        assertEquals(ChatBridgeRow.LINKED, ChatBridges.row(wa))
    }

    @Test
    fun `GET bridges - off, on a gateway, and an answer that is none`() {
        val off = ChatBridgesJson.info(json("""{"ok":true,"bridges":{"available":false,"networks":[]}}"""))!!
        assertFalse(off.available)
        assertTrue(off.networks.isEmpty())
        assertFalse(ChatBridgesJson.info(json("""{"bridges":{"available":true,"location":"gateway","networks":[]}}"""))!!.onServer)
        assertNull(ChatBridgesJson.info(json("""{"ok":true}""")))
        assertNull(ChatBridgesJson.info(null))
    }

    @Test
    fun `a child is locked until the parents allow the service`() {
        val locked = ChatBridgesJson.info(
            json("""{"bridges":{"available":true,"networks":[{"id":"whatsapp","state":"running","allowed":false,"lock":"child","consent":null,"logins":[]}]}}"""),
        )!!.networks.single()
        assertFalse(locked.allowed)
        assertEquals(ChatBridges.LOCK_CHILD, locked.lock)
        assertFalse(locked.consent)
        assertEquals(ChatBridgeRow.LOCKED, ChatBridges.row(locked))
        // a lock without the flag still locks; no lock and no flag does not
        assertFalse(ChatBridgesJson.info(json("""{"bridges":{"available":true,"networks":[{"id":"whatsapp","state":"running","lock":"child"}]}}"""))!!.networks.single().allowed)
        assertTrue(ChatBridgesJson.info(json("""{"bridges":{"available":true,"networks":[{"id":"whatsapp","state":"running"}]}}"""))!!.networks.single().allowed)
        // the permission was taken back while an account is linked: the row shows the link (unlinking is always allowed)
        assertEquals(ChatBridgeRow.LINKED, ChatBridges.row(net(allowed = false, logins = arrayOf(login(ChatBridges.LOGIN_CONNECTED)))))
        assertEquals(ChatBridgeRow.LOGGED_OUT, ChatBridges.row(net(allowed = false, logins = arrayOf(login(ChatBridges.LOGIN_LOGGED_OUT)))))
    }

    // ─── what a row says ────────────────────────────────────────────

    @Test
    fun `the row - the bridge's own state first, then the login`() {
        assertEquals(ChatBridgeRow.NONE, ChatBridges.row(net()))
        assertEquals(ChatBridgeRow.STARTING, ChatBridges.row(net(state = ChatBridges.NET_STARTING, logins = arrayOf(login(ChatBridges.LOGIN_CONNECTED)))))
        assertEquals(ChatBridgeRow.UNREACHABLE, ChatBridges.row(net(state = ChatBridges.NET_UNREACHABLE, logins = arrayOf(login(ChatBridges.LOGIN_CONNECTED)))))
        assertEquals(ChatBridgeRow.UNREACHABLE, ChatBridges.row(net(state = ChatBridges.NET_FAILED)))
        assertEquals(ChatBridgeRow.LINKED, ChatBridges.row(net(logins = arrayOf(login(ChatBridges.LOGIN_CONNECTED)))))
        assertEquals(ChatBridgeRow.LINKED, ChatBridges.row(net(logins = arrayOf(login(ChatBridges.LOGIN_CONNECTING)))))
        assertEquals(ChatBridgeRow.LOGGED_OUT, ChatBridges.row(net(logins = arrayOf(login(ChatBridges.LOGIN_LOGGED_OUT)))))
        assertEquals(ChatBridgeRow.ERROR, ChatBridges.row(net(logins = arrayOf(login(ChatBridges.LOGIN_ERROR)))))
        assertEquals(ChatBridgeRow.ERROR, ChatBridges.row(net(logins = arrayOf(login(ChatBridges.LOGIN_UNKNOWN)))))
        // outdated is a mark on a linked row, not a state of its own
        assertEquals(ChatBridgeRow.LINKED, ChatBridges.row(net(outdated = true, logins = arrayOf(login(ChatBridges.LOGIN_CONNECTED)))))
    }

    @Test
    fun `the login that matters most - and when linking again is offered`() {
        val n = net(logins = arrayOf(login(ChatBridges.LOGIN_LOGGED_OUT, "old"), login(ChatBridges.LOGIN_CONNECTED, "new"), login(ChatBridges.LOGIN_ERROR, "err")))
        assertEquals("new", ChatBridges.mainLogin(n)!!.id)
        assertEquals("err", ChatBridges.mainLogin(net(logins = arrayOf(login(ChatBridges.LOGIN_LOGGED_OUT, "old"), login(ChatBridges.LOGIN_ERROR, "err"))))!!.id)
        assertNull(ChatBridges.mainLogin(net()))
        assertFalse(ChatBridges.relinkOffered(n), "linked: nothing to link again")
        assertFalse(ChatBridges.relinkOffered(net()), "never linked: „Verknüpfen“ instead")
        assertTrue(ChatBridges.relinkOffered(net(logins = arrayOf(login(ChatBridges.LOGIN_LOGGED_OUT)))))
        assertTrue(ChatBridges.relinkOffered(net(logins = arrayOf(login(ChatBridges.LOGIN_ERROR)))))
        assertFalse(ChatBridges.relinkOffered(net(state = ChatBridges.NET_STARTING, logins = arrayOf(login(ChatBridges.LOGIN_LOGGED_OUT)))), "not while the bridge does not run")
    }

    // ─── the steps of a login ───────────────────────────────────────

    @Test
    fun `a login - the number is asked for, a code shows, the phone confirmed`() {
        val ask = ChatBridgesJson.loginState(json("""{"ok":true,"login":{"processId":"p-1","step":{"id":"fi.mau.whatsapp.login.phone","type":"user_input","fields":[{"id":"phone_number","type":"phone_number","optional":false}]}}}"""))!!
        assertEquals("p-1", ask.processId)
        assertFalse(ask.waiting)
        assertEquals(ChatBridgeStep.Phone("fi.mau.whatsapp.login.phone", "phone_number"), ask.step)

        val code = ChatBridgesJson.loginState(json("""{"login":{"processId":"p-1","step":{"id":"fi.mau.whatsapp.login.code","type":"display_and_wait","display":{"type":"code","data":"ABCD-1234"}}}}"""))!!
        val c = assertInstanceOf(ChatBridgeStep.Code::class.java, code.step)
        assertEquals("ABCD-1234", c.code)
        assertEquals("fi.mau.whatsapp.login.code", c.id)

        val waiting = ChatBridgesJson.loginState(json("""{"login":{"processId":"p-1","waiting":true}}"""))!!
        assertTrue(waiting.waiting)
        assertNull(waiting.step)

        val done = ChatBridgesJson.loginState(json("""{"login":{"processId":"p-1","step":{"id":"fi.mau.whatsapp.login.complete","type":"complete","loginId":"4915120000000"}}}"""))!!
        assertEquals(ChatBridgeStep.Complete, done.step)
    }

    @Test
    fun `a QR step is drawn from the server's modules, else from its content`() {
        val sent = ChatBridgesJson.loginState(
            json("""{"login":{"processId":"p","step":{"id":"qr","type":"display_and_wait","display":{"type":"qr","data":"2@abc","matrix":{"size":3,"rows":["101","010","111"]}}}}}"""),
        )!!.step
        val q = assertInstanceOf(ChatBridgeStep.Qr::class.java, sent)
        assertEquals(3, q.matrix.size)
        assertEquals(listOf("101", "010", "111"), q.matrix.rows)

        // an app API before 1.13.0 sends no matrix; one that is no square of 0 and 1 is not drawn
        for (matrix in listOf("", ""","matrix":{"size":3,"rows":["101","01","111"]}""", ""","matrix":{"size":2,"rows":["1x","01"]}""", ""","matrix":{"size":900,"rows":[]}""")) {
            val own = ChatBridgesJson.loginState(json("""{"login":{"processId":"p","step":{"id":"qr","type":"display_and_wait","display":{"type":"qr","data":"2@AbCdEf0123456789,xyz,key"$matrix}}}}"""))!!.step
            val m = assertInstanceOf(ChatBridgeStep.Qr::class.java, own).matrix
            assertTrue(m.valid)
            assertTrue(m.size >= 21, "a QR code has at least 21 modules")
            // the finder pattern in the corner: no quiet zone around the modules
            assertEquals("1111111", m.rows[0].take(7))
        }
        assertNull(ChatBridges.qr(""))
        assertNull(ChatBridges.qr("x".repeat(ChatBridges.QR_MAX_CHARS + 1)))
    }

    @Test
    fun `steps this app cannot show - the other way has to do`() {
        fun step(s: String) = ChatBridgesJson.loginState(json("""{"login":{"processId":"p","step":$s}}"""))!!.step
        assertEquals(ChatBridgeStep.Unsupported, step("""{"id":"x","type":"cookies"}"""))
        assertEquals(ChatBridgeStep.Unsupported, step("""{"id":"x","type":"user_input","fields":[{"id":"mail","type":"email"}]}"""), "no number, code or password: no field for it here")
        assertEquals(ChatBridgeStep.Unsupported, step("""{"id":"x","type":"display_and_wait","display":{"type":"emoji","data":"🙂"}}"""))
        assertEquals(ChatBridgeStep.Unsupported, step("""{"id":"x","type":"display_and_wait","display":{"type":"code","data":""}}"""))
        assertEquals(ChatBridgeStep.Unsupported, step("""{"id":"x","type":"display_and_wait","display":{"type":"qr","data":""}}"""))
        assertEquals(ChatBridgeStep.Unsupported, step("""{"id":"not an id","type":"display_and_wait","display":{"type":"code","data":"ABCD-1234"}}"""))
        // the portal's default: a question without fields asks for the number
        assertEquals(ChatBridgeStep.Phone("x", "phone_number"), step("""{"id":"x","type":"user_input"}"""))
        // no process, no login
        assertNull(ChatBridgesJson.loginState(json("""{"login":{"step":{"id":"x","type":"complete"}}}""")))
        assertNull(ChatBridgesJson.loginState(json("""{"login":{"processId":"a/b","waiting":true}}""")))
        assertNull(ChatBridgesJson.loginState(json("""{"ok":true}""")))
    }

    // ─── refusals ───────────────────────────────────────────────────

    @Test
    fun `the error codes of the bridges`() {
        fun e(code: String, http: Int, reason: String? = null) = ChatBridges.error(ChatResult.Failed(code, http, reason))
        assertEquals(ChatBridgeError.OFF, e("bridge_off", 409, "off"))
        assertEquals(ChatBridgeError.UNREACHABLE, e("bridge_unreachable", 503, "not_running"))
        assertEquals(ChatBridgeError.UNSUPPORTED, e("bridge_unsupported", 409, "unsupported_step"))
        assertEquals(ChatBridgeError.FAILED, e("bridge_failed", 502, "m_bad_json"))
        assertEquals(ChatBridgeError.FAILED, e("bridge_failed", 502))
        assertEquals(ChatBridgeError.EXPIRED, e("bridge_failed", 502, "fi_mau_whatsapp_login_timeout"), "the code ran out")
        assertEquals(ChatBridgeError.EXPIRED, e("bridge_failed", 502, "login_expired"))
        assertEquals(ChatBridgeError.LOCKED, e("bridge_locked", 403))
        assertEquals(ChatBridgeError.CONSENT, e("consent_required", 409))
        assertEquals(ChatBridgeError.RATE_LIMITED, e("rate_limited", 429))
        assertEquals(ChatBridgeError.RATE_LIMITED, e("http_429", 429))
        assertEquals(ChatBridgeError.FORBIDDEN, e("forbidden", 403))
        assertEquals(ChatBridgeError.GENERIC, e("not_found", 404))
        assertEquals(ChatBridgeError.GENERIC, e("internal", 500))
        assertEquals(ChatBridgeError.OFFLINE, ChatBridges.error(ChatResult.Offline))
        assertEquals(ChatBridgeError.GENERIC, ChatBridges.error(ChatResult.NotConfigured))
    }

    @Test
    fun `the reason of an error answer is a short word or nothing`() {
        assertEquals("fi_mau_whatsapp_login_timeout", ChatJson.errorReason(json("""{"ok":false,"code":"bridge_failed","reason":"fi_mau_whatsapp_login_timeout"}""")))
        assertNull(ChatJson.errorReason(json("""{"ok":false,"code":"bridge_failed"}""")))
        assertNull(ChatJson.errorReason(json("""{"reason":"Not A Word"}""")))
        assertNull(ChatJson.errorReason(json("""{"reason":7}""")))
        assertNull(ChatJson.errorReason(null))
    }

    // ─── number, paths ──────────────────────────────────────────────

    @Test
    fun `a number with its country code, as the bridge takes it`() {
        assertEquals("+4915120000000", ChatBridges.phone("+49 1512 0000000"))
        assertEquals("+4915120000000", ChatBridges.phone("0049 (1512) 000-00.00/"))
        assertEquals("+4915120000000", ChatBridges.phone(" +49-1512-0000000 "))
        assertNull(ChatBridges.phone("01512 0000000"), "without the country code")
        assertNull(ChatBridges.phone("+49 1512 000000a"))
        assertNull(ChatBridges.phone("+12345"), "too short")
        assertNull(ChatBridges.phone("+1234567890123456"), "too long")
        assertNull(ChatBridges.phone(""))
    }

    @Test
    fun `paths - ids of the server as encoded segments, nothing else`() {
        assertEquals("bridges/whatsapp/consent", ChatBridges.path("whatsapp", "consent"))
        assertEquals("bridges/whatsapp/login", ChatBridges.path("whatsapp", "login"))
        assertEquals("bridges/whatsapp/login/p%3A1%2Ba%3D/fi.mau.whatsapp.login.code", ChatBridges.path("whatsapp", "login", "p:1+a=", "fi.mau.whatsapp.login.code"))
        assertEquals("bridges/whatsapp/logins/4915120000000", ChatBridges.path("whatsapp", "logins", "4915120000000"))
        assertNull(ChatBridges.path("whatsapp", "login", "a/b"), "no path of its own")
        assertNull(ChatBridges.path("whatsapp", "login", "../me"))
        assertNull(ChatBridges.path("whatsapp", "logins", ""))
        assertNull(ChatBridges.path("icq", "login"), "a service this app does not know")
        // Messenger 1.23
        assertEquals("bridges/signal/handoff", ChatBridges.path("signal", "handoff"))
    }

    @Test
    fun `Messenger 1 23 - Signal links by QR code only, brings 30 days once, has its own consent and a handoff`() {
        val info = ChatBridgesJson.info(
            json(
                """{"ok":true,"bridges":{"available":true,"location":"server",
                "handoff":{"network":"signal","target":"windows","expiresAt":"2026-11-16T10:12:00.000Z"},
                "networks":[
                {"id":"whatsapp","state":"running","allowed":true,"logins":[],"flows":["phone","qr"],"history":{"maxDays":null,"phoneConfirms":false}},
                {"id":"signal","state":"running","allowed":true,"consent":null,"logins":[],"flows":["qr"],
                 "history":{"maxDays":30,"phoneConfirms":true},"recognition":{"available":true,"asked":false,"allowed":false}}]}}""",
            ),
        )!!
        assertEquals(listOf("whatsapp", "signal"), info.networks.map { it.id })
        val sg = info.networks[1]
        assertEquals(listOf(ChatBridges.FLOW_QR), sg.flows)
        assertTrue(ChatBridges.qrOnly(sg))
        assertFalse(ChatBridges.qrOnly(info.networks[0]))
        assertEquals(ChatBridges.FLOW_PHONE, ChatBridges.firstFlow(info.networks[0]))
        assertEquals(30, sg.historyMaxDays)
        assertTrue(sg.phoneConfirms)
        assertEquals("signal", info.recognitionOf("signal")!!.network)
        assertTrue(info.recognitionOf("signal")!!.ask)
        assertNull(info.recognitionOf("whatsapp"), "an older server: WhatsApp's consent only at the top")
        assertEquals(ChatBridges.HANDOFF_WINDOWS, info.handoff!!.target)
        assertEquals("signal", info.handoff!!.network)
        // an older server names no flows: both ways
        val old = ChatBridgesJson.info(json("""{"bridges":{"available":true,"networks":[{"id":"whatsapp","state":"running","logins":[]}]}}"""))!!
        assertEquals(listOf(ChatBridges.FLOW_PHONE, ChatBridges.FLOW_QR), old.networks[0].flows)
        assertNull(ChatBridgesJson.handoff(json("""{"network":"signal","target":"fax"}""")))
    }

    // ─── the calls ──────────────────────────────────────────────────

    private val client: ApiClient = mockk()
    private val media: ApiClient = mockk()

    private fun api(): ChatApi {
        val setup: SetupRepository = mockk()
        every { setup.getServerUrl() } returns "https://gc.example"
        every { setup.isConfigured() } returns true
        val provider: ApiClientProvider = mockk()
        every { provider.getClient(any()) } returns client
        every { provider.mediaClient(any()) } returns media
        val settings: SettingsRepository = mockk()
        every { settings.getLocale() } returns flowOf("de")
        return ChatApi(setup, provider, settings)
    }

    private fun refusal(http: Int, body: String): Response<JsonObject> = Response.error(http, body.toResponseBody("application/json".toMediaType()))

    @Test
    fun `the calls - routes, bodies, and the wait on the patient client`() = runBlocking {
        val api = api()
        val body = slot<JsonObject>()
        coEvery { client.pluginGet(MESSENGER_PLUGIN, "bridges", any()) } returns Response.success(json("""{"ok":true,"bridges":{"available":false,"networks":[]}}"""))
        assertFalse(api.bridges().valueOrNull()!!.available)

        coEvery { client.pluginPost(MESSENGER_PLUGIN, "bridges/whatsapp/consent", capture(body), any()) } returns Response.success(json("""{"ok":true,"consent":"2026-11-14T10:00:00.000Z"}"""))
        assertInstanceOf(ChatResult.Ok::class.java, api.bridgeConsent("whatsapp"))
        assertEquals("""{"accept":true}""", body.captured.toString())

        coEvery { client.pluginPost(MESSENGER_PLUGIN, "bridges/whatsapp/login", capture(body), any()) } returns
            Response.success(json("""{"ok":true,"login":{"processId":"p:1","step":{"id":"phone","type":"user_input","fields":[{"id":"phone_number","type":"phone_number"}]}}}"""))
        assertEquals("p:1", api.bridgeLoginStart("whatsapp", ChatBridges.FLOW_PHONE).valueOrNull()!!.processId)
        assertEquals("""{"flow":"phone"}""", body.captured.toString())

        // the number goes in the body under the field's id; the answer is the code
        coEvery { media.pluginPost(MESSENGER_PLUGIN, "bridges/whatsapp/login/p%3A1/phone", capture(body), any()) } returns
            Response.success(json("""{"ok":true,"login":{"processId":"p:1","step":{"id":"code","type":"display_and_wait","display":{"type":"code","data":"ABCD-1234"}}}}"""))
        val code = api.bridgeLoginStep("whatsapp", "p:1", "phone", mapOf("phone_number" to "+4915120000000")).valueOrNull()!!
        assertInstanceOf(ChatBridgeStep.Code::class.java, code.step)
        assertEquals("""{"input":{"phone_number":"+4915120000000"}}""", body.captured.toString())

        // waiting for the phone: an empty body
        coEvery { media.pluginPost(MESSENGER_PLUGIN, "bridges/whatsapp/login/p%3A1/code", capture(body), any()) } returns Response.success(json("""{"ok":true,"login":{"processId":"p:1","waiting":true}}"""))
        assertTrue(api.bridgeLoginStep("whatsapp", "p:1", "code").valueOrNull()!!.waiting)
        assertEquals("{}", body.captured.toString())

        coEvery { client.pluginDelete(MESSENGER_PLUGIN, "bridges/whatsapp/login/p%3A1", any()) } returns Response.success(json("""{"ok":true,"cancelled":true}"""))
        assertInstanceOf(ChatResult.Ok::class.java, api.bridgeLoginCancel("whatsapp", "p:1"))
        coEvery { client.pluginDelete(MESSENGER_PLUGIN, "bridges/whatsapp/logins/4915120000000", any()) } returns Response.success(json("""{"ok":true,"unlinked":true}"""))
        assertInstanceOf(ChatResult.Ok::class.java, api.bridgeUnlink("whatsapp", "4915120000000"))

        // an id that is none never becomes a path
        assertEquals(ChatResult.Failed("invalid", 0), api.bridgeUnlink("whatsapp", "a/../b"))
        assertEquals(ChatResult.Failed("invalid", 0), api.bridgeLoginStep("whatsapp", "p", "a b"))
        coVerify(exactly = 2) { client.pluginDelete(any(), any(), any()) }
    }

    @Test
    fun `a refusal carries the bridge's reason`() = runBlocking {
        val api = api()
        coEvery { media.pluginPost(MESSENGER_PLUGIN, any(), any(), any()) } returns
            refusal(502, """{"ok":false,"code":"bridge_failed","error":"Die Brücke hat das abgelehnt.","reason":"fi_mau_whatsapp_login_timeout"}""")
        val r = api.bridgeLoginStep("whatsapp", "p", "code")
        assertEquals(ChatResult.Failed("bridge_failed", 502, "fi_mau_whatsapp_login_timeout", message = "Die Brücke hat das abgelehnt."), r)
        assertEquals(ChatBridgeError.EXPIRED, ChatBridges.error(r))

        coEvery { client.pluginPost(MESSENGER_PLUGIN, "bridges/whatsapp/login", any(), any()) } returns refusal(403, """{"ok":false,"code":"bridge_locked","error":"…"}""")
        assertEquals(ChatBridgeError.LOCKED, ChatBridges.error(api.bridgeLoginStart("whatsapp", ChatBridges.FLOW_QR)))
        coEvery { client.pluginPost(MESSENGER_PLUGIN, "bridges/whatsapp/consent", any(), any()) } returns refusal(409, """{"ok":false,"code":"bridge_off","reason":"off"}""")
        assertEquals(ChatBridgeError.OFF, ChatBridges.error(api.bridgeConsent("whatsapp")))
    }

    // ─── nothing of it in a log ─────────────────────────────────────

    @Test
    fun `number and code stay out of every text made of the state`() {
        val l = login(ChatBridges.LOGIN_CONNECTED)
        val n = net(logins = arrayOf(l))
        val step = ChatBridgeStep.Code("code", "ABCD-1234")
        val state = ChatBridgeLoginState("p", false, step)
        val qr = ChatBridgeStep.Qr("qr", ChatBridges.qr("2@secret-content")!!)
        for (text in listOf(l.toString(), n.toString(), ChatBridgesInfo(true, true, listOf(n)).toString(), step.toString(), state.toString(), qr.toString(), qr.matrix.toString())) {
            assertFalse(text.contains("1512"), text)
            assertFalse(text.contains("ABCD"), text)
            assertFalse(text.contains("0101") || text.contains("1010"), text)
        }
        assertNotNull(l.name)
    }

    @Test
    fun `the page keeps screenshots out and writes nothing down`() {
        fun source(path: String): String = listOf(File(path), File("app", path)).first { it.exists() }.readText()
        val page = source("src/main/java/com/gatecontrol/android/ui/chat/settings/ChatLinkedServices.kt")
        assertTrue(page.contains("SecureScreenEffect()"), "no screenshot of a number or a code")
        for (path in listOf(
            "src/main/java/com/gatecontrol/android/ui/chat/settings/ChatLinkedServices.kt",
            "src/main/java/com/gatecontrol/android/ui/chat/settings/LinkedServicesViewModel.kt",
            "src/main/java/com/gatecontrol/android/chat/ChatBridges.kt",
            "src/main/java/com/gatecontrol/android/chat/ChatBridgesRepository.kt",
        )) {
            val text = source(path)
            assertFalse(text.contains("Timber") || text.contains("Log."), "$path logs nothing")
            assertFalse(text.contains("rememberSaveable") || text.contains("SavedStateHandle"), "$path keeps nothing in the saved state")
            assertFalse(text.contains("SharedPreferences") || text.contains("DataStore"), "$path stores nothing")
        }
    }

    @Test
    fun `Messenger 1 24 - Telegram by number, code and password, not set up says so, a bot and a large group`() {
        val info = ChatBridgesJson.info(
            json(
                """{"ok":true,"bridges":{"available":true,"location":"server","networks":[
                {"id":"telegram","state":"stopped","allowed":true,"logins":[],"flows":["qr","phone"],"needs":"app",
                 "history":{"maxDays":null,"phoneConfirms":false}}]}}""",
            ),
        )!!
        val tg = info.networks.single()
        assertEquals(ChatBridges.TELEGRAM, tg.id)
        assertEquals(ChatBridges.NEEDS_APP, tg.needs)
        assertEquals(ChatBridgeRow.NOT_SET_UP, ChatBridges.row(tg))
        assertEquals(ChatBridges.FLOW_PHONE, ChatBridges.firstFlow(tg), "on a phone the number comes first")
        assertFalse(ChatBridges.qrOnly(tg))
        assertTrue(ChatBridges.qrOnSecondScreen(ChatBridges.TELEGRAM))
        val code = ChatBridgesJson.loginState(
            json("""{"login":{"processId":"p1","step":{"id":"fi.mau.telegram.login.code","type":"user_input","fields":[{"id":"fi.mau.telegram.login.code","type":"2fa_code"}]}}}"""),
        )!!.step as ChatBridgeStep.Secret
        assertFalse(code.password)
        assertFalse(code.retry)
        val pw = ChatBridgesJson.loginState(
            json("""{"login":{"processId":"p1","step":{"id":"fi.mau.telegram.login.password.incorrect","type":"user_input","retry":true,"fields":[{"id":"fi.mau.telegram.login.password","type":"password"}]}}}"""),
        )!!.step as ChatBridgeStep.Secret
        assertTrue(pw.password)
        assertTrue(pw.retry)
        assertEquals("fi.mau.telegram.login.password", pw.fieldId)
        val bot = ChatBridgeChat.conversation(json("""{"network":"telegram","label":"Telegram","state":"ok","bot":true}"""))!!
        assertTrue(bot.bot)
        val group = ChatBridgeChat.conversation(json("""{"network":"telegram","label":"Telegram","state":"ok","members":340}"""))!!
        assertEquals(340, group.members)
        assertNull(ChatBridgeChat.conversation(json("""{"network":"telegram","label":"Telegram","state":"ok","members":-3}"""))!!.members)
    }
}
