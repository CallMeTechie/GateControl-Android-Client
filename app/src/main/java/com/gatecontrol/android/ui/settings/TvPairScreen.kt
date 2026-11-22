package com.gatecontrol.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.components.GcTextField
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.theme.GateControlTheme

/**
 * "TV koppeln": the code GateControl TV shows (and, scanned or opened, its QR code) → the TV is set up through the
 * server, also when phone and TV are not in the same Wi-Fi. [link]: a `gatecontrol://tv-pair` link (scanner, camera
 * app), handled once.
 */
@Composable
fun TvPairScreen(
    onBack: () -> Unit,
    onScan: () -> Unit,
    link: String?,
    viewModel: TvPairViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val extra = GateControlTheme.extraColors
    LaunchedEffect(link) { viewModel.onLink(link) }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()),
    ) {
        GcSubpageBar(
            title = stringResource(R.string.tv_pair_title),
            onBack = onBack,
            backDescription = stringResource(R.string.common_back),
        )
        Column(
            Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            GcCard(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.tv_pair_hint), style = MaterialTheme.typography.bodyMedium, color = extra.muted)
                if (state.link != null) {
                    GcBanner(tone = GcTone.Info, icon = GcIcons.Tv) {
                        Text(stringResource(R.string.tv_pair_qr_read), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                GcTextField(
                    value = state.code,
                    onValueChange = viewModel::onCodeChange,
                    label = stringResource(R.string.tv_pair_code_label),
                    placeholder = "ABCD-EFGH",
                    hint = stringResource(R.string.tv_pair_code_hint),
                    mono = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { if (state.canApprove) viewModel.approve() }),
                )
                GcPrimaryButton(
                    text = stringResource(R.string.tv_pair_button),
                    onClick = viewModel::approve,
                    enabled = state.canApprove,
                    loading = state.busy,
                    icon = GcIcons.Tv,
                )
                GcOutlineButton(
                    text = stringResource(R.string.tv_pair_scan),
                    onClick = onScan,
                    icon = GcIcons.Qr,
                )
            }
            when (val r = state.result) {
                is TvPairResult.Paired -> GcBanner(tone = GcTone.Ok, icon = GcIcons.Check) {
                    Text(
                        if (r.name.isNullOrBlank()) stringResource(R.string.tv_pair_done)
                        else stringResource(R.string.tv_pair_done_named, r.name),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                is TvPairResult.Failed -> GcBanner(tone = GcTone.Error, icon = GcIcons.Alert) {
                    Text(errorText(r), style = MaterialTheme.typography.bodyMedium)
                }
                null -> Unit
            }
            Text(stringResource(R.string.tv_pair_footer), style = MaterialTheme.typography.bodySmall, color = extra.faint)
        }
    }
}

@Composable
private fun errorText(r: TvPairResult.Failed): String = when (r.error) {
    TvPairError.INVALID_CODE -> stringResource(R.string.tv_pair_err_code)
    TvPairError.LOCKED -> stringResource(R.string.tv_pair_err_locked, ((r.retryAfterS ?: 900L) + 59) / 60)
    TvPairError.NOT_ALLOWED -> stringResource(R.string.tv_pair_err_not_allowed)
    TvPairError.USER_DISABLED -> stringResource(R.string.setup_enroll_forbidden)
    TvPairError.RATE_LIMITED -> stringResource(R.string.tv_pair_err_rate)
    TvPairError.NOT_SUPPORTED -> stringResource(R.string.tv_pair_err_server_old)
    TvPairError.OTHER_SERVER -> stringResource(R.string.tv_pair_err_other_server)
    TvPairError.NOT_A_TV_CODE -> stringResource(R.string.tv_pair_err_not_tv)
    TvPairError.NOT_SET_UP -> stringResource(R.string.tv_pair_err_not_set_up)
    TvPairError.NETWORK -> stringResource(R.string.tv_pair_err_network)
}

