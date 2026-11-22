package com.gatecontrol.android.quickactions

import android.content.Context
import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.network.ApiClient
import com.gatecontrol.android.network.ApiClientProvider
import com.gatecontrol.android.network.QuickAction
import com.gatecontrol.android.network.QuickActionRunRequest
import com.gatecontrol.android.network.QuickActionWidget
import com.gatecontrol.android.network.QuickActionsResponse
import com.google.gson.Gson
import com.google.gson.JsonParser
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import retrofit2.Response
import java.util.Locale

/**
 * The heating cards of Smart Home (1.14.0) and the host's new widget parts
 * (GateControl 1.318.0): a choice without a selection, disabled controls and
 * the day bar. Rooms and values are made up.
 */
class QuickActionHeatingTest {

    /** A room card as `heat:<id>` lists it: actual value, target stepper (0.5), five options, the plan bar. */
    private val roomJson = """
        {"plugin":"gatecontrol-smarthome","pluginName":"Smart Home","id":"heat:4","title":"Leseecke","kind":"button",
         "icon":"M12 3v10a4 4 0 1 0 0 8","device":{"id":"heat:4","title":"Leseecke"},
         "widget":{"label":"Ist","value":20.5,"unit":"°C","status":"Boost · noch 18 min, danach Komfort 23 °C","state":"on",
           "power":false,"readings":[],"badge":null,
           "controls":[
             {"id":"sp","kind":"stepper","label":"Soll","value":21,"min":5,"max":30,"step":0.5,"unit":"°C","disabled":false},
             {"id":"preset","kind":"choice","label":"Preset","value":null,"disabled":false,"options":[
               {"id":"comfort","label":"Komfort","icon":"M12 4v2"},{"id":"eco","label":"Eco","icon":"M5 19c8 0 14-6 14-14"},
               {"id":"night","label":"Nacht","icon":"M20 14A8 8 0 1 1 10 4"},{"id":"frost","label":"Frost","icon":"M12 2v20"},
               {"id":"boost","label":"Boost","icon":"M12 3c2 4 6 6 6 11a6 6 0 0 1-12 0"}]}],
           "timeline":{"from":0,"to":24,"now":6.8667,"segments":[
             {"from":0,"to":6,"tone":"neutral","color":null,"label":"Nacht"},
             {"from":6,"to":8,"tone":null,"color":"#F59E0B","label":"Komfort"},
             {"from":8,"to":16,"tone":"good","color":null,"label":"Eco"},
             {"from":16,"to":22.5,"tone":null,"color":"#f90","label":"Komfort"},
             {"from":22.5,"to":24,"tone":"neutral","color":null,"label":null}]}}}
    """.trimIndent()

    private fun widget(json: String) = Gson().fromJson(json, QuickActionWidget::class.java)
    private fun room() = QuickActionRepository.sanitize(listOf(Gson().fromJson(roomJson, QuickAction::class.java))).single()

    @Test
    fun `choice value null lights up no option, an unknown value still the first`() {
        val preset = room().widget!!.controls.single { it.id == "preset" }
        assertNull(preset.choice)
        assertEquals(5, preset.options.size)

        val unknown = QuickActionWidgetItem.from(
            widget("""{"controls":[{"id":"m","kind":"choice","value":"turbo","options":[{"id":"a","label":"A"},{"id":"b","label":"B"}]}]}"""),
        )!!.controls.single()
        assertEquals("a", unknown.choice)
        val chosen = QuickActionWidgetItem.from(
            widget("""{"controls":[{"id":"m","kind":"choice","value":"b","options":[{"id":"a","label":"A"},{"id":"b","label":"B"}]}]}"""),
        )!!.controls.single()
        assertEquals("b", chosen.choice)
    }

    @Test
    fun `the fifth option that applies (Von Hand) is shown as chosen, eight options are kept`() {
        val manual = QuickActionWidgetItem.from(
            widget(
                """{"controls":[{"id":"preset","kind":"choice","value":"manual","options":[
                  {"id":"comfort","label":"Komfort"},{"id":"eco","label":"Eco"},{"id":"night","label":"Nacht"},
                  {"id":"frost","label":"Frost"},{"id":"manual","label":"Von Hand"}]}]}""",
            ),
        )!!.controls.single()
        assertEquals("manual", manual.choice)
        val options = (1..8).joinToString(",") { """{"id":"o$it","label":"O$it"}""" }
        val eight = QuickActionWidgetItem.from(widget("""{"controls":[{"id":"m","kind":"choice","value":null,"options":[$options]}]}"""))!!
        assertEquals(8, eight.controls.single().options.size)
    }

    @Test
    fun `only a JSON true disables a control`() {
        fun disabledOf(v: String) = QuickActionWidgetItem.from(
            widget("""{"controls":[{"id":"sp","kind":"stepper","value":21,"min":5,"max":30,"step":0.5,"disabled":$v}]}"""),
        )!!.controls.single().disabled
        assertTrue(disabledOf("true"))
        assertFalse(disabledOf("false"))
        assertFalse(disabledOf("1"))
        assertFalse(disabledOf("\"true\""))
        assertFalse(disabledOf("null"))
        val choice = QuickActionWidgetItem.from(
            widget("""{"controls":[{"id":"p","kind":"choice","value":"a","disabled":true,"options":[{"id":"a","label":"A"},{"id":"b","label":"B"}]}]}"""),
        )!!.controls.single()
        assertTrue(choice.disabled)
        assertFalse(room().widget!!.controls.any { it.disabled })
    }

    @Test
    fun `a target in half degrees keeps its decimal`() {
        val sp = room().widget!!.controls.single { it.id == "sp" }
        assertEquals(0.5, sp.step)
        assertEquals("21,0 °C", sp.format(21.0, Locale.GERMANY))
        assertEquals("21,5 °C", sp.format(21.5, Locale.GERMANY))
        assertEquals("21.5 °C", sp.format(21.5, Locale.US))
        val whole = QuickActionControlItem("v", QuickActionControlItem.KIND_SLIDER, number = 40.0, min = 0.0, max = 100.0, step = 1.0, unit = "%")
        assertEquals("40 %", whole.format(40.0, Locale.GERMANY))
    }

    @Test
    fun `the day bar comes through with colours, tones, now and the axis`() {
        val tl = room().widget!!.timeline!!
        assertEquals(0.0, tl.from)
        assertEquals(24.0, tl.to)
        assertEquals(5, tl.segments.size)
        assertEquals("#f59e0b", tl.segments[1].color) // lower case
        assertEquals(0xFFF59E0B.toInt(), tl.segments[1].argb)
        assertEquals(0xFFFF9900.toInt(), tl.segments[3].argb) // #rgb
        assertNull(tl.segments[0].argb)
        assertEquals("neutral", tl.segments[0].tone)
        assertNull(tl.segments[4].label)
        assertEquals(listOf("0", "6", "12", "18", "24"), tl.ticks())
        assertEquals("6:52", tl.positionText(tl.now!!))
        assertEquals(0.25f, tl.fraction(6.0))
        assertEquals(
            listOf("0:00–6:00 Nacht", "6:00–8:00 Komfort", "8:00–16:00 Eco", "16:00–22:30 Komfort", "22:30–24:00"),
            tl.spokenSegments(),
        )
    }

    @Test
    fun `defaults, no now and gaps are fine`() {
        val tl = QuickActionTimelineItem.from(JsonParser.parseString("""{"segments":[{"from":7,"to":9,"tone":"warn"},{"from":17,"to":22}]}"""))!!
        assertEquals(0.0, tl.from)
        assertEquals(24.0, tl.to)
        assertNull(tl.now)
        assertEquals(2, tl.segments.size)
    }

    @Test
    fun `an invalid timeline is dropped, the widget stays`() {
        val bad = listOf(
            """{"segments":[]}""",
            """{"segments":[{"from":8,"to":6}]}""", // from ≥ to
            """{"segments":[{"from":0,"to":8},{"from":6,"to":10}]}""", // overlapping
            """{"segments":[{"from":10,"to":12},{"from":0,"to":8}]}""", // out of order
            """{"segments":[{"from":0,"to":25}]}""", // outside the range
            """{"now":25,"segments":[{"from":0,"to":8}]}""", // now outside
            """{"from":10,"to":10,"segments":[{"from":10,"to":10}]}""",
            """{"segments":[{"from":0,"to":8,"tone":"blue"}]}""",
            """{"segments":[{"from":0,"to":8,"color":"red"}]}""",
            """{"segments":[{"from":0,"to":8,"color":"#ff000080"}]}""",
            """{"segments":[{"from":0,"to":8,"label":"   "}]}""",
            """{"segments":[{"from":0,"to":8,"label":"ein viel zu langer Name"}]}""",
            """{"segments":[{"from":"0","to":8}]}""",
            """{"segments":[${(0 until 13).joinToString(",") { """{"from":$it,"to":${it + 1}}""" }}]}""", // 13
            """[1,2]""",
        )
        bad.forEach { json ->
            assertNull(QuickActionTimelineItem.from(JsonParser.parseString(json)), json)
            val w = QuickActionWidgetItem.from(widget("""{"status":"Komfort","timeline":$json}"""))
            assertNotNull(w, json)
            assertNull(w!!.timeline, json)
        }
        val twelve = (0 until 12).joinToString(",") { """{"from":${it * 2},"to":${it * 2 + 2}}""" }
        assertEquals(12, QuickActionTimelineItem.from(JsonParser.parseString("""{"segments":[$twelve]}"""))!!.segments.size)
    }

    @Test
    fun `a widget with only a timeline is kept, a non-day range is spoken as numbers`() {
        val w = QuickActionWidgetItem.from(widget("""{"timeline":{"from":0,"to":100,"segments":[{"from":0,"to":37.5,"label":"Voll"}]}}"""))!!
        val tl = w.timeline!!
        assertFalse(tl.isDay)
        assertEquals(listOf("0", "25", "50", "75", "100"), tl.ticks().map { it.replace(',', '.') })
        assertEquals("0–37" + java.text.DecimalFormatSymbols.getInstance().decimalSeparator + "5 Voll", tl.spokenSegments().single())
    }

    @Test
    fun `the card offers the day bar as a part after the status, with the boost and plan buttons`() {
        val boost = QuickActionItem(plugin = "gatecontrol-smarthome", pluginName = "Smart Home", id = "heat:4:boost", title = "Boost beenden", deviceId = "heat:4", deviceTitle = "Leseecke")
        val plan = QuickActionItem(plugin = "gatecontrol-smarthome", pluginName = "Smart Home", id = "heat:4:plan", title = "Zurück zum Plan", deviceId = "heat:4", deviceTitle = "Leseecke")
        val device = QuickActionDevices.group(listOf(room(), boost, plan)).single()
        assertNull(device.powerAction)
        val ids = device.parts().map { it.id }
        assertEquals(
            listOf(
                WidgetPart.VALUE, WidgetPart.STATUS, WidgetPart.TIMELINE, WidgetPart.control("sp"), WidgetPart.control("preset"),
                WidgetPart.action("heat:4"), WidgetPart.action("heat:4:boost"), WidgetPart.action("heat:4:plan"),
            ),
            ids,
        )
        // A widget pinned before the plugin had a bar shows it once it comes (a new part).
        val pin = StartWidgetPin(device = device.key, parts = listOf(WidgetPart.VALUE, WidgetPart.STATUS), known = listOf(WidgetPart.VALUE, WidgetPart.STATUS))
        assertTrue(WidgetPart.TIMELINE in pin.shownParts(device).map { it.id })
    }

    @Test
    fun `timeline, empty choice and disabled survive the stored pins`() {
        val item = room().let { r ->
            r.copy(widget = r.widget!!.copy(controls = r.widget!!.controls.map { it.copy(disabled = it.id == "sp") }))
        }
        val back = QuickActionRepository.decodePins(QuickActionRepository.encodePins(listOf(item))).single()
        assertEquals(item.widget, back.widget)
        assertNull(back.widget!!.controls.single { it.id == "preset" }.choice)
        assertTrue(back.widget!!.controls.single { it.id == "sp" }.disabled)
        // A pin stored before 1.318 has neither field.
        val old = Gson().toJson(listOf(item.copy(widget = item.widget!!.copy(timeline = null))))
            .replace(",\"disabled\":true", "").replace(",\"disabled\":false", "")
        val legacy = QuickActionRepository.decodePins(old).single()
        assertNull(legacy.widget!!.timeline)
        assertFalse(legacy.widget!!.controls.any { it.disabled })
    }

    // --- 409 control_disabled -----------------------------------------------------------------

    private val stored = MutableStateFlow<String?>(null)
    private val settings = mockk<SettingsRepository> {
        every { getQuickActionPins() } returns stored
        every { getStartWidgets() } returns MutableStateFlow(null)
        every { getLocale() } returns kotlinx.coroutines.flow.flowOf("de")
        coEvery { updateQuickActionPins(any()) } coAnswers { stored.value = firstArg<(String?) -> String>()(stored.value) }
    }
    private val setup = mockk<SetupRepository> { every { getServerUrl() } returns "https://gc.example.test" }
    private val api = mockk<ApiClient>()
    private val provider = mockk<ApiClientProvider> { every { getClient(any()) } returns api }

    @Test
    fun `a run of a disabled control gets its own reason`() = runTest {
        coEvery { api.runQuickAction("gatecontrol-smarthome", "heat:4", "de", QuickActionRunRequest("app", "sp", 21.5)) } returns
            Response.error(409, """{"ok":false,"error":"control_disabled"}""".toResponseBody(null))
        coEvery { api.getQuickActions(any()) } returns Response.success(QuickActionsResponse(ok = true, actions = emptyList()))
        val result = QuickActionRepository(mockk<Context>(relaxed = true), setup, provider, settings).control(room(), "sp", 21.5)
        assertFalse(result.ok)
        assertNull(result.message)
        assertEquals(QuickActionResult.CONTROL_DISABLED, result.reason)
    }

    @Test
    fun `control_disabled only counts with 409`() {
        assertEquals(QuickActionResult.CONTROL_DISABLED, QuickActionResult.reasonOf(409, "control_disabled"))
        assertNull(QuickActionResult.reasonOf(409, "state_unknown"))
        assertNull(QuickActionResult.reasonOf(409))
        assertEquals(QuickActionResult.GONE, QuickActionResult.reasonOf(404, "not_found"))
    }
}
