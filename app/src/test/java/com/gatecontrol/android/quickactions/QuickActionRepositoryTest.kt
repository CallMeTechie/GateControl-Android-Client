package com.gatecontrol.android.quickactions

import android.content.Context
import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.network.ApiClient
import com.gatecontrol.android.network.ApiClientProvider
import com.gatecontrol.android.network.QuickAction
import com.gatecontrol.android.network.QuickActionDevice
import com.gatecontrol.android.network.QuickActionRunRequest
import com.gatecontrol.android.network.QuickActionRunResponse
import com.gatecontrol.android.network.QuickActionsResponse
import com.google.gson.Gson
import com.google.gson.JsonPrimitive
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
    private val storedWidgets = MutableStateFlow<String?>(null)
    private val settings = mockk<SettingsRepository> {
        every { getQuickActionPins() } returns stored
        every { getStartWidgets() } returns storedWidgets
        every { getLocale() } returns kotlinx.coroutines.flow.flowOf("de")
        coEvery { updateQuickActionPins(any()) } coAnswers {
            val transform = firstArg<(String?) -> String>()
            stored.value = transform(stored.value)
        }
        coEvery { updateStartWidgets(any()) } coAnswers {
            val transform = firstArg<(String?) -> String>()
            storedWidgets.value = transform(storedWidgets.value)
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
        val items = QuickActionRepository.sanitize((1..14).map { QuickAction(plugin = "p", id = "a$it", title = "A$it") })
        items.forEach { r.togglePin(it) }
        assertEquals(QuickActionRepository.MAX_PINS, r.currentPins().size)
        r.togglePin(items.first())
        assertFalse(r.currentPins().any { it.key == items.first().key })
    }

    private val climateJson = """
        {"plugin":"gatecontrol-midea","pluginName":"Klima","id":"ac:1","title":"Wohnzimmer","kind":"toggle","state":"on",
         "widget":{"value":21.5,"unit":"°C","status":"Kühlen · Ziel 22 °C","state":"on","controls":[
           {"id":"target","kind":"stepper","label":"Ziel","value":22,"min":16,"max":30,"step":0.5,"unit":"°C"},
           {"id":"mode","kind":"choice","label":"Modus","value":"cool","options":[{"id":"auto","label":"Auto"},{"id":"cool","label":"Kühlen"}]},
           {"id":"bad","kind":"slider","value":5,"min":10,"max":1,"step":1},
           {"id":"odd kind","kind":"knob","value":1,"min":0,"max":1,"step":1},
           {"id":"one","kind":"choice","value":"x","options":[{"id":"x","label":"X"}]}
         ]}}
    """.trimIndent()

    @Test
    fun `a widget with its controls is kept, broken controls are dropped, values clamped`() {
        val raw = Gson().fromJson(climateJson, QuickAction::class.java)
        val item = QuickActionRepository.sanitize(listOf(raw)).single()
        val w = item.widget!!
        assertEquals("21.5".replace('.', java.text.DecimalFormatSymbols.getInstance().decimalSeparator), w.value)
        assertEquals("°C", w.unit)
        assertEquals("on", w.state)
        assertEquals(listOf("target", "mode"), w.controls.map { it.id })
        assertEquals(22.0, w.controls[0].number)
        assertEquals(0.5, w.controls[0].step)
        assertEquals("cool", w.controls[1].choice)
        assertEquals(2, w.controls[1].options.size)
        assertFalse(item.asWidget)

        val clamped = QuickActionRepository.sanitize(listOf(raw.copy(widget = raw.widget!!.copy(controls = raw.widget!!.controls!!.take(1).map { it.copy(value = JsonPrimitive(99)) })))).single()
        assertEquals(30.0, clamped.widget!!.controls[0].number)
        val unknownChoice = QuickActionRepository.sanitize(listOf(raw.copy(widget = raw.widget!!.copy(controls = listOf(raw.widget!!.controls!![1].copy(value = JsonPrimitive("heat"))))))).single()
        assertEquals("auto", unknownChoice.widget!!.controls[0].choice)
        assertNull(QuickActionRepository.sanitize(listOf(light)).single().widget)
    }

    @Test
    fun `a 1_37 widget pin becomes a widget pin of its device with every part`() = runTest {
        val r = repo()
        val raw = Gson().fromJson(climateJson, QuickAction::class.java)
        val item = QuickActionRepository.sanitize(listOf(raw)).single()
        stored.value = QuickActionRepository.encodePins(listOf(item.copy(asWidget = true), QuickActionRepository.sanitize(listOf(light)).single()))
        assertEquals(listOf("gatecontrol-smarthome/light:3"), r.currentPins().map { it.key }, "no button while it waits to move")

        // the server now groups it under a device
        val grouped = raw.copy(device = QuickActionDevice(id = "ac:1", title = "Wohnzimmer"))
        coEvery { api.getQuickActions(any()) } returns Response.success(QuickActionsResponse(ok = true, actions = listOf(grouped, light)))
        r.refresh()

        assertEquals(listOf("gatecontrol-smarthome/light:3"), r.currentPins().map { it.key })
        val pin = r.currentWidgets().single()
        assertEquals("gatecontrol-midea/ac:1", pin.device, "found again by its action")
        assertNull(pin.parts, "all parts, like 1.37 showed")
        assertEquals("Wohnzimmer", pin.snapshot!!.title)
    }

    @Test
    fun `buttons and widgets of a device are set from the sheet`() = runTest {
        val r = repo()
        val lockJson = """{"plugin":"gatecontrol-skoda","id":"lock:1","title":"Verriegelt","kind":"toggle","state":"on","confirm":true,"confirmWhen":"off",
            "device":{"id":"v:1","title":"Enyaq"},"look":{"on":{"icon":"M1 1h2","tone":"good"},"off":{"icon":"M2 2h2","tone":"crit"}}}"""
        val climate = Gson().fromJson("""{"plugin":"gatecontrol-skoda","id":"climate:1","title":"Klimatisierung","kind":"toggle","state":"off",
            "device":{"id":"v:1","title":"Enyaq"},"widget":{"label":"Ladestand","value":78,"unit":"%","power":false,
            "readings":[{"id":"range","label":"Reichweite","value":312,"unit":"km"},{"id":"lock","label":"Verriegelung","icon":"M1 1h2","state":"good"}]}}""", QuickAction::class.java)
        val lock = Gson().fromJson(lockJson, QuickAction::class.java)
        val device = QuickActionDevices.group(QuickActionRepository.sanitize(listOf(climate, lock))).single()

        r.setButtons(device, setOf("lock:1"))
        assertEquals(listOf("gatecontrol-skoda/lock:1"), r.currentPins().map { it.key })
        r.setWidget(device, listOf(WidgetPart.VALUE, WidgetPart.reading("lock")), wide = true)
        val pin = r.currentWidgets().single()
        assertEquals("gatecontrol-skoda/v:1", pin.device)
        assertTrue(pin.wide)
        assertEquals(listOf(WidgetPart.VALUE, WidgetPart.reading("lock")), pin.shownParts(device).map { it.id })

        r.setButtons(device, emptySet())
        assertTrue(r.currentPins().isEmpty())
        r.removeWidget(device.key)
        assertTrue(r.currentWidgets().isEmpty())
    }

    @Test
    fun `a control run sends control and value and stores the answered widget`() = runTest {
        val r = repo()
        val raw = Gson().fromJson(climateJson, QuickAction::class.java)
        val item = QuickActionRepository.sanitize(listOf(raw)).single()
        r.togglePin(item)
        val answered = raw.widget!!.copy(value = JsonPrimitive("23"), status = "Kühlen · Ziel 23 °C")
        coEvery { api.runQuickAction("gatecontrol-midea", "ac:1", "de", QuickActionRunRequest("app", "target", 23.0)) } returns
            Response.success(QuickActionRunResponse(ok = true, state = "on", widget = answered))

        assertTrue(r.control(item, "target", 23.0).ok)

        val pin = r.currentPins().single()
        assertEquals("23", pin.widget!!.value)
        assertEquals("Kühlen · Ziel 23 °C", pin.widget!!.status)
        assertEquals("on", pin.state)
    }
}
