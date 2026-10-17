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
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.R
import com.gatecontrol.android.familie.FamilieRepository
import com.gatecontrol.android.familie.FamilieResult
import com.gatecontrol.android.familie.PluginDirectory
import com.gatecontrol.android.familie.errorCode
import com.gatecontrol.android.familie.locate.FamiliePermissions.GeofenceReadiness
import com.gatecontrol.android.familie.places.GeofenceRegistrar
import com.gatecontrol.android.familie.places.RulePlaceWatcher
import com.gatecontrol.android.familie.presence.PresenceCheck
import com.gatecontrol.android.familie.presence.PresenceReporter
import com.gatecontrol.android.familie.presence.PresenceSense
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcSectionLabel
import com.gatecontrol.android.ui.components.GcSwitchRow
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.familie.FamilieConfirmDialog
import com.gatecontrol.android.ui.familie.familieErrorText
import com.gatecontrol.android.ui.familie.rememberLocationPermissionFlow
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
 * "Anwesenheit teilen" and (Familie 1.5) "Orte für Regeln teilen" in the
 * own profile: shown while the Familie plugin is there and its presence
 * module is on (from the stored membership; no
 * extra server call when the profile opens). Until 1.32 this was a card on
 * the Familie presence screen and a group in the app settings.
 */
@HiltViewModel
class ProfilePresenceViewModel @Inject constructor(
    private val repository: FamilieRepository,
    private val reporter: PresenceReporter,
    private val rulePlaces: RulePlaceWatcher,
    private val registrar: GeofenceRegistrar,
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
        viewModelScope.launch {
            // My consents as the server has them now (also whether it knows the place consent yet).
            if (repository.cachedProfile()?.modules?.presence == true) reporter.fetch()
        }
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

    // ─── Orte für Regeln teilen (Familie 1.5) ─────────────────────────

    val placesShare: StateFlow<Boolean> = rulePlaces.share

    /** Only from Familie 1.5 on (learnt from `GET presence`, fetched when the profile opens). */
    val placesSupported: StateFlow<Boolean> = rulePlaces.supported

    private val _placesSaving = MutableStateFlow(false)
    val placesSaving: StateFlow<Boolean> = _placesSaving.asStateFlow()

    private val _placesError = MutableStateFlow<String?>(null)
    val placesError: StateFlow<String?> = _placesError.asStateFlow()

    private val _readiness = MutableStateFlow(registrar.readiness())
    /** Whether the phone can watch places (Play services, precise + background location). */
    val readiness: StateFlow<GeofenceReadiness> = _readiness.asStateFlow()

    fun setPlacesShare(share: Boolean) {
        if (_placesSaving.value) return
        _placesSaving.value = true
        viewModelScope.launch {
            val r = rulePlaces.setShare(share)
            _placesError.value = when {
                r is FamilieResult.Ok -> null
                // A server before Familie 1.5 does not know the consent: say so instead of a raw code.
                r is FamilieResult.Failed && r.code == "invalid" -> "places_unsupported"
                else -> r.errorCode()
            }
            _placesSaving.value = false
        }
    }

    /** After the permission flow or when the screen comes back: re-check and register the places again. */
    fun permissionsChanged() {
        _readiness.value = registrar.readiness()
        rulePlaces.permissionsChanged()
    }

    fun clearPlacesError() {
        _placesError.value = null
    }
}

/** The profile section "Familie" with "Anwesenheit teilen" and "Orte für Regeln teilen" (only while it applies). */
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
        val placesSupported by viewModel.placesSupported.collectAsStateWithLifecycle()
        if (!placesSupported) return@Column
        val placesShare by viewModel.placesShare.collectAsStateWithLifecycle()
        val placesSaving by viewModel.placesSaving.collectAsStateWithLifecycle()
        val placesError by viewModel.placesError.collectAsStateWithLifecycle()
        val readiness by viewModel.readiness.collectAsStateWithLifecycle()
        LifecycleResumeEffect(Unit) {
            viewModel.permissionsChanged()
            onPauseOrDispose { }
        }
        PlacesShareCard(
            share = placesShare,
            readiness = readiness,
            saving = placesSaving,
            error = placesError,
            onShare = viewModel::setPlacesShare,
            onPermissionsChanged = viewModel::permissionsChanged,
            onClearError = viewModel::clearPlacesError,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/**
 * "Orte für Regeln teilen" (Familie 1.5): the phone reports only that I
 * entered or left a place a family rule uses — never a position. Needs
 * precise and background location (asked right after the consent, each step
 * with its explanation); while it is missing a banner offers the permission.
 */
@Composable
fun PlacesShareCard(
    share: Boolean,
    readiness: GeofenceReadiness,
    saving: Boolean,
    error: String?,
    onShare: (Boolean) -> Unit,
    onPermissionsChanged: () -> Unit,
    onClearError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirm by remember { mutableStateOf(false) }
    val errorText = when (error) {
        null -> null
        "places_unsupported" -> stringResource(R.string.familie_rule_places_unsupported)
        else -> familieErrorText(error)
    }
    val startPermission = rememberLocationPermissionFlow(
        background = true,
        foregroundText = R.string.familie_perm_rule_places_text,
        backgroundText = R.string.familie_perm_rule_places_bg_text,
        onResult = { onPermissionsChanged() },
    )
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.Top) {
            GcSwitchRow(
                label = stringResource(R.string.familie_rule_places_share),
                description = stringResource(R.string.familie_rule_places_share_desc),
                checked = share,
                onCheckedChange = { on -> if (on) confirm = true else onShare(false) },
                icon = GcIcons.Pin,
                enabled = !saving,
            )
        }
        if (share) {
            when (readiness) {
                GeofenceReadiness.UNAVAILABLE -> GcBanner(tone = GcTone.Warn, icon = GcIcons.Alert) {
                    Text(stringResource(R.string.familie_rule_places_unavailable), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                }
                GeofenceReadiness.NEED_LOCATION, GeofenceReadiness.NEED_BACKGROUND -> {
                    GcBanner(tone = GcTone.Warn, icon = GcIcons.Pin) {
                        Text(stringResource(R.string.familie_rule_places_need_permission), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                    }
                    GcPrimaryButton(text = stringResource(R.string.familie_perm_grant), onClick = startPermission, icon = GcIcons.Pin)
                }
                GeofenceReadiness.READY -> Unit
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
            title = stringResource(R.string.familie_rule_places_consent_title),
            text = stringResource(R.string.familie_rule_places_consent_text),
            confirmLabel = stringResource(R.string.familie_presence_consent_yes),
            onConfirm = {
                confirm = false
                onClearError()
                onShare(true)
                if (readiness == GeofenceReadiness.NEED_LOCATION || readiness == GeofenceReadiness.NEED_BACKGROUND) startPermission()
            },
            onDismiss = { confirm = false },
        )
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
