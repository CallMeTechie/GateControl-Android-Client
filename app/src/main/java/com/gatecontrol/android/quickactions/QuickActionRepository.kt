package com.gatecontrol.android.quickactions

import android.content.Context
import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.network.ApiClientProvider
import com.gatecontrol.android.network.QuickAction
import com.gatecontrol.android.support.CrashReports
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import retrofit2.HttpException
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** A quick action as the app keeps it: valid ids, a kind it knows. */
data class QuickActionItem(
    val plugin: String,
    val pluginName: String,
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val toggle: Boolean = false,
    /** "on" | "off" for a switch, else null. */
    val state: String? = null,
    val confirm: Boolean = false,
    /**
     * SVG path `d` of the action's icon (24×24 stroke, like a plugin tab
     * icon), null = none (the bolt). Added in 1.32: pins stored before have
     * no such field and Gson leaves it null.
     */
    val icon: String? = null,
) {
    val key: String get() = "$plugin/$id"
    val isOn: Boolean get() = toggle && state == "on"
}

/** State of the list from the server. */
sealed interface QuickActionsLoad {
    data object Idle : QuickActionsLoad
    data object Loading : QuickActionsLoad
    data object Loaded : QuickActionsLoad
    /** Server without quick actions (404) — an older GateControl. */
    data object Unsupported : QuickActionsLoad
    data object NotConfigured : QuickActionsLoad
    data class Failed(val reason: String) : QuickActionsLoad
}

/** Result of running an action. */
data class QuickActionResult(val ok: Boolean, val message: String?)

/**
 * Plugin quick actions (`GET /api/v1/client/actions`, server-side hooks of
 * the plugins): the list for the device's user, the actions pinned to the
 * start screen, app shortcuts, Quick Settings tiles and widget, and running
 * one (`POST /api/v1/client/actions/{plugin}/{id}`).
 *
 * Pins are stored on the device (title and last state included), so tiles,
 * shortcuts and the widget show them without asking the server first.
 */
@Singleton
class QuickActionRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val setupRepository: SetupRepository,
    private val apiClientProvider: ApiClientProvider,
    private val settingsRepository: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashReports.coroutineHandler)

    private val _actions = MutableStateFlow<List<QuickActionItem>>(emptyList())
    val actions: StateFlow<List<QuickActionItem>> = _actions.asStateFlow()

    private val _load = MutableStateFlow<QuickActionsLoad>(QuickActionsLoad.Idle)
    val load: StateFlow<QuickActionsLoad> = _load.asStateFlow()

    val pins: StateFlow<List<QuickActionItem>> = settingsRepository.getQuickActionPins()
        .map { decodePins(it) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Pins read straight from storage (tiles, shortcuts: the flow may not have loaded yet). */
    suspend fun currentPins(): List<QuickActionItem> = decodePins(settingsRepository.getQuickActionPins().first())

    suspend fun refresh(): QuickActionsLoad {
        val serverUrl = setupRepository.getServerUrl()
        if (serverUrl.isBlank()) return QuickActionsLoad.NotConfigured.also { _load.value = it }
        _load.value = QuickActionsLoad.Loading
        val result = try {
            val response = withTimeoutOrNull(TIMEOUT_MS) { apiClientProvider.getClient(serverUrl).getQuickActions(lang()) }
            when {
                response == null -> QuickActionsLoad.Failed("timeout")
                response.code() == 404 -> QuickActionsLoad.Unsupported
                !response.isSuccessful -> QuickActionsLoad.Failed("HTTP ${response.code()}")
                else -> {
                    val items = sanitize(response.body()?.actions.orEmpty())
                    _actions.value = items
                    syncPins(items)
                    QuickActionsLoad.Loaded
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d("Quick actions unavailable (%s)", e.javaClass.simpleName)
            QuickActionsLoad.Failed(e.javaClass.simpleName)
        }
        _load.value = result
        return result
    }

    /** The app's own UI language, so titles and messages match it. */
    private suspend fun lang(): String =
        runCatching { settingsRepository.getLocale().first() }.getOrNull()?.takeIf { it == "de" || it == "en" } ?: "de"

    fun refreshAsync() {
        scope.launch { refresh() }
    }

    suspend fun togglePin(item: QuickActionItem) {
        settingsRepository.updateQuickActionPins { raw ->
            val current = decodePins(raw)
            val next = if (current.any { it.key == item.key }) current.filter { it.key != item.key }
            else (current + item).take(MAX_PINS)
            encodePins(next)
        }
        afterPinsChanged()
    }

    /** Runs [item]; the new switch state goes into the list and the pins. */
    suspend fun run(item: QuickActionItem): QuickActionResult {
        val serverUrl = setupRepository.getServerUrl()
        if (serverUrl.isBlank()) return QuickActionResult(false, null)
        return try {
            val response = withTimeoutOrNull(RUN_TIMEOUT_MS) {
                apiClientProvider.getClient(serverUrl).runQuickAction(item.plugin, item.id, lang())
            } ?: return QuickActionResult(false, null)
            val body = response.body()
            if (!response.isSuccessful || body?.ok != true) {
                Timber.w("Quick action %s failed: HTTP %d", item.key, response.code())
                response.errorBody()?.close()
                return QuickActionResult(false, body?.message?.let(::clean))
            }
            val state = body.state?.takeIf { it == "on" || it == "off" }
            if (item.toggle && state != null) updateState(item.key, state)
            QuickActionResult(true, body.message?.let(::clean))
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            QuickActionResult(false, null)
        } catch (e: Exception) {
            Timber.d("Quick action %s failed (%s)", item.key, e.javaClass.simpleName)
            QuickActionResult(false, null)
        }
    }

    private suspend fun updateState(key: String, state: String) {
        _actions.value = _actions.value.map { if (it.key == key) it.copy(state = state) else it }
        settingsRepository.updateQuickActionPins { raw ->
            encodePins(decodePins(raw).map { if (it.key == key) it.copy(state = state) else it })
        }
        afterPinsChanged()
    }

    /** Pinned entries take the server's current title and state; vanished ones stay (offline, licence). */
    private suspend fun syncPins(items: List<QuickActionItem>) {
        val byKey = items.associateBy { it.key }
        var changed = false
        settingsRepository.updateQuickActionPins { raw ->
            val current = decodePins(raw)
            val next = current.map { pin -> byKey[pin.key] ?: pin }
            changed = next != current
            encodePins(next)
        }
        if (changed) afterPinsChanged()
    }

    private fun afterPinsChanged() {
        scope.launch { QuickActionSurfaces.refresh(context, currentPins()) }
    }

    companion object {
        const val MAX_PINS = 8
        private const val TIMEOUT_MS = 8_000L
        private const val RUN_TIMEOUT_MS = 15_000L
        private val ID = Regex("^[A-Za-z0-9:._-]{1,64}$")
        private val PLUGIN = Regex("^[a-z0-9][a-z0-9._-]{0,63}$")
        private val gson = Gson()
        private val pinType = object : TypeToken<List<QuickActionItem>>() {}.type

        /** Drops entries with unusable ids or kinds; caps texts. */
        fun sanitize(raw: List<QuickAction>): List<QuickActionItem> = raw.mapNotNull { a ->
            val plugin = a.plugin?.takeIf { PLUGIN.matches(it) } ?: return@mapNotNull null
            val id = a.id?.takeIf { ID.matches(it) } ?: return@mapNotNull null
            val title = a.title?.let(::clean)?.take(40)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val toggle = when (a.kind) {
                "toggle" -> true
                "button", null -> false
                else -> return@mapNotNull null
            }
            QuickActionItem(
                plugin = plugin,
                pluginName = a.pluginName?.let(::clean)?.take(40)?.takeIf { it.isNotBlank() } ?: plugin,
                id = id,
                title = title,
                subtitle = a.subtitle?.let(::clean)?.take(60)?.takeIf { it.isNotBlank() },
                toggle = toggle,
                state = if (toggle) a.state?.takeIf { it == "on" || it == "off" } else null,
                confirm = a.confirm == true,
                icon = QuickActionIcons.sanitize(a.icon),
            )
        }.distinctBy { it.key }

        internal fun clean(s: String): String = s.filter { !it.isISOControl() }.trim()

        @Suppress("SENSELESS_COMPARISON")
        internal fun decodePins(raw: String?): List<QuickActionItem> = try {
            if (raw.isNullOrBlank()) emptyList()
            // Gson bypasses Kotlin null-safety: drop entries an older format left incomplete.
            else gson.fromJson<List<QuickActionItem>>(raw, pinType).orEmpty()
                .filter { it != null && it.plugin != null && it.id != null && it.title != null && it.pluginName != null }
        } catch (e: Exception) {
            emptyList()
        }

        internal fun encodePins(pins: List<QuickActionItem>): String = gson.toJson(pins)
    }
}
