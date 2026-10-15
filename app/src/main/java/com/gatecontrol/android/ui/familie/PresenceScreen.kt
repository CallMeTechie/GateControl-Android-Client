package com.gatecontrol.android.ui.familie

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.familie.FamilieRepository
import com.gatecontrol.android.familie.FamilieResult
import com.gatecontrol.android.familie.presence.PresenceMember
import com.gatecontrol.android.familie.presence.PresencePayload
import com.gatecontrol.android.familie.presence.PresenceReporter
import com.gatecontrol.android.familie.presence.PresenceState
import com.gatecontrol.android.familie.push.FamiliePushEvent
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcChevron
import com.gatecontrol.android.ui.components.GcChip
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcListRow
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.profile.MyAvatarViewModel
import com.gatecontrol.android.ui.theme.GateControlTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Anwesenheit: who is home. My own "Anwesenheit teilen" lives in the
 * profile (ui/profile/ProfilePresence.kt); `GET presence` still syncs it.
 */
@HiltViewModel
class PresenceViewModel @Inject constructor(
    repository: FamilieRepository,
    private val reporter: PresenceReporter,
) : FamilieLoadViewModel<PresencePayload>(repository) {

    /** My consent (for the hint that leads to the profile). */
    val share: StateFlow<Boolean> = reporter.share

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
}

/**
 * Anwesenheit (contract C, mockup AdminAutomations' "Gerade zuhause"):
 * who is home or away since when, who does not share. Parents set the
 * smart-home rules in the portal / admin page; the start screen links here
 * for parents only (members' rings already show home / away). The own
 * switch is in the profile ([onOpenProfile]); my own avatar takes the ring
 * of the start tab ([MyAvatarViewModel]).
 */
@Composable
fun PresenceScreen(
    onBack: () -> Unit,
    onOpenProfile: () -> Unit = {},
    viewModel: PresenceViewModel = hiltViewModel(),
    avatarViewModel: MyAvatarViewModel = hiltViewModel(),
) {
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val data by viewModel.data.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val share by viewModel.share.collectAsStateWithLifecycle()
    val myAvatar by avatarViewModel.ui.collectAsStateWithLifecycle()
    FamilieToasts(viewModel)

    val p = profile
    FamiliePageFrame(
        title = stringResource(R.string.familie_presence_title),
        onBack = onBack,
        refreshing = busy && data != null,
        onRefresh = { viewModel.refresh() },
    ) {
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
                            leading = {
                                FamilieMemberAvatar(m, p, myAvatar, state?.state, size = 36.dp)
                            },
                            trailing = { PresenceChip(state) },
                        )
                    }
                }
                GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.Top) {
                    GcListRow(
                        title = stringResource(R.string.familie_presence_share),
                        description = stringResource(if (share) R.string.familie_presence_own_on else R.string.familie_presence_own_off),
                        onClick = onOpenProfile,
                        leading = { Icon(GcIcons.Home, contentDescription = null, tint = GateControlTheme.extraColors.accentText, modifier = Modifier.size(22.dp)) },
                        trailing = { GcChevron() },
                    )
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
