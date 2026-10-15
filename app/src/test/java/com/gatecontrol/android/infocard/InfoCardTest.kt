package com.gatecontrol.android.infocard

import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.network.ApiClient
import com.gatecontrol.android.network.ApiClientProvider
import com.gatecontrol.android.network.ClientTilesResponse
import com.google.gson.Gson
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import retrofit2.Response

class InfoCardTest {

    private val json = """
        {"ok":true,"tiles":[
          {"key":"gatecontrol-smarthome/Klima/Wohnzimmer","plugin":"gatecontrol-smarthome","pluginName":"Smart Home","section":"Klima","title":"Wohnzimmer","value":"21,5","unit":"°C","state":"good","icon":"M3 12h18"},
          {"key":"gatecontrol-skoda/Auto/Akku","plugin":"gatecontrol-skoda","pluginName":"Škoda","section":null,"title":"Akku","value":80,"unit":"%","state":"warn","icon":null},
          {"key":"gatecontrol-skoda/Auto/Tür","plugin":"gatecontrol-skoda","pluginName":"Škoda","section":"Auto","title":"Tür","value":null,"unit":null,"state":"bogus","icon":"<script>"},
          {"key":"","plugin":"p","title":"no key"},
          {"key":"x/y/z","plugin":"Bad Plugin!","title":"bad plugin"},
          {"key":"x/y/w","plugin":"p","title":"  "},
          {"key":"gatecontrol-smarthome/Klima/Wohnzimmer","plugin":"gatecontrol-smarthome","title":"duplicate"}
        ]}
    """.trimIndent()

    private fun parsed(): List<InfoTile> =
        InfoTile.sanitize(Gson().fromJson(json, ClientTilesResponse::class.java).tiles.orEmpty())

    private fun tilesState(vararg keys: String) = InfoTilesState(
        InfoTilesState.Status.LOADED,
        keys.map { InfoTile(it, "p", "P", null, it, "1", null, null, null) },
    )

    private fun id(key: String) = InfoSource.PluginTile(key).id

    @Test
    fun `tiles JSON is parsed, checked and capped`() {
        val tiles = parsed()
        assertEquals(
            listOf("gatecontrol-smarthome/Klima/Wohnzimmer", "gatecontrol-skoda/Auto/Akku", "gatecontrol-skoda/Auto/Tür"),
            tiles.map { it.key },
        )
        val climate = tiles[0]
        assertEquals("Smart Home", climate.pluginName)
        assertEquals("Klima", climate.section)
        assertEquals("21,5", climate.value)
        assertEquals("°C", climate.unit)
        assertEquals(TileState.GOOD, climate.state)
        assertEquals("M3 12h18", climate.icon)
        // A number is shown as text, a null section stays null.
        assertEquals("80", tiles[1].value)
        assertNull(tiles[1].section)
        assertEquals(TileState.WARN, tiles[1].state)
        // Unknown state and unusable icon are dropped, a missing value stays null.
        assertNull(tiles[2].state)
        assertNull(tiles[2].icon)
        assertNull(tiles[2].value)
    }

    @Test
    fun `source ids are generic and parse back`() {
        val source = InfoSource.PluginTile("a/b/c")
        assertEquals("tile:a/b/c", source.id)
        assertEquals(source, InfoSource.parse("tile:a/b/c"))
        assertNull(InfoSource.parse("tile:"))
        assertNull(InfoSource.parse("builtin:usage"))
        assertEquals(source, InfoSlide.Tile(tilesState("a/b/c").tiles.single()).source)
    }

    @Test
    fun `card is hidden on older servers and without tiles`() {
        val selection = InfoSelection(listOf(id("a")))
        assertEquals(InfoCardView.Hidden, InfoCardLogic.view(InfoTilesState(InfoTilesState.Status.UNSUPPORTED), selection))
        assertEquals(InfoCardView.Hidden, InfoCardLogic.view(InfoTilesState(InfoTilesState.Status.LOADED), selection))
        assertEquals(InfoCardView.Hidden, InfoCardLogic.view(InfoTilesState(), selection))
        assertEquals(InfoCardView.Hidden, InfoCardLogic.view(InfoTilesState(InfoTilesState.Status.FAILED), InfoSelection()))
    }

    @Test
    fun `nothing chosen shows only the choose button`() {
        assertEquals(InfoCardView.Choose, InfoCardLogic.view(tilesState("a", "b"), InfoSelection()))
        // Every chosen tile vanished: back to choosing.
        assertEquals(InfoCardView.Choose, InfoCardLogic.view(tilesState("a", "b"), InfoSelection(listOf(id("gone")))))
    }

    @Test
    fun `chosen slides keep their order, vanished ones are skipped, favourite first`() {
        val state = tilesState("a", "b", "c")
        val view = InfoCardLogic.view(state, InfoSelection(listOf(id("c"), id("gone"), id("a")), favorite = id("a")))
        view as InfoCardView.Slides
        assertEquals(listOf(id("c"), id("a")), view.slides.map { it.id })
        assertEquals(1, view.initialPage)
        // A vanished favourite: the first slide.
        val noFav = InfoCardLogic.view(state, InfoSelection(listOf(id("c"), id("a")), favorite = id("gone"))) as InfoCardView.Slides
        assertEquals(0, noFav.initialPage)
        // A failed refresh keeps the last tiles: the card stays.
        val failed = InfoCardLogic.view(state.copy(status = InfoTilesState.Status.FAILED), InfoSelection(listOf(id("b"))))
        assertTrue(failed is InfoCardView.Slides)
    }

    @Test
    fun `toggle, move and favourite`() {
        var s = InfoSelection()
        s = InfoCardLogic.toggle(s, id("a"))
        s = InfoCardLogic.toggle(s, id("b"))
        s = InfoCardLogic.toggle(s, id("c"))
        assertEquals(listOf(id("a"), id("b"), id("c")), s.ids)
        s = InfoCardLogic.favorite(s, id("b"))
        assertEquals(id("b"), s.favorite)
        // Not chosen: no favourite.
        assertEquals(s, InfoCardLogic.favorite(s, id("x")))
        s = InfoCardLogic.move(s, id("c"), -1, s.ids)
        assertEquals(listOf(id("a"), id("c"), id("b")), s.ids)
        s = InfoCardLogic.move(s, id("a"), -1, s.ids)
        assertEquals(listOf(id("a"), id("c"), id("b")), s.ids)
        s = InfoCardLogic.move(s, id("a"), 5, s.ids)
        assertEquals(listOf(id("c"), id("b"), id("a")), s.ids)
        // Removing the favourite clears it.
        s = InfoCardLogic.toggle(s, id("b"))
        assertEquals(listOf(id("c"), id("a")), s.ids)
        assertNull(s.favorite)
    }

    @Test
    fun `moving among visible ones keeps vanished choices stored`() {
        val s = InfoSelection(listOf(id("a"), id("gone"), id("b")))
        val moved = InfoCardLogic.move(s, id("b"), -1, visible = listOf(id("a"), id("b")))
        assertEquals(listOf(id("b"), id("a"), id("gone")), moved.ids)
    }

    @Test
    fun `at most MAX_SELECTED are chosen`() {
        var s = InfoSelection()
        repeat(InfoCardLogic.MAX_SELECTED + 3) { s = InfoCardLogic.toggle(s, id("t$it")) }
        assertEquals(InfoCardLogic.MAX_SELECTED, s.ids.size)
    }

    @Test
    fun `selection is stored per login`() {
        val a = InfoSelection(listOf(id("x"), id("y")), favorite = id("y"))
        val b = InfoSelection(listOf(id("z")))
        var raw = InfoCardStore.write(null, "ownerA", a)
        raw = InfoCardStore.write(raw, "ownerB", b)
        assertEquals(a, InfoCardStore.read(raw, "ownerA"))
        assertEquals(b, InfoCardStore.read(raw, "ownerB"))
        assertEquals(InfoSelection(), InfoCardStore.read(raw, "ownerC"))
        assertEquals(InfoSelection(), InfoCardStore.read(raw, ""))
        assertEquals(InfoSelection(), InfoCardStore.read("not json", "ownerA"))
        // A favourite that is not chosen is not stored.
        val raw2 = InfoCardStore.write(null, "o", InfoSelection(listOf(id("x")), favorite = id("q")))
        assertNull(InfoCardStore.read(raw2, "o").favorite)
    }

    @Test
    fun `only the most recent logins are kept`() {
        var raw: String? = null
        repeat(10) { raw = InfoCardStore.write(raw, "o$it", InfoSelection(listOf(id("t$it")))) }
        assertEquals(InfoSelection(), InfoCardStore.read(raw, "o0"))
        assertEquals(InfoSelection(), InfoCardStore.read(raw, "o1"))
        assertEquals(listOf(id("t9")), InfoCardStore.read(raw, "o9").ids)
        assertEquals(listOf(id("t2")), InfoCardStore.read(raw, "o2").ids)
    }

    // ─── Repository ───────────────────────────────────────────────────

    private val stored = MutableStateFlow<String?>(null)
    private val settings = mockk<SettingsRepository> {
        every { getInfoCard() } returns stored
        every { getLocale() } returns flowOf("en")
        coEvery { updateInfoCard(any()) } coAnswers {
            stored.value = firstArg<(String?) -> String>()(stored.value)
        }
    }
    private val revision = MutableStateFlow(0L)
    private var token = "token-1"
    private val setup = mockk<SetupRepository> {
        every { getServerUrl() } returns "https://gc.example.com"
        every { getApiToken() } answers { token }
        every { this@mockk.revision } returns this@InfoCardTest.revision
    }
    private val api = mockk<ApiClient>()
    private val provider = mockk<ApiClientProvider> { every { getClient(any()) } returns api }

    @Test
    fun `older server is unsupported, a failure keeps the last tiles`() = runTest {
        val repo = InfoCardRepository(setup, provider, settings)
        coEvery { api.getClientTiles("en") } returns Response.success(Gson().fromJson(json, ClientTilesResponse::class.java))
        assertEquals(3, repo.refresh().tiles.size)
        coEvery { api.getClientTiles(any()) } throws java.io.IOException("offline")
        val failed = repo.refresh()
        assertEquals(InfoTilesState.Status.FAILED, failed.status)
        assertEquals(3, failed.tiles.size)
        coEvery { api.getClientTiles(any()) } returns Response.error(404, "".toResponseBody())
        assertEquals(InfoTilesState(InfoTilesState.Status.UNSUPPORTED), repo.refresh())
        coEvery { api.getClientTiles(any()) } returns Response.success(ClientTilesResponse(ok = true, tiles = emptyList()))
        val empty = repo.refresh()
        assertEquals(InfoCardView.Hidden, InfoCardLogic.view(empty, InfoSelection()))
    }

    @Test
    fun `choice follows the login`() = runTest {
        val repo = InfoCardRepository(setup, provider, settings)
        repo.update { InfoCardLogic.toggle(it, id("a")) }
        repo.update { InfoCardLogic.favorite(it, id("a")) }
        assertEquals(InfoSelection(listOf(id("a")), id("a")), repo.selection.first())
        token = "token-2"
        revision.value += 1
        assertEquals(InfoSelection(), repo.selection.first())
        token = "token-1"
        revision.value += 1
        assertEquals(listOf(id("a")), repo.selection.first().ids)
    }
}
