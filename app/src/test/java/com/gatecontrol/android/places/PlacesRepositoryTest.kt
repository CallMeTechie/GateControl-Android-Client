package com.gatecontrol.android.places

import com.gatecontrol.android.data.EncryptedStorage
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.familie.PluginDirectory
import com.gatecontrol.android.network.ApiClient
import com.gatecontrol.android.network.ApiClientProvider
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import retrofit2.Response

/** „Meine Orte“ against the server: the copy for offline, older servers, changes, „wird noch genutzt“, the search. */
class PlacesRepositoryTest {

    private val stored = mutableMapOf<String, String>()
    private val secure = mockk<EncryptedStorage> {
        every { getString(any(), any()) } answers { stored[firstArg()] ?: secondArg() }
        every { putString(any(), any()) } answers { stored[firstArg()] = secondArg() }
        every { remove(any()) } answers { stored.remove(firstArg<String>()); Unit }
    }
    private val setup = mockk<SetupRepository> {
        every { getServerUrl() } returns "https://gc.example.com"
        every { getApiToken() } returns "token"
        every { isConfigured() } returns true
        every { revision } returns MutableStateFlow(0L)
    }
    private val api = mockk<ApiClient>()
    private val provider = mockk<ApiClientProvider> { every { getClient(any()) } returns api }
    private fun repo() = PlacesRepository(setup, provider, secure)
    private fun json(s: String): JsonObject = JsonParser.parseString(s).asJsonObject
    private fun error(code: Int, body: String) = Response.error<JsonObject>(code, body.toResponseBody("application/json".toMediaType()))
    private val owner = PluginDirectory.ownerKey("https://gc.example.com", "token")

    private val list = """{"ok":true,"family":false,"search":"osm","places":[
        {"id":12,"name":"Arbeit","icon":"work","lat":50.110922,"lon":8.682127,"radiusM":200,"visibility":"self"}]}"""

    @Test
    fun `the list is loaded and kept for this login`() = runTest {
        coEvery { api.getMyPlaces() } returns Response.success(json(list))
        val state = repo().refresh()
        assertEquals(PlacesSupport.SUPPORTED, state.support)
        assertEquals(listOf("Arbeit"), state.places.map { it.name })
        val cached = MyPlaces.decodeCache(stored.values.single(), owner)
        assertEquals(listOf(12L), cached?.places?.map { it.id })
    }

    @Test
    fun `without a network the copy stays and says so`() = runTest {
        stored["profile_places_v1"] = MyPlaces.encodeCache(MyPlaces.list(json(list))!!, owner)
        coEvery { api.getMyPlaces() } throws java.io.IOException("offline")
        val state = repo().refresh()
        assertEquals(listOf("Arbeit"), state.places.map { it.name })
        assertTrue(state.offline)
    }

    @Test
    fun `an older server hides the page, a token without a user too`() = runTest {
        coEvery { api.getMyPlaces() } returns error(404, "{}")
        assertEquals(PlacesSupport.UNSUPPORTED, repo().refresh().support)
        coEvery { api.getMyPlaces() } returns error(403, """{"ok":false,"code":"NO_USER"}""")
        assertEquals(PlacesSupport.NO_USER, repo().refresh().support)
        assertTrue(stored.isEmpty())
    }

    @Test
    fun `a new place goes into the copy at once`() = runTest {
        coEvery { api.getMyPlaces() } returns Response.success(json(list))
        val body = slot<RequestBody>()
        coEvery { api.addMyPlace(capture(body)) } returns Response.success(
            201,
            json("""{"ok":true,"place":{"id":13,"name":"Zuhause","icon":"home","lat":50.05,"lon":8.6,"radiusM":150,"visibility":"family"}}"""),
        )
        val r = repo()
        r.refresh()
        val done = r.create(PlaceDraft(name = "Zuhause", icon = "home", lat = 50.05, lon = 8.6, visibility = "family"))
        assertInstanceOf(PlaceEdit.Done::class.java, done)
        assertEquals(listOf("Arbeit", "Zuhause"), r.state.value.places.map { it.name })
        val sent = Buffer().also { body.captured.writeTo(it) }.readUtf8()
        assertTrue(sent.contains("\"visibility\":\"family\""), sent)
        assertEquals(2, MyPlaces.decodeCache(stored.values.single(), owner)?.places?.size)
    }

    @Test
    fun `without a point nothing is sent`() = runTest {
        val r = repo().create(PlaceDraft(name = "Arbeit"))
        assertEquals(PlaceEdit.Refused("INVALID", "lat"), r)
        coVerify(exactly = 0) { api.addMyPlace(any()) }
    }

    @Test
    fun `a name that exists is refused with its field`() = runTest {
        coEvery { api.addMyPlace(any()) } returns error(409, """{"ok":false,"code":"DUPLICATE","field":"name","error":"Den Namen gibt es schon."}""")
        val r = repo().create(PlaceDraft(name = "Arbeit", lat = 50.0, lon = 8.0)) as PlaceEdit.Refused
        assertEquals("DUPLICATE", r.code)
        assertEquals("name", r.field)
        assertEquals("Den Namen gibt es schon.", r.message)
    }

    @Test
    fun `deleting a place in use names who uses it, force deletes it`() = runTest {
        coEvery { api.getMyPlaces() } returns Response.success(json(list))
        coEvery { api.deleteMyPlace(12, null) } returns error(
            409,
            """{"ok":false,"code":"IN_USE","error":"Wird noch genutzt","usedBy":[{"plugin":"gatecontrol-messenger","pluginName":"Messenger","kind":"location_message","count":2}]}""",
        )
        coEvery { api.deleteMyPlace(12, 1) } returns Response.success(json("""{"ok":true,"deleted":true,"id":12}"""))
        val r = repo()
        r.refresh()
        val refused = r.delete(12) as PlaceEdit.Refused
        assertEquals("IN_USE", refused.code)
        assertEquals(2, refused.usedBy.single().count)
        assertEquals(1, r.state.value.places.size)
        assertInstanceOf(PlaceEdit.Done::class.java, r.delete(12, force = true))
        assertTrue(r.state.value.places.isEmpty())
    }

    @Test
    fun `a change that changes nothing is not sent`() = runTest {
        coEvery { api.getMyPlaces() } returns Response.success(json(list))
        val r = repo()
        r.refresh()
        val p = r.state.value.places.single()
        assertEquals(PlaceEdit.Done(p), r.update(p, MyPlaces.draftOf(p)))
        coVerify(exactly = 0) { api.patchMyPlace(any(), any()) }
    }

    @Test
    fun `the search sends a rounded position, and its refusals`() = runTest {
        coEvery { api.getMyPlaces() } returns Response.success(json(list))
        coEvery { api.searchPlaces("Bauhaus", "50.11,8.68") } returns Response.success(
            json("""{"ok":true,"results":[{"name":"Bauhaus","address":"Industriestraße 20, Musterstadt","lat":50.1,"lon":8.7,"source":"osm"}],"attribution":{"text":"© OpenStreetMap","url":"https://www.openstreetmap.org/copyright"}}"""),
        )
        val r = repo()
        r.refresh()
        val hits = r.search(" Bauhaus ", 50.110922 to 8.682127) as PlaceSearch.Hits
        assertEquals("Bauhaus", hits.hits.single().name)
        assertEquals("© OpenStreetMap", hits.attribution)
        // too short: not asked
        assertEquals(PlaceSearch.Refused("INVALID"), r.search("ba", null))
        coEvery { api.searchPlaces("Kanal", null) } returns error(429, """{"ok":false,"code":"RATE_LIMITED","retryAfter":120}""")
        assertEquals(PlaceSearch.Refused("RATE_LIMITED", 120), r.search("Kanal", null))
        coEvery { api.searchPlaces("Linden", null) } returns error(403, """{"ok":false,"code":"SEARCH_OFF"}""")
        assertEquals(PlaceSearch.Refused("SEARCH_OFF"), r.search("Linden", null))
    }

    @Test
    fun `with the search switched off on the server the phone does not ask`() = runTest {
        coEvery { api.getMyPlaces() } returns Response.success(json(list.replace("\"osm\"", "\"off\"")))
        val r = repo()
        r.refresh()
        assertEquals(PlaceSearch.Refused("SEARCH_OFF"), r.search("Lindenweg", null))
        coVerify(exactly = 0) { api.searchPlaces(any(), any()) }
    }

    @Test
    fun `refusals without a code get one from the status`() {
        assertEquals("NOT_FOUND", PlacesRepository.refusal(404, null).code)
        assertEquals("RATE_LIMITED", PlacesRepository.refusal(429, null).code)
        assertEquals("IN_USE", PlacesRepository.refusal(409, json("""{"usedBy":[]}""")).code)
        assertNull(PlacesRepository.refusal(400, json("""{"code":"INVALID","field":"name!"}""")).field)
    }
}
