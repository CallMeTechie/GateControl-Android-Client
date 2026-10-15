package com.gatecontrol.android.ui.profile

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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.R
import com.gatecontrol.android.familie.FamilieRepository
import com.gatecontrol.android.familie.FamilieResult
import com.gatecontrol.android.familie.PluginDirectory
import com.gatecontrol.android.familie.errorCode
import com.gatecontrol.android.familie.presence.PresenceCheck
import com.gatecontrol.android.familie.presence.PresenceReporter
import com.gatecontrol.android.familie.presence.PresenceSense
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcSectionLabel
import com.gatecontrol.android.ui.components.GcSwitchRow
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.familie.FamilieConfirmDialog
import com.gatecontrol.android.ui.familie.familieErrorText
import com.gatecontrol.android.ui.theme.GateControlTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * "Anwesenheit teilen" in the own profile: shown while the Familie plugin
 * is there and its presence module is on (from the stored membership; no
 * extra server call when the profile opens). Until 1.32 this was a card on
 * the Familie presence screen and a group in the app settings.
 */
@HiltViewModel
class ProfilePresenceViewModel @Inject constructor(
    private val repository: FamilieRepository,
    private val reporter: PresenceReporter,
    plugins: PluginDirectory,
) : ViewModel() {

    val visible: StateFlow<Boolean> = combine(repository.profile, plugins.state) { p, s ->
        s.familieApi && p?.modules?.presence == true
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

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

/** The profile section "Familie" with "Anwesenheit teilen" (only while it applies). */
@Composable
fun ProfilePresenceSection(viewModel: ProfilePresenceViewModel, modifier: Modifier = Modifier) {
    val visible by viewModel.visible.collectAsStateWithLifecycle()
    if (!visible) return
    val share by viewModel.share.collectAsStateWithLifecycle()
    val check by viewModel.check.collectAsStateWithLifecycle()
    val sense by viewModel.sense.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    val shareError by viewModel.shareError.collectAsStateWithLifecycle()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        GcSectionLabel(
            stringResource(R.string.profile_section_familie),
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
        )
        PresenceShareCard(share, check, sense, saving, shareError, viewModel::setShare, viewModel::clearShareError)
    }
}

/**
 * "Anwesenheit teilen" with the explanation, a confirmation before it goes
 * on, the detection state and, when the server can't tell (older than
 * core 1.167, without the check, unreachable), a short note why.
 */
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
