package com.gatecontrol.android.ui.mail.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.R
import com.gatecontrol.android.mail.MCalChanged
import com.gatecontrol.android.mail.MCalendar
import com.gatecontrol.android.mail.MCalendars
import com.gatecontrol.android.mail.MMe
import com.gatecontrol.android.mail.MPrincipal
import com.gatecontrol.android.mail.MResult
import com.gatecontrol.android.mail.MShare
import com.gatecontrol.android.mail.MSharedAccountCalendar
import com.gatecontrol.android.mail.MSharedCalendar
import com.gatecontrol.android.mail.MSharedList
import com.gatecontrol.android.mail.MailPimJson
import com.gatecontrol.android.mail.MailPlanLogic
import com.gatecontrol.android.mail.MailRepository
import com.gatecontrol.android.navigation.Screen
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcSegmented
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.components.GcSwitchRow
import com.gatecontrol.android.ui.components.GcTextField
import com.gatecontrol.android.ui.mail.mailContext
import com.gatecontrol.android.ui.mail.toast
import com.gatecontrol.android.ui.theme.GateControlTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What „Kalender teilen“ shows. */
data class ShareState(
    val shares: List<MShare>? = null,
    val loading: Boolean = false,
    val error: MResult<*>? = null,
    /** The level a new share gets (mockup: Lesen). */
    val level: String = MShare.READ,
    val query: String = "",
    val results: List<MPrincipal> = emptyList(),
    val shared: MSharedList? = null,
    val failure: MResult<*>? = null,
)

/**
 * „Kalender teilen“ (mockup Teilen, Mail 1.9.0) of an own GateControl calendar: a person or group (`GET principals`) at
 * Frei/Gebucht, Lesen or Bearbeiten, „Geteilt mit“ with their state and level, „Frei/Gebucht für alle in GateControl“
 * and „Mit mir geteilt“ with „Anzeigen“. Without a calendar only the last two.
 */
@HiltViewModel
class ShareCalendarViewModel @Inject constructor(
    private val repository: MailRepository,
    saved: SavedStateHandle,
) : ViewModel() {

    private val pim = repository.pim
    private val api = repository.api
    val me: StateFlow<MMe?> = repository.me
    val calendars: StateFlow<MCalendars?> = pim.calendars
    val calendarId: Long? = saved.get<String>(Screen.MailShareCalendar.ARG_CAL)?.toLongOrNull()?.takeIf { it > 0 }

    private val _state = MutableStateFlow(ShareState())
    val state: StateFlow<ShareState> = _state.asStateFlow()

    private var searchJob: Job? = null

    init {
        if (pim.calendars.value == null) viewModelScope.launch { pim.refreshCalendars() }
        loadShares()
        loadShared()
        viewModelScope.launch {
            pim.calChanged.collect { c ->
                if (c.kind == MCalChanged.SHARES) {
                    if (c.calendarId == null || c.calendarId == calendarId) loadShares(quiet = true)
                    loadShared()
                }
            }
        }
    }

    fun loadShares(quiet: Boolean = false) {
        val id = calendarId ?: return
        if (!quiet) _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val r = api.shares(id)) {
                is MResult.Ok -> {
                    _state.update { it.copy(shares = r.value.items, loading = false, error = null) }
                    r.value.freeBusy?.let { fb -> pim.calendars.value?.prefs?.let { p -> if (p.freeBusy != fb) pim.putPrefs(p.copy(freeBusy = fb)) } }
                }
                else -> _state.update { it.copy(loading = false, error = r) }
            }
        }
    }

    fun loadShared() {
        viewModelScope.launch { api.shared().valueOrNull()?.let { l -> _state.update { it.copy(shared = l) } } }
    }

    fun setLevel(level: String) = _state.update { it.copy(level = level) }

    fun search(q: String) {
        _state.update { it.copy(query = q.take(60)) }
        searchJob?.cancel()
        if (q.trim().length < 2) {
            _state.update { it.copy(results = emptyList()) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(250)
            val found = api.principals(q).valueOrNull().orEmpty()
            _state.update { it.copy(results = found) }
        }
    }

    fun share(p: MPrincipal) {
        val id = calendarId ?: return
        val level = _state.value.level
        _state.update { it.copy(query = "", results = emptyList()) }
        viewModelScope.launch {
            when (val r = api.addShare(id, p.type, p.id, level)) {
                is MResult.Ok -> loadShares(quiet = true)
                else -> _state.update { it.copy(failure = r) }
            }
        }
    }

    fun changeLevel(s: MShare, level: String) {
        val id = calendarId ?: return
        if (level == s.level) return
        _state.update { st -> st.copy(shares = st.shares?.map { if (it.id == s.id) it.copy(level = level) else it }) }
        viewModelScope.launch {
            val r = api.patchShare(id, s.id, level)
            if (r !is MResult.Ok) _state.update { it.copy(failure = r) }
            loadShares(quiet = true)
        }
    }

    fun remove(s: MShare) {
        val id = calendarId ?: return
        _state.update { st -> st.copy(shares = st.shares?.filter { it.id != s.id }) }
        viewModelScope.launch {
            val r = api.deleteShare(id, s.id)
            if (r !is MResult.Ok) _state.update { it.copy(failure = r) }
            loadShares(quiet = true)
        }
    }

    /** „Frei/Gebucht für alle in GateControl“ (the person's setting). */
    fun setFreeBusy(on: Boolean) {
        val before = pim.calendars.value?.prefs ?: return
        pim.putPrefs(before.copy(freeBusy = on))
        viewModelScope.launch {
            when (val r = api.calendarSettings(MailPimJson.calSettingsBody(freeBusy = on))) {
                is MResult.Ok -> pim.putPrefs(r.value)
                else -> {
                    pim.putPrefs(before)
                    _state.update { it.copy(failure = r) }
                }
            }
        }
    }

    /** „Anzeigen“ of a calendar shared with the person (the first time: the owner reads „hat angenommen“). */
    fun toggleShared(x: MSharedCalendar) {
        _state.update { st -> st.copy(shared = st.shared?.copy(items = st.shared.items.map { if (it.id == x.id) it.copy(visible = !x.visible) else it })) }
        viewModelScope.launch {
            val r = api.patchShared(x.id, visible = !x.visible)
            if (r !is MResult.Ok) _state.update { it.copy(failure = r) }
            loadShared()
            pim.refreshCalendars()
        }
    }

    /** „Anzeigen“ of an account's calendar of another mailbox (like the account's own ones). */
    fun toggleAccount(x: MSharedAccountCalendar) {
        _state.update { st -> st.copy(shared = st.shared?.copy(accounts = st.shared.accounts.map { if (it.calendarId == x.calendarId) it.copy(visible = !x.visible) else it })) }
        viewModelScope.launch {
            when (val r = api.patchCalendar(x.calendarId, MailPimJson.calendarPatch(visible = !x.visible))) {
                is MResult.Ok -> pim.putCalendar(r.value)
                else -> _state.update { it.copy(failure = r) }
            }
            loadShared()
        }
    }

    fun clearFailure() = _state.update { it.copy(failure = null) }
}

/** „Kalender teilen“ (mockup Teilen). */
@Composable
fun ShareCalendarScreen(onBack: () -> Unit, viewModel: ShareCalendarViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val cals by viewModel.calendars.collectAsStateWithLifecycle()
    val me by viewModel.me.collectAsStateWithLifecycle()
    val context = mailContext()
    val extra = GateControlTheme.extraColors
    LaunchedEffect(state.failure) {
        state.failure?.let {
            toast(context, planErrorText(context, it))
            viewModel.clearFailure()
        }
    }
    val cal: MCalendar? = cals?.calendar(viewModel.calendarId)
    Column(Modifier.fillMaxSize().imePadding()) {
        GcSubpageBar(
            title = stringResource(if (viewModel.calendarId != null) R.string.share_title else R.string.share_with_me),
            onBack = onBack,
            backDescription = stringResource(R.string.mail_back),
        )
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (viewModel.calendarId != null) {
                Row(Modifier.padding(2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(14.dp).clip(RoundedCornerShape(4.dp)).background(calColor(cal?.color)))
                    Text(cal?.name.orEmpty(), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text(stringResource(R.string.share_gc_calendar), style = MaterialTheme.typography.bodySmall, color = extra.muted)
                }
                AddCard(state, viewModel)
                Text(stringResource(R.string.share_shared_with), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(2.dp).semantics { heading() })
                GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.Top) {
                    val shares = state.shares
                    when {
                        shares == null && state.loading -> Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) }
                        shares == null -> Text(state.error?.let { planErrorText(context, it) }.orEmpty(), color = extra.muted, modifier = Modifier.padding(14.dp))
                        shares.isEmpty() -> Text(stringResource(R.string.share_none), color = extra.muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(14.dp))
                        else -> shares.forEach { s -> ShareRow(s, onLevel = { viewModel.changeLevel(s, it) }, onRemove = { viewModel.remove(s) }) }
                    }
                }
            }
            if (me?.has(MMe.FEATURE_FREE_BUSY) == true) {
                GcCard(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp), verticalArrangement = Arrangement.Top) {
                    GcSwitchRow(
                        label = stringResource(R.string.share_fb_all),
                        description = stringResource(R.string.share_fb_all_d),
                        checked = cals?.prefs?.freeBusy ?: true,
                        onCheckedChange = viewModel::setFreeBusy,
                    )
                }
            }
            Text(stringResource(R.string.share_with_me), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(2.dp).semantics { heading() })
            GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.Top) {
                val l = state.shared
                if (l == null) {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) }
                } else if (l.items.isEmpty() && l.accounts.isEmpty()) {
                    Text(stringResource(R.string.share_mine_none), color = extra.muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(14.dp))
                } else {
                    l.items.forEach { x ->
                        val level = levelText(x.level) + (x.group?.let { " · " + stringResource(R.string.share_via, it) } ?: "") + if (!x.accepted) " · " + stringResource(R.string.share_new) else ""
                        SharedRow(stringResource(R.string.share_from, x.name, x.ownerName.ifBlank { "?" }), level, x.color, x.visible) { viewModel.toggleShared(x) }
                    }
                    l.accounts.forEach { x ->
                        val account = me?.account(x.accountId)?.label
                        SharedRow(
                            x.name + (account?.let { " · $it" } ?: ""),
                            listOf(x.ownerEmail, levelText(if (x.writable) MShare.WRITE else MShare.READ)).filter { it.isNotBlank() }.joinToString(" · "),
                            x.color,
                            x.visible,
                        ) { viewModel.toggleAccount(x) }
                    }
                }
            }
            Text(stringResource(R.string.share_accounts_note), style = MaterialTheme.typography.bodySmall, color = extra.muted, modifier = Modifier.padding(horizontal = 2.dp))
            Box(Modifier.heightIn(min = 16.dp))
        }
    }
}

@Composable
internal fun levelText(level: String): String = stringResource(
    when (level) {
        MShare.FREEBUSY -> R.string.share_level_freebusy
        MShare.WRITE -> R.string.share_level_write
        else -> R.string.share_level_read
    },
)

/** „Person oder Gruppe hinzufügen“: the search, its results, the level for the new share. */
@Composable
private fun AddCard(state: ShareState, viewModel: ShareCalendarViewModel) {
    val extra = GateControlTheme.extraColors
    GcCard(contentPadding = PaddingValues(14.dp)) {
        Text(stringResource(R.string.share_add), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        GcTextField(state.query, viewModel::search, stringResource(R.string.share_add), placeholder = stringResource(R.string.share_search_ph))
        if (state.results.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(extra.panel2)) {
                state.results.forEach { p ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { viewModel.share(p) }.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Avatar(p.name, p.color, group = p.type == MShare.GROUP)
                        Text(
                            if (p.type == MShare.GROUP) stringResource(R.string.share_group_entry, p.sub ?: p.name) else p.name,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
        GcSegmented(options = MShare.LEVELS.map { it to levelText(it) }, selected = state.level, onSelect = viewModel::setLevel)
        Text(stringResource(R.string.share_levels_note), style = MaterialTheme.typography.bodySmall, color = extra.muted)
    }
}

@Composable
private fun Avatar(name: String, color: String?, group: Boolean) {
    val c = if (group) Color(0xFFF5B544) else calColor(color, fallback = Color(0xFF79B8FF))
    Box(Modifier.size(32.dp).clip(CircleShape).background(c.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
        if (group) {
            Icon(GcIcons.Users, contentDescription = null, tint = c, modifier = Modifier.size(18.dp))
        } else {
            Text(MailPlanLogic.initials(name), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = c)
        }
    }
}

/** One share: avatar, name, its state or the group's size, the level with „Nicht mehr teilen“. */
@Composable
private fun ShareRow(s: MShare, onLevel: (String) -> Unit, onRemove: () -> Unit) {
    val extra = GateControlTheme.extraColors
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Avatar(s.name, null, group = s.type == MShare.GROUP)
        Column(Modifier.weight(1f)) {
            Text(s.name.ifBlank { "?" }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                when {
                    s.type == MShare.GROUP -> androidx.compose.ui.res.pluralStringResource(R.plurals.share_members, s.members ?: 0, s.members ?: 0)
                    s.state == MShare.STATE_ACCEPTED -> stringResource(R.string.share_state_accepted)
                    else -> stringResource(R.string.share_state_pending)
                },
                style = MaterialTheme.typography.bodySmall,
                color = extra.muted,
            )
        }
        Box {
            val label = levelText(s.level)
            val desc = stringResource(R.string.share_level_for, s.name)
            Row(
                Modifier.heightIn(min = 36.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, extra.border2, RoundedCornerShape(10.dp))
                    .clickable(role = Role.DropdownList) { open = true }.semantics { contentDescription = "$desc: $label" }
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Icon(GcIcons.ChevronDown, contentDescription = null, tint = extra.muted, modifier = Modifier.size(16.dp))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                MShare.LEVELS.forEach { l -> DropdownMenuItem(text = { Text(levelText(l)) }, onClick = { open = false; onLevel(l) }) }
                DropdownMenuItem(text = { Text(stringResource(R.string.share_remove), color = MaterialTheme.colorScheme.error) }, onClick = { open = false; onRemove() })
            }
        }
    }
}

/** A calendar shared with the person: its color (a frame while hidden), name and owner, level, „Anzeigen“. */
@Composable
private fun SharedRow(title: String, sub: String, color: String, visible: Boolean, onToggle: () -> Unit) {
    val extra = GateControlTheme.extraColors
    val c = calColor(color)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(14.dp).clip(RoundedCornerShape(4.dp)).then(if (visible) Modifier.background(c) else Modifier.border(2.dp, c, RoundedCornerShape(4.dp))))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = extra.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(
            Modifier.heightIn(min = 36.dp).clip(RoundedCornerShape(10.dp))
                .then(if (visible) Modifier.background(extra.accentBg) else Modifier.border(1.dp, extra.border2, RoundedCornerShape(10.dp)))
                .toggleable(value = visible, role = Role.Switch, onValueChange = { onToggle() })
                .padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(R.string.share_show), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = if (visible) extra.accentText else MaterialTheme.colorScheme.onSurface)
        }
    }
}
