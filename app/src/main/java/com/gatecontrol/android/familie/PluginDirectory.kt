package com.gatecontrol.android.familie

import android.content.Context
import androidx.core.content.edit
import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.network.ApiClientProvider
import com.gatecontrol.android.network.ClientPlugin
import com.gatecontrol.android.support.CrashReports
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** What the server's plugins offer this app (`GET /api/v1/client/plugins`). */
data class PluginAvailability(
    /** The Familie plugin is listed with an app tab for this user. */
    val familieTab: Boolean = false,
    /** SVG path of the tab icon (24×24 stroke), as the plugin declares it. */
    val familieIcon: String? = null,
    /** The Familie plugin is listed at all (its API answers). */
    val familieApi: Boolean = false,
    /** The Alltag plugin is listed: the dates screen also shows its appointments. */
    val alltag: Boolean = false,
    /**
     * The door plugin ("Tür & Kamera") is listed for this user. It never
     * gets a tab of its own (its `ui.app` entry is ignored): its pushes and
     * a long press on its quick actions open the door screen (docs/DOOR.md).
     */
    val door: Boolean = false,
    /**
     * The messenger plugin is listed with an app tab for this user (core
     * 1.177+, not on kiosk devices): the tab "Chats" (docs/FEATURES.md).
     */
    val messengerTab: Boolean = false,
    /**
     * The Schule plugin (WebUntis timetable) is listed for this user: the
     * Familie tab gets the card "Stundenplan" and its screen. It never gets
     * a tab of its own (docs/FAMILIE.md "Stundenplan").
     */
    val schule: Boolean = false,
    /**
     * The GateDrop plugin is listed for this user: "Teilen → GateControl"
     * shows the GateDrop sheet (people, own devices, NAS, link), the
     * screen "Geteilt" and GateDrop's pushes (contract F).
     */
    val gatedrop: Boolean = false,
    /**
     * The Vault plugin (password manager, end-to-end encrypted) is listed for
     * this user: the tab "Vault" and the autofill service.
     */
    val vault: Boolean = false,
    /**
     * The Drive plugin (files of the person, the family and the team) is
     * listed for this user: the tab "Drive".
     */
    val drive: Boolean = false,
) {
    companion object {
        val NONE = PluginAvailability()

        /** Pure: which features a plugin list switches on. */
        fun from(plugins: List<ClientPlugin>): PluginAvailability {
            val familie = plugins.firstOrNull { it.id == FAMILIE_PLUGIN }
            val tab = familie?.tab
            return PluginAvailability(
                familieTab = tab != null,
                familieIcon = icon(tab?.icon),
                familieApi = familie != null,
                alltag = plugins.any { it.id == ALLTAG_PLUGIN },
                door = plugins.any { it.id == com.gatecontrol.android.door.DOOR_PLUGIN },
                messengerTab = plugins.any { it.id == com.gatecontrol.android.chat.MESSENGER_PLUGIN && it.tab != null },
                schule = plugins.any { it.id == com.gatecontrol.android.familie.school.SCHULE_PLUGIN },
                gatedrop = plugins.any { it.id == com.gatecontrol.android.gatedrop.GATEDROP_PLUGIN },
                vault = plugins.any { it.id == com.gatecontrol.android.vault.VAULT_PLUGIN },
                drive = plugins.any { it.id == com.gatecontrol.android.drive.DRIVE_PLUGIN },
            )
        }

        private val ICON_RE = Regex("^[MmLlHhVvCcSsQqTtAaZz0-9 ,.+\\-eE]+$")

        private fun icon(path: String?): String? = path?.takeIf { it.length in 1..2000 && ICON_RE.matches(it) }
    }
}

/**
 * Plugin list of the server, cached on the device so the Familie tab is
 * there right from the start (no flicker) and stays while offline. Loaded
 * on start and when the app comes to the foreground (at most every few
 * minutes). An older server (404) or a token without the scope has no
 * plugins: no tab.
 */
@Singleton
class PluginDirectory @Inject constructor(
    @ApplicationContext context: Context,
    private val setupRepository: SetupRepository,
    private val apiClientProvider: ApiClientProvider,
    private val settingsRepository: SettingsRepository,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashReports.coroutineHandler)
    private val mutex = Mutex()
    private val gson = Gson()

    @Volatile private var lastRefreshMs = 0L
    @Volatile private var lastAttemptMs = 0L

    private val _state = MutableStateFlow(readCache())
    val state: StateFlow<PluginAvailability> = _state.asStateFlow()

    init {
        // Another server or token: forget what the old one offered.
        scope.launch {
            setupRepository.revision.collect {
                val cached = readCache()
                if (cached != _state.value) _state.value = cached
                lastRefreshMs = 0L
                lastAttemptMs = 0L
            }
        }
    }

    private fun ownerKey(): String = ownerKey(setupRepository.getServerUrl(), setupRepository.getApiToken())

    private fun readCache(): PluginAvailability {
        if (prefs.getString(KEY_OWNER, null) != ownerKey()) return PluginAvailability.NONE
        return runCatching { gson.fromJson(prefs.getString(KEY_STATE, null), PluginAvailability::class.java) }.getOrNull()
            ?: PluginAvailability.NONE
    }

    private fun writeCache(value: PluginAvailability) {
        prefs.edit {
            putString(KEY_OWNER, ownerKey())
            putString(KEY_STATE, gson.toJson(value))
        }
    }

    /** Asks the server; keeps the cached answer when it cannot be reached. */
    suspend fun refresh(): Boolean = mutex.withLock {
        lastAttemptMs = System.currentTimeMillis()
        val url = setupRepository.getServerUrl()
        if (url.isBlank() || !setupRepository.isConfigured()) {
            _state.value = PluginAvailability.NONE
            return@withLock false
        }
        try {
            val lang = runCatching { settingsRepository.getLocale().first() }.getOrNull()?.takeIf { it == "de" || it == "en" } ?: "de"
            val response = withTimeoutOrNull(TIMEOUT_MS) { apiClientProvider.getClient(url).getClientPlugins(lang) }
                ?: return@withLock false
            val next = when {
                response.isSuccessful -> PluginAvailability.from(response.body()?.plugins.orEmpty())
                // Older server, no owner or scope: no plugins for this app.
                response.code() == 404 || response.code() == 401 || response.code() == 403 -> PluginAvailability.NONE
                else -> return@withLock false
            }
            lastRefreshMs = System.currentTimeMillis()
            writeCache(next)
            _state.value = next
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d("Plugins: list unavailable (%s)", e.javaClass.simpleName)
            false
        }
    }

    /** On start / foreground: refresh unless it was done a moment ago. */
    fun refreshIfStale() {
        val now = System.currentTimeMillis()
        if (now - maxOf(lastRefreshMs, lastAttemptMs) < MIN_INTERVAL_MS) return
        lastAttemptMs = now
        scope.launch { refresh() }
    }

    companion object {
        private const val PREFS = "familie_plugins"
        private const val KEY_OWNER = "owner"
        private const val KEY_STATE = "state"
        private const val TIMEOUT_MS = 8_000L
        private const val MIN_INTERVAL_MS = 3 * 60_000L

        /** Short hash of server + token: cached data belongs to exactly this login. */
        fun ownerKey(serverUrl: String, token: String): String {
            if (serverUrl.isBlank()) return ""
            val digest = MessageDigest.getInstance("SHA-256").digest("$serverUrl\n$token".toByteArray())
            return digest.take(12).joinToString("") { "%02x".format(it) }
        }
    }
}
