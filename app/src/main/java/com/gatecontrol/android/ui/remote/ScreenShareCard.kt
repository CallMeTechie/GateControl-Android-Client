package com.gatecontrol.android.ui.remote

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import androidx.compose.foundation.BorderStroke
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.DisposableEffect
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
    /** Stage 6: „Nur ansehen“ — the helper can no longer operate the phone. */
    val onViewOnly: () -> Unit = {},
    /** Stage 6: „Steuerung einrichten“ — the hint before Android's accessibility settings. */
    val onSetupControl: () -> Unit = {},
)

/**
 * „Bildschirm freigeben“ at the top of the Remote segment (Fernzugriff, stage 5 — only watching; design section 5):
 * the switch, the code with its time left, „Ohne Code für Berechtigte“, the helper's question, Android's question,
 * the red bar while somebody watches and „Sitzung beendet. Neuer Code bereit.“ afterwards. Only shown when the
 * plugin „Fernzugriff“ answers this app.
 *
 * Stage 6 (GitHub build only): „Steuerung ist noch aus“ with „Steuerung einrichten“ (the hint required before Android's
 * accessibility settings), the question „… möchte deinen Bildschirm sehen und steuern“ with „Nur ansehen lassen“, and
 * while the helper controls the phone the red bar „… steuert deinen Bildschirm“ with „Nur ansehen“ and „Beenden“.
 */
@Composable
fun ScreenShareSection(modifier: Modifier = Modifier, viewModel: ScreenShareViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    if (!state.available) return
    val context = LocalContext.current
    var setup by rememberSaveable { mutableStateOf(false) }

    // stage 6: back from Android's accessibility settings — is the service on now?
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, state.controlSupported) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME && state.controlSupported) viewModel.refreshControl() }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

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
            ScreenShareLiveBar(name = live.viewerName, joined = live.viewerJoined, onStop = viewModel::stop, control = live.control, onViewOnly = viewModel::viewOnly)
        }
        ScreenShareCard(
            state = state,
            now = now,
            actions = ScreenShareActions(
                onToggle = viewModel::setEnabled,
                onOpen = viewModel::setOpen,
                onStop = viewModel::stop,
                onDismissEnded = viewModel::clearEnded,
                onViewOnly = viewModel::viewOnly,
                onSetupControl = { setup = true },
            ),
        )
    }

    state.request?.let { req ->
        val left = ((req.expiresAt - now) / 1000L).toInt()
        LaunchedEffect(req.id, left <= 0) { if (left <= 0) viewModel.requestExpired(req.id) }
        if (left > 0) {
            ScreenRequestDialog(
                req, left,
                canControl = state.controlSupported && state.controlReady,
                controlSupported = state.controlSupported,
                onAllow = { control -> viewModel.answer(true, control) },
                onDeny = { viewModel.answer(false) },
            )
        }
    }

    if (setup) {
        ScreenControlSetupDialog(
            onSettings = {
                setup = false
                runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            },
            onAppInfo = {
                runCatching {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            },
            onDismiss = { setup = false },
        )
    }
}

/** The red bar while the screen is shared: who watches (stage 6: who controls, with „Nur ansehen“), „Beenden“. */
@Composable
fun ScreenShareLiveBar(name: String, joined: Boolean, onStop: () -> Unit, modifier: Modifier = Modifier, control: Boolean = false, onViewOnly: () -> Unit = {}) {
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
        Icon(if (control) GcIcons.Hand else GcIcons.Eye, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        Text(
            when {
                !joined -> stringResource(R.string.screen_share_waiting_viewer, name)
                control -> stringResource(R.string.screen_control_live_bar, name)
                else -> stringResource(R.string.screen_share_live_bar, name)
            },
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 2,
            modifier = Modifier.weight(1f).testTag(if (control) "screen-live-control" else "screen-live-view"),
        )
        if (control) {
            TextButton(onClick = onViewOnly, modifier = Modifier.testTag("screen-bar-view-only")) {
                Text(stringResource(R.string.screen_control_view_only), color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        }
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
                live != null -> LivePart(live.viewerName, live.since, live.viewerJoined, actions.onStop, live.control, actions.onViewOnly)
                state.pending != null -> Text(stringResource(R.string.screen_share_starting), style = MaterialTheme.typography.bodyMedium, color = extra.muted)
                state.enabled -> {
                    CodePart(state, now, actions)
                    // stage 6 (GitHub build only): is control set up?
                    if (state.controlSupported) ControlPart(state.controlReady, actions.onSetupControl)
                }
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

/** Stage 6: „Steuerung ist noch aus“ with „Steuerung einrichten“ — or that it is set up (the person still decides each time). */
@Composable
private fun ControlPart(ready: Boolean, onSetup: () -> Unit) {
    val extra = GateControlTheme.extraColors
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (ready) extra.panel2 else extra.accentBg)
            .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp)
            .testTag(if (ready) "screen-control-ready" else "screen-control-off"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(if (ready) GcIcons.Check else GcIcons.Info, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(18.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(if (ready) R.string.screen_control_on_title else R.string.screen_control_off_title), style = MaterialTheme.typography.titleSmall, color = scheme.onSurface)
            Text(stringResource(if (ready) R.string.screen_control_on_desc else R.string.screen_control_off_desc), style = MaterialTheme.typography.bodySmall, color = extra.muted)
        }
        TextButton(onClick = onSetup, modifier = Modifier.testTag("screen-control-setup")) {
            Text(stringResource(if (ready) R.string.screen_control_settings else R.string.screen_control_setup))
        }
    }
}

@Composable
private fun LivePart(name: String, since: Long, joined: Boolean, onStop: () -> Unit, control: Boolean = false, onViewOnly: () -> Unit = {}) {
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
                when {
                    !joined -> stringResource(R.string.screen_share_waiting_viewer, name)
                    control -> stringResource(R.string.screen_control_live_since, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(since)))
                    else -> stringResource(R.string.screen_share_live_since, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(since)))
                },
                style = MaterialTheme.typography.bodySmall,
                color = extra.muted,
            )
        }
    }
    Text(stringResource(R.string.screen_share_relay_hint), style = MaterialTheme.typography.bodySmall, color = extra.muted)
    // stage 6: take control away at once, watching goes on (for good: control again only with a new request)
    if (control) {
        OutlinedButton(
            onClick = onViewOnly,
            modifier = Modifier.fillMaxWidth().height(48.dp).testTag("screen-view-only"),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, LiveRed),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = LiveRed),
        ) {
            Icon(GcIcons.Eye, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.screen_control_view_only), modifier = Modifier.padding(start = 8.dp), fontWeight = FontWeight.SemiBold)
        }
        Text(stringResource(R.string.screen_control_view_only_desc, name), style = MaterialTheme.typography.bodySmall, color = extra.muted)
    }
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

/**
 * The helper's question: name, the seconds left; „Ablehnen“ / „Erlauben“. Android asks again afterwards.
 * Stage 6: a request for „Ansehen und steuern“ shows the clear choice „Nur ansehen lassen“ — control only when the person
 * leaves it off ([onAllow] true). With control not set up ([canControl] false) it stays at watching, and says so.
 */
@Composable
fun ScreenRequestDialog(
    req: ScreenRequest,
    secondsLeft: Int,
    onAllow: (control: Boolean) -> Unit,
    onDeny: () -> Unit,
    canControl: Boolean = false,
    controlSupported: Boolean = false,
) {
    val extra = GateControlTheme.extraColors
    val asksControl = req.control
    var viewOnly by remember(req.id) { mutableStateOf(false) }
    val control = asksControl && canControl && !viewOnly
    AlertDialog(
        onDismissRequest = {},
        containerColor = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.extraLarge,
        icon = { Initials(req.fromName, 48) },
        title = {
            Text(
                stringResource(if (asksControl) R.string.screen_control_ask_title else R.string.screen_share_ask_title, req.fromName),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (asksControl && canControl) stringResource(R.string.screen_control_ask_sub, secondsLeft) else stringResource(R.string.screen_share_ask_sub, secondsLeft),
                    style = MaterialTheme.typography.bodyMedium,
                    color = extra.muted,
                )
                when {
                    asksControl && canControl -> Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(extra.panel2)
                            .clickable { viewOnly = !viewOnly }
                            .padding(end = 12.dp)
                            .testTag("screen-view-only-choice"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = viewOnly, onCheckedChange = { viewOnly = it })
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.screen_control_view_only_choice), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                            Text(stringResource(R.string.screen_control_view_only_choice_sub, req.fromName), style = MaterialTheme.typography.bodySmall, color = extra.muted)
                        }
                    }
                    asksControl && controlSupported -> Text(stringResource(R.string.screen_control_ask_off, req.fromName), style = MaterialTheme.typography.bodySmall, color = extra.muted)
                    asksControl -> Text(stringResource(R.string.screen_control_ask_play, req.fromName), style = MaterialTheme.typography.bodySmall, color = extra.muted)
                }
                Text(stringResource(R.string.screen_share_ask_android), style = MaterialTheme.typography.bodySmall, color = extra.muted)
            }
        },
        confirmButton = { TextButton(onClick = { onAllow(control) }, modifier = Modifier.testTag("screen-allow")) { Text(stringResource(R.string.screen_share_allow)) } },
        dismissButton = { TextButton(onClick = onDeny, modifier = Modifier.testTag("screen-deny")) { Text(stringResource(R.string.screen_share_deny)) } },
    )
}

/**
 * Stage 6: the hint before the accessibility settings (Play's „prominent disclosure“, design „Steuerung erlauben“): what
 * the service does and does not do, that it can be switched off, the way for an app from GitHub (restricted settings).
 */
@Composable
fun ScreenControlSetupDialog(onSettings: () -> Unit, onAppInfo: () -> Unit, onDismiss: () -> Unit) {
    val extra = GateControlTheme.extraColors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.extraLarge,
        icon = { Icon(GcIcons.Hand, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp)) },
        title = { Text(stringResource(R.string.screen_control_a11y_title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.testTag("screen-control-disclosure")) {
                Text(stringResource(R.string.screen_control_a11y_disclosure), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(GcIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp).padding(top = 2.dp))
                    Text(stringResource(R.string.screen_control_a11y_off_anytime), style = MaterialTheme.typography.bodySmall, color = extra.muted)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(GcIcons.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp).padding(top = 2.dp))
                    Text(stringResource(R.string.screen_control_a11y_restricted), style = MaterialTheme.typography.bodySmall, color = extra.muted)
                }
                TextButton(onClick = onAppInfo, modifier = Modifier.testTag("screen-control-app-info")) { Text(stringResource(R.string.screen_control_a11y_app_info)) }
            }
        },
        confirmButton = { TextButton(onClick = onSettings, modifier = Modifier.testTag("screen-control-go")) { Text(stringResource(R.string.screen_control_a11y_go)) } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("screen-control-later")) { Text(stringResource(R.string.screen_control_a11y_later)) } },
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
