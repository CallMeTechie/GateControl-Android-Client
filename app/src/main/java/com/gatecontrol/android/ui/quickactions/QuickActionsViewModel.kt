package com.gatecontrol.android.ui.quickactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.familie.FamilieRepository
import com.gatecontrol.android.familie.rules.RuleOpenStore
import com.gatecontrol.android.layout.LayoutAccess
import com.gatecontrol.android.layout.StartArrangement
import com.gatecontrol.android.layout.StartEntry
import com.gatecontrol.android.quickactions.QuickActionDeviceGroup
import com.gatecontrol.android.quickactions.QuickActionDevices
import com.gatecontrol.android.quickactions.QuickActionItem
import com.gatecontrol.android.quickactions.QuickActionRepository
import com.gatecontrol.android.quickactions.QuickActionResult
import com.gatecontrol.android.quickactions.QuickActionScreens
import com.gatecontrol.android.quickactions.QuickActionsLoad
import com.gatecontrol.android.quickactions.StartWidgetPin
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * A pin sheet to show: the device [deviceKey], opened from a start-tab
 * widget ([widget]) or button ([action]: its key), [fromStart] by a long
 * press on the start tab (only that side, with the reminder and arranging rows);
 * [page] the widget page beside Start it was opened on (null: Start).
 */
data class PinSheetRequest(
    val deviceKey: String,
    val widget: Boolean,
    val action: String? = null,
    val fromStart: Boolean = true,
    val page: String? = null,
)

/** The list "Schnellaktionen und Widgets", the pin sheet and the start tab's buttons and widgets. */
@HiltViewModel
class QuickActionsViewModel @Inject constructor(
    private val repository: QuickActionRepository,
    private val arrangement: StartArrangement,
    private val openReminders: RuleOpenStore,
    familie: FamilieRepository,
) : ViewModel() {

    /** The start tab's blocks in their order (tiles, widgets, Info and usage card). */
    val entries: StateFlow<List<StartEntry>> = arrangement.entries

    /** The blocks of the start tab (key null) and of each widget page beside it. */
    val pages: StateFlow<Map<String?, List<StartEntry>>> = arrangement.pages

    /** Where the order lives and whether this person may change it. */
    val layoutAccess: StateFlow<LayoutAccess> = arrangement.access

    private val _sheet = MutableStateFlow<PinSheetRequest?>(null)
    /** The pin sheet open on the start tab (long press on a widget or button). */
    val sheet: StateFlow<PinSheetRequest?> = _sheet.asStateFlow()

    private val _confirming = MutableStateFlow<QuickActionItem?>(null)
    /** An action waiting for "Ausführen?" before it runs. */
    val confirming: StateFlow<QuickActionItem?> = _confirming.asStateFlow()

    val actions: StateFlow<List<QuickActionItem>> = repository.actions
    val pins: StateFlow<List<QuickActionItem>> = repository.pins
    val widgets: StateFlow<List<StartWidgetPin>> = repository.widgets
    val load: StateFlow<QuickActionsLoad> = repository.load

    /** The server's actions as devices (one row each in the list). */
    val devices: StateFlow<List<QuickActionDeviceGroup>> = repository.actions
        .map { QuickActionDevices.group(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), QuickActionDevices.group(repository.actions.value))

    private val _running = MutableStateFlow<String?>(null)
    /** Key of the action running right now. */
    val running: StateFlow<String?> = _running.asStateFlow()

    private val _adjusting = MutableStateFlow<Set<String>>(emptySet())
    /** Keys (`<action key>/<control id>`) of widget controls whose value is on its way to the server. */
    val adjusting: StateFlow<Set<String>> = _adjusting.asStateFlow()

    private val _results = MutableSharedFlow<Pair<QuickActionItem, QuickActionResult>>(extraBufferCapacity = 4)
    val results: SharedFlow<Pair<QuickActionItem, QuickActionResult>> = _results.asSharedFlow()

    private val pending = HashMap<String, Job>()

    /** `<plugin>/<id>` of actions with an open Familie reminder of mine: their widget shows „!“. */
    val alerts: StateFlow<Set<String>> = combine(openReminders.open, familie.profile) { open, p -> RuleOpenStore.alertActions(open, p?.me?.userId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun refresh() {
        viewModelScope.launch { repository.refresh() }
    }

    /** `<plugin>/<id>` of actions an active reminder of mine is about: their widget shows a small clock. */
    val planned: StateFlow<Set<String>> = combine(openReminders.rules, familie.profile) { rules, p -> RuleOpenStore.plannedActions(rules, p?.me?.userId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** The open reminders again (on showing the start tab; at most once a minute). */
    fun refreshAlerts() {
        viewModelScope.launch { openReminders.refresh() }
    }

    /** The account's order of the start tab (throttled; on opening the tab). */
    fun refreshLayout() = arrangement.refresh()

    fun openSheet(request: PinSheetRequest) {
        _sheet.value = request
    }

    fun closeSheet() {
        _sheet.value = null
    }

    /**
     * Runs [item], asking first where it wants that ([confirming]); an
     * action that opens an app screen (the logbook) opens it instead.
     */
    fun ask(item: QuickActionItem) {
        when {
            QuickActionScreens.open(item) -> Unit
            item.needsConfirm -> _confirming.value = item
            else -> run(item)
        }
    }

    fun confirm() {
        val item = _confirming.value ?: return
        _confirming.value = null
        run(item)
    }

    fun cancelConfirm() {
        _confirming.value = null
    }

    /**
     * The device's buttons on the start tab become exactly [actionIds];
     * new ones join [page] (a widget page beside Start, null: Start).
     */
    fun setButtons(device: QuickActionDeviceGroup, actionIds: Set<String>, page: String? = null) {
        viewModelScope.launch {
            repository.setButtons(device, actionIds)
            arrangement.syncFromPins(page)
        }
    }

    fun setWidget(device: QuickActionDeviceGroup, parts: List<String>, wide: Boolean, page: String? = null) {
        viewModelScope.launch {
            repository.setWidget(device, parts, wide)
            arrangement.syncFromPins(page)
        }
    }

    fun removeWidget(deviceKey: String) {
        viewModelScope.launch {
            repository.removeWidget(deviceKey)
            arrangement.syncFromPins()
        }
    }

    /** Arranged on "Start-Tab anordnen" (or a widget page's [page]): the new order and widths. */
    fun saveArrangement(entries: List<StartEntry>, page: String? = null) {
        viewModelScope.launch { arrangement.save(entries, page) }
    }

    /** "−" on a tile while arranging: the button leaves the start tab. */
    /** What Mail pins to Start (favourite tiles, the calendar widget; Mail 1.4.0). */
    val mailPins: StateFlow<com.gatecontrol.android.mail.MailStartPins> = arrangement.mailPins

    /** Takes the favourite [id] off Start. */
    fun unpinContact(id: Long) {
        viewModelScope.launch { arrangement.pinContact(com.gatecontrol.android.mail.StartContactPin(id, ""), on = false) }
    }

    fun unpinTile(key: String) {
        viewModelScope.launch {
            repository.removePin(key)
            arrangement.syncFromPins()
        }
    }

    /** Runs [item] (the caller asked first when [QuickActionItem.needsConfirm]). */
    fun run(item: QuickActionItem) {
        if (_running.value != null) return
        _running.value = item.key
        viewModelScope.launch {
            val result = repository.run(item)
            _running.value = null
            _results.emit(item to result)
        }
    }

    /**
     * A widget control changed to [value]: sent after [DEBOUNCE_MS] without
     * another change of the same control, so stepping "+ + +" is one request.
     * Only a failure is reported ([results]); success shows in the widget.
     */
    fun control(item: QuickActionItem, controlId: String, value: Any) {
        val key = "${item.key}/$controlId"
        pending.remove(key)?.cancel()
        _adjusting.update { it + key }
        pending[key] = viewModelScope.launch {
            try {
                delay(DEBOUNCE_MS)
                val result = repository.control(item, controlId, value)
                if (!result.ok) _results.emit(item to result)
            } finally {
                if (pending[key] == this.coroutineContext[Job]) pending.remove(key)
                _adjusting.update { it - key }
            }
        }
    }

    companion object {
        const val DEBOUNCE_MS = 600L
    }
}
