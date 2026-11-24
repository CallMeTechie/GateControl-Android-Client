package com.gatecontrol.android.mail

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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import retrofit2.Response
import java.io.IOException

/**
 * The routes of GateControl Mail through the client plugin bridge as the phone app and GateControl TV call them:
 * reading, answering through a draft, sending with „Rückgängig“, archive with `undo`, the AI.
 */
class MailApiTest {

    private val client = mockk<ApiClient>()
    private var configured = true
    private val setup = mockk<SetupRepository> {
        every { getServerUrl() } returns "https://gc.example.com"
        every { isConfigured() } answers { configured }
    }
    private val settings = mockk<SettingsRepository> { every { getLocale() } returns flowOf("de") }
    private val provider = mockk<ApiClientProvider> { every { getClient(any()) } returns client }
    private val api = MailApi(setup, provider, settings)

    private fun ok(json: String): Response<JsonObject> = Response.success(JsonParser.parseString(json).asJsonObject)

    private fun error(code: Int, json: String): Response<JsonObject> =
        Response.error(code, json.toResponseBody("application/json".toMediaType()))

    private val lang = mapOf("lang" to "de")

    @Test
    fun `a mail to read - the text, the attachments, the invitation kept as it came`() = runBlocking {
        coEvery { client.pluginGet(MAIL_PLUGIN, "messages/7", lang) } returns ok(
            """{"id":7,"accountId":1,"from":{"name":"Mila","email":"mila@example.com"},"subject":"Grillen","text":"Hallo",
               "attachments":[{"id":3,"name":"a.pdf","mime":"application/pdf","size":10}],
               "invite":{"id":9,"start":1,"method":"request"}}""",
        )
        val (m, raw) = (api.message(7) as MResult.Ok).value
        assertEquals("Hallo", m.text)
        assertEquals(listOf("a.pdf"), m.attachments.map { it.name })
        assertEquals(9L, m.inviteJson?.get("id")?.asLong)
        assertTrue(raw.has("invite"))
    }

    @Test
    fun `an account's calendar side is read here, the Microsoft defaults too`() {
        val me = MailJson.me(JsonParser.parseString(
            """{"features":["mail"],"accounts":[{"id":1,"provider":"imap","email":"konto@example.com","calendar":{"state":"syncing","readOnly":true,"server":" dav.example.com "}}]}""",
        ).asJsonObject)!!
        val side = me.accounts.single().calendar!!
        assertEquals(MPimSide.STATE_SYNCING, side.state)
        assertTrue(side.readOnly)
        assertEquals("dav.example.com", side.server)
        assertNull(me.accounts.single().contacts)
        assertEquals(MMicrosoft.DEFAULT_AUTHORITY, me.microsoft.authority)
    }

    @Test
    fun `answer - draft with mode, the text into it, send, stop the send`() = runBlocking {
        val create = slot<JsonObject>()
        coEvery { client.pluginPost(MAIL_PLUGIN, "drafts", capture(create), lang) } returns ok(
            """{"draft":{"id":5,"accountId":1,"to":[{"email":"mila@example.com"}],"subject":"Re: Grillen","html":"<blockquote>Hallo</blockquote>"}}""",
        )
        val body = JsonObject().apply {
            addProperty("replyTo", 7)
            addProperty("mode", "reply")
        }
        val d = (api.createDraft(body) as MResult.Ok).value
        assertEquals(5L, d.id)
        assertEquals("reply", create.captured["mode"].asString)

        val put = slot<JsonObject>()
        coEvery { client.pluginPut(MAIL_PLUGIN, "drafts/5", capture(put), lang) } returns ok("""{"draft":{"id":5,"accountId":1,"html":"x"}}""")
        val html = MailLogic.composeHtml("Bin dabei", MailLogic.splitDraft(d.html).second)
        api.updateDraft(5, MailJson.draftBody(null, null, null, null, null, html, replyTo = 7, mode = "reply"))
        assertEquals(html, put.captured["html"].asString)

        coEvery { client.pluginPost(MAIL_PLUGIN, "drafts/5/send", any(), lang) } returns ok("""{"outboxId":77,"undoUntil":1700000010000}""")
        val sent = (api.send(5, null) as MResult.Ok).value
        assertEquals(77L, sent.outboxId)
        assertEquals(1700000010000L, sent.undoUntil)

        coEvery { client.pluginPost(MAIL_PLUGIN, "outbox/77/cancel", any(), lang) } returns ok("""{"draftId":5}""")
        assertEquals(5L, (api.cancel(77) as MResult.Ok).value)

        coEvery { client.pluginPost(MAIL_PLUGIN, "outbox/78/cancel", any(), lang) } returns error(409, """{"error":{"code":"too_late"}}""")
        assertEquals("too_late", (api.cancel(78) as MResult.Failed).code)
    }

    @Test
    fun `archive with undo, then undo`() = runBlocking {
        val sent = slot<JsonObject>()
        coEvery { client.pluginPost(MAIL_PLUGIN, "messages/archive", capture(sent), lang) } returns ok(
            """{"ok":true,"items":[],"count":1,"ids":[7],"undo":{"token":"tok_1","kind":"archive","count":1}}""",
        )
        val done = (api.bulk("archive", MailBulk.body(MailBulk.Action.ARCHIVE, listOf(7L), threads = false)) as MResult.Ok).value
        assertEquals("tok_1", done.undo?.token)
        assertEquals(7L, sent.captured["ids"].asJsonArray.single().asLong)
        // only the bulk routes
        assertEquals(MailApi.CODE_INVALID, (api.bulk("../drafts", JsonObject()) as MResult.Failed).code)

        coEvery { client.pluginPost(MAIL_PLUGIN, "messages/undo", any(), lang) } returns ok("""{"kind":"archive","restored":1,"failed":0,"items":[]}""")
        assertEquals(1, (api.undo("tok_1") as MResult.Ok).value.restored)
    }

    @Test
    fun `the AI - result, nothing yet, switched off`() = runBlocking {
        coEvery { client.pluginGet(MAIL_PLUGIN, "ai/7", lang) } returns ok(
            """{"status":"done","summary":"Grillen am Samstag.","replies":[{"label":"Ja","text":"Ja, gern."}]}""",
        )
        val ai = (api.ai(7) as MResult.Ok).value
        assertEquals("Grillen am Samstag.", ai.summary)
        assertEquals(listOf("Ja, gern."), ai.replies.map { it.text })

        coEvery { client.pluginGet(MAIL_PLUGIN, "ai/8", lang) } returns ok("""{"status":"none"}""")
        assertEquals(false, (api.ai(8) as MResult.Ok).value.done)

        coEvery { client.pluginGet(MAIL_PLUGIN, "ai/9", lang) } returns error(403, """{"error":{"code":"ai_off"}}""")
        assertEquals("ai_off", (api.ai(9) as MResult.Failed).code)
    }

    @Test
    fun `no connection, not set up`() = runBlocking {
        coEvery { client.pluginGet(MAIL_PLUGIN, "me", lang) } throws IOException("down")
        assertEquals(MResult.Offline, api.me())
        configured = false
        assertEquals(MResult.NotConfigured, api.me())
    }

    @Test
    fun `flags of a mail read`() = runBlocking {
        val sent = slot<JsonObject>()
        coEvery { client.pluginPost(MAIL_PLUGIN, "messages/flags", capture(sent), lang) } returns ok("""{"ok":true,"items":[]}""")
        assertTrue(api.flags(listOf(7L), unread = false) is MResult.Ok)
        assertEquals(false, sent.captured["unread"].asBoolean)
        coVerify { client.pluginPost(MAIL_PLUGIN, "messages/flags", any(), lang) }
    }
}
