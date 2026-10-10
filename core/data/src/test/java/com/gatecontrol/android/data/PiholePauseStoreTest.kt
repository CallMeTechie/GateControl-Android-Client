package com.gatecontrol.android.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class PiholePauseStoreTest {

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var store: PiholePauseStore

    @BeforeEach
    fun setUp() {
        dataStore = mockk()
        store = PiholePauseStore(dataStore)
    }

    @Test
    fun `load returns null when nothing stored`() = runTest {
        every { dataStore.data } returns flowOf(preferencesOf())
        assertNull(store.load())
    }

    @Test
    fun `load returns finite pause`() = runTest {
        every { dataStore.data } returns flowOf(
            preferencesOf(
                PiholePauseStore.PAUSE_END_AT to 5_000_000L,
                PiholePauseStore.PAUSE_PRESET to 300,
                PiholePauseStore.PAUSE_PERMANENT to false,
            )
        )
        val s = store.load()!!
        assertEquals(5_000_000L, s.endAtMillis)
        assertEquals(300, s.presetSec)
        assertEquals(false, s.permanent)
    }

    @Test
    fun `load maps preset -1 to null`() = runTest {
        every { dataStore.data } returns flowOf(
            preferencesOf(
                PiholePauseStore.PAUSE_END_AT to 5_000_000L,
                PiholePauseStore.PAUSE_PRESET to -1,
                PiholePauseStore.PAUSE_PERMANENT to false,
            )
        )
        assertNull(store.load()!!.presetSec)
    }

    @Test
    fun `load returns permanent pause with null end`() = runTest {
        every { dataStore.data } returns flowOf(
            preferencesOf(PiholePauseStore.PAUSE_PERMANENT to true)
        )
        val s = store.load()!!
        assertTrue(s.permanent)
        assertNull(s.endAtMillis)
    }

    @Test
    fun `save writes finite pause`() = runTest {
        coEvery { dataStore.updateData(any()) } coAnswers {
            val transform = firstArg<suspend (Preferences) -> Preferences>()
            transform(preferencesOf())
        }
        store.save(endAtMillis = 9_000_000L, presetSec = 1800, permanent = false)
    }

    @Test
    fun `clear empties the store`() = runTest {
        coEvery { dataStore.updateData(any()) } coAnswers {
            val transform = firstArg<suspend (Preferences) -> Preferences>()
            transform(
                preferencesOf(
                    PiholePauseStore.PAUSE_END_AT to 1L,
                    PiholePauseStore.PAUSE_PERMANENT to true,
                )
            )
        }
        store.clear()
    }
}
