package com.gatecontrol.android.quickactions

import android.content.Context
import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.network.ApiClientProvider
import com.gatecontrol.android.network.QuickAction
import com.gatecontrol.android.network.QuickActionRunRequest
import com.gatecontrol.android.network.QuickActionRunResponse
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
    /** Reading and controls (core 1.171+), null = a plain button/switch. */
    val widget: QuickActionWidgetItem? = null,
    /**
     * Pins of 1.37 only: was shown as a widget card. Read once to turn such
     * a pin into a widget pin ([StartWidgetPin]); never written any more.
     */
    val asWidget: Boolean = false,
    /** The device the action belongs to (core 1.172+), null = its own device. */
    val deviceId: String? = null,
    val deviceTitle: String? = null,
    val deviceSubtitle: String? = null,
    val deviceIcon: String? = null,
    /** Switches: icon and tone per state (core 1.172+), e.g. a red open lock while off. */
    val look: QuickActionLookItem? = null,
    /** Switches: ask first only before switching to this state, "on" | "off" (core 1.172+). */
    val confirmWhen: String? = null,
) {
    val key: String get() = "$plugin/$id"
    val isOn: Boolean get() = toggle && state == "on"

    /** The device's key: actions without a device are a device of their own. */
    val deviceKey: String get() = "$plugin/" + (deviceId ?: "@$id")

    /** The state a run switches a switch to: "off" when on, else "on" (unknown state too). */
    val target: String? get() = if (toggle) (if (state == "on") "off" else "on") else null

    /** Whether to ask before running: only towards [confirmWhen] when the plugin gives it, else [confirm]. */
    val needsConfirm: Boolean get() = if (toggle && confirmWhen != null) target == confirmWhen else confirm

    /** The look of the current state, null without one (or with the state unknown). */
    val currentLook: QuickActionLookStateItem? get() = if (toggle) look?.forState(state) else null

    /** The icon to draw now: the current state's own one, else the action's. */
    val displayIcon: String? get() = currentLook?.icon ?: icon
}

/** Icon and tone of a switch per state (core 1.172+). */
data class QuickActionLookItem(
    val on: QuickActionLookStateItem? = null,
    val off: QuickActionLookStateItem? = null,
) {
    fun forState(state: String?): QuickActionLookStateItem? = when (state) {
        "on" -> on
        "off" -> off
        else -> null
    }

    companion object {
        val TONES = setOf("good", "warn", "crit", "neutral")

        fun from(raw: com.gatecontrol.android.network.QuickActionLook?): QuickActionLookItem? {
            raw ?: return null
            val on = state(raw.on)
            val off = state(raw.off)
            return if (on == null && off == null) null else QuickActionLookItem(on, off)
        }

        private fun state(raw: com.gatecontrol.android.network.QuickActionLookState?): QuickActionLookStateItem? {
            raw ?: return null
            val icon = QuickActionIcons.sanitize(raw.icon)
            val tone = raw.tone?.takeIf { it in TONES }
            return if (icon == null && tone == null) null else QuickActionLookStateItem(icon, tone)
        }
    }
}

/** [tone]: good | warn | crit | neutral, null = the usual look. */
data class QuickActionLookStateItem(val icon: String? = null, val tone: String? = null)

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
        .map { raw -> decodePins(raw).filter { !it.asWidget } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Devices pinned to the start tab as widgets, in their order. */
    val widgets: StateFlow<List<StartWidgetPin>> = settingsRepository.getStartWidgets()
        .map { StartWidgetPin.decode(it) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Pins read straight from storage (tiles, shortcuts: the flow may not have loaded yet). */
    suspend fun currentPins(): List<QuickActionItem> =
        decodePins(settingsRepository.getQuickActionPins().first()).filter { !it.asWidget }

    suspend fun currentWidgets(): List<StartWidgetPin> = StartWidgetPin.decode(settingsRepository.getStartWidgets().first())

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
                    migrateWidgetPins()
                    syncPins(items)
                    syncWidgets(QuickActionDevices.group(items))
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

    /**
     * The buttons of [device] on the start tab become exactly [actionIds]:
     * the ones that stay keep their place, new ones go to the end (at most
     * [MAX_PINS] in all).
     */
    suspend fun setButtons(device: QuickActionDeviceGroup, actionIds: Set<String>) {
        settingsRepository.updateQuickActionPins { raw ->
            encodePins(QuickActionDevices.withButtons(decodePins(raw), device, actionIds, MAX_PINS))
        }
        afterPinsChanged()
    }

    /** Pins [device] as a widget showing [parts] (ids of [QuickActionDeviceGroup.parts]), [wide] = full width. */
    suspend fun setWidget(device: QuickActionDeviceGroup, parts: List<String>, wide: Boolean) {
        settingsRepository.updateStartWidgets { raw ->
            StartWidgetPin.encode(StartWidgetPin.withWidget(StartWidgetPin.decode(raw), device, parts, wide, MAX_WIDGETS))
        }
    }

    suspend fun removeWidget(deviceKey: String) {
        settingsRepository.updateStartWidgets { raw ->
            StartWidgetPin.encode(StartWidgetPin.decode(raw).filter { it.device != deviceKey })
        }
    }

    /** 1.37 kept widgets as flagged action pins: they become widget pins with every part. */
    private suspend fun migrateWidgetPins() {
        var moved = emptyList<QuickActionItem>()
        settingsRepository.updateQuickActionPins { raw ->
            val current = decodePins(raw)
            moved = current.filter { it.asWidget }
            if (moved.isEmpty()) raw.orEmpty() else encodePins(current.filter { !it.asWidget })
        }
        if (moved.isEmpty()) return
        settingsRepository.updateStartWidgets { raw ->
            val current = StartWidgetPin.decode(raw)
            val added = moved.map { StartWidgetPin(device = it.deviceKey, anchor = it.key) }
                .filter { pin -> current.none { it.device == pin.device } }
            StartWidgetPin.encode((current + added).take(MAX_WIDGETS))
        }
    }

    /** Widget pins follow their device (a changed device id is found again by an action) and keep a copy for offline. */
    private suspend fun syncWidgets(devices: List<QuickActionDeviceGroup>) {
        settingsRepository.updateStartWidgets { raw ->
            val current = StartWidgetPin.decode(raw)
            if (current.isEmpty()) raw.orEmpty() else StartWidgetPin.encode(current.map { it.synced(devices) })
        }
    }

    /** Runs [item]; the new switch state and widget go into the list and the pins. */
    suspend fun run(item: QuickActionItem): QuickActionResult {
        val outcome = call(item.plugin, item.id, QuickActionRunRequest.SOURCE_APP)
        if (outcome.result.ok) update(item.key, outcome.state?.takeIf { item.toggle }, outcome.widget)
        return outcome.result
    }

    /**
     * Sets the widget control [controlId] of [item] to [value] (a number, or
     * an option id; core 1.171+): a run with `control`/`value`. The answer's
     * widget and state replace the stored ones.
     */
    suspend fun control(item: QuickActionItem, controlId: String, value: Any): QuickActionResult {
        val outcome = call(item.plugin, item.id, QuickActionRunRequest.SOURCE_APP, controlId, value)
        if (outcome.result.ok) update(item.key, outcome.state?.takeIf { item.toggle }, outcome.widget)
        return outcome.result
    }

    /**
     * Runs the quick action [id] of [plugin] from a push message's button
     * (`source: notification`, core 1.169+). Ids are checked like the
     * server's list; tiles and pins pick up a changed state afterwards.
     */
    suspend fun runFromNotification(plugin: String, id: String): QuickActionResult {
        if (!PLUGIN.matches(plugin) || !ID.matches(id)) return QuickActionResult(false, null)
        val outcome = call(plugin, id, QuickActionRunRequest.SOURCE_NOTIFICATION)
        if (outcome.result.ok) {
            val key = "$plugin/$id"
            val toggle = _actions.value.any { it.key == key && it.toggle }
            update(key, outcome.state?.takeIf { toggle }, outcome.widget)
            refreshAsync()
        }
        return outcome.result
    }

    /** What a run answered: the result, the switch's new state ("on"/"off") and the widget after it. */
    private data class Outcome(val result: QuickActionResult, val state: String? = null, val widget: QuickActionWidgetItem? = null)

    /** `POST /api/v1/client/actions/{plugin}/{id}`, with `control`/`value` for a widget control. */
    private suspend fun call(plugin: String, id: String, source: String, control: String? = null, value: Any? = null): Outcome {
        val serverUrl = setupRepository.getServerUrl()
        if (serverUrl.isBlank()) return Outcome(QuickActionResult(false, null))
        return try {
            val response = withTimeoutOrNull(RUN_TIMEOUT_MS) {
                apiClientProvider.getClient(serverUrl).runQuickAction(plugin, id, lang(), QuickActionRunRequest(source, control, value))
            } ?: return Outcome(QuickActionResult(false, null))
            val body = response.body()
            if (!response.isSuccessful || body?.ok != true) {
                Timber.w("Quick action %s/%s failed: HTTP %d", plugin, id, response.code())
                // The plugin's reason ("außerhalb des Zeitfensters") when it sent one.
                val errorMessage = body?.message ?: runCatching {
                    response.errorBody()?.use { b -> b.string().takeIf { it.length < 16_384 } }
                        ?.let { gson.fromJson(it, QuickActionRunResponse::class.java) }?.message
                }.getOrNull()
                return Outcome(QuickActionResult(false, errorMessage?.let(::clean)?.take(200)?.takeIf { it.isNotBlank() }))
            }
            Outcome(
                result = QuickActionResult(true, body.message?.let(::clean)?.take(200)),
                state = body.state?.takeIf { it == "on" || it == "off" },
                widget = QuickActionWidgetItem.from(body.widget),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            Outcome(QuickActionResult(false, null))
        } catch (e: Exception) {
            Timber.d("Quick action %s/%s failed (%s)", plugin, id, e.javaClass.simpleName)
            Outcome(QuickActionResult(false, null))
        }
    }

    /** A run's new [state] and/or [widget] (null = unchanged) into the list and the pins. */
    private suspend fun update(key: String, state: String?, widget: QuickActionWidgetItem?) {
        if (state == null && widget == null) return
        val apply: (QuickActionItem) -> QuickActionItem = { it.copy(state = state ?: it.state, widget = widget ?: it.widget) }
        _actions.value = _actions.value.map { if (it.key == key) apply(it) else it }
        settingsRepository.updateQuickActionPins { raw ->
            encodePins(decodePins(raw).map { if (it.key == key) apply(it) else it })
        }
        afterPinsChanged()
    }

    /** Pinned entries take the server's current title, state and widget; vanished ones stay (offline, licence). */
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
        /** Buttons on the start tab (and the source of shortcuts, tiles and the home-screen widget). */
        const val MAX_PINS = 12
        /** Devices shown as widgets on the start tab. */
        const val MAX_WIDGETS = 8
        private val DEVICE_ID = Regex("^[A-Za-z0-9:._-]{1,64}$")
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
            val device = deviceOf(a.device)
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
                widget = QuickActionWidgetItem.from(a.widget),
                deviceId = device?.id,
                deviceTitle = device?.title,
                deviceSubtitle = device?.subtitle,
                deviceIcon = device?.icon,
                look = if (toggle) QuickActionLookItem.from(a.look) else null,
                confirmWhen = if (toggle) a.confirmWhen?.takeIf { it == "on" || it == "off" } else null,
            )
        }.distinctBy { it.key }

        private data class Device(val id: String, val title: String, val subtitle: String?, val icon: String?)

        private fun deviceOf(raw: com.gatecontrol.android.network.QuickActionDevice?): Device? {
            raw ?: return null
            val id = raw.id?.takeIf { DEVICE_ID.matches(it) } ?: return null
            val title = raw.title?.let(::clean)?.take(40)?.takeIf { it.isNotBlank() } ?: return null
            return Device(
                id = id,
                title = title,
                subtitle = raw.subtitle?.let(::clean)?.take(60)?.takeIf { it.isNotBlank() },
                icon = QuickActionIcons.sanitize(raw.icon),
            )
        }

        internal fun clean(s: String): String = s.filter { !it.isISOControl() }.trim()

        @Suppress("SENSELESS_COMPARISON")
        internal fun decodePins(raw: String?): List<QuickActionItem> = try {
            if (raw.isNullOrBlank()) emptyList()
            // Gson bypasses Kotlin null-safety: drop entries an older format left incomplete.
            else gson.fromJson<List<QuickActionItem>>(raw, pinType).orEmpty()
                .filter { it != null && it.plugin != null && it.id != null && it.title != null && it.pluginName != null }
                .map { if (it.widget != null) it.copy(widget = it.widget.normalized()) else it }
        } catch (e: Exception) {
            emptyList()
        }

        internal fun encodePins(pins: List<QuickActionItem>): String = gson.toJson(pins)
    }
}
