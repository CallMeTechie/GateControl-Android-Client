package com.gatecontrol.android.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class PiholeLeftoversTest {

    private val endAt = longPreferencesKey("pihole_pause_end_at")
    private val preset = intPreferencesKey("pihole_pause_preset")
    private val permanent = booleanPreferencesKey("pihole_pause_permanent")
    private val theme = stringPreferencesKey("theme")
    private val tab = SettingsRepository.NETWORK_DEFAULT_TAB

    @Test
    fun `the pause state of the removed Pi-hole view is deleted, everything else stays`() = runTest {
        val before = preferencesOf(endAt to 1_700_000_000_000L, preset to 300, permanent to false, theme to "dark", tab to "remote")
        assertTrue(PiholeLeftovers.shouldMigrate(before))
        val after = PiholeLeftovers.migrate(before)
        assertEquals(preferencesOf(theme to "dark", tab to "remote"), after)
        assertFalse(PiholeLeftovers.shouldMigrate(after), "once is enough")
    }

    @Test
    fun `a stored default segment Pi-hole becomes DNS-Schutz`() = runTest {
        val after = PiholeLeftovers.migrate(preferencesOf(tab to "pihole", permanent to true))
        assertEquals(preferencesOf(tab to "dns"), after)
        assertTrue(PiholeLeftovers.shouldMigrate(preferencesOf(tab to "pihole")))
    }

    @Test
    fun `nothing to do on a store without leftovers`() = runTest {
        assertFalse(PiholeLeftovers.shouldMigrate(preferencesOf()))
        assertFalse(PiholeLeftovers.shouldMigrate(preferencesOf(theme to "light", tab to "dns")))
    }

    @Test
    fun `runs when the real store is first read`(@TempDir dir: File) = runTest {
        // as an app up to 1.105 left it (written by its own store; the updated app opens a copy of the file)
        val old = File(dir, "old.preferences_pb")
        val oldScope = TestScope(UnconfinedTestDispatcher(testScheduler))
        PreferenceDataStoreFactory.create(scope = oldScope, produceFile = { old }).edit {
            it[endAt] = 1L; it[preset] = -1; it[permanent] = true; it[tab] = "pihole"; it[theme] = "dark"
        }
        oldScope.cancel()
        val file = old.copyTo(File(dir, "s.preferences_pb"))

        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        val store = PreferenceDataStoreFactory.create(migrations = listOf(PiholeLeftovers), scope = scope, produceFile = { file })
        val prefs = store.data.first()
        assertNull(prefs[endAt]); assertNull(prefs[preset]); assertNull(prefs[permanent])
        assertEquals("dns", prefs[tab])
        assertEquals("dark", prefs[theme])
        scope.cancel()
    }
}
