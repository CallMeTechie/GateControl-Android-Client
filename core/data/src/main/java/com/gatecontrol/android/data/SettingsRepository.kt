package com.gatecontrol.android.data

import com.gatecontrol.android.common.SplitTunnelMode
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Vertrauenswürdige WLANs": in a trusted WLAN the VPN disconnects, outside
 * it connects again. [lastActedKey] is the network identity the monitor
 * last handled — it only acts when the identity changes.
 */
data class TrustedNetworkSettings(
    val enabled: Boolean = false,
    val homeEnabled: Boolean = true,
    val ssids: Set<String> = emptySet(),
    val lastActedKey: String? = null,
)

@Singleton
class SettingsRepository @Inject constructor(private val dataStore: DataStore<Preferences>) {

    companion object {
        val THEME = stringPreferencesKey("theme")
        val LOCALE = stringPreferencesKey("locale")
        val AUTO_CONNECT = booleanPreferencesKey("auto_connect")
        val SPLIT_TUNNEL_ENABLED = booleanPreferencesKey("split_tunnel_enabled")
        val SPLIT_TUNNEL_ROUTES = stringPreferencesKey("split_tunnel_routes")
        val SPLIT_TUNNEL_APPS = stringPreferencesKey("split_tunnel_apps")
        // New split-tunnel keys (v2 JSON format)
        val SPLIT_TUNNEL_MODE = stringPreferencesKey("split_tunnel_mode")
        val SPLIT_TUNNEL_NETWORKS = stringPreferencesKey("split_tunnel_networks")
        val SPLIT_TUNNEL_APPS_V2 = stringPreferencesKey("split_tunnel_apps_v2")
        val SPLIT_TUNNEL_ADMIN_LOCKED = booleanPreferencesKey("split_tunnel_admin_locked")
        // Trusted WLANs
        val TRUSTED_NET_ENABLED = booleanPreferencesKey("trusted_net_enabled")
        val TRUSTED_NET_HOME_ENABLED = booleanPreferencesKey("trusted_net_home_enabled")
        val TRUSTED_NET_SSIDS = stringSetPreferencesKey("trusted_net_ssids")
        val TRUSTED_NET_LAST_KEY = stringPreferencesKey("trusted_net_last_key")
        // Services: favourites by domain (stable when a route is edited)
        val SERVICE_FAVORITES = stringSetPreferencesKey("service_favorites")
        // Quick actions pinned to the start screen, shortcuts, tiles and widget (JSON list)
        val QUICK_ACTION_PINS = stringPreferencesKey("quick_action_pins")
        /** Location sharing on tap (satellite button on the start screen). */
        val LOCATION_SHARING = booleanPreferencesKey("location_sharing")
        /** Last position sent, JSON (format owned by the app's LocationShareRepository). */
        val LOCATION_LAST_SENT = stringPreferencesKey("location_last_sent")
        /** Own profile (picture URL, favourite colour), JSON owned by the app's ProfileRepository. */
        val PROFILE_CACHE = stringPreferencesKey("profile_cache")
        /** Start tab: page of the throughput/data usage card shown first ("throughput" | "usage"). */
        val USAGE_CARD_FAVORITE = stringPreferencesKey("usage_card_favorite")
        /** Start tab "Info" card: chosen contents per login, JSON owned by the app's InfoCardRepository. */
        val INFO_CARD = stringPreferencesKey("info_card")
        /** Netzwerk tab: segment shown first ("services" | "pihole" | "remote"), chosen by a long press. */
        val NETWORK_DEFAULT_TAB = stringPreferencesKey("network_default_tab")
        /** Start tab: devices pinned as widgets, JSON owned by the app's StartWidgetPin. */
        val START_WIDGETS = stringPreferencesKey("start_widgets")
        /** Order of the start and Familie tabs (and the server's scope for it), JSON owned by the app's TabLayoutRepository. */
        val TAB_LAYOUT = stringPreferencesKey("tab_layout")
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

    fun getSplitTunnelEnabled(): Flow<Boolean> =
        dataStore.data.map { it[SPLIT_TUNNEL_ENABLED] ?: false }

    fun getSplitTunnelRoutes(): Flow<String> =
        dataStore.data.map { it[SPLIT_TUNNEL_ROUTES] ?: "" }

    fun getSplitTunnelApps(): Flow<String> =
        dataStore.data.map { it[SPLIT_TUNNEL_APPS] ?: "" }

    // New split-tunnel getters (v2 JSON format)
    fun getSplitTunnelMode(): Flow<SplitTunnelMode> =
        dataStore.data.map { SplitTunnelMode.fromWire(it[SPLIT_TUNNEL_MODE]) }

    fun getSplitTunnelNetworks(): Flow<String> =
        dataStore.data.map { it[SPLIT_TUNNEL_NETWORKS] ?: "[]" }

    fun getSplitTunnelAppsV2(): Flow<String> =
        dataStore.data.map { it[SPLIT_TUNNEL_APPS_V2] ?: "[]" }

    fun getSplitTunnelAdminLocked(): Flow<Boolean> =
        dataStore.data.map { it[SPLIT_TUNNEL_ADMIN_LOCKED] ?: false }

    fun getTrustedNetworkSettings(): Flow<TrustedNetworkSettings> = dataStore.data.map { prefs ->
        TrustedNetworkSettings(
            enabled = prefs[TRUSTED_NET_ENABLED] ?: false,
            homeEnabled = prefs[TRUSTED_NET_HOME_ENABLED] ?: true,
            ssids = prefs[TRUSTED_NET_SSIDS] ?: emptySet(),
            lastActedKey = prefs[TRUSTED_NET_LAST_KEY],
        )
    }

    suspend fun setTrustedNetworksEnabled(value: Boolean) {
        dataStore.edit { it[TRUSTED_NET_ENABLED] = value }
    }

    suspend fun setTrustedHomeEnabled(value: Boolean) {
        dataStore.edit { it[TRUSTED_NET_HOME_ENABLED] = value }
    }

    suspend fun setTrustedSsids(ssids: Set<String>) {
        dataStore.edit { it[TRUSTED_NET_SSIDS] = ssids }
    }

    /** Atomic add / remove, so two quick edits never overwrite each other. */
    suspend fun updateTrustedSsids(transform: (Set<String>) -> Set<String>) {
        dataStore.edit { it[TRUSTED_NET_SSIDS] = transform(it[TRUSTED_NET_SSIDS] ?: emptySet()) }
    }

    suspend fun setTrustedLastActedKey(key: String) {
        dataStore.edit { it[TRUSTED_NET_LAST_KEY] = key }
    }

    /** Favourite services (domains), shown on top of the list. */
    fun getServiceFavorites(): Flow<Set<String>> =
        dataStore.data.map { it[SERVICE_FAVORITES] ?: emptySet() }

    /** Pinned quick actions as JSON (format owned by the app's QuickActionRepository). */
    fun getQuickActionPins(): Flow<String?> = dataStore.data.map { it[QUICK_ACTION_PINS] }

    /** Atomic read-modify-write of the pinned quick actions. */
    suspend fun updateQuickActionPins(transform: (String?) -> String) {
        dataStore.edit { it[QUICK_ACTION_PINS] = transform(it[QUICK_ACTION_PINS]) }
    }

    fun getLocationSharing(): Flow<Boolean> = dataStore.data.map { it[LOCATION_SHARING] ?: false }

    suspend fun setLocationSharing(value: Boolean) {
        dataStore.edit { it[LOCATION_SHARING] = value }
    }

    fun getLocationLastSent(): Flow<String?> = dataStore.data.map { it[LOCATION_LAST_SENT] }

    suspend fun setLocationLastSent(value: String?) {
        dataStore.edit { if (value == null) it.remove(LOCATION_LAST_SENT) else it[LOCATION_LAST_SENT] = value }
    }

    /** Last answer of `GET /client/me/profile` (offline / fast start), null = none. */
    fun getProfileCache(): Flow<String?> = dataStore.data.map { it[PROFILE_CACHE] }

    suspend fun setProfileCache(value: String?) {
        dataStore.edit { if (value == null) it.remove(PROFILE_CACHE) else it[PROFILE_CACHE] = value }
    }

    /** Page of the throughput/data usage card shown first, null = never chosen (the app's default). */
    fun getUsageCardFavorite(): Flow<String?> = dataStore.data.map { it[USAGE_CARD_FAVORITE] }

    suspend fun setUsageCardFavorite(value: String) {
        dataStore.edit { it[USAGE_CARD_FAVORITE] = value }
    }

    /** Devices pinned to the start tab as widgets (JSON, see the app's StartWidgetPin). */
    fun getStartWidgets(): Flow<String?> = dataStore.data.map { it[START_WIDGETS] }

    /** Atomic read-modify-write of the widget pins. */
    suspend fun updateStartWidgets(transform: (String?) -> String) {
        dataStore.edit { it[START_WIDGETS] = transform(it[START_WIDGETS]) }
    }

    fun getTabLayout(): Flow<String?> = dataStore.data.map { it[TAB_LAYOUT] }

    /** Atomic read-modify-write of the tab order. */
    suspend fun updateTabLayout(transform: (String?) -> String) {
        dataStore.edit { it[TAB_LAYOUT] = transform(it[TAB_LAYOUT]) }
    }

    /** Segment the Netzwerk tab opens with, null = never chosen (the first available). */
    fun getNetworkDefaultTab(): Flow<String?> = dataStore.data.map { it[NETWORK_DEFAULT_TAB] }

    suspend fun setNetworkDefaultTab(value: String) {
        dataStore.edit { it[NETWORK_DEFAULT_TAB] = value }
    }

    /** Contents of the "Info" card on the start tab (JSON, see the app's InfoCardRepository). */
    fun getInfoCard(): Flow<String?> = dataStore.data.map { it[INFO_CARD] }

    /** Atomic read-modify-write of the "Info" card contents. */
    suspend fun updateInfoCard(transform: (String?) -> String) {
        dataStore.edit { it[INFO_CARD] = transform(it[INFO_CARD]) }
    }

    /** Adds or removes [domain]; atomic, so two quick taps never lose one. */
    suspend fun toggleServiceFavorite(domain: String) {
        dataStore.edit {
            val current = it[SERVICE_FAVORITES] ?: emptySet()
            it[SERVICE_FAVORITES] = if (domain in current) current - domain else current + domain
        }
    }



    suspend fun setTheme(value: String) {
        dataStore.edit { it[THEME] = value }
    }

    suspend fun setLocale(value: String) {
        dataStore.edit { it[LOCALE] = value }
    }

    suspend fun setAutoConnect(value: Boolean) {
        dataStore.edit { it[AUTO_CONNECT] = value }
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
    suspend fun setSplitTunnelMode(mode: SplitTunnelMode) {
        dataStore.edit { it[SPLIT_TUNNEL_MODE] = mode.wire }
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
                prefs[SPLIT_TUNNEL_MODE] = if (oldEnabled) SplitTunnelMode.INCLUDE.wire else SplitTunnelMode.OFF.wire

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


}
