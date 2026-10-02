package com.gatecontrol.android.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.gatecontrol.android.common.ClientPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Last known client policy from the server (see [ClientPolicy]). Persisted so
 * it applies offline and after a reboot; null = never fetched (no
 * restriction).
 */
@Singleton
class ClientPolicyRepository @Inject constructor(private val dataStore: DataStore<Preferences>) {

    data class Cached(val policy: ClientPolicy, val version: String?)

    companion object {
        val POLICY_JSON = stringPreferencesKey("client_policy_json")
        val POLICY_VERSION = stringPreferencesKey("client_policy_version")
    }

    /** Cached policy, null when none was ever fetched (or the cache is unreadable). */
    fun cached(): Flow<Cached?> = dataStore.data.map { prefs ->
        ClientPolicy.fromJson(prefs[POLICY_JSON])?.let { Cached(it, prefs[POLICY_VERSION]) }
    }

    suspend fun current(): Cached? = cached().first()

    suspend fun save(policy: ClientPolicy, version: String?) {
        dataStore.edit { prefs ->
            prefs[POLICY_JSON] = policy.toJson()
            if (version.isNullOrBlank()) prefs.remove(POLICY_VERSION) else prefs[POLICY_VERSION] = version
        }
    }

    /** Drop the cache (server changed): back to "never fetched". */
    suspend fun clear() {
        dataStore.edit { prefs ->
            prefs.remove(POLICY_JSON)
            prefs.remove(POLICY_VERSION)
        }
    }
}
