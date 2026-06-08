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

        // ── 防检测设置键 ─────────────────────────────────────────────────
        val STEALTH_PORT_HOPPING = booleanPreferencesKey("stealth_port_hopping")
        val STEALTH_TIMING_JITTER = booleanPreferencesKey("stealth_timing_jitter")
        val STEALTH_PACKET_PADDING = booleanPreferencesKey("stealth_packet_padding")
        val STEALTH_PADDING_MTU = intPreferencesKey("stealth_padding_mtu")
        val STEALTH_KEEPALIVE_RANDOM = booleanPreferencesKey("stealth_keepalive_random")
        val STEALTH_KEEPALIVE_JITTER_SEC = intPreferencesKey("stealth_keepalive_jitter_sec")
        val STEALTH_DECOY_DNS = booleanPreferencesKey("stealth_decoy_dns")
        val STEALTH_AUTO_RECONNECT = booleanPreferencesKey("stealth_auto_reconnect")
        val STEALTH_CANDIDATE_PORTS = stringPreferencesKey("stealth_candidate_ports")
        val STEALTH_JITTER_MIN_MS = intPreferencesKey("stealth_jitter_min_ms")
        val STEALTH_JITTER_MAX_MS = intPreferencesKey("stealth_jitter_max_ms")
        // ─────────────────────────────────────────────────────────────────
    }

    fun getTheme(): Flow<String> = dataStore.data.map { it[THEME] ?: "system" }

    fun getLocale(): Flow<String> = dataStore.data.map { prefs ->
        prefs[LOCALE] ?: run {
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

    // ── 防检测设置读取 ────────────────────────────────────────────────────

    fun getStealthPortHopping(): Flow<Boolean> =
        dataStore.data.map { it[STEALTH_PORT_HOPPING] ?: false }

    fun getStealthTimingJitter(): Flow<Boolean> =
        dataStore.data.map { it[STEALTH_TIMING_JITTER] ?: false }

    fun getStealthPacketPadding(): Flow<Boolean> =
        dataStore.data.map { it[STEALTH_PACKET_PADDING] ?: false }

    fun getStealthPaddingMtu(): Flow<Int> =
        dataStore.data.map { it[STEALTH_PADDING_MTU] ?: 1280 }

    fun getStealthKeepaliveRandom(): Flow<Boolean> =
        dataStore.data.map { it[STEALTH_KEEPALIVE_RANDOM] ?: false }

    fun getStealthKeepaliveJitterSec(): Flow<Int> =
        dataStore.data.map { it[STEALTH_KEEPALIVE_JITTER_SEC] ?: 5 }

    fun getStealthDecoyDns(): Flow<Boolean> =
        dataStore.data.map { it[STEALTH_DECOY_DNS] ?: false }

    fun getStealthAutoReconnect(): Flow<Boolean> =
        dataStore.data.map { it[STEALTH_AUTO_RECONNECT] ?: false }

    /** 存储为逗号分隔字符串，如 "443,80,8080,51820" */
    fun getStealthCandidatePorts(): Flow<List<Int>> =
        dataStore.data.map { prefs ->
            prefs[STEALTH_CANDIDATE_PORTS]
                ?.split(",")
                ?.mapNotNull { it.trim().toIntOrNull() }
                ?.filter { it in 1..65535 }
                ?.takeIf { it.isNotEmpty() }
                ?: listOf(443, 80, 8080, 8443, 53, 123, 51820)
        }

    fun getStealthJitterMinMs(): Flow<Int> =
        dataStore.data.map { it[STEALTH_JITTER_MIN_MS] ?: 100 }

    fun getStealthJitterMaxMs(): Flow<Int> =
        dataStore.data.map { it[STEALTH_JITTER_MAX_MS] ?: 800 }

    // ── 防检测设置写入 ────────────────────────────────────────────────────

    suspend fun setStealthPortHopping(enabled: Boolean) {
        dataStore.edit { it[STEALTH_PORT_HOPPING] = enabled }
    }

    suspend fun setStealthTimingJitter(enabled: Boolean) {
        dataStore.edit { it[STEALTH_TIMING_JITTER] = enabled }
    }

    suspend fun setStealthPacketPadding(enabled: Boolean) {
        dataStore.edit { it[STEALTH_PACKET_PADDING] = enabled }
    }

    suspend fun setStealthPaddingMtu(mtu: Int) {
        val clamped = mtu.coerceIn(1024, 1500)
        dataStore.edit { it[STEALTH_PADDING_MTU] = clamped }
    }

    suspend fun setStealthKeepaliveRandom(enabled: Boolean) {
        dataStore.edit { it[STEALTH_KEEPALIVE_RANDOM] = enabled }
    }

    suspend fun setStealthKeepaliveJitterSec(jitter: Int) {
        val clamped = jitter.coerceIn(1, 30)
        dataStore.edit { it[STEALTH_KEEPALIVE_JITTER_SEC] = clamped }
    }

    suspend fun setStealthDecoyDns(enabled: Boolean) {
        dataStore.edit { it[STEALTH_DECOY_DNS] = enabled }
    }

    suspend fun setStealthAutoReconnect(enabled: Boolean) {
        dataStore.edit { it[STEALTH_AUTO_RECONNECT] = enabled }
    }

    suspend fun setStealthCandidatePorts(ports: List<Int>) {
        val valid = ports.filter { it in 1..65535 }.distinct()
        dataStore.edit { it[STEALTH_CANDIDATE_PORTS] = valid.joinToString(",") }
    }

    suspend fun setStealthJitterMinMs(ms: Int) {
        val clamped = ms.coerceIn(0, 2000)
        dataStore.edit { it[STEALTH_JITTER_MIN_MS] = clamped }
    }

    suspend fun setStealthJitterMaxMs(ms: Int) {
        val clamped = ms.coerceIn(50, 5000)
        dataStore.edit { it[STEALTH_JITTER_MAX_MS] = clamped }
    }

    // ─────────────────────────────────────────────────────────────────────

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
     * 迁移旧分流配置（v1 → v2）。
     */
    suspend fun migrateSplitTunnelIfNeeded() {
        dataStore.edit { prefs ->
            val oldEnabled = prefs[SPLIT_TUNNEL_ENABLED]
            if (oldEnabled != null && prefs[SPLIT_TUNNEL_MODE] == null) {
                prefs[SPLIT_TUNNEL_MODE] = if (oldEnabled) "include" else "off"

                val oldRoutes = prefs[SPLIT_TUNNEL_ROUTES] ?: ""
                if (oldRoutes.isNotBlank()) {
                    val networks = oldRoutes.split("\n", ",")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .map { """{"cidr":"$it","label":""}""" }
                    prefs[SPLIT_TUNNEL_NETWORKS] = "[${networks.joinToString(",")}]"
                }

                val oldApps = prefs[SPLIT_TUNNEL_APPS] ?: ""
                if (oldApps.isNotBlank()) {
                    val apps = oldApps.split("\n", ",")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .map { """{"package":"$it","label":""}""" }
                    prefs[SPLIT_TUNNEL_APPS_V2] = "[${apps.joinToString(",")}]"
                }

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
