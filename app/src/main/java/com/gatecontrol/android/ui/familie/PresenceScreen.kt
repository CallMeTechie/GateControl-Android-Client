package com.gatecontrol.android.ui.familie

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.R
import com.gatecontrol.android.familie.FamilieProfile
import com.gatecontrol.android.familie.FamilieRepository
import com.gatecontrol.android.familie.FamilieResult
import com.gatecontrol.android.familie.errorCode
import com.gatecontrol.android.familie.presence.PresenceCheck
import com.gatecontrol.android.familie.presence.PresenceMember
import com.gatecontrol.android.familie.presence.PresencePayload
import com.gatecontrol.android.familie.presence.PresenceReporter
import com.gatecontrol.android.familie.presence.PresenceSense
import com.gatecontrol.android.familie.presence.PresenceState
import com.gatecontrol.android.familie.push.FamiliePushEvent
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcChip
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcListRow
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcSwitchRow
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.theme.GateControlTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Anwesenheit: who is home, and my own "Anwesenheit teilen" (also used by
 * the app settings).
 */
@HiltViewModel
class PresenceViewModel @Inject constructor(
    repository: FamilieRepository,
    private val reporter: PresenceReporter,
) : FamilieLoadViewModel<PresencePayload>(repository) {

    val share: StateFlow<Boolean> = reporter.share
    val check: StateFlow<PresenceCheck> = reporter.check
    val sense: StateFlow<PresenceSense> = reporter.sense

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    private val _shareError = MutableStateFlow<String?>(null)
    val shareError: StateFlow<String?> = _shareError.asStateFlow()

    init {
        start()
    }

    override suspend fun load(): FamilieResult<PresencePayload> =
        when (val r = reporter.fetch()) {
            is FamilieResult.Ok -> FamilieResult.Ok(r.value.payload)
            is FamilieResult.Failed -> r
            FamilieResult.Offline -> FamilieResult.Offline
            FamilieResult.NotConfigured -> FamilieResult.NotConfigured
        }

    override fun refreshOn(event: FamiliePushEvent): Boolean =
        event is FamiliePushEvent.PresenceRule || (event is FamiliePushEvent.Other && event.topicKey == "presence")

    fun setShare(share: Boolean) {
        if (_saving.value) return
        _saving.value = true
        viewModelScope.launch {
            val r = reporter.setShare(share)
            _shareError.value = if (r is FamilieResult.Ok) null else r.errorCode()
            _saving.value = false
            if (r is FamilieResult.Ok) refresh()
        }
    }

    fun clearShareError() {
        _shareError.value = null
    }
}

/**
 * Anwesenheit (contract C, mockup AdminAutomations' "Gerade zuhause"):
 * who is home or away, who does not share; my own switch with the
 * explanation. Parents set the smart-home rules in the portal / admin page.
 */
@Composable
fun PresenceScreen(onBack: () -> Unit, viewModel: PresenceViewModel = hiltViewModel()) {
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val data by viewModel.data.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    FamilieToasts(viewModel)

    val p = profile
    FamiliePageFrame(
        title = stringResource(R.string.familie_presence_title),
        onBack = onBack,
        refreshing = busy && data != null,
        onRefresh = { viewModel.refresh() },
    ) {
        PresenceShareCard(viewModel)
        val d = data
        when {
            d == null && error != null -> FamilieErrorBanner(error, onRetry = { viewModel.refresh() })
            d == null || p == null -> FamilieLoading()
            else -> {
                if (error != null) FamilieErrorBanner(error)
                GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.Top) {
                    p.everyone.forEachIndexed { i, m ->
                        if (i > 0) GcRowDivider()
                        val state = d.of(m.userId)
                        GcListRow(
                            title = if (m.userId == p.me.userId) m.name + " (" + stringResource(R.string.familie_you) + ")" else m.name,
                            description = presenceLine(state),
                            leading = { FamilieAvatar(m.name, m.color, size = 36.dp, face = m.face, presence = state?.state) },
                            trailing = { PresenceChip(state) },
                        )
                    }
                }
                if (p.me.isParent) {
                    GcBanner(tone = GcTone.Info, icon = GcIcons.Zap) {
                        Text(stringResource(R.string.familie_presence_rules_hint), style = MaterialTheme.typography.bodySmall, color = GateControlTheme.extraColors.muted)
                    }
                }
            }
        }
    }
}

/**
 * "Anwesenheit teilen" with the explanation, a confirmation before it goes
 * on, the detection state and, when the server can't tell (older than
 * core 1.167, without the check, unreachable), a short note why.
 */
@Composable
fun PresenceShareCard(viewModel: PresenceViewModel, modifier: Modifier = Modifier) {
    val share by viewModel.share.collectAsStateWithLifecycle()
    val check by viewModel.check.collectAsStateWithLifecycle()
    val sense by viewModel.sense.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    val shareError by viewModel.shareError.collectAsStateWithLifecycle()
    PresenceShareCard(share, check, sense, saving, shareError, viewModel::setShare, viewModel::clearShareError, modifier)
}

@Composable
fun PresenceShareCard(
    share: Boolean,
    check: PresenceCheck,
    sense: PresenceSense,
    saving: Boolean,
    shareError: String?,
    onShare: (Boolean) -> Unit,
    onClearError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirm by remember { mutableStateOf(false) }
    val extra = GateControlTheme.extraColors
    val errorText = shareError?.let { familieErrorText(it) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.Top) {
            GcSwitchRow(
                label = stringResource(R.string.familie_presence_share),
                description = stringResource(R.string.familie_presence_share_desc),
                checked = share,
                onCheckedChange = { on -> if (on) confirm = true else onShare(false) },
                icon = GcIcons.Home,
                enabled = !saving,
            )
            if (share) {
                GcRowDivider()
                val undecided = sense == PresenceSense.UNKNOWN && check != PresenceCheck.NOT_YET
                Text(
                    when {
                        sense == PresenceSense.HOME -> stringResource(R.string.familie_presence_now_home)
                        sense == PresenceSense.AWAY -> stringResource(R.string.familie_presence_now_away)
                        undecided -> stringResource(R.string.familie_presence_now_undecided)
                        else -> stringResource(R.string.familie_presence_now_unknown)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (undecided) extra.warn else extra.muted,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
        val note = when {
            !share -> null
            check == PresenceCheck.OLD_SERVER -> R.string.familie_presence_old_server
            check == PresenceCheck.UNSUPPORTED -> R.string.familie_presence_unsupported
            check == PresenceCheck.FAILED -> R.string.familie_presence_check_failed
            else -> null
        }
        if (note != null) {
            GcBanner(tone = if (check == PresenceCheck.FAILED) GcTone.Info else GcTone.Warn, icon = GcIcons.Wifi) {
                Text(stringResource(note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
            }
        }
        if (errorText != null) {
            GcBanner(tone = GcTone.Error, icon = GcIcons.Alert) {
                Text(errorText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
    if (confirm) {
        FamilieConfirmDialog(
            title = stringResource(R.string.familie_presence_consent_title),
            text = stringResource(R.string.familie_presence_consent_text),
            confirmLabel = stringResource(R.string.familie_presence_consent_yes),
            onConfirm = {
                confirm = false
                onClearError()
                onShare(true)
            },
            onDismiss = { confirm = false },
        )
    }
}

/**
 * "Anwesenheit teilen" in the app settings: shown while the Familie plugin
 * is there and the presence module is on (from the stored membership; no
 * extra server call when the settings open).
 */
@HiltViewModel
class PresenceSettingsViewModel @Inject constructor(
    private val repository: FamilieRepository,
    private val reporter: PresenceReporter,
    plugins: com.gatecontrol.android.familie.PluginDirectory,
) : androidx.lifecycle.ViewModel() {

    val visible: StateFlow<Boolean> = kotlinx.coroutines.flow.combine(repository.profile, plugins.state) { p, s ->
        s.familieApi && p?.modules?.presence == true
    }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), false)

    val share: StateFlow<Boolean> = reporter.share
    val check: StateFlow<PresenceCheck> = reporter.check
    val sense: StateFlow<PresenceSense> = reporter.sense

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    private val _shareError = MutableStateFlow<String?>(null)
    val shareError: StateFlow<String?> = _shareError.asStateFlow()

    init {
        viewModelScope.launch { repository.cachedProfile() }
    }

    fun setShare(share: Boolean) {
        if (_saving.value) return
        _saving.value = true
        viewModelScope.launch {
            val r = reporter.setShare(share)
            _shareError.value = if (r is FamilieResult.Ok) null else r.errorCode()
            _saving.value = false
        }
    }

    fun clearShareError() {
        _shareError.value = null
    }
}

/** The settings group "Familie" with "Anwesenheit teilen" (only while it applies). */
@Composable
fun FamiliePresenceSettings(viewModel: PresenceSettingsViewModel = hiltViewModel()) {
    val visible by viewModel.visible.collectAsStateWithLifecycle()
    if (!visible) return
    val share by viewModel.share.collectAsStateWithLifecycle()
    val check by viewModel.check.collectAsStateWithLifecycle()
    val sense by viewModel.sense.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    val shareError by viewModel.shareError.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        com.gatecontrol.android.ui.components.GcSectionLabel(
            stringResource(R.string.familie_presence_settings_group),
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
        )
        PresenceShareCard(share, check, sense, saving, shareError, viewModel::setShare, viewModel::clearShareError)
    }
}

@Composable
fun presenceLine(state: PresenceMember?): String = when (state?.state) {
    PresenceState.HOME -> state.since?.let { stringResource(R.string.familie_presence_home_since, relativeStamp(it)) } ?: stringResource(R.string.familie_presence_home)
    PresenceState.AWAY -> state.since?.let { stringResource(R.string.familie_presence_away_since, relativeStamp(it)) } ?: stringResource(R.string.familie_presence_away)
    PresenceState.NOT_SHARED, null -> stringResource(R.string.familie_presence_not_shared)
    PresenceState.UNKNOWN -> stringResource(R.string.familie_presence_unknown)
}

@Composable
fun PresenceChip(state: PresenceMember?) {
    when (state?.state) {
        PresenceState.HOME -> GcChip(stringResource(R.string.familie_presence_home), tone = GcTone.Ok, small = true)
        PresenceState.AWAY -> GcChip(stringResource(R.string.familie_presence_away), tone = GcTone.Neutral, small = true)
        else -> GcChip("–", tone = GcTone.Neutral, small = true)
    }
}

/** Short status under a member's avatar on the start screen ("zuhause" / "unterwegs"), null when unknown. */
@Composable
fun presenceShort(state: PresenceMember?): String? = when (state?.state) {
    PresenceState.HOME -> stringResource(R.string.familie_presence_home)
    PresenceState.AWAY -> stringResource(R.string.familie_presence_away)
    else -> null
}

/** Names of the members at home ("Anna, Tim"), for the start screen. */
fun homeNames(p: FamilieProfile, presence: List<PresenceMember>?): List<String> =
    presence.orEmpty().filter { it.state == PresenceState.HOME }.mapNotNull { m -> p.nameOf(m.userId) }
