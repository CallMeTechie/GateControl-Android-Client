package com.gatecontrol.android.ui.pihole

import com.gatecontrol.android.data.LicenseRepository
import com.gatecontrol.android.data.PiholePauseState
import com.gatecontrol.android.data.PiholePauseStore
import com.gatecontrol.android.network.PiholeBlocking
import com.gatecontrol.android.network.PiholeRepository
import com.gatecontrol.android.network.PiholeSummary
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PiholeViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var piholeRepository: PiholeRepository
    private lateinit var licenseRepository: LicenseRepository
    private lateinit var pauseStore: PiholePauseStore
    private var now = 1_000_000L

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        piholeRepository = mockk(relaxed = true)
        licenseRepository = mockk(relaxed = true)
        pauseStore = mockk(relaxed = true)
        // Default: nothing persisted, no summary (override per test).
        coEvery { pauseStore.load() } returns null
        coEvery { piholeRepository.getSummary() } returns null
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private fun vm() = PiholeViewModel(piholeRepository, licenseRepository, pauseStore) { now }

    private fun summary(state: String, timer: Long?) =
        PiholeSummary(blocking = PiholeBlocking(state, timer))

    @Test
    fun `refresh populates summary`() = runTest {
        coEvery { piholeRepository.getSummary() } returns summary("enabled", null).copy(
            queries = com.gatecontrol.android.network.PiholeQueries(total = 64)
        )
        val vm = vm()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(64L, vm.uiState.value.summary?.queries?.total)
        assertTrue(vm.uiState.value.everLoaded)
    }

    @Test
    fun `refresh never nulls an existing summary`() = runTest {
        coEvery { piholeRepository.getSummary() } returns summary("enabled", null)
        val vm = vm()
        testDispatcher.scheduler.advanceUntilIdle()
        // Now the server returns null transiently.
        coEvery { piholeRepository.getSummary() } returns null
        vm.refresh()
        testDispatcher.scheduler.advanceUntilIdle()
        assertNotNull(vm.uiState.value.summary)
        assertTrue(vm.uiState.value.everLoaded)
    }

    @Test
    fun `pauseBlocking sets optimistic finite pause immediately`() = runTest {
        coEvery { piholeRepository.setBlocking(false, 300) } returns true
        val vm = vm()
        testDispatcher.scheduler.advanceUntilIdle()
        vm.pauseBlocking(300)
        // Optimistic state is set synchronously, before the POST coroutine runs.
        val s = vm.uiState.value
        assertEquals(300, s.pausedPresetSec)
        assertEquals(now + 300_000L, s.pauseEndAtMillis)
        assertEquals("disabled", s.pendingIntent)
        assertFalse(s.pausePermanent)
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(vm.uiState.value.actionPending)
    }

    @Test
    fun `pauseBlocking permanent sets pausePermanent and null end`() = runTest {
        coEvery { piholeRepository.setBlocking(false, null) } returns true
        val vm = vm()
        testDispatcher.scheduler.advanceUntilIdle()
        vm.pauseBlocking(null)
        val s = vm.uiState.value
        assertTrue(s.pausePermanent)
        assertNull(s.pauseEndAtMillis)
        assertNull(s.pausedPresetSec)
        assertEquals("disabled", s.pendingIntent)
    }

    @Test
    fun `pauseBlocking rolls back on POST failure`() = runTest {
        coEvery { piholeRepository.setBlocking(false, 300) } returns false
        val vm = vm()
        testDispatcher.scheduler.advanceUntilIdle()
        vm.pauseBlocking(300)
        testDispatcher.scheduler.advanceUntilIdle()
        val s = vm.uiState.value
        assertNull(s.pauseEndAtMillis)
        assertNull(s.pendingIntent)
        assertEquals("blocking_failed", s.error)
        assertFalse(s.actionPending)
    }

    @Test
    fun `resumeBlocking clears pause immediately`() = runTest {
        coEvery { piholeRepository.setBlocking(true, null) } returns true
        val vm = vm()
        testDispatcher.scheduler.advanceUntilIdle()
        vm.resumeBlocking()
        val s = vm.uiState.value
        assertNull(s.pauseEndAtMillis)
        assertFalse(s.pausePermanent)
        assertEquals("enabled", s.pendingIntent)
    }

    @Test
    fun `resumeBlocking restores pause on POST failure`() = runTest {
        coEvery { piholeRepository.setBlocking(false, 300) } returns true
        coEvery { piholeRepository.setBlocking(true, null) } returns false
        val vm = vm()
        testDispatcher.scheduler.advanceUntilIdle()
        vm.pauseBlocking(300)
        testDispatcher.scheduler.advanceUntilIdle()
        vm.resumeBlocking()
        testDispatcher.scheduler.advanceUntilIdle()
        val s = vm.uiState.value
        assertEquals(now + 300_000L, s.pauseEndAtMillis)
        assertEquals(300, s.pausedPresetSec)
        assertNull(s.pendingIntent)
        assertEquals("blocking_failed", s.error)
    }

    @Test
    fun `reconcile within grace keeps optimistic pause despite lagging server`() = runTest {
        coEvery { piholeRepository.setBlocking(false, 300) } returns true
        val vm = vm()
        testDispatcher.scheduler.advanceUntilIdle()
        vm.pauseBlocking(300)
        testDispatcher.scheduler.advanceUntilIdle()
        // Server still reports the old "enabled" state, but we are inside the grace window.
        now += 5_000L
        coEvery { piholeRepository.getSummary() } returns summary("enabled", null)
        vm.refresh()
        testDispatcher.scheduler.advanceUntilIdle()
        assertNotNull(vm.uiState.value.pauseEndAtMillis)
        assertEquals("disabled", vm.uiState.value.pendingIntent)
    }

    @Test
    fun `reconcile confirms when server matches pendingIntent`() = runTest {
        coEvery { piholeRepository.setBlocking(false, 300) } returns true
        val vm = vm()
        testDispatcher.scheduler.advanceUntilIdle()
        vm.pauseBlocking(300)
        testDispatcher.scheduler.advanceUntilIdle()
        coEvery { piholeRepository.getSummary() } returns summary("disabled", 300)
        vm.refresh()
        testDispatcher.scheduler.advanceUntilIdle()
        assertNull(vm.uiState.value.pendingIntent)
        assertNotNull(vm.uiState.value.pauseEndAtMillis)
    }

    @Test
    fun `reconcile fails only after two post-grace misses`() = runTest {
        coEvery { piholeRepository.setBlocking(false, 300) } returns true
        val vm = vm()
        testDispatcher.scheduler.advanceUntilIdle()
        vm.pauseBlocking(300)
        testDispatcher.scheduler.advanceUntilIdle()
        // Move past the grace window; server keeps contradicting (still "enabled").
        now += 20_000L
        coEvery { piholeRepository.getSummary() } returns summary("enabled", null)
        vm.refresh()
        testDispatcher.scheduler.advanceUntilIdle()
        // First post-grace miss: still paused, no error yet.
        assertNotNull(vm.uiState.value.pauseEndAtMillis)
        assertNull(vm.uiState.value.error)
        vm.refresh()
        testDispatcher.scheduler.advanceUntilIdle()
        // Second consecutive miss → failure.
        assertNull(vm.uiState.value.pauseEndAtMillis)
        assertEquals("blocking_failed", vm.uiState.value.error)
    }

    @Test
    fun `reconcile post-grace miss then confirmation does not fail`() = runTest {
        coEvery { piholeRepository.setBlocking(false, 300) } returns true
        val vm = vm()
        testDispatcher.scheduler.advanceUntilIdle()
        vm.pauseBlocking(300)
        testDispatcher.scheduler.advanceUntilIdle()
        now += 20_000L
        coEvery { piholeRepository.getSummary() } returns summary("enabled", null)
        vm.refresh()
        testDispatcher.scheduler.advanceUntilIdle()
        // Second poll confirms the intended direction.
        coEvery { piholeRepository.getSummary() } returns summary("disabled", 280)
        vm.refresh()
        testDispatcher.scheduler.advanceUntilIdle()
        assertNull(vm.uiState.value.pendingIntent)
        assertNull(vm.uiState.value.error)
        assertNotNull(vm.uiState.value.pauseEndAtMillis)
    }

    @Test
    fun `reconcile drift threshold keeps end when within 2s`() = runTest {
        coEvery { piholeRepository.setBlocking(false, 300) } returns true
        val vm = vm()
        testDispatcher.scheduler.advanceUntilIdle()
        vm.pauseBlocking(300)
        testDispatcher.scheduler.advanceUntilIdle()
        val originalEnd = vm.uiState.value.pauseEndAtMillis
        // Confirm with a server timer ~1s off → end must NOT move.
        coEvery { piholeRepository.getSummary() } returns summary("disabled", 299)
        vm.refresh()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(originalEnd, vm.uiState.value.pauseEndAtMillis)
    }

    @Test
    fun `reconcile preset tolerance falls back to generic when timer far off`() = runTest {
        coEvery { piholeRepository.setBlocking(false, 300) } returns true
        val vm = vm()
        testDispatcher.scheduler.advanceUntilIdle()
        vm.pauseBlocking(300)
        testDispatcher.scheduler.advanceUntilIdle()
        coEvery { piholeRepository.getSummary() } returns summary("disabled", 1750)
        vm.refresh()
        testDispatcher.scheduler.advanceUntilIdle()
        assertNull(vm.uiState.value.pausedPresetSec)
        assertNotNull(vm.uiState.value.pauseEndAtMillis)
    }

    @Test
    fun `reconcile trusts server when not pending - external resume clears pause`() = runTest {
        coEvery { piholeRepository.setBlocking(false, 300) } returns true
        val vm = vm()
        testDispatcher.scheduler.advanceUntilIdle()
        vm.pauseBlocking(300)
        testDispatcher.scheduler.advanceUntilIdle()
        // Confirm pause (pendingIntent → null).
        coEvery { piholeRepository.getSummary() } returns summary("disabled", 300)
        vm.refresh()
        testDispatcher.scheduler.advanceUntilIdle()
        // Now an external client re-enables blocking.
        coEvery { piholeRepository.getSummary() } returns summary("enabled", null)
        vm.refresh()
        testDispatcher.scheduler.advanceUntilIdle()
        assertNull(vm.uiState.value.pauseEndAtMillis)
        assertFalse(vm.uiState.value.pausePermanent)
    }

    @Test
    fun `onPauseExpired clears pause and arms enabled intent`() = runTest {
        coEvery { piholeRepository.setBlocking(false, 30) } returns true
        val vm = vm()
        testDispatcher.scheduler.advanceUntilIdle()
        vm.pauseBlocking(30)
        testDispatcher.scheduler.advanceUntilIdle()
        vm.onPauseExpired()
        val s = vm.uiState.value
        assertNull(s.pauseEndAtMillis)
        assertEquals("enabled", s.pendingIntent)
    }

    @Test
    fun `onPauseExpired then lagging disabled poll does not recreate pause`() = runTest {
        coEvery { piholeRepository.setBlocking(false, 30) } returns true
        val vm = vm()
        testDispatcher.scheduler.advanceUntilIdle()
        vm.pauseBlocking(30)
        testDispatcher.scheduler.advanceUntilIdle()
        vm.onPauseExpired()
        testDispatcher.scheduler.advanceUntilIdle()
        // Lagging server still reports a short remaining pause, within grace.
        coEvery { piholeRepository.getSummary() } returns summary("disabled", 1)
        vm.refresh()
        testDispatcher.scheduler.advanceUntilIdle()
        assertNull(vm.uiState.value.pauseEndAtMillis)
        assertEquals("enabled", vm.uiState.value.pendingIntent)
    }

    @Test
    fun `onPauseExpired is idempotent when already enabled`() = runTest {
        val vm = vm()
        testDispatcher.scheduler.advanceUntilIdle()
        // No pause active.
        vm.onPauseExpired()
        val s = vm.uiState.value
        assertNull(s.pauseEndAtMillis)
        assertNull(s.pendingIntent)
    }

    @Test
    fun `restore reinstates finite pause with fresh grace`() = runTest {
        coEvery { pauseStore.load() } returns PiholePauseState(
            endAtMillis = now + 120_000L, presetSec = 300, permanent = false
        )
        // Lagging server reports enabled right after restore.
        coEvery { piholeRepository.getSummary() } returns summary("enabled", null)
        val vm = vm()
        testDispatcher.scheduler.advanceUntilIdle()
        // The restored pause must survive the lagging poll (fresh grace, pendingIntent="disabled").
        assertEquals(now + 120_000L, vm.uiState.value.pauseEndAtMillis)
        assertEquals("disabled", vm.uiState.value.pendingIntent)
    }

    @Test
    fun `restore drops expired pause`() = runTest {
        coEvery { pauseStore.load() } returns PiholePauseState(
            endAtMillis = now - 5_000L, presetSec = 300, permanent = false
        )
        coEvery { piholeRepository.getSummary() } returns summary("enabled", null)
        val vm = vm()
        testDispatcher.scheduler.advanceUntilIdle()
        assertNull(vm.uiState.value.pauseEndAtMillis)
    }

    @Test
    fun `pause POST failure error survives a routine refresh`() = runTest {
        coEvery { piholeRepository.setBlocking(false, 300) } returns false
        coEvery { piholeRepository.getSummary() } returns summary("enabled", null)
        val vm = vm()
        testDispatcher.scheduler.advanceUntilIdle()
        vm.pauseBlocking(300)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("blocking_failed", vm.uiState.value.error)
        // A routine background poll must NOT wipe the action error.
        vm.refresh()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("blocking_failed", vm.uiState.value.error)
    }

    @Test
    fun `restored pause contradicted past grace adopts server without error`() = runTest {
        // Pause restored from a previous session (pendingFromAction = false).
        coEvery { pauseStore.load() } returns PiholePauseState(
            endAtMillis = now + 120_000L, presetSec = 300, permanent = false
        )
        coEvery { piholeRepository.getSummary() } returns summary("enabled", null)
        val vm = vm()
        testDispatcher.scheduler.advanceUntilIdle()
        // Push past the grace window; server keeps reporting "enabled" (pause not active server-side).
        now += 20_000L
        vm.refresh()
        testDispatcher.scheduler.advanceUntilIdle()
        // Restored optimism yields to the authoritative server — silently, NO error toast.
        assertNull(vm.uiState.value.pauseEndAtMillis)
        assertNull(vm.uiState.value.pendingIntent)
        assertNull(vm.uiState.value.error)
    }
}
