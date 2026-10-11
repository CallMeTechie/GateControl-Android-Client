package com.gatecontrol.android.push

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.notify.PushClient
import com.gatecontrol.android.notify.PushMode
import com.gatecontrol.android.notify.PushPrefsStore
import com.gatecontrol.android.notify.PushRepository
import com.gatecontrol.android.notify.PushStatus
import com.gatecontrol.android.tunnel.TunnelManager
import com.gatecontrol.android.tunnel.TunnelState
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class PushControllerTest {

    @TempDir lateinit var dir: File

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tunnel = MutableStateFlow<TunnelState>(TunnelState.Disconnected)
    private val revision = MutableStateFlow(0L)
    private val client: PushClient = mockk(relaxed = true) {
        every { status } returns MutableStateFlow(PushStatus.Off)
        every { isRunning } returns true
    }
    private val repository: PushRepository = mockk(relaxed = true)
    private val tunnelManager: TunnelManager = mockk { every { state } returns tunnel }
    private val setup: SetupRepository = mockk {
        every { isConfigured() } returns true
        every { revision } returns this@PushControllerTest.revision
    }
    private lateinit var store: PushPrefsStore
    private lateinit var controller: PushController

    @BeforeEach
    fun setUp() {
        store = PushPrefsStore(
            PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(dir, "c.preferences_pb") }),
        )
        controller = PushController(mockk<Context>(relaxed = true), store, client, repository, setup, tunnelManager, mockk(relaxed = true))
    }

    @AfterEach
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `only with VPN runs the client in the supervisor scope while the tunnel is up`() {
        runBlocking {
            store.setMode(PushMode.VPN_ONLY)
            store.setEnabled(true)
        }
        controller.start(scope)
        verify(timeout = 3_000) { client.stop(null, PushStatus.WaitingForVpn) }

        tunnel.value = TunnelState.Connected(1L)
        verify(timeout = 3_000) { client.start(scope, "tunnel") }

        tunnel.value = TunnelState.Disconnected
        verify(timeout = 3_000, atLeast = 2) { client.stop(null, PushStatus.WaitingForVpn) }
    }

    @Test
    fun `switched off stops the client and tells the server`() {
        runBlocking {
            store.setMode(PushMode.VPN_ONLY)
            store.setEnabled(true)
        }
        tunnel.value = TunnelState.Connected(1L)
        controller.start(scope)
        verify(timeout = 3_000) { client.start(scope, "tunnel") }

        runBlocking { store.setEnabled(false) }
        verify(timeout = 3_000) { client.stop(null, PushStatus.Off) }
        coVerify(timeout = 3_000) { repository.syncPrefs(any()) }
    }

    @Test
    fun `a changed setup reconnects right away`() {
        runBlocking {
            store.setMode(PushMode.VPN_ONLY)
            store.setEnabled(true)
        }
        tunnel.value = TunnelState.Connected(1L)
        controller.start(scope)
        verify(timeout = 3_000) { client.start(scope, "tunnel") }
        revision.value = 1
        verify(timeout = 3_000) { client.kick("setup", true) }
    }

    @Test
    fun `retry forces a reconnect`() {
        controller.retry()
        verify { client.kick("user", true) }
    }
}
