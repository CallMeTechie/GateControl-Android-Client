package com.gatecontrol.android.familie.rules

import com.gatecontrol.android.familie.FamilieApi
import com.gatecontrol.android.familie.FamilieRepository
import com.gatecontrol.android.familie.FamilieResult
import com.gatecontrol.android.familie.PluginDirectory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The rules and today's open reminders as last seen (`GET rules`): every
 * rules screen hands its answer in ([update]), the start tab fetches it now
 * and then ([refresh]) so a device widget can show its „!“ (a reminder is
 * open) or its small clock (a reminder about it is set).
 */
@Singleton
class RuleOpenStore @Inject constructor(
    private val api: FamilieApi,
    private val familie: FamilieRepository,
    private val plugins: PluginDirectory,
) {
    private val _open = MutableStateFlow<List<RuleOpen>>(emptyList())
    val open: StateFlow<List<RuleOpen>> = _open.asStateFlow()

    private val _rules = MutableStateFlow<List<Rule>>(emptyList())
    val rules: StateFlow<List<Rule>> = _rules.asStateFlow()

    private val lock = Mutex()
    private var fetchedAt = 0L

    fun update(p: RulesPayload) {
        _open.value = p.open
        _rules.value = p.rules
        fetchedAt = System.currentTimeMillis()
    }

    /** A rule paused / resumed in the list. */
    fun setActive(id: Int, active: Boolean) {
        _rules.value = _rules.value.map { if (it.id == id) it.copy(active = active) else it }
    }

    /** Without the module `rules` (or Familie) nothing is open; at most every [MIN_GAP_MS] unless [force]. */
    suspend fun refresh(force: Boolean = false) = lock.withLock {
        val profile = familie.profile.value ?: familie.cachedProfile()
        if (!plugins.state.value.familieApi || profile?.modules?.rules != true) {
            _open.value = emptyList()
            _rules.value = emptyList()
            return@withLock
        }
        if (!force && System.currentTimeMillis() - fetchedAt < MIN_GAP_MS) return@withLock
        val r = api.rules()
        if (r is FamilieResult.Ok) update(r.value)
    }

    companion object {
        const val MIN_GAP_MS = 60_000L

        /** `<plugin>/<id>` of the quick actions with an open reminder of [myUserId] (not the children's). */
        fun alertActions(open: List<RuleOpen>, myUserId: Int?): Set<String> =
            open.filter { it.isMine(myUserId) }.flatMap { it.actionIds }.toSet()

        /**
         * `<plugin>/<id>` of the quick actions an active reminder of [myUserId] is about (its device
         * values, its buttons, the devices it switches) — house reminders count, other people's own not.
         */
        fun plannedActions(rules: List<Rule>, myUserId: Int?): Set<String> =
            rules.filter { r ->
                r.active && RuleLogic.matches(r, RuleFilter.REMINDERS) && (r.scope != RuleScope.PERSONAL || r.ownerId == null || r.ownerId == myUserId)
            }.flatMap { r ->
                r.conditions.device.map { it.actionId } +
                    r.actions.flatMap { a ->
                        listOfNotNull(a.actionId) + a.buttons.filter { it.startsWith(RuleButton.QUICK_PREFIX) }.map { it.removePrefix(RuleButton.QUICK_PREFIX) }
                    }
            }.toSet()
    }
}
