package com.gatecontrol.android.quickactions

import android.content.Context
import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.network.ApiClient
import com.gatecontrol.android.network.ApiClientProvider
import com.gatecontrol.android.network.QuickAction
import com.gatecontrol.android.network.QuickActionRunRequest
import com.gatecontrol.android.network.QuickActionRunResponse
import com.gatecontrol.android.network.QuickActionsResponse
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import retrofit2.Response

class QuickActionRepositoryTest {

    private val stored = MutableStateFlow<String?>(null)
    private val settings = mockk<SettingsRepository> {
        every { getQuickActionPins() } returns stored
        every { getLocale() } returns kotlinx.coroutines.flow.flowOf("de")
        coEvery { updateQuickActionPins(any()) } coAnswers {
            val transform = firstArg<(String?) -> String>()
            stored.value = transform(stored.value)
        }
    }
    private val setup = mockk<SetupRepository> { every { getServerUrl() } returns "https://gc.example.com" }
    private val api = mockk<ApiClient>()
    private val provider = mockk<ApiClientProvider> { every { getClient(any()) } returns api }
    private val context = mockk<Context>(relaxed = true)

    private fun repo() = QuickActionRepository(context, setup, provider, settings)

    private val light = QuickAction(plugin = "gatecontrol-smarthome", pluginName = "Smart Home", id = "light:3", title = "Wohnzimmer", kind = "toggle", state = "off")

    @Test
    fun `sanitize keeps valid entries and drops broken ones`() {
        val items = QuickActionRepository.sanitize(
            listOf(
                light,
                QuickAction(plugin = "gatecontrol-skoda", id = "unlock:1", title = "Entriegeln", kind = "button", confirm = true, state = "on"),
                QuickAction(plugin = "Bad Plugin!", id = "x", title = "x"),
                QuickAction(plugin = "p", id = "has space", title = "x"),
                QuickAction(plugin = "p", id = "ok", title = "  "),
                QuickAction(plugin = "p", id = "ok2", title = "x", kind = "slider"),
                light, // duplicate
            ),
        )
        assertEquals(listOf("gatecontrol-smarthome/light:3", "gatecontrol-skoda/unlock:1"), items.map { it.key })
        assertTrue(items[0].toggle)
        assertEquals("off", items[0].state)
        assertTrue(items[1].confirm)
        assertNull(items[1].state) // buttons have no state
        assertEquals("gatecontrol-skoda", items[1].pluginName) // falls back to the id
    }

    @Test
    fun `texts are capped and control characters removed`() {
        val item = QuickActionRepository.sanitize(
            listOf(QuickAction(plugin = "p", id = "a", title = "A\u0007" + "b".repeat(60), subtitle = "s\n")),
        ).single()
        assertEquals(40, item.title.length)
        assertFalse(item.title.contains('\u0007'))
        assertEquals("s", item.subtitle)
    }

    @Test
    fun `pins survive a round trip and broken storage reads as empty`() {
        val pins = QuickActionRepository.sanitize(listOf(light))
        assertEquals(pins, QuickActionRepository.decodePins(QuickActionRepository.encodePins(pins)))
        assertEquals(emptyList<QuickActionItem>(), QuickActionRepository.decodePins("{not json"))
        assertEquals(emptyList<QuickActionItem>(), QuickActionRepository.decodePins("""[{"plugin":"p"}]"""))
    }

    @Test
    fun `older server without quick actions is reported as unsupported`() = runTest {
        coEvery { api.getQuickActions(any()) } returns Response.error(404, "{}".toResponseBody(null))
        assertEquals(QuickActionsLoad.Unsupported, repo().refresh())
    }

    @Test
    fun `refresh loads the list and updates pinned titles and state`() = runTest {
        val r = repo()
        stored.value = QuickActionRepository.encodePins(QuickActionRepository.sanitize(listOf(light.copy(title = "Alt"))))
        coEvery { api.getQuickActions(any()) } returns Response.success(QuickActionsResponse(ok = true, actions = listOf(light.copy(state = "on"))))

        assertEquals(QuickActionsLoad.Loaded, r.refresh())

        assertEquals(1, r.actions.value.size)
        val pin = r.currentPins().single()
        assertEquals("Wohnzimmer", pin.title)
        assertEquals("on", pin.state)
    }

    @Test
    fun `run stores the new switch state`() = runTest {
        val r = repo()
        val item = QuickActionRepository.sanitize(listOf(light)).single()
        r.togglePin(item)
        coEvery { api.runQuickAction("gatecontrol-smarthome", "light:3", "de", QuickActionRunRequest("app")) } returns
            Response.success(QuickActionRunResponse(ok = true, message = "Licht an", state = "on"))

        val result = r.run(item)

        assertTrue(result.ok)
        assertEquals("Licht an", result.message)
        assertEquals("on", r.currentPins().single().state)
    }

    @Test
    fun `failed run keeps the state`() = runTest {
        val r = repo()
        val item = QuickActionRepository.sanitize(listOf(light)).single()
        r.togglePin(item)
        coEvery { api.runQuickAction(any(), any(), any(), any()) } returns Response.error(502, """{"ok":false}""".toResponseBody(null))

        assertFalse(r.run(item).ok)
        assertEquals("off", r.currentPins().single().state)
    }

    @Test
    fun `a notification button runs with source notification and shows the plugin's reason on failure`() = runTest {
        val r = repo()
        coEvery { api.runQuickAction("gatecontrol-door", "1:open", "de", QuickActionRunRequest("notification")) } returns
            Response.success(QuickActionRunResponse(ok = true, message = "Haustür geöffnet"))
        val ok = r.runFromNotification("gatecontrol-door", "1:open")
        assertTrue(ok.ok)
        assertEquals("Haustür geöffnet", ok.message)

        coEvery { api.runQuickAction("gatecontrol-door", "1:unlock", "de", QuickActionRunRequest("notification")) } returns
            Response.error(403, """{"ok":false,"message":"Außerhalb des Zeitfensters"}""".toResponseBody(null))
        val refused = r.runFromNotification("gatecontrol-door", "1:unlock")
        assertFalse(refused.ok)
        assertEquals("Außerhalb des Zeitfensters", refused.message)
    }

    @Test
    fun `a notification button with a malformed id never reaches the server`() = runTest {
        val r = repo()
        assertFalse(r.runFromNotification("gatecontrol-door", "../admin").ok)
        assertFalse(r.runFromNotification("Bad Plugin", "1:open").ok)
        io.mockk.coVerify(exactly = 0) { api.runQuickAction(any(), any(), any(), any()) }
    }

    @Test
    fun `pinning twice unpins and the number of pins is capped`() = runTest {
        val r = repo()
        val items = QuickActionRepository.sanitize((1..10).map { QuickAction(plugin = "p", id = "a$it", title = "A$it") })
        items.forEach { r.togglePin(it) }
        assertEquals(QuickActionRepository.MAX_PINS, r.currentPins().size)
        r.togglePin(items.first())
        assertFalse(r.currentPins().any { it.key == items.first().key })
    }
}
