package com.gatecontrol.android.ui.notifications

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.notify.PushClient
import com.gatecontrol.android.notify.PushMode
import com.gatecontrol.android.notify.PushPlatform
import com.gatecontrol.android.notify.PushPrefsStore
import com.gatecontrol.android.notify.PushRepository
import com.gatecontrol.android.notify.PushStatus
import com.gatecontrol.android.notify.model.PushTopic
import com.gatecontrol.android.push.PortalLinkResolver
import com.gatecontrol.android.push.PushController
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.Closeable
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationSettingsViewModelTest {

    @TempDir lateinit var dir: File

    private lateinit var store: PushPrefsStore
    private val repository: PushRepository = mockk(relaxed = true)
    private val controller: PushController = mockk(relaxed = true)
    private val portal: PortalLinkResolver = mockk(relaxed = true)
    private val status = MutableStateFlow<PushStatus>(PushStatus.Off)
    private val client: PushClient = mockk { every { status } returns this@NotificationSettingsViewModelTest.status }
    private val setup: SetupRepository = mockk {
        every { getServerUrl() } returns "https://gc.example.com"
        every { getPortalUrl() } returns "https://portal.example.com"
    }
    private var restricted = true
    private val platform = object : PushPlatform {
        override fun isBatteryRestricted() = restricted
        override fun watchNetwork(onChange: (String) -> Unit) = Closeable {}
    }

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        store = PushPrefsStore(
            PreferenceDataStoreFactory.create(
                scope = storeScope,
                produceFile = { File(dir, "s.preferences_pb") },
            ),
        )
    }

    private val viewModels = mutableListOf<androidx.lifecycle.ViewModel>()
    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @AfterEach
    fun tearDown() {
        // Nothing may resume on Dispatchers.Main after resetMain (it would fail a later test).
        runBlocking { viewModels.forEach { it.viewModelScope.coroutineContext[Job]!!.cancelAndJoin() } }
        storeScope.cancel()
        Dispatchers.resetMain()
    }

    private fun vm() = NotificationSettingsViewModel(store, repository, controller, platform, portal, setup, client).also { viewModels += it }

    private fun <T> await(block: suspend () -> T): T = runBlocking { withTimeout(5_000) { block() } }

    @Test
    fun `first switch-on proposes the mode instead of enabling right away`() {
        val vm = vm()
        vm.setEnabled(true)
        await { vm.uiState.first { it.modeDialogVisible } }
        assertFalse(await { store.current() }.enabled)
    }

    @Test
    fun `confirming always enables push and asks for the notification permission`() {
        val vm = vm()
        vm.refreshSystemState(notificationsAllowed = false)
        vm.setEnabled(true)
        vm.confirmMode(PushMode.ALWAYS)
        assertEquals(SettingsEffect.RequestNotificationPermission, await { vm.effect.first() })
        val s = await { store.current() }
        assertTrue(s.enabled)
        assertTrue(s.modeChosen)
        assertEquals(PushMode.ALWAYS, s.mode)
        assertFalse(vm.uiState.value.modeDialogVisible)

        // Permission answered → the battery exemption follows (optimisation active).
        vm.onNotificationPermissionResult(true)
        assertEquals(SettingsEffect.RequestBatteryExemption, await { vm.effect.first() })
    }

    @Test
    fun `only with VPN needs no battery exemption`() {
        val vm = vm()
        vm.refreshSystemState(notificationsAllowed = true)
        vm.confirmMode(PushMode.VPN_ONLY)
        await { vm.uiState.first { it.enabled && it.mode == PushMode.VPN_ONLY } }
        vm.onNotificationPermissionResult(true)
        Thread.sleep(200)
        val next = runBlocking { kotlinx.coroutines.withTimeoutOrNull(300) { vm.effect.first() } }
        assertEquals(null, next)
    }

    @Test
    fun `once a mode was chosen the switch acts directly`() {
        await { store.setMode(PushMode.VPN_ONLY) }
        val vm = vm()
        vm.setEnabled(true)
        await { vm.uiState.first { it.enabled } }
        assertFalse(vm.uiState.value.modeDialogVisible)
        vm.setEnabled(false)
        await { vm.uiState.first { !it.enabled } }
    }

    @Test
    fun `topics come from hello and can be muted, admin notices cannot`() {
        await { store.saveTopics(listOf(PushTopic("devices", "Geräte"), PushTopic("admin_notice", "Admin"), PushTopic("plugin:skoda:charging", "Laden"))) }
        val vm = vm()
        await { vm.uiState.first { it.topics.size == 3 } }
        vm.setTopicEnabled("devices", false)
        vm.setTopicEnabled("admin_notice", false)
        val rows = await { vm.uiState.first { s -> s.topics.any { it.id == "devices" && it.muted } } }.topics
        assertTrue(rows.first { it.id == "admin_notice" }.locked)
        assertFalse(rows.first { it.id == "admin_notice" }.muted)
        assertEquals("Laden", rows.first { it.id == "plugin:skoda:charging" }.label)
        assertEquals(setOf("devices"), await { store.current() }.mutedTopics)
    }

    @Test
    fun `test message reports success and failure`() {
        coEvery { repository.requestTest() } returns Result.success(130L)
        val vm = vm()
        vm.requestTest()
        await { vm.uiState.first { it.testState == TestState.Sent } }
        coEvery { repository.requestTest() } returns Result.failure(IllegalStateException("x"))
        vm.requestTest()
        await { vm.uiState.first { it.testState == TestState.Failed } }
    }

    @Test
    fun `resume re-reads battery state and nudges the controller`() {
        val vm = vm()
        await { vm.uiState.first { it.batteryRestricted } }
        restricted = false
        vm.refreshSystemState(notificationsAllowed = true)
        await { vm.uiState.first { !it.batteryRestricted } }
        verify { controller.onForeground() }
    }

    @Test
    fun `status and host are shown`() {
        val vm = vm()
        status.value = PushStatus.Connected("direct", 1L)
        val s = await { vm.uiState.first { it.status is PushStatus.Connected } }
        assertEquals("gc.example.com", s.host)
        assertTrue(s.portalAvailable)
        vm.retry()
        verify { controller.retry() }
    }
}
