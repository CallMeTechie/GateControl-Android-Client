package com.gatecontrol.android.ui.remote

import android.app.Activity
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.gatecontrol.android.R
import com.gatecontrol.android.remote.ScreenRequest
import com.gatecontrol.android.remote.ScreenShareUi
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcIconSquare
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcSectionLabel
import com.gatecontrol.android.ui.components.GcSwitch
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.theme.GateControlTheme
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/** What the card does: the callbacks of [ScreenShareCard] (stateless, for previews and tests). */
data class ScreenShareActions(
    val onToggle: (Boolean) -> Unit = {},
    val onOpen: (Boolean) -> Unit = {},
    val onStop: () -> Unit = {},
    val onDismissEnded: () -> Unit = {},
)

/**
 * „Bildschirm freigeben“ at the top of the Remote segment (Fernzugriff, stage 5 — only watching; design section 5):
 * the switch, the code with its time left, „Ohne Code für Berechtigte“, the helper's question, Android's question,
 * the red bar while somebody watches and „Sitzung beendet. Neuer Code bereit.“ afterwards. Only shown when the
 * plugin „Fernzugriff“ answers this app.
 */
@Composable
fun ScreenShareSection(modifier: Modifier = Modifier, viewModel: ScreenShareViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    if (!state.available) return
    val context = LocalContext.current

    // while the card is open: report every minute (the plugin's online state; questions a signal lost)
    LaunchedEffect(state.enabled) {
        while (state.enabled) {
            viewModel.heartbeat()
            delay(60_000L)
        }
    }
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(1_000L)
            value = System.currentTimeMillis()
        }
    }
    // the code ran out: a new one (the plugin keeps it ≤ 10 minutes)
    LaunchedEffect(state.code?.expiresAt, now >= (state.code?.expiresAt ?: Long.MAX_VALUE)) {
        val exp = state.code?.expiresAt ?: return@LaunchedEffect
        if (System.currentTimeMillis() >= exp) viewModel.refreshCode()
    }

    // allowed: Android asks next (each sharing is a new consent; it can never be skipped)
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        viewModel.consent(r.resultCode, if (r.resultCode == Activity.RESULT_OK) r.data else null)
    }
    LaunchedEffect(state.pending?.requestId) {
        if (state.pending == null) return@LaunchedEffect
        val mpm = context.getSystemService(MediaProjectionManager::class.java)
        runCatching { consent.launch(mpm.createScreenCaptureIntent()) }.onFailure { viewModel.consent(Activity.RESULT_CANCELED, null) }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        state.live?.let { live ->
            ScreenShareLiveBar(name = live.viewerName, joined = live.viewerJoined, onStop = viewModel::stop)
        }
        ScreenShareCard(
            state = state,
            now = now,
            actions = ScreenShareActions(
                onToggle = viewModel::setEnabled,
                onOpen = viewModel::setOpen,
                onStop = viewModel::stop,
                onDismissEnded = viewModel::clearEnded,
            ),
        )
    }

    state.request?.let { req ->
        val left = ((req.expiresAt - now) / 1000L).toInt()
        LaunchedEffect(req.id, left <= 0) { if (left <= 0) viewModel.requestExpired(req.id) }
        if (left > 0) ScreenRequestDialog(req, left, onAllow = { viewModel.answer(true) }, onDeny = { viewModel.answer(false) })
    }
}

/** The red bar while the screen is shared: who watches, „Beenden“. */
@Composable
fun ScreenShareLiveBar(name: String, joined: Boolean, onStop: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(LiveRed)
            .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
            .testTag("screen-live-bar"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(GcIcons.Eye, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        Text(
            if (joined) stringResource(R.string.screen_share_live_bar, name) else stringResource(R.string.screen_share_waiting_viewer, name),
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 2,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onStop) {
            Text(stringResource(R.string.screen_share_stop), color = Color.White, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** The card itself — stateless. */
@Composable
fun ScreenShareCard(state: ScreenShareUi, now: Long, actions: ScreenShareActions, modifier: Modifier = Modifier) {
    val extra = GateControlTheme.extraColors
    val scheme = MaterialTheme.colorScheme
    val live = state.live
    Column(modifier = modifier.testTag("screen-share"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        state.ended?.takeIf { live == null }?.let { e ->
            GcBanner(tone = GcTone.Ok, icon = GcIcons.Check, modifier = Modifier.clickable(onClick = actions.onDismissEnded)) {
                Text(
                    stringResource(R.string.screen_share_after) + " " +
                        pluralStringResource(R.plurals.screen_share_after_detail, e.minutes.coerceAtLeast(1), e.viewerName, e.minutes.coerceAtLeast(1)),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        GcCard(borderColor = if (live != null) LiveRed else extra.border) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GcIconSquare(background = if (live != null) extra.errorBg else extra.accentBg) {
                    Icon(GcIcons.Monitor, contentDescription = null, tint = if (live != null) LiveRed else scheme.primary, modifier = Modifier.size(22.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.screen_share_title), style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
                    Text(stringResource(R.string.screen_share_sub), style = MaterialTheme.typography.bodySmall, color = extra.muted)
                }
                Box(Modifier.semantics { contentDescription = "screen-share-switch" }) {
                    GcSwitch(checked = state.enabled, onCheckedChange = if (state.busy || live != null) null else actions.onToggle, enabled = !state.busy)
                }
            }
            when {
                live != null -> LivePart(live.viewerName, live.since, live.viewerJoined, actions.onStop)
                state.pending != null -> Text(stringResource(R.string.screen_share_starting), style = MaterialTheme.typography.bodyMedium, color = extra.muted)
                state.enabled -> CodePart(state, now, actions)
            }
            state.error?.let { err ->
                Text(stringResource(errorText(err)), style = MaterialTheme.typography.bodySmall, color = scheme.error)
            }
        }
    }
}

@Composable
private fun CodePart(state: ScreenShareUi, now: Long, actions: ScreenShareActions) {
    val extra = GateControlTheme.extraColors
    val code = state.code
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GcSectionLabel(stringResource(R.string.screen_share_your_code))
        Text(
            code?.code?.replace('-', ' ') ?: "··· ··· ···",
            style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 34.sp, letterSpacing = 2.sp),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag("screen-code"),
        )
        val left = code?.let { (it.expiresAt - now).coerceAtLeast(0L) } ?: 0L
        LinearProgressIndicator(
            progress = { (left / CODE_MS.toFloat()).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = MaterialTheme.colorScheme.primary,
            trackColor = extra.border,
        )
        val minutes = ((left + 59_999L) / 60_000L).toInt().coerceAtLeast(1)
        Text(
            pluralStringResource(R.plurals.screen_share_code_hint, minutes, minutes),
            style = MaterialTheme.typography.bodySmall,
            color = extra.muted,
            textAlign = TextAlign.Center,
        )
    }
    // „Ohne Code für Berechtigte“ — Android asks every time all the same
    val open = state.shareMode == "open"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(extra.panel2)
            .clickable(enabled = state.allowOpen && !state.busy) { actions.onOpen(!open) }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.screen_share_open_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(
                stringResource(if (state.allowOpen) R.string.screen_share_open_desc else R.string.screen_share_open_locked),
                style = MaterialTheme.typography.bodySmall,
                color = extra.muted,
            )
        }
        GcSwitch(checked = open, onCheckedChange = null, enabled = state.allowOpen && !state.busy)
    }
}

@Composable
private fun LivePart(name: String, since: Long, joined: Boolean, onStop: () -> Unit) {
    val extra = GateControlTheme.extraColors
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(extra.panel2)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Initials(name)
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleSmall, color = scheme.onSurface)
            Text(
                if (joined) stringResource(R.string.screen_share_live_since, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(since)))
                else stringResource(R.string.screen_share_waiting_viewer, name),
                style = MaterialTheme.typography.bodySmall,
                color = extra.muted,
            )
        }
    }
    Text(stringResource(R.string.screen_share_relay_hint), style = MaterialTheme.typography.bodySmall, color = extra.muted)
    Button(
        onClick = onStop,
        modifier = Modifier.fillMaxWidth().height(48.dp),
        colors = ButtonDefaults.buttonColors(containerColor = LiveRed, contentColor = Color.White),
        shape = RoundedCornerShape(16.dp),
    ) {
        Icon(GcIcons.Close, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(stringResource(R.string.screen_share_stop_session), modifier = Modifier.padding(start = 8.dp), fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Initials(name: String, size: Int = 40) {
    val letters = name.split(' ', '-', '.').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() }.ifEmpty { "?" }
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        Text(letters, color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
    }
}

/** The helper's question: name, „Nur ansehen“, the seconds left; „Ablehnen“ / „Erlauben“. Android asks again afterwards. */
@Composable
fun ScreenRequestDialog(req: ScreenRequest, secondsLeft: Int, onAllow: () -> Unit, onDeny: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        containerColor = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.extraLarge,
        icon = { Initials(req.fromName, 48) },
        title = {
            Text(stringResource(R.string.screen_share_ask_title, req.fromName), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.screen_share_ask_sub, secondsLeft), style = MaterialTheme.typography.bodyMedium, color = GateControlTheme.extraColors.muted)
                Text(stringResource(R.string.screen_share_ask_android), style = MaterialTheme.typography.bodySmall, color = GateControlTheme.extraColors.muted)
            }
        },
        confirmButton = { TextButton(onClick = onAllow, modifier = Modifier.testTag("screen-allow")) { Text(stringResource(R.string.screen_share_allow)) } },
        dismissButton = { TextButton(onClick = onDeny, modifier = Modifier.testTag("screen-deny")) { Text(stringResource(R.string.screen_share_deny)) } },
    )
}

private const val CODE_MS = 10 * 60_000L

/** The red of a running sharing (design: „rote Leiste“) — the same in light and dark, white text on it. */
private val LiveRed = Color(0xFFC2410C)

private fun errorText(code: String): Int = when (code) {
    "no_share_right" -> R.string.screen_share_err_no_right
    "screen_unavailable" -> R.string.screen_share_err_unavailable
    "offline" -> R.string.screen_share_err_offline
    "unlicensed", "plugin_unavailable", "http_404", "http_503" -> R.string.screen_share_err_plugin
    "request_expired" -> R.string.screen_share_err_expired
    else -> R.string.screen_share_err_failed
}
