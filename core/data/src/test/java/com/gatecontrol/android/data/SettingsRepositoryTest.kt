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
    fun `setTheme updates theme value`() = runTest {
        coEvery { dataStore.updateData(any()) } coAnswers {
            val transform = firstArg<suspend (Preferences) -> Preferences>()
            transform(preferencesOf())
        }

        repository.setTheme("light")
    }

    @Test
    fun `trusted networks are off with home detection on by default`() = runTest {
        every { dataStore.data } returns flowOf(preferencesOf())

        repository.getTrustedNetworkSettings().test {
            assertEquals(TrustedNetworkSettings(enabled = false, homeEnabled = true, ssids = emptySet(), lastActedKey = null), awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `trusted networks read stored values`() = runTest {
        every { dataStore.data } returns flowOf(
            preferencesOf(
                SettingsRepository.TRUSTED_NET_ENABLED to true,
                SettingsRepository.TRUSTED_NET_HOME_ENABLED to false,
                SettingsRepository.TRUSTED_NET_SSIDS to setOf("Heimnetz", "Büro"),
                SettingsRepository.TRUSTED_NET_LAST_KEY to "cell",
            ),
        )

        repository.getTrustedNetworkSettings().test {
            assertEquals(TrustedNetworkSettings(true, false, setOf("Heimnetz", "Büro"), "cell"), awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `updateTrustedSsids transforms the stored set`() = runTest {
        var written: Preferences? = null
        coEvery { dataStore.updateData(any()) } coAnswers {
            val transform = firstArg<suspend (Preferences) -> Preferences>()
            transform(preferencesOf(SettingsRepository.TRUSTED_NET_SSIDS to setOf("A"))).also { written = it }
        }

        repository.updateTrustedSsids { it + "B" }

        assertEquals(setOf("A", "B"), written?.get(SettingsRepository.TRUSTED_NET_SSIDS))
    }
}
