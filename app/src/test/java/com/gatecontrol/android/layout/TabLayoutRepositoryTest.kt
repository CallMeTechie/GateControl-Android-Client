package com.gatecontrol.android.layout

import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.network.ApiClient
import com.gatecontrol.android.network.ApiClientProvider
import com.gatecontrol.android.network.ClientLayoutDto
import com.gatecontrol.android.network.ClientLayoutPutResponse
import com.gatecontrol.android.network.ClientLayoutRequest
import com.gatecontrol.android.network.ClientLayoutResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import retrofit2.Response

class TabLayoutRepositoryTest {

    private val stored = MutableStateFlow<String?>(null)
    private val settings = mockk<SettingsRepository> {
        every { getTabLayout() } returns stored
        coEvery { updateTabLayout(any()) } coAnswers {
            val transform = firstArg<(String?) -> String>()
            stored.value = transform(stored.value)
        }
    }
    private val setup = mockk<SetupRepository> {
        every { getServerUrl() } returns "https://gc.example.com"
        every { getApiToken() } returns "token"
    }
    private val api = mockk<ApiClient>()
    private val provider = mockk<ApiClientProvider> { every { getClient(any()) } returns api }
    private val sent = slot<ClientLayoutRequest>()

    private fun repo() = TabLayoutRepository(setup, provider, settings)
    private fun state() = StoredTabLayout.decode(stored.value)
    private fun <T> error(code: Int): Response<T> = Response.error(code, "{}".toResponseBody())

    private fun serves(scope: String, editable: Boolean = true, layout: ClientLayoutDto? = null) {
        coEvery { api.getClientLayout() } returns Response.success(ClientLayoutResponse(ok = true, scope = scope, editable = editable, layout = layout))
    }

    @Test
    fun `an older server keeps the order on the device`() = runTest {
        coEvery { api.getClientLayout() } returns error(404)
        val repo = repo()
        repo.saveStart(listOf("usage", "info"))
        repo.refresh(force = true)
        assertFalse(state().access.server)
        assertEquals(listOf("usage", "info"), state().layout.start)
        coVerify(exactly = 0) { api.putClientLayout(any()) }
    }

    @Test
    fun `the account's order replaces the device's`() = runTest {
        serves("user", layout = ClientLayoutDto(start = listOf("info", "widget:p/d"), familie = listOf("tasks", "members"), familieHidden = listOf("board")))
        stored.value = StoredTabLayout.encode(StoredTabLayout(TabLayout(start = listOf("usage")), owner = null))
        repo().refresh(force = true)
        val s = state()
        assertEquals(listOf("info", "widget:p/d"), s.layout.start)
        assertEquals(listOf("board"), s.layout.familieHidden)
        assertTrue(s.access.shared)
        assertFalse(s.pending)
    }

    @Test
    fun `with no order on the server yet the device's goes up`() = runTest {
        serves("user", layout = null)
        coEvery { api.putClientLayout(capture(sent)) } returns Response.success(ClientLayoutPutResponse(ok = true))
        val repo = repo()
        repo.saveFamilie(listOf("tasks", "members"), listOf("board"))
        repo.refresh(force = true)
        assertEquals(listOf("tasks", "members"), sent.captured.layout.familie)
        assertEquals(listOf("board"), sent.captured.layout.familieHidden)
        assertFalse(state().pending)
    }

    @Test
    fun `a change is sent with the entries only other devices have`() = runTest {
        serves("user", layout = ClientLayoutDto(start = listOf("tile:a/1", "widget:other/phone", "info", "usage")))
        coEvery { api.putClientLayout(capture(sent)) } returns Response.success(ClientLayoutPutResponse(ok = true))
        val repo = repo()
        repo.refresh(force = true)
        repo.saveStart(listOf("usage", "tile:a/1", "info"))
        assertEquals(listOf("usage", "tile:a/1", "widget:other/phone", "info"), sent.captured.layout.start)
        assertEquals(listOf("usage", "tile:a/1", "info"), state().layout.start)
        assertFalse(state().pending)
    }

    @Test
    fun `a failed send stays pending and goes first on the next refresh`() = runTest {
        serves("user", layout = ClientLayoutDto(start = listOf("info", "usage")))
        coEvery { api.putClientLayout(any()) } throws java.io.IOException("offline")
        val repo = repo()
        repo.refresh(force = true)
        repo.saveStart(listOf("usage", "info"))
        assertTrue(state().pending)
        coEvery { api.putClientLayout(capture(sent)) } returns Response.success(ClientLayoutPutResponse(ok = true))
        repo.refresh(force = true)
        assertEquals(listOf("usage", "info"), sent.captured.layout.start)
        assertEquals(listOf("usage", "info"), state().layout.start)
        assertFalse(state().pending)
    }

    @Test
    fun `device scope and a global order for admins only are respected`() = runTest {
        serves("device")
        val repo = repo()
        repo.refresh(force = true)
        repo.saveStart(listOf("usage", "info"))
        coVerify(exactly = 0) { api.putClientLayout(any()) }
        assertFalse(state().access.shared)

        serves("global", editable = false, layout = ClientLayoutDto(start = listOf("info", "usage")))
        repo.refresh(force = true)
        assertTrue(state().access.locked)
        assertEquals(listOf("info", "usage"), state().layout.start)
        repo.saveStart(listOf("usage", "info"))
        coVerify(exactly = 0) { api.putClientLayout(any()) }
        assertEquals(listOf("info", "usage"), state().layout.start)
    }

    @Test
    fun `409 device_scope keeps it on the device, 403 locks it`() = runTest {
        serves("user", layout = ClientLayoutDto(start = listOf("info", "usage")))
        coEvery { api.putClientLayout(any()) } returns error(409)
        val repo = repo()
        repo.refresh(force = true)
        repo.saveStart(listOf("usage", "info"))
        assertEquals(LayoutScope.DEVICE, state().access.scope)
        assertEquals(listOf("usage", "info"), state().layout.start)

        serves("global", editable = true, layout = ClientLayoutDto(start = listOf("info", "usage")))
        coEvery { api.putClientLayout(any()) } returns error(403)
        repo.refresh(force = true)
        repo.saveStart(listOf("usage", "info"))
        assertTrue(state().access.locked)
        assertEquals(listOf("info", "usage"), state().layout.start)
    }
}
