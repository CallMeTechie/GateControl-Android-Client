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
    fun `getTheme returns dark by default`() = runTest {
        every { dataStore.data } returns flowOf(preferencesOf())

        repository.getTheme().test {
            assertEquals("dark", awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `getLocale returns de by default`() = runTest {
        every { dataStore.data } returns flowOf(preferencesOf())

        repository.getLocale().test {
            assertEquals("de", awaitItem())
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

    @Test
    fun `setTheme updates theme value`() = runTest {
        coEvery { dataStore.updateData(any()) } coAnswers {
            val transform = firstArg<suspend (Preferences) -> Preferences>()
            transform(preferencesOf())
        }

        repository.setTheme("light")
    }
}
