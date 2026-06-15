package com.gatecontrol.android.ui.pihole

import com.gatecontrol.android.data.LicenseRepository
import com.gatecontrol.android.network.PiholeRepository
import com.gatecontrol.android.network.PiholeBlocking
import com.gatecontrol.android.network.PiholeQueries
import com.gatecontrol.android.network.PiholeSummary
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PiholeViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var piholeRepository: PiholeRepository
    private lateinit var licenseRepository: LicenseRepository

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        piholeRepository = mockk(relaxed = true)
        licenseRepository = mockk(relaxed = true)
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private fun vm() = PiholeViewModel(piholeRepository, licenseRepository)

    @Test
    fun `refresh populates summary`() = runTest {
        coEvery { piholeRepository.getSummary() } returns PiholeSummary(
            queries = PiholeQueries(total = 64, blocked = 3, percent = 4.7),
            gravity = 84973, blocking = PiholeBlocking("enabled", null), attribution = "collapsed"
        )
        val vm = vm()
        vm.refresh()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(64L, vm.uiState.value.summary?.queries?.total)
    }

    @Test
    fun `pauseBlocking sets pending and confirms when state matches`() = runTest {
        coEvery { piholeRepository.setBlocking(false, 300) } returns true
        // After action, confirming read returns disabled → pending cleared.
        coEvery { piholeRepository.getSummary() } returns PiholeSummary(
            blocking = PiholeBlocking("disabled", 300)
        )
        val vm = vm()
        vm.pauseBlocking(300)
        testDispatcher.scheduler.advanceUntilIdle()
        coVerify { piholeRepository.setBlocking(false, 300) }
        assertEquals("disabled", vm.uiState.value.summary?.blocking?.state)
        assertTrue(!vm.uiState.value.actionPending)
    }
}
