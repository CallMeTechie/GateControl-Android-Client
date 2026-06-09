package com.gatecontrol.android.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SettingsRepositoryTest {

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var repository: SettingsRepository

    @BeforeEach
    fun setUp() {
        dataStore = mockk()
        repository = SettingsRepository(dataStore)
    }

    @Test
    fun `getTheme returns system by default`() = runTest {
        every { dataStore.data } returns flowOf(preferencesOf())

        repository.getTheme().test {
            assertEquals("system", awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `getLocale returns system language by default`() = runTest {
        every { dataStore.data } returns flowOf(preferencesOf())
        val expected = if (java.util.Locale.getDefault().language == "de") "de" else "en"

        repository.getLocale().test {
            assertEquals(expected, awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `getAutoConnect returns false by default`() = runTest {
        every { dataStore.data } returns flowOf(preferencesOf())

        repository.getAutoConnect().test {
            assertFalse(awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `getCheckInterval returns 30 by default`() = runTest {
        every { dataStore.data } returns flowOf(preferencesOf())

        repository.getCheckInterval().test {
            assertEquals(30, awaitItem())
            awaitComplete()
        }
    }

    // ── 完整保留：防检测设置项默认值验证 ──
    @Test
    fun `getStealthDefaultValues returns correct system defaults`() = runTest {
        every { dataStore.data } returns flowOf(preferencesOf())

        repository.getStealthPortHopping().test { assertFalse(awaitItem()); awaitComplete() }
        repository.getStealthTimingJitter().test { assertFalse(awaitItem()); awaitComplete() }
        repository.getStealthPacketPadding().test { assertFalse(awaitItem()); awaitComplete() }
        repository.getStealthPaddingMtu().test { assertEquals(1280, awaitItem()); awaitComplete() }
    }

    // ── 全新加入：双轨主动探测引擎默认值测试 ──
    @Test
    fun `getProbeDefaultValues returns structural fallbacks`() = runTest {
        every { dataStore.data } returns flowOf(preferencesOf())

        repository.getProbeBypassTarget().test { assertEquals("223.5.5.5", awaitItem()); awaitComplete() }
        repository.getProbeBypassPort().test { assertEquals(80, awaitItem()); awaitComplete() }
        repository.getProbeTunnelTarget().test { assertEquals("8.8.8.8", awaitItem()); awaitComplete() }
        repository.getProbeTunnelPort().test { assertEquals(53, awaitItem()); awaitComplete() }
        repository.getProbeTimeoutMs().test { assertEquals(3000L, awaitItem()); awaitComplete() }
        repository.getProbeFailureThreshold().test { assertEquals(3, awaitItem()); awaitComplete() }
    }

    @Test
    fun `setTheme updates theme value`() = runTest {
        coEvery { dataStore.updateData(any()) } coAnswers {
            val transform = firstArg<suspend (Preferences) -> Preferences>()
            transform(preferencesOf())
        }

        repository.setTheme("light")
    }
}
