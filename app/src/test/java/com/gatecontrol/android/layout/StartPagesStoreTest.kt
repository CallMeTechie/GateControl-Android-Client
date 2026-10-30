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
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import retrofit2.Response

/** Pinning a page beside Start through the store, with a server that keeps the account's order. */
class StartPagesStoreTest {
    private val stored = MutableStateFlow<String?>(null)
    private val settings = mockk<SettingsRepository> {
        every { getTabLayout() } returns stored
        every { getHideBarOnlyStart() } returns flowOf(true)
        coEvery { updateTabLayout(any()) } coAnswers {
            val transform = firstArg<(String?) -> String>()
            stored.value = transform(stored.value)
        }
    }
    private val setup = mockk<SetupRepository> {
        every { getServerUrl() } returns "https://gc.example.com"
        every { getApiToken() } returns "token"
    }
    private var server: ClientLayoutDto? = ClientLayoutDto(start = listOf("tile:a/1", "widget:p/d", "info", "usage"))
    private val api = mockk<ApiClient> {
        coEvery { getClientLayout() } coAnswers { Response.success(ClientLayoutResponse(ok = true, scope = "user", editable = true, layout = server)) }
        coEvery { putClientLayout(any()) } coAnswers {
            server = firstArg<ClientLayoutRequest>().layout
            Response.success(ClientLayoutPutResponse(ok = true))
        }
    }
    private val provider = mockk<ApiClientProvider> { every { getClient(any()) } returns api }

    private fun repo() = TabLayoutRepository(setup, provider, settings)

    @Test
    fun `a pinned page is sent and survives the next refresh`() = runTest {
        val repo = repo()
        val store = StartPagesStore(repo, settings)
        repo.refresh(force = true)
        store.pin(StartPages.NETWORK)
        assertEquals(listOf("network"), StartPages.pages(server!!.start.orEmpty()))
        // The app started again: the account's order still has the page.
        repo().refresh(force = true)
        assertEquals(listOf("network"), StartPages.pages(repo.current().start))
    }

    @Test
    fun `a page pinned before the server answered is sent, not replaced by the server's order`() = runTest {
        val repo = repo()
        StartPagesStore(repo, settings).pin(StartPages.FAMILIE)
        repo.refresh(force = true)
        assertEquals(listOf("familie"), StartPages.pages(repo.current().start))
        assertEquals(listOf("familie"), StartPages.pages(server!!.start.orEmpty()))
        // The other device's blocks stay.
        assertEquals(listOf("tile:a/1", "widget:p/d", "info", "usage"), StartPages.split(server!!.start.orEmpty()).start)
    }
}
