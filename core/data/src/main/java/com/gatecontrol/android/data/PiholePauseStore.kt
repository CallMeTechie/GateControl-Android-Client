package com.gatecontrol.android.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** Persisted Pi-hole pause state for restore across app restarts. presetSec=null ⇒ permanent/unknown. */
data class PiholePauseState(
    val endAtMillis: Long?,
    val presetSec: Int?,
    val permanent: Boolean,
)

@Singleton
class PiholePauseStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    companion object {
        val PAUSE_END_AT = longPreferencesKey("pihole_pause_end_at")
        val PAUSE_PRESET = intPreferencesKey("pihole_pause_preset") // -1 = permanent/unknown
        val PAUSE_PERMANENT = booleanPreferencesKey("pihole_pause_permanent")
    }

    /** Reads the persisted pause state, or null when nothing is stored. */
    suspend fun load(): PiholePauseState? {
        val prefs = dataStore.data.first()
        val permanent = prefs[PAUSE_PERMANENT] ?: false
        val end = prefs[PAUSE_END_AT]
        val preset = prefs[PAUSE_PRESET]
        if (!permanent && end == null) return null
        return PiholePauseState(
            endAtMillis = if (permanent) null else end,
            presetSec = if (preset == null || preset < 0) null else preset,
            permanent = permanent,
        )
    }

    /** Saves a finite (endAtMillis + presetSec) or permanent (permanent=true, endAtMillis ignored) pause. */
    suspend fun save(endAtMillis: Long?, presetSec: Int?, permanent: Boolean) {
        dataStore.edit {
            it[PAUSE_PERMANENT] = permanent
            if (permanent || endAtMillis == null) it.remove(PAUSE_END_AT) else it[PAUSE_END_AT] = endAtMillis
            it[PAUSE_PRESET] = presetSec ?: -1
        }
    }

    /** Removes all persisted pause keys. */
    suspend fun clear() {
        dataStore.edit {
            it.remove(PAUSE_END_AT)
            it.remove(PAUSE_PRESET)
            it.remove(PAUSE_PERMANENT)
        }
    }
}
