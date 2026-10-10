package com.gatecontrol.android.service

import com.gatecontrol.android.common.ClientPolicy
import com.gatecontrol.android.common.SplitTunnelMode
import com.gatecontrol.android.data.ClientPolicyRepository
import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.network.ApiClient
import com.gatecontrol.android.network.ApiClientProvider
import com.gatecontrol.android.network.ClientPolicyPayload
import com.gatecontrol.android.network.ClientPolicyResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import retrofit2.Response

class ClientPolicyManagerTest {

    private lateinit var repository: ClientPolicyRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var setupRepository: SetupRepository
    private lateinit var apiClientProvider: ApiClientProvider
    private lateinit var apiClient: ApiClient
    private lateinit var manager: ClientPolicyManager

    private val stored = MutableStateFlow<ClientPolicyRepository.Cached?>(null)

    @BeforeEach
    fun setUp() {
        stored.value = null
        repository = mockk(relaxed = true) {
            every { cached() } returns stored
            coEvery { current() } answers { stored.value }
            coEvery { save(any(), any()) } answers { stored.value = ClientPolicyRepository.Cached(firstArg(), secondArg()) }
            coEvery { clear() } answers { stored.value = null }
        }
        settingsRepository = mockk(relaxed = true) {
            every { getAutoConnect() } returns flowOf(false)
            every { getSplitTunnelMode() } returns flowOf(SplitTunnelMode.INCLUDE)
        }
        setupRepository = mockk(relaxed = true) {
            every { getServerUrl() } returns "https://gc.example"
        }
        apiClient = mockk(relaxed = true)
        apiClientProvider = mockk(relaxed = true) {
            every { getClient(any()) } returns apiClient
        }
        manager = ClientPolicyManager(repository, settingsRepository, setupRepository, apiClientProvider)
    }

    private fun ok(version: String, payload: ClientPolicyPayload) =
        Response.success(ClientPolicyResponse(ok = true, version = version, managed = true, policy = payload))

    @Test
    fun `never fetched means unrestricted`() = runTest {
        assertEquals(ClientPolicy.UNRESTRICTED, manager.current())
    }

    @Test
    fun `a fetched policy is stored and forces the settings`() = runTest {
        coEvery { apiClient.getClientPolicy(null) } returns ok(
            "abcd1234",
            ClientPolicyPayload(autoConnect = "required", splitTunnelModes = listOf("off", "exclude")),
        )
        assertEquals(ClientPolicyManager.Result.UPDATED, manager.refresh())
        assertEquals(ClientPolicy.AutoConnect.REQUIRED, manager.current().autoConnect)
        assertEquals("abcd1234", stored.value?.version)
        coVerify { settingsRepository.setAutoConnect(true) }
        coVerify { settingsRepository.setSplitTunnelMode(SplitTunnelMode.OFF) }
    }

    @Test
    fun `cached version is sent and 304 keeps the policy`() = runTest {
        stored.value = ClientPolicyRepository.Cached(ClientPolicy(lockServer = true), "beef")
        coEvery { apiClient.getClientPolicy("\"beef\"") } returns
            Response.error(
                "".toResponseBody(null),
                okhttp3.Response.Builder()
                    .code(304)
                    .message("Not Modified")
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .request(okhttp3.Request.Builder().url("https://gc.example/api/v1/client/policy").build())
                    .build(),
            )
        assertEquals(ClientPolicyManager.Result.UNCHANGED, manager.refresh())
        assertEquals(true, manager.current().lockServer)
    }

    @Test
    fun `unreachable server or old server keeps the last known policy`() = runTest {
        stored.value = ClientPolicyRepository.Cached(ClientPolicy(lockSettings = true), "beef")
        coEvery { apiClient.getClientPolicy(any()) } throws java.io.IOException("offline")
        assertEquals(ClientPolicyManager.Result.UNAVAILABLE, manager.refresh())
        coEvery { apiClient.getClientPolicy(any()) } returns Response.error(404, "{}".toResponseBody(null))
        assertEquals(ClientPolicyManager.Result.UNAVAILABLE, manager.refresh())
        assertEquals(true, manager.current().lockSettings)
    }

    @Test
    fun `noteVersion refreshes only on a different version`() = runTest {
        stored.value = ClientPolicyRepository.Cached(ClientPolicy.UNRESTRICTED, "aaaa")
        assertEquals(ClientPolicyManager.Result.UNCHANGED, manager.noteVersion("aaaa"))
        assertEquals(ClientPolicyManager.Result.UNCHANGED, manager.noteVersion(null))
        coVerify(exactly = 0) { apiClient.getClientPolicy(any()) }
        coEvery { apiClient.getClientPolicy("\"aaaa\"") } returns ok("bbbb", ClientPolicyPayload(killSwitch = "required"))
        assertEquals(ClientPolicyManager.Result.UPDATED, manager.noteVersion("bbbb"))
        assertEquals(ClientPolicy.KillSwitch.REQUIRED, manager.current().killSwitch)
    }

    @Test
    fun `reset drops the old policy`() = runTest {
        stored.value = ClientPolicyRepository.Cached(ClientPolicy(lockServer = true), "aaaa")
        coEvery { apiClient.getClientPolicy(any()) } throws java.io.IOException("offline")
        manager.reset()
        assertEquals(ClientPolicy.UNRESTRICTED, manager.current())
    }
}
