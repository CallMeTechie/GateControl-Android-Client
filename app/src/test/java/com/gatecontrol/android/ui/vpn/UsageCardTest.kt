package com.gatecontrol.android.ui.vpn

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.gatecontrol.android.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** The throughput/data usage card: pages and the persisted favourite (long press). */
class UsageCardTest {

    @TempDir
    lateinit var dir: File

    // Own thread: not starved by work other test classes left on Dispatchers.IO.
    private val storeThread = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val storeScope = CoroutineScope(storeThread.asCoroutineDispatcher() + SupervisorJob())

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    private val viewModels = mutableListOf<androidx.lifecycle.ViewModel>()

    @AfterEach
    fun tearDown() {
        // Nothing may resume on Dispatchers.Main after resetMain.
        runBlocking { viewModels.forEach { it.viewModelScope.coroutineContext[Job]!!.cancelAndJoin() } }
        storeScope.cancel()
        storeThread.shutdown()
        Dispatchers.resetMain()
    }

    private fun track(vm: UsageCardViewModel): UsageCardViewModel = vm.also { viewModels += it }

    private fun repository(): SettingsRepository = SettingsRepository(
        PreferenceDataStoreFactory.create(scope = storeScope, produceFile = { File(dir, "s.preferences_pb") }),
    )

    @Test
    fun `pages and the default favourite`() {
        assertEquals(listOf(UsageCardPages.THROUGHPUT, UsageCardPages.USAGE), UsageCardPages.pages(usageAvailable = true))
        assertEquals(listOf(UsageCardPages.THROUGHPUT), UsageCardPages.pages(usageAvailable = false))
        // Never chosen: Datennutzung first.
        assertEquals(UsageCardPages.USAGE, UsageCardPages.favorite(null))
        assertEquals(UsageCardPages.USAGE, UsageCardPages.favorite("bogus"))
        assertEquals(UsageCardPages.THROUGHPUT, UsageCardPages.favorite(UsageCardPages.THROUGHPUT))
    }

    @Test
    fun `long press favourite is stored and read back`() = runBlocking {
        val settings = repository()
        assertNull(settings.getUsageCardFavorite().first())
        val vm = track(UsageCardViewModel(settings))
        withTimeout(5_000) {
            assertEquals(UsageCardPages.USAGE, vm.favorite.filterNotNull().first())
        }
        vm.setFavorite(UsageCardPages.THROUGHPUT)
        withTimeout(5_000) {
            assertEquals(UsageCardPages.THROUGHPUT, settings.getUsageCardFavorite().filterNotNull().first())
            assertEquals(UsageCardPages.THROUGHPUT, vm.favorite.first { it == UsageCardPages.THROUGHPUT })
        }
        // Unknown pages are ignored.
        vm.setFavorite("nope")
        assertEquals(UsageCardPages.THROUGHPUT, settings.getUsageCardFavorite().first())
        // A new screen (new ViewModel) starts on the stored page.
        val again = track(UsageCardViewModel(settings))
        withTimeout(5_000) {
            assertEquals(UsageCardPages.THROUGHPUT, again.favorite.filterNotNull().first())
        }
    }

    @Test
    fun `theme switch follows the system only for system`() {
        assertEquals(true, com.gatecontrol.android.ui.profile.ThemeToggleViewModel.isDark("dark", systemDark = false))
        assertEquals(false, com.gatecontrol.android.ui.profile.ThemeToggleViewModel.isDark("light", systemDark = true))
        assertEquals(true, com.gatecontrol.android.ui.profile.ThemeToggleViewModel.isDark("system", systemDark = true))
    }
}
