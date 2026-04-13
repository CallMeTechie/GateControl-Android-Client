package com.gatecontrol.android.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepository @Inject constructor(private val dataStore: DataStore<Preferences>) {

    companion object {
        val THEME = stringPreferencesKey("theme")
        val LOCALE = stringPreferencesKey("locale")
        val AUTO_CONNECT = booleanPreferencesKey("auto_connect")
        val KILL_SWITCH = booleanPreferencesKey("kill_switch")
        val SPLIT_TUNNEL_ENABLED = booleanPreferencesKey("split_tunnel_enabled")
        val SPLIT_TUNNEL_ROUTES = stringPreferencesKey("split_tunnel_routes")
        val SPLIT_TUNNEL_APPS = stringPreferencesKey("split_tunnel_apps")
        // New split-tunnel keys (v2 JSON format)
        val SPLIT_TUNNEL_MODE = stringPreferencesKey("split_tunnel_mode")
        val SPLIT_TUNNEL_NETWORKS = stringPreferencesKey("split_tunnel_networks")
        val SPLIT_TUNNEL_APPS_V2 = stringPreferencesKey("split_tunnel_apps_v2")
        val SPLIT_TUNNEL_ADMIN_LOCKED = booleanPreferencesKey("split_tunnel_admin_locked")
        val CHECK_INTERVAL = intPreferencesKey("check_interval")
        val CONFIG_POLL_INTERVAL = intPreferencesKey("config_poll_interval")
    }

    fun getTheme(): Flow<String> = dataStore.data.map { it[THEME] ?: "system" }

    fun getLocale(): Flow<String> = dataStore.data.map { prefs ->
        prefs[LOCALE] ?: run {
            // Default to system language, fall back to English if not supported
            val sysLang = java.util.Locale.getDefault().language
            if (sysLang == "de") "de" else "en"
        }
    }

    fun getAutoConnect(): Flow<Boolean> = dataStore.data.map { it[AUTO_CONNECT] ?: false }

    fun getKillSwitch(): Flow<Boolean> = dataStore.data.map { it[KILL_SWITCH] ?: false }

    fun getSplitTunnelEnabled(): Flow<Boolean> =
        dataStore.data.map { it[SPLIT_TUNNEL_ENABLED] ?: false }

    fun getSplitTunnelRoutes(): Flow<String> =
        dataStore.data.map { it[SPLIT_TUNNEL_ROUTES] ?: "" }

    fun getSplitTunnelApps(): Flow<String> =
        dataStore.data.map { it[SPLIT_TUNNEL_APPS] ?: "" }

    // New split-tunnel getters (v2 JSON format)
    fun getSplitTunnelMode(): Flow<String> =
        dataStore.data.map { it[SPLIT_TUNNEL_MODE] ?: "off" }

    fun getSplitTunnelNetworks(): Flow<String> =
        dataStore.data.map { it[SPLIT_TUNNEL_NETWORKS] ?: "[]" }

    fun getSplitTunnelAppsV2(): Flow<String> =
        dataStore.data.map { it[SPLIT_TUNNEL_APPS_V2] ?: "[]" }

    fun getSplitTunnelAdminLocked(): Flow<Boolean> =
        dataStore.data.map { it[SPLIT_TUNNEL_ADMIN_LOCKED] ?: false }

    fun getCheckInterval(): Flow<Int> = dataStore.data.map { it[CHECK_INTERVAL] ?: 30 }

    fun getConfigPollInterval(): Flow<Int> =
        dataStore.data.map { it[CONFIG_POLL_INTERVAL] ?: 300 }

    suspend fun setTheme(value: String) {
        dataStore.edit { it[THEME] = value }
    }

    suspend fun setLocale(value: String) {
        dataStore.edit { it[LOCALE] = value }
    }

    suspend fun setAutoConnect(value: Boolean) {
        dataStore.edit { it[AUTO_CONNECT] = value }
    }

    suspend fun setKillSwitch(value: Boolean) {
        dataStore.edit { it[KILL_SWITCH] = value }
    }

    suspend fun setSplitTunnelEnabled(value: Boolean) {
        dataStore.edit { it[SPLIT_TUNNEL_ENABLED] = value }
    }

    suspend fun setSplitTunnelRoutes(value: String) {
        dataStore.edit { it[SPLIT_TUNNEL_ROUTES] = value }
    }

    suspend fun setSplitTunnelApps(value: String) {
        dataStore.edit { it[SPLIT_TUNNEL_APPS] = value }
    }

    // New split-tunnel setters (v2 JSON format)
    suspend fun setSplitTunnelMode(mode: String) {
        dataStore.edit { it[SPLIT_TUNNEL_MODE] = mode }
    }

    suspend fun setSplitTunnelNetworks(json: String) {
        dataStore.edit { it[SPLIT_TUNNEL_NETWORKS] = json }
    }

    suspend fun setSplitTunnelAppsV2(json: String) {
        dataStore.edit { it[SPLIT_TUNNEL_APPS_V2] = json }
    }

    suspend fun setSplitTunnelAdminLocked(locked: Boolean) {
        dataStore.edit { it[SPLIT_TUNNEL_ADMIN_LOCKED] = locked }
    }

    /**
     * Migrates old split-tunnel preferences (v1) to new JSON format (v2).
     * Only runs once: when old keys exist but new keys don't.
     */
    suspend fun migrateSplitTunnelIfNeeded() {
        dataStore.edit { prefs ->
            val oldEnabled = prefs[SPLIT_TUNNEL_ENABLED]
            if (oldEnabled != null && prefs[SPLIT_TUNNEL_MODE] == null) {
                // Mode: old enabled=true was include-mode (only these routes through VPN)
                prefs[SPLIT_TUNNEL_MODE] = if (oldEnabled) "include" else "off"

                // Routes: newline/comma-separated CIDRs → JSON with empty labels
                val oldRoutes = prefs[SPLIT_TUNNEL_ROUTES] ?: ""
                if (oldRoutes.isNotBlank()) {
                    val networks = oldRoutes.split("\n", ",")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .map { """{"cidr":"$it","label":""}""" }
                    prefs[SPLIT_TUNNEL_NETWORKS] = "[${networks.joinToString(",")}]"
                }

                // Apps: comma/newline-separated packages → JSON with empty labels
                val oldApps = prefs[SPLIT_TUNNEL_APPS] ?: ""
                if (oldApps.isNotBlank()) {
                    val apps = oldApps.split("\n", ",")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .map { """{"package":"$it","label":""}""" }
                    prefs[SPLIT_TUNNEL_APPS_V2] = "[${apps.joinToString(",")}]"
                }

                // Clear old keys
                prefs.remove(SPLIT_TUNNEL_ENABLED)
                prefs.remove(SPLIT_TUNNEL_ROUTES)
                prefs.remove(SPLIT_TUNNEL_APPS)
            }
        }
    }

    suspend fun setCheckInterval(value: Int) {
        val clamped = value.coerceIn(5, 300)
        dataStore.edit { it[CHECK_INTERVAL] = clamped }
    }

    suspend fun setConfigPollInterval(value: Int) {
        val clamped = value.coerceIn(30, 3600)
        dataStore.edit { it[CONFIG_POLL_INTERVAL] = clamped }
    }
}
