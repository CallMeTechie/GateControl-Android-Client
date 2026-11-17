package com.gatecontrol.android.chat

import android.app.Application
import androidx.test.core.app.ApplicationProvider
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response

/**
 * The report „gespeichert, aber nirgends zu sehen“: an Ortsnachricht saved from a WhatsApp chat with the Familie place
 * „Arbeit“ (`familieId` `place:3`), „Jedes Mal“ and a time window — exactly as Messenger 1.27.1's `placemsgs.js view()`
 * answers it (`target.reply` null for a fixed way, `lastFiredAt` null, `updatedAt`, `serverTime`). It must be in the
 * list after `POST` and after `GET`, and `DELETE` must take it out again (and with it out of what the phone watches).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ChatPlaceMessagesListTest {

    private fun json(s: String): JsonObject = JsonParser.parseString(s).asJsonObject

    private val device = "android-0011223344556677"

    /** `view()` of placemsgs.js for the user's case, field by field. */
    private val serverItem = """{"id":7,"deviceId":"$device","trigger":"enter",
        "place":{"familieId":"place:3","name":"Arbeit","lat":51.2345,"lon":7.1234,"radiusM":150},
        "text":"Bin auf der Arbeit angekommen.\nBis heute Abend!",
        "conversationId":41,"way":"whatsapp","repeat":"always",
        "window":{"days":[1,2,3,4,5],"from":"07:00","to":"09:30"},"ask":true,"enabled":true,
        "lastFiredAt":null,"doneAt":null,"createdAt":"2026-11-17T07:12:03.120Z","updatedAt":"2026-11-17T07:12:03.120Z",
        "target":{"title":"Kim M.","personId":null,"reply":null}}"""

    private val serverList = """{"placeMessages":[$serverItem],
        "limits":{"activeMax":10,"textMax":1000,"nameMax":80,"radiusMinM":100,"radiusMaxM":2000,"radiusDefaultM":150,"everyS":3600,"lateS":1800},
        "serverTime":"2026-11-17T07:12:04.000Z"}"""

    private val client: ApiClient = mockk()

    private fun setup(): SetupRepository = mockk<SetupRepository>().also {
        every { it.getServerUrl() } returns "https://gc.example"
        every { it.getApiToken() } returns "token-test"
        every { it.isConfigured() } returns true
    }

    private fun api(setup: SetupRepository): ChatApi {
        val provider: ApiClientProvider = mockk()
        every { provider.getClient(any()) } returns client
        every { provider.mediaClient(any()) } returns client
        val settings: SettingsRepository = mockk()
        every { settings.getLocale() } returns flowOf("de")
        return ChatApi(setup, provider, settings)
    }

    private fun repo(): ChatPlaceMessagesRepository {
        val setup = setup()
        return ChatPlaceMessagesRepository(ApplicationProvider.getApplicationContext(), api(setup), mockk(relaxed = true), setup)
    }

    @Test
    fun `the server's entry is read without dropping it`() {
        val l = ChatPlaceMessages.list(json(serverList))!!
        val p = l.items.single()
        assertEquals(7L, p.id)
        assertEquals("place:3", p.place.familieId)
        assertEquals("Arbeit", p.place.name)
        assertEquals("whatsapp", p.way)
        assertEquals(ChatPlaceMessages.ALWAYS, p.repeat)
        assertEquals(ChatPmWindow(listOf(1, 2, 3, 4, 5), "07:00", "09:30"), p.window)
        assertEquals("Kim M.", p.target?.title)
        assertNull(p.target?.reply)
        assertNull(p.lastFiredAt)
        assertTrue(p.active)
        // the copy on the phone reads back the same
        assertEquals(l, ChatPlaceMessages.list(ChatPlaceMessages.writeList(l)))
    }

    @Test
    fun `saved, listed, deleted - and no longer watched`() = runBlocking {
        val repo = repo()
        assertEquals(device.length, repo.deviceId.length)
        coEvery { client.pluginPost(MESSENGER_PLUGIN, "place-messages", any(), any()) } returns
            Response.success(json("""{"placeMessage":${serverItem.replace(device, repo.deviceId)}}"""))
        val draft = ChatPmDraft(
            trigger = ChatPlaceMessages.ENTER,
            place = ChatPmPlace("place:3", "Arbeit", 51.2345, 7.1234, 150),
            text = "Bin auf der Arbeit angekommen.\nBis heute Abend!",
            conversationId = 41,
            way = "whatsapp",
            repeat = ChatPlaceMessages.ALWAYS,
            window = ChatPmWindow(listOf(1, 2, 3, 4, 5), "07:00", "09:30"),
        )
        assertTrue(repo.create(draft) is ChatResult.Ok)
        // in the list right after saving (the POST's answer), before any GET
        assertEquals(listOf(7L), repo.list.value!!.items.map { it.id })

        // GET place-messages (the list page loads it again): still there
        coEvery { client.pluginGet(MESSENGER_PLUGIN, "place-messages", any()) } returns
            Response.success(json(serverList.replace(device, repo.deviceId)))
        assertTrue(repo.refresh() is ChatResult.Ok)
        val p = repo.list.value!!.items.single()
        assertEquals("Arbeit", p.place.name)
        assertEquals(listOf(7L), ChatPlaceMessages.watched(repo.cachedItems(), repo.deviceId, 10).map { it.id })

        // DELETE place-messages/7 → { deleted, id }: out of the list, out of what the phone watches
        coEvery { client.pluginDelete(MESSENGER_PLUGIN, "place-messages/7", any()) } returns
            Response.success(json("""{"deleted":true,"id":7}"""))
        assertTrue(repo.delete(7) is ChatResult.Ok)
        coVerify(exactly = 1) { client.pluginDelete(MESSENGER_PLUGIN, "place-messages/7", any()) }
        assertTrue(repo.list.value!!.items.isEmpty())
        assertTrue(ChatPlaceMessages.watched(repo.cachedItems(), repo.deviceId, 10).isEmpty())
    }

    @Test
    fun `deleted elsewhere already - not_found also takes it out`() = runBlocking {
        val repo = repo()
        coEvery { client.pluginGet(MESSENGER_PLUGIN, "place-messages", any()) } returns Response.success(json(serverList))
        repo.refresh()
        coEvery { client.pluginDelete(MESSENGER_PLUGIN, "place-messages/7", any()) } returns Response.error(
            404,
            """{"ok":false,"code":"not_found"}""".toResponseBody("application/json".toMediaType()),
        )
        assertTrue(repo.delete(7) is ChatResult.Ok)
        assertTrue(repo.list.value!!.items.isEmpty())
    }

    // ─── the chat info's section ────────────────────────────────────

    private fun conv(s: String) = ChatJson.conversation(json(s))!!

    @Test
    fun `the chat's own - a WhatsApp chat, and a person's from any of its ways`() {
        val items = ChatPlaceMessages.list(json(serverList))!!.items
        val other = items.single().copy(id = 8, conversationId = 99)
        val all = items + other
        val wa = conv(
            """{"id":41,"kind":"direct","title":"Kim M.","members":[{"userId":1,"name":"Lea"}],"me":{"role":"member","notify":"all"},"muted":false,"unread":0,
            "updatedAt":"2026-11-17T10:00:00.000Z","bridge":{"network":"whatsapp","label":"WhatsApp","state":"ok","supports":["text"]},"can":{"send":true}}""",
        )
        assertEquals(listOf(7L), ChatPlaceMessages.forChat(all, wa).map { it.id })

        // the server keeps a person's at the lead chat (40) — the WhatsApp way (41) shows it, and the lead chat too
        val atLead = items.single().copy(conversationId = 40, target = ChatPmTarget("Kim Muster", 12, null))
        val person = """{"id":12,"name":"Kim Muster","nameFrom":"account","userId":5,"conversationId":40,"names":["Kim Muster"],"replyPref":"auto",
            "ways":[{"way":"gatecontrol","label":"GateControl","conversationId":40,"title":"Kim Muster","bridge":null,"can":{"send":true}},
                    {"way":"whatsapp","label":"WhatsApp","conversationId":41,"title":"Kim M.","bridge":{"network":"whatsapp","label":"WhatsApp","state":"ok","supports":["text"]},"can":{"send":true}}],
            "reply":{"way":"whatsapp","label":"WhatsApp","conversationId":41}}"""
        val lead = conv(
            """{"id":40,"kind":"direct","title":"Kim Muster","peer":{"userId":5,"name":"Kim Muster"},"members":[{"userId":1,"name":"Lea"},{"userId":5,"name":"Kim Muster"}],
            "me":{"role":"member","notify":"all"},"muted":false,"unread":0,"updatedAt":"2026-11-17T10:00:00.000Z","can":{"send":true},"person":$person}""",
        )
        val way = conv(
            """{"id":41,"kind":"direct","title":"Kim M.","members":[{"userId":1,"name":"Lea"}],"me":{"role":"member","notify":"all"},"muted":false,"unread":0,
            "updatedAt":"2026-11-17T10:00:00.000Z","bridge":{"network":"whatsapp","label":"WhatsApp","state":"ok","supports":["text"]},"can":{"send":true},"person":$person}""",
        )
        assertEquals(listOf(7L), ChatPlaceMessages.forChat(listOf(atLead, other), lead).map { it.id })
        assertEquals(listOf(7L), ChatPlaceMessages.forChat(listOf(atLead, other), way).map { it.id })
        assertTrue(ChatPlaceMessages.forChat(listOf(other), lead).isEmpty())
    }

    @Test
    fun `the text in one short line`() {
        assertEquals("Bin auf der Arbeit angekommen. Bis heute Abend!", ChatPlaceMessages.preview("Bin auf der Arbeit angekommen.\nBis heute Abend!"))
        val long = ChatPlaceMessages.preview("a".repeat(100), 20)
        assertEquals(20, long.length)
        assertTrue(long.endsWith("…"))
    }
}
