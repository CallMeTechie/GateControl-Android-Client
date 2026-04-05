package com.gatecontrol.android.data

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SetupRepositoryTest {

    private lateinit var storage: EncryptedStorage
    private lateinit var repository: SetupRepository

    @BeforeEach
    fun setUp() {
        storage = mockk(relaxed = true)
        repository = SetupRepository(storage)
    }

    @Test
    fun `save stores serverUrl, apiToken and peerId`() {
        repository.save("https://example.com", "token123", 42)

        verify { storage.putString("server_url", "https://example.com") }
        verify { storage.putString("api_token", "token123") }
        verify { storage.putInt("peer_id", 42) }
    }

    @Test
    fun `getServerUrl returns stored value`() {
        every { storage.getString("server_url", "") } returns "https://example.com"

        assertEquals("https://example.com", repository.getServerUrl())
    }

    @Test
    fun `getApiToken returns stored value`() {
        every { storage.getString("api_token", "") } returns "mytoken"

        assertEquals("mytoken", repository.getApiToken())
    }

    @Test
    fun `getPeerId returns stored value`() {
        every { storage.getInt("peer_id", -1) } returns 7

        assertEquals(7, repository.getPeerId())
    }

    @Test
    fun `isConfigured returns true when url and token are non-empty`() {
        every { storage.getString("server_url", "") } returns "https://example.com"
        every { storage.getString("api_token", "") } returns "token"

        assertTrue(repository.isConfigured())
    }

    @Test
    fun `isConfigured returns false when url is empty`() {
        every { storage.getString("server_url", "") } returns ""
        every { storage.getString("api_token", "") } returns "token"

        assertFalse(repository.isConfigured())
    }

    @Test
    fun `isConfigured returns false when token is empty`() {
        every { storage.getString("server_url", "") } returns "https://example.com"
        every { storage.getString("api_token", "") } returns ""

        assertFalse(repository.isConfigured())
    }

    @Test
    fun `hasWireGuardConfig returns true when config is stored`() {
        every { storage.getString("wg_config", "") } returns "[Interface]\nPrivateKey=abc"

        assertTrue(repository.hasWireGuardConfig())
    }

    @Test
    fun `hasWireGuardConfig returns false when config is empty`() {
        every { storage.getString("wg_config", "") } returns ""

        assertFalse(repository.hasWireGuardConfig())
    }

    @Test
    fun `clear delegates to storage clear`() {
        repository.clear()

        verify { storage.clear() }
    }
}
