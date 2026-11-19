package com.gatecontrol.android.data

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * What the removed Pi-hole view (until 1.105) left in the settings store:
 * the pause state (`pihole_pause_*`) and a default Netzwerk segment
 * "pihole". Runs once, when the store is first read after the update
 * (DataStore migration, [DataModule][com.gatecontrol.android.data.di.DataModule]);
 * after that [shouldMigrate] finds nothing and it does nothing.
 */
object PiholeLeftovers : DataMigration<Preferences> {

    /** The keys of the former PiholePauseStore, by name: their types do not matter for removing them. */
    private val PAUSE_KEYS = listOf("pihole_pause_end_at", "pihole_pause_preset", "pihole_pause_permanent")

    /** The segment that stood for Pi-hole; DNS-Schutz takes its place (as the app did since 1.106). */
    private const val OLD_SEGMENT = "pihole"
    private const val NEW_SEGMENT = "dns"

    private fun pauseKeys(prefs: Preferences): List<Preferences.Key<*>> =
        prefs.asMap().keys.filter { it.name in PAUSE_KEYS }

    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        pauseKeys(currentData).isNotEmpty() || currentData[SettingsRepository.NETWORK_DEFAULT_TAB] == OLD_SEGMENT

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()
        pauseKeys(currentData).forEach { prefs.remove(it) }
        if (prefs[SettingsRepository.NETWORK_DEFAULT_TAB] == OLD_SEGMENT) prefs[SettingsRepository.NETWORK_DEFAULT_TAB] = NEW_SEGMENT
        return prefs.toPreferences()
    }

    override suspend fun cleanUp() = Unit
}
