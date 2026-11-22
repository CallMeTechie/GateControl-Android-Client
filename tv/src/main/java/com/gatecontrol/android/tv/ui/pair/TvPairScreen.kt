package com.gatecontrol.android.tv.ui.pair

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.gatecontrol.android.common.EnrollmentLink
import com.gatecontrol.android.tv.R
import com.gatecontrol.android.tv.pair.PairError
import com.gatecontrol.android.tv.pair.PairEvent
import com.gatecontrol.android.tv.pair.PairState
import com.gatecontrol.android.tv.pair.PairingMachine
import com.gatecontrol.android.tv.pair.ServerInputError
import com.gatecontrol.android.tv.ui.components.TvButton
import com.gatecontrol.android.tv.ui.components.TvButtonStyle
import com.gatecontrol.android.tv.ui.components.TvPill
import com.gatecontrol.android.tv.ui.components.TvPillTone
import com.gatecontrol.android.tv.ui.components.TvTextInput
import com.gatecontrol.android.tv.ui.home.Clock
import com.gatecontrol.android.tv.ui.home.TvIcons
import com.gatecontrol.android.tv.ui.theme.GateControlTvTheme
import com.gatecontrol.android.ui.theme.MonoFontFamily
import kotlinx.coroutines.delay

/**
 * Pairing of GateControl TV (mockup "2 · Kopplung per QR und Code"): no password on the TV. Left the steps for the
 * phone, right the QR code with the pairing id and the code to type on the phone; the TV polls the server until the
 * phone approved. Without a phone: server address + setup code from the portal. The server address is asked once.
 */
@Composable
fun TvPairScreen(state: PairState, onEvent: (PairEvent) -> Unit, modifier: Modifier = Modifier) {
    val colors = GateControlTvTheme.colors
    var manual by rememberSaveable { mutableStateOf(false) }
    // the remote's Back leaves the form without a phone
    BackHandler(enabled = manual) { manual = false }
    Box(modifier.fillMaxSize().background(colors.bg)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 70.dp, end = 30.dp, top = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(22.dp).background(colors.accent, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                Icon(TvIcons.Mark, contentDescription = null, tint = colors.onAccent, modifier = Modifier.size(14.dp))
            }
            Text(stringResource(R.string.tv_app_name), style = MaterialTheme.typography.titleMedium, color = colors.text)
            Spacer(Modifier.weight(1f))
            Clock()
        }
        val server = when (state) {
            is PairState.Starting -> state.server
            is PairState.Waiting -> state.server
            is PairState.Failed -> state.server
            is PairState.Enrolling -> state.server
            else -> null
        }
        when {
            manual -> ManualSetup(server, onEvent, onBack = { manual = false })
            state is PairState.NeedServer -> ServerEntry(state.error, onEvent)
            else -> PhonePairing(state, onEvent, onManual = { manual = true })
        }
    }
    // a manual setup that worked (or is running) leaves the form
    LaunchedEffect(state) { if (state is PairState.Enrolling || state is PairState.Paired) manual = false }
}

/** Two columns like the mockup (`.pair`: 60 dp top, 70 dp sides, 40 dp between). */
@Composable
private fun TwoColumns(left: @Composable () -> Unit, right: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxSize().padding(start = 70.dp, end = 70.dp, top = 60.dp, bottom = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(40.dp),
    ) {
        Column(Modifier.weight(1f)) { left() }
        Column(Modifier.weight(1f).fillMaxSize(), verticalArrangement = Arrangement.Center) { right() }
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.headlineMedium, color = GateControlTvTheme.colors.text, modifier = Modifier.padding(bottom = 10.dp))
}

@Composable
private fun Lead(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = GateControlTvTheme.colors.muted, modifier = Modifier.padding(bottom = 12.dp))
}

@Composable
private fun Footnote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = GateControlTvTheme.colors.faint, modifier = Modifier.padding(top = 12.dp))
}

/** `.steps`: numbered in an accent circle. */
@Composable
private fun Steps(steps: List<String>) {
    val colors = GateControlTvTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        steps.forEachIndexed { i, step ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(20.dp).background(colors.accentBg, CircleShape), contentAlignment = Alignment.Center) {
                    Text("${i + 1}", style = MaterialTheme.typography.labelMedium, color = colors.accentText)
                }
                Text(step, style = MaterialTheme.typography.bodyMedium, color = colors.text, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PhonePairing(state: PairState, onEvent: (PairEvent) -> Unit, onManual: () -> Unit) {
    val colors = GateControlTvTheme.colors
    val first = remember { FocusRequester() }
    TwoColumns(
        left = {
            Heading(stringResource(R.string.tv_pair_heading))
            Lead(stringResource(R.string.tv_pair_lead))
            Steps(
                listOf(
                    stringResource(R.string.tv_pair_step1),
                    stringResource(R.string.tv_pair_step2),
                    stringResource(R.string.tv_pair_step3),
                ),
            )
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state is PairState.Failed) {
                    TvButton(
                        stringResource(R.string.tv_pair_new_code),
                        onClick = { onEvent(PairEvent.Retry) },
                        style = TvButtonStyle.Primary,
                        modifier = Modifier.focusRequester(first),
                    )
                    TvButton(stringResource(R.string.tv_pair_without_phone), onClick = onManual)
                } else {
                    TvButton(stringResource(R.string.tv_pair_without_phone), onClick = onManual, modifier = Modifier.focusRequester(first))
                }
                TvButton(stringResource(R.string.tv_pair_change_server), onClick = { onEvent(PairEvent.ChangeServer) })
            }
            Footnote(stringResource(R.string.tv_pair_any_network))
        },
        right = {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(colors.panel, RoundedCornerShape(12.dp))
                    .border(1.dp, colors.line, RoundedCornerShape(12.dp))
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (state) {
                    is PairState.Waiting -> {
                        TvQrCode(state.code.link, stringResource(R.string.tv_pair_qr_description))
                        Column {
                            CodeLabel(stringResource(R.string.tv_pair_code_label))
                            Text(state.code.code, style = MaterialTheme.typography.displaySmall.copy(fontFamily = MonoFontFamily), color = colors.text)
                            Countdown(state.code.expiresAt)
                            Spacer(Modifier.height(12.dp))
                            TvPill(stringResource(R.string.tv_pair_waiting), TvPillTone.Info)
                        }
                    }
                    is PairState.Failed -> Column(Modifier.padding(vertical = 8.dp)) {
                        CodeLabel(stringResource(R.string.tv_pair_stopped))
                        Text(errorText(state.error), style = MaterialTheme.typography.bodyMedium, color = colors.text)
                    }
                    is PairState.Enrolling -> Column(Modifier.padding(vertical = 8.dp)) {
                        TvPill(stringResource(R.string.tv_pair_enrolling), TvPillTone.Accent)
                    }
                    else -> Column(Modifier.padding(vertical = 8.dp)) {
                        TvPill(stringResource(R.string.tv_pair_fetching), TvPillTone.Neutral)
                    }
                }
            }
            Footnote(stringResource(R.string.tv_pair_qr_note))
        },
    )
    LaunchedEffect(state is PairState.Failed) { runCatching { first.requestFocus() } }
}

@Composable
private fun CodeLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = GateControlTvTheme.colors.faint,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

/** "Gültig noch 9:12 Min.", once a second. */
@Composable
private fun Countdown(expiresAt: Long) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(expiresAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val left = ((expiresAt - now).coerceAtLeast(0) + 999) / 1000
    Text(
        stringResource(R.string.tv_pair_valid_for, left / 60, (left % 60).toInt()),
        style = MaterialTheme.typography.bodySmall,
        color = GateControlTvTheme.colors.muted,
        modifier = Modifier.padding(top = 9.dp),
    )
}

@Composable
private fun errorText(error: PairError): String = stringResource(
    when (error) {
        PairError.NETWORK -> R.string.tv_pair_err_network
        PairError.NOT_SUPPORTED -> R.string.tv_pair_err_server_old
        PairError.BUSY, PairError.RATE_LIMITED -> R.string.tv_pair_err_busy
        PairError.NOT_ALLOWED -> R.string.tv_pair_err_not_allowed
        PairError.USER_DISABLED -> R.string.tv_pair_err_user_disabled
        PairError.LIMIT_REACHED -> R.string.tv_pair_err_limit
        PairError.ENROLL_FAILED, PairError.INVALID_ANSWER -> R.string.tv_pair_err_enroll
        PairError.INVALID_SETUP_CODE -> R.string.tv_pair_err_setup_code
    },
)

/** The server address, typed in once. */
@Composable
private fun ServerEntry(error: ServerInputError?, onEvent: (PairEvent) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val first = remember { FocusRequester() }
    val submit = { onEvent(PairEvent.ServerEntered(text)) }
    TwoColumns(
        left = {
            Heading(stringResource(R.string.tv_server_heading))
            Lead(stringResource(R.string.tv_server_lead))
            TvTextInput(
                value = text,
                onValueChange = { text = it.trim() },
                label = stringResource(R.string.tv_server_label),
                placeholder = "gc.example.com",
                mono = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onGo = { submit() }),
                modifier = Modifier.focusRequester(first),
            )
            if (error != null) {
                Text(
                    stringResource(if (error == ServerInputError.HTTPS_ONLY) R.string.tv_server_https_only else R.string.tv_server_invalid),
                    style = MaterialTheme.typography.bodySmall,
                    color = GateControlTvTheme.colors.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Row(Modifier.padding(top = 16.dp)) {
                TvButton(stringResource(R.string.tv_next), onClick = submit, style = TvButtonStyle.Primary, enabled = text.isNotBlank())
            }
        },
        right = {
            Footnote(stringResource(R.string.tv_server_note))
        },
    )
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

/** "Ohne Handy einrichten": server address and the 16-character setup code from the portal. */
@Composable
private fun ManualSetup(server: String?, onEvent: (PairEvent) -> Unit, onBack: () -> Unit) {
    var address by rememberSaveable { mutableStateOf(server?.removePrefix("https://").orEmpty()) }
    var code by rememberSaveable { mutableStateOf("") }
    val first = remember { FocusRequester() }
    val serverOk = PairingMachine.serverOf(address).first != null
    val codeOk = EnrollmentLink.normalizeCode(code) != null
    TwoColumns(
        left = {
            Heading(stringResource(R.string.tv_manual_heading))
            Lead(stringResource(R.string.tv_manual_lead))
            TvTextInput(
                value = address,
                onValueChange = { address = it.trim() },
                label = stringResource(R.string.tv_server_label),
                placeholder = "gc.example.com",
                mono = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next, autoCorrectEnabled = false),
                modifier = Modifier.focusRequester(first),
            )
            Spacer(Modifier.height(10.dp))
            TvTextInput(
                value = code,
                onValueChange = { code = it.uppercase().take(19) },
                label = stringResource(R.string.tv_manual_code_label),
                placeholder = "XXXX-XXXX-XXXX-XXXX",
                mono = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Done,
                    autoCorrectEnabled = false,
                ),
            )
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TvButton(
                    stringResource(R.string.tv_manual_submit),
                    onClick = { onEvent(PairEvent.ManualSetup(address, code)) },
                    style = TvButtonStyle.Primary,
                    enabled = serverOk && codeOk,
                )
                TvButton(stringResource(R.string.tv_back), onClick = onBack)
            }
        },
        right = {
            Footnote(stringResource(R.string.tv_manual_note))
        },
    )
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}
