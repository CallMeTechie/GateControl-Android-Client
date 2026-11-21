package com.gatecontrol.android.quickactions

import android.content.Context
import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.network.ApiClient
import com.gatecontrol.android.network.ApiClientProvider
import com.gatecontrol.android.network.QuickActionRunRequest
import com.gatecontrol.android.network.QuickActionRunResponse
import com.gatecontrol.android.network.QuickActionsResponse
import com.google.gson.Gson
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

/**
 * The logic chains of Smart Home 1.12.0 as quick actions (a button `chain:<id>` each, the
 * shared device `chains` without a real device, before the devices) and a widget's badge
 * (a car's battery), from an example list as the server sends it (names made up).
 */
class QuickActionChainsTest {

    private val chainIcon = "M6 3v12M18 9c0 7-12 5-12 9M9 18a3 3 0 1 1-6 0 3 3 0 0 1 6 0zM21 6a3 3 0 1 1-6 0 3 3 0 0 1 6 0z"
    private val batteryIcon = "M3 7h15v10H3zM21 10v4"

    private val payload = """
        {"ok":true,"actions":[
          {"plugin":"gatecontrol-smarthome","pluginName":"Smart Home","id":"chain:7","title":"Abendlicht","subtitle":"Kette · Gartenhaus",
           "icon":"$chainIcon","kind":"button","device":{"id":"chains","title":"Logikketten","icon":"$chainIcon"}},
          {"plugin":"gatecontrol-smarthome","pluginName":"Smart Home","id":"chain:12","title":"Urlaubsmodus","subtitle":"Kette · über Server",
           "icon":"$chainIcon","kind":"button","device":{"id":"chains","title":"Logikketten","icon":"$chainIcon"}},
          {"plugin":"gatecontrol-smarthome","pluginName":"Smart Home","id":"light:3","title":"Stehlampe","kind":"toggle","state":"off"},
          {"plugin":"gatecontrol-skoda","pluginName":"Fahrzeuge","id":"lock:1","title":"Verriegeln","kind":"toggle","state":"on",
           "device":{"id":"car:1","title":"Familienauto"},
           "widget":{"status":"Geparkt","power":false,"badge":{"icon":"$batteryIcon","value":78,"unit":"%","state":"warn","label":"Akku"}}}
        ]}
    """.trimIndent()

    private fun items() = QuickActionRepository.sanitize(Gson().fromJson(payload, QuickActionsResponse::class.java).actions.orEmpty())

    @Test
    fun `chains come first as one device of their own, with their subtitles`() {
        val devices = QuickActionDevices.group(items())
        assertEquals(listOf("gatecontrol-smarthome/chains", "gatecontrol-smarthome/@light:3", "gatecontrol-skoda/car:1"), devices.map { it.key })
        val chains = devices.first()
        assertEquals("Logikketten", chains.title)
        assertEquals(chainIcon, chains.icon)
        assertNotNull(QuickActionIcons.nodes(chains.icon), "the chain icon (with arcs) is drawable")
        assertEquals(listOf("Abendlicht", "Urlaubsmodus"), chains.actions.map { it.title })
        assertEquals(listOf("Kette · Gartenhaus", "Kette · über Server"), chains.actions.map { it.subtitle })
        assertTrue(chains.actions.none { it.toggle || it.needsConfirm })
        assertNull(chains.widget, "chains are buttons, no widget")
        assertEquals(listOf(WidgetPart.Kind.ACTION, WidgetPart.Kind.ACTION), chains.parts().map { it.kind })
    }

    @Test
    fun `all sixteen actions a plugin may list are kept in the server's order`() {
        val raw = Gson().fromJson(payload, QuickActionsResponse::class.java).actions.orEmpty()
        val many = (1..16).map { raw.first().copy(id = "chain:$it", title = "Kette $it") }
        assertEquals((1..16).map { "chain:$it" }, QuickActionRepository.sanitize(many).map { it.id })
    }

    @Test
    fun `the badge reads as icon, value with unit and tone`() {
        val car = items().first { it.plugin == "gatecontrol-skoda" }
        val badge = QuickActionBadgeView.of(car)!!
        assertEquals(batteryIcon, badge.icon)
        assertEquals("78 %", badge.text)
        assertEquals("warn", badge.tone)
        assertEquals("Akku: 78 %", badge.description)
        assertNull(QuickActionBadgeView.of(items().first()), "a chain has no badge")
    }

    @Test
    fun `a badge without value or state shows only the icon in the quiet colour`() {
        val badge = QuickActionBadgeView.of(QuickActionReadingItem("badge", "", icon = batteryIcon, state = "off"))!!
        assertNull(badge.text)
        assertNull(badge.tone)
        assertEquals("", badge.description)
        assertNull(QuickActionBadgeView.of(QuickActionReadingItem("badge", "Akku", value = "50", icon = "not a path")))
    }

    @Test
    fun `a badge survives storing the pin`() {
        val car = items().first { it.plugin == "gatecontrol-skoda" }
        val back = QuickActionRepository.decodePins(QuickActionRepository.encodePins(listOf(car))).single()
        assertEquals("78 %", QuickActionBadgeView.of(back)?.text)
    }

    // ── running a chain ──

    private val stored = MutableStateFlow<String?>(null)
    private val settings = mockk<SettingsRepository> {
        every { getQuickActionPins() } returns stored
        every { getStartWidgets() } returns MutableStateFlow<String?>(null)
        every { getLocale() } returns kotlinx.coroutines.flow.flowOf("de")
        coEvery { updateQuickActionPins(any()) } coAnswers { stored.value = firstArg<(String?) -> String>()(stored.value) }
        coEvery { updateStartWidgets(any()) } returns Unit
    }
    private val setup = mockk<SetupRepository> { every { getServerUrl() } returns "https://gc.example.com" }
    private val api = mockk<ApiClient> {
        coEvery { getQuickActions(any()) } returns Response.success(QuickActionsResponse(ok = true, actions = emptyList()))
    }
    private val provider = mockk<ApiClientProvider> { every { getClient(any()) } returns api }
    private fun repo() = QuickActionRepository(mockk<Context>(relaxed = true), setup, provider, settings)
    private fun chain() = items().first()

    @Test
    fun `a started chain answers with its text`() = runTest {
        coEvery { api.runQuickAction("gatecontrol-smarthome", "chain:7", "de", QuickActionRunRequest("app")) } returns
            Response.success(QuickActionRunResponse(ok = true, message = "„Abendlicht“ gestartet"))
        val result = repo().run(chain())
        assertTrue(result.ok)
        assertEquals("„Abendlicht“ gestartet", result.message)
    }

    @Test
    fun `a chain whose Nur wenn does not hold is not ok and says why`() = runTest {
        coEvery { api.runQuickAction(any(), any(), any(), any()) } returns
            Response.success(QuickActionRunResponse(ok = false, message = "„Abendlicht“ nicht gestartet – „Nur wenn“ gilt gerade nicht"))
        val result = repo().run(chain())
        assertFalse(result.ok)
        assertEquals("„Abendlicht“ nicht gestartet – „Nur wenn“ gilt gerade nicht", result.message)
        assertNull(result.reason)
    }

    @Test
    fun `a chain no longer listed is gone, a token without owner says so`() = runTest {
        coEvery { api.runQuickAction(any(), "chain:7", any(), any()) } returns Response.error(404, """{"ok":false,"error":"not_found"}""".toResponseBody(null))
        coEvery { api.runQuickAction(any(), "chain:12", any(), any()) } returns Response.error(403, """{"ok":false,"error":"no_user"}""".toResponseBody(null))
        val r = repo()
        val gone = r.run(chain())
        assertFalse(gone.ok)
        assertNull(gone.message)
        assertEquals(QuickActionResult.GONE, gone.reason)
        assertEquals(QuickActionResult.NO_USER, r.run(items()[1]).reason)
    }

    @Test
    fun `reasons for the statuses without a text of the plugin`() {
        assertEquals(QuickActionResult.GONE, QuickActionResult.reasonOf(404))
        assertEquals(QuickActionResult.NO_USER, QuickActionResult.reasonOf(403))
        assertEquals(QuickActionResult.BUSY, QuickActionResult.reasonOf(429))
        assertEquals(QuickActionResult.TIMEOUT, QuickActionResult.reasonOf(504))
        assertNull(QuickActionResult.reasonOf(502))
    }
}
