package com.gatecontrol.android.ui.rules

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.familie.FamilieApi
import com.gatecontrol.android.familie.FamilieRepository
import com.gatecontrol.android.familie.FamilieResult
import com.gatecontrol.android.familie.PluginDirectory
import com.gatecontrol.android.familie.errorCode
import com.gatecontrol.android.familie.rules.Rule
import com.gatecontrol.android.familie.rules.RuleCan
import com.gatecontrol.android.familie.rules.RuleFilter
import com.gatecontrol.android.familie.rules.RuleLogic
import com.gatecontrol.android.familie.rules.RuleOptions
import com.gatecontrol.android.familie.rules.RuleScope
import com.gatecontrol.android.familie.rules.RuleTestResult
import com.gatecontrol.android.familie.rules.RulesPayload
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/** How the rules list stands. */
sealed interface RulesLoad {
    data object Loading : RulesLoad
    data class Loaded(val payload: RulesPayload) : RulesLoad

    /** The plugin has no rules API (older Familie: 404) — a hint instead of a list. */
    data object Unsupported : RulesLoad
    data class Failed(val code: String?) : RulesLoad
}

private fun unsupported(r: FamilieResult<*>): Boolean = r is FamilieResult.Failed && (r.http == 404 || r.code == "not_found" || r.code == "http_404")

/**
 * The profile row "Regeln und Erinnerungen" (mockup ProfileRules): shown
 * while Familie's module `rules` is on and the caller may create house or
 * personal rules; its line counts active / paused rules and the last run.
 */
@HiltViewModel
class ProfileRulesViewModel @Inject constructor(
    private val api: FamilieApi,
    familie: FamilieRepository,
    plugins: PluginDirectory,
) : ViewModel() {

    private val available: StateFlow<Boolean> = combine(familie.profile, plugins.state) { p, s -> s.familieApi && p?.modules?.rules == true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _payload = MutableStateFlow<RulesPayload?>(null)

    /** null: not shown. */
    val summary: StateFlow<RuleLogic.Summary?> = combine(available, _payload) { on, p ->
        if (on && p != null && p.can.any) RuleLogic.summary(p) else null
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch { familie.cachedProfile() }
    }

    fun refresh() {
        viewModelScope.launch {
            if (!available.value) return@launch
            api.rules().let { r -> if (r is FamilieResult.Ok) _payload.value = r.value else if (unsupported(r)) _payload.value = null }
        }
    }
}

/** "Regeln und Erinnerungen" (mockup RulesList): filter, pause switch, protocol. */
@HiltViewModel
class RulesListViewModel @Inject constructor(private val api: FamilieApi) : ViewModel() {

    private val _load = MutableStateFlow<RulesLoad>(RulesLoad.Loading)
    val load: StateFlow<RulesLoad> = _load.asStateFlow()

    private val _filter = MutableStateFlow(RuleFilter.ALL)
    val filter: StateFlow<RuleFilter> = _filter.asStateFlow()

    private val _options = MutableStateFlow(RuleOptions())
    /** Device and place names for the lines under the rules (best effort). */
    val options: StateFlow<RuleOptions> = _options.asStateFlow()

    private val errors = Channel<String?>(Channel.BUFFERED)
    val error: Flow<String?> = errors.receiveAsFlow()

    fun setFilter(f: RuleFilter) {
        _filter.value = f
    }

    fun refresh() {
        viewModelScope.launch {
            api.ruleOptions().let { o -> if (o is FamilieResult.Ok) _options.value = o.value }
        }
        viewModelScope.launch {
            val r = api.rules()
            _load.value = when {
                r is FamilieResult.Ok -> RulesLoad.Loaded(r.value)
                unsupported(r) -> RulesLoad.Unsupported
                _load.value is RulesLoad.Loaded -> _load.value.also { errors.trySend(r.errorCode()) }
                else -> RulesLoad.Failed(r.errorCode())
            }
        }
    }

    /** The pause switch: changed at once, back on failure. */
    fun setActive(rule: Rule, active: Boolean) {
        val id = rule.id ?: return
        val loaded = _load.value as? RulesLoad.Loaded ?: return
        fun with(on: Boolean) = RulesLoad.Loaded(loaded.payload.copy(rules = loaded.payload.rules.map { if (it.id == id) it.copy(active = on) else it }))
        _load.value = with(active)
        viewModelScope.launch {
            val r = api.setRuleActive(id, active)
            if (r !is FamilieResult.Ok) {
                _load.value = with(!active)
                errors.trySend(r.errorCode())
            }
        }
    }
}

/** One-shot results of the editor. */
sealed interface RuleEditorEvent {
    data object Saved : RuleEditorEvent
    data object Deleted : RuleEditorEvent
    data class Error(val code: String?, val field: String? = null) : RuleEditorEvent
}

/**
 * The editor Wann → Nur wenn → Dann (mockups RuleEditor, ReminderEditor):
 * an existing rule ([load] with its id), a new one, or a reminder for one
 * device ([load] with its action id: personal, schedule, the device's value,
 * a push with its buttons).
 */
@HiltViewModel
class RuleEditorViewModel @Inject constructor(
    private val api: FamilieApi,
    private val familie: FamilieRepository,
) : ViewModel() {

    private val _draft = MutableStateFlow<Rule?>(null)
    val draft: StateFlow<Rule?> = _draft.asStateFlow()

    private val _options = MutableStateFlow(RuleOptions())
    val options: StateFlow<RuleOptions> = _options.asStateFlow()

    private val _can = MutableStateFlow(RuleCan())
    val can: StateFlow<RuleCan> = _can.asStateFlow()

    private val _loadState = MutableStateFlow<RulesLoad>(RulesLoad.Loading)
    val loadState: StateFlow<RulesLoad> = _loadState.asStateFlow()

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    private val _test = MutableStateFlow<RuleTestResult?>(null)
    val test: StateFlow<RuleTestResult?> = _test.asStateFlow()

    private val events = Channel<RuleEditorEvent>(Channel.BUFFERED)
    val event: Flow<RuleEditorEvent> = events.receiveAsFlow()

    val myUserId: Int? get() = familie.profile.value?.me?.userId

    private var loadedKey: String? = null

    /** [ruleId] an existing rule; else new — for the device [actionId] a reminder. */
    fun load(ruleId: Int?, actionId: String?, personal: Boolean) {
        val key = "$ruleId|$actionId|$personal"
        if (loadedKey == key) return
        loadedKey = key
        viewModelScope.launch {
            if (familie.profile.value == null) familie.cachedProfile() ?: familie.refreshProfile()
            val opts = api.ruleOptions()
            if (opts is FamilieResult.Ok) _options.value = opts.value
            val list = api.rules()
            if (list is FamilieResult.Ok) _can.value = list.value.can
            if (opts is FamilieResult.Ok && list !is FamilieResult.Ok) _can.value = opts.value.can
            if (unsupported(list) && unsupported(opts)) {
                _loadState.value = RulesLoad.Unsupported
                return@launch
            }
            val me = myUserId
            _draft.value = when {
                ruleId != null -> (list as? FamilieResult.Ok)?.value?.rules?.firstOrNull { it.id == ruleId }
                actionId != null -> RuleLogic.reminderFor(_options.value.device(actionId), actionId, me, LocalDate.now().dayOfWeek)
                else -> RuleLogic.newRule(personal || !_can.value.house, me)
            }
            _loadState.value = when {
                _draft.value != null -> RulesLoad.Loaded(RulesPayload(emptyList(), emptyList(), _can.value))
                list is FamilieResult.Ok -> RulesLoad.Failed("not_found")
                else -> RulesLoad.Failed(list.errorCode())
            }
            savedDraft = _draft.value?.takeIf { it.id != null }?.let { RuleLogic.forScope(it, me) }
            if (ruleId != null) runTest()
        }
    }

    fun edit(change: (Rule) -> Rule) {
        _draft.value = _draft.value?.let(change)
    }

    fun setScope(personal: Boolean) = edit { RuleLogic.forScope(it.copy(scope = if (personal) RuleScope.PERSONAL else RuleScope.HOUSE), myUserId) }

    /** A saved rule as stored (`rules/<id>/test`) unless changed; a new or changed one as drafted (`rules/test`). */
    private var savedDraft: Rule? = null

    fun runTest() {
        val d = _draft.value?.let { RuleLogic.forScope(it, myUserId) } ?: return
        val id = d.id
        viewModelScope.launch {
            val r = if (id != null && d == savedDraft) api.testRule(id) else api.testRuleDraft(d)
            _test.value = (r as? FamilieResult.Ok)?.value
        }
    }

    fun save() {
        val d = _draft.value?.let { RuleLogic.forScope(it, myUserId) } ?: return
        if (_saving.value || !RuleLogic.valid(d)) return
        _saving.value = true
        viewModelScope.launch {
            val id = d.id
            val r = if (id == null) api.createRule(d) else api.updateRule(id, d)
            _saving.value = false
            if (r is FamilieResult.Ok) events.trySend(RuleEditorEvent.Saved)
            else events.trySend(RuleEditorEvent.Error(r.errorCode(), (r as? FamilieResult.Failed)?.field))
        }
    }

    fun delete() {
        val id = _draft.value?.id ?: return
        if (_saving.value) return
        _saving.value = true
        viewModelScope.launch {
            val r = api.deleteRule(id)
            _saving.value = false
            events.trySend(if (r is FamilieResult.Ok) RuleEditorEvent.Deleted else RuleEditorEvent.Error(r.errorCode()))
        }
    }
}
