package com.gatecontrol.android.ui.familie

import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.familie.FamilieProfile
import com.gatecontrol.android.familie.LocateMode
import com.gatecontrol.android.familie.LocateRequest
import com.gatecontrol.android.familie.SosState
import com.gatecontrol.android.familie.locate.FamiliePermissions
import com.gatecontrol.android.location.DeviceLocator
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcFilterChip
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcListRow
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcSectionLabel
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.theme.GateControlTheme
import kotlinx.coroutines.launch

/**
 * Kinder-Handy (mockup AppLocateChild): the child sees every locate request
 * ("Wer hat mich wann geortet"), its mode, confirms or declines open
 * requests, says "Bin angekommen" and — held for 2 seconds — sends an SOS.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChildScreen(onBack: () -> Unit, viewModel: ChildViewModel = hiltViewModel()) {
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val requests by viewModel.requests.collectAsStateWithLifecycle()
    val arrival by viewModel.arrival.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val sos by viewModel.sos.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val extra = GateControlTheme.extraColors
    var permissionTick by remember { mutableIntStateOf(0) }

    LifecycleResumeEffect(Unit) {
        permissionTick++
        viewModel.refresh()
        onPauseOrDispose { }
    }

    val noticeText = when (val n = notice) {
        is ChildNotice.LocationSent -> stringResource(R.string.familie_locate_sent, n.accuracyM)
        ChildNotice.Declined -> stringResource(R.string.familie_child_declined)
        is ChildNotice.Error -> childErrorText(n.code)
        null -> null
    }
    LaunchedEffect(noticeText) {
        if (noticeText != null) {
            Toast.makeText(context, noticeText, Toast.LENGTH_LONG).show()
            viewModel.clearNotice()
        }
    }

    val p = profile
    val startPermission = rememberLocationPermissionFlow(
        background = p?.me?.locateMode == LocateMode.AUTO,
        foregroundText = R.string.familie_perm_child_text,
        backgroundText = R.string.familie_perm_child_bg_text,
        onResult = { permissionTick++ },
    )

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        GcSubpageBar(title = stringResource(R.string.familie_child_title), onBack = onBack, backDescription = stringResource(R.string.common_back))
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (p == null) {
                FamilieLoading()
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FamilieAvatar(p.me.name, p.me.color ?: p.member(p.me.userId)?.color, size = 52.dp, highlighted = true, face = p.faceOf(p.me.userId))
                Text(stringResource(R.string.familie_child_hello, p.me.name), style = MaterialTheme.typography.headlineSmall)
            }

            val list = requests.orEmpty()
            // Transparency: the last answer, as the mockup's banner.
            list.firstOrNull { it.status == LocateRequest.ANSWERED }?.let { last ->
                val created = FamilieFormat.parseInstant(last.at ?: last.createdAt)?.toEpochMilli()
                if (created != null && System.currentTimeMillis() - created < 24 * 60 * 60_000L) {
                    GcBanner(tone = GcTone.Info, icon = GcIcons.Crosshair) {
                        Text(
                            stringResource(R.string.familie_child_was_located, (last.requestedByName ?: memberName(p, last.requestedBy)), relativeStamp(last.at ?: last.createdAt)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            if (p.modules.locate) {
                list.filter { it.status == LocateRequest.PENDING }.forEach { r ->
                    PendingRequestCard(r, p, sending = busy == "locate:${r.id}", onSend = { viewModel.send(r.id) }, onDecline = { viewModel.decline(r.id) })
                }
                ModeCard(p, permissionTick, onGrant = startPermission)
            }

            if (p.modules.arrivalSos) {
                ArrivalCard(arrival, sending = busy == "arrival", onArrived = viewModel::arrived)
                SosCard(sos, sending = busy == "sos", onSos = viewModel::sos, onReset = viewModel::clearSos)
            }

            if (p.modules.locate) {
                GcSectionLabel(stringResource(R.string.familie_child_who_located), Modifier.padding(start = 4.dp, top = 4.dp))
                if (list.isEmpty()) {
                    Text(stringResource(R.string.familie_child_never_located), style = MaterialTheme.typography.bodyMedium, color = extra.muted, modifier = Modifier.padding(4.dp))
                } else {
                    GcCard(contentPadding = PaddingValues(vertical = 2.dp), verticalArrangement = Arrangement.Top) {
                        list.take(20).forEachIndexed { i, r ->
                            if (i > 0) GcRowDivider()
                            GcListRow(
                                title = stringResource(R.string.familie_child_located_by, (r.requestedByName ?: memberName(p, r.requestedBy)), relativeStamp(r.createdAt)),
                                description = locateStatusLine(r.status, r.at ?: r.createdAt),
                            )
                        }
                    }
                }
                Text(stringResource(R.string.familie_locate_retention), style = MaterialTheme.typography.bodySmall, color = extra.muted, modifier = Modifier.padding(horizontal = 4.dp))
            }
        }
    }
}

@Composable
private fun childErrorText(code: String): String = when (code) {
    "no_permission" -> stringResource(R.string.familie_locate_fail_permission)
    "location_off" -> stringResource(R.string.familie_locate_fail_off)
    "timeout" -> stringResource(R.string.familie_locate_fail_timeout)
    else -> familieErrorText(code)
}

@Composable
private fun PendingRequestCard(r: LocateRequest, p: FamilieProfile, sending: Boolean, onSend: () -> Unit, onDecline: () -> Unit) {
    val name = (r.requestedByName ?: memberName(p, r.requestedBy))
    GcCard(borderColor = MaterialTheme.colorScheme.primary) {
        Text(stringResource(R.string.familie_child_request_title, name), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.familie_child_request_text, name), style = MaterialTheme.typography.bodySmall, color = GateControlTheme.extraColors.muted)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GcOutlineButton(text = stringResource(R.string.familie_action_decline), onClick = onDecline, modifier = Modifier.weight(1f), enabled = !sending)
            GcPrimaryButton(text = stringResource(R.string.familie_action_send_location), onClick = onSend, loading = sending, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun ModeCard(p: FamilieProfile, permissionTick: Int, onGrant: () -> Unit) {
    val context = LocalContext.current
    val extra = GateControlTheme.extraColors
    // Re-read after returning from the permission dialogs / settings.
    val foreground = remember(permissionTick) { DeviceLocator.hasPermission(context) }
    val background = remember(permissionTick) { DeviceLocator.hasBackgroundPermission(context) }
    val needsBackground = FamiliePermissions.childNeedsBackground(p.me.locateMode, foreground, background)
    GcCard {
        Text(stringResource(R.string.familie_child_mode_title), style = MaterialTheme.typography.titleMedium)
        Text(
            when (p.me.locateMode) {
                LocateMode.AUTO -> stringResource(R.string.familie_child_mode_auto)
                LocateMode.CONFIRM -> stringResource(R.string.familie_child_mode_confirm)
                else -> stringResource(R.string.familie_child_mode_off)
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(stringResource(R.string.familie_child_mode_hint), style = MaterialTheme.typography.bodySmall, color = extra.muted)
        if (p.me.locateMode != LocateMode.OFF && (needsBackground || !foreground)) {
            GcBanner(tone = GcTone.Warn, icon = GcIcons.Pin) {
                Text(
                    stringResource(if (needsBackground) R.string.familie_child_bg_needed else R.string.familie_child_fg_needed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            GcPrimaryButton(text = stringResource(R.string.familie_perm_grant), onClick = onGrant, icon = GcIcons.Pin)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ArrivalCard(sent: ArrivalSent?, sending: Boolean, onArrived: (String?) -> Unit) {
    val extra = GateControlTheme.extraColors
    val labels = listOf(
        stringResource(R.string.familie_place_school),
        stringResource(R.string.familie_place_training),
        stringResource(R.string.familie_place_friends),
        stringResource(R.string.familie_place_home),
    )
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    GcCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(GcIcons.Flag, contentDescription = null, tint = extra.accentText, modifier = Modifier.size(22.dp))
            Text(stringResource(R.string.familie_arrived), style = MaterialTheme.typography.titleMedium)
        }
        Text(stringResource(R.string.familie_arrived_hint), style = MaterialTheme.typography.bodySmall, color = extra.muted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            labels.forEach { l -> GcFilterChip(text = l, selected = picked == l, onClick = { picked = if (picked == l) null else l }) }
        }
        GcPrimaryButton(
            text = if (picked != null) stringResource(R.string.familie_arrived_at, picked!!) else stringResource(R.string.familie_arrived),
            onClick = { onArrived(picked) },
            loading = sending,
            icon = GcIcons.Check,
        )
        if (sent != null) {
            Text(
                stringResource(R.string.familie_arrived_sent, FamilieFormat.time(java.time.Instant.ofEpochMilli(sent.atMs))),
                style = MaterialTheme.typography.bodySmall,
                color = extra.accentText,
            )
        }
    }
}

@Composable
private fun SosCard(sos: SosState?, sending: Boolean, onSos: () -> Unit, onReset: () -> Unit) {
    val extra = GateControlTheme.extraColors
    GcCard {
        if (sos == null) {
            var confirm by rememberSaveable { mutableStateOf(false) }
            SosHoldButton(sending = sending, onTriggered = onSos, onAccessibleClick = { confirm = true })
            if (confirm) {
                FamilieConfirmDialog(
                    title = stringResource(R.string.familie_sos_confirm_title),
                    text = stringResource(R.string.familie_sos_hint),
                    confirmLabel = stringResource(R.string.familie_sos_confirm),
                    destructive = true,
                    onConfirm = {
                        confirm = false
                        onSos()
                    },
                    onDismiss = { confirm = false },
                )
            }
            Text(stringResource(R.string.familie_sos_hint), style = MaterialTheme.typography.bodySmall, color = extra.muted)
        } else {
            GcBanner(tone = GcTone.Error, icon = GcIcons.Siren) {
                Text(stringResource(R.string.familie_sos_sent), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
            if (sos.ack != null) {
                GcBanner(tone = GcTone.Ok, icon = GcIcons.Check) {
                    Text(sos.ack, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                }
            } else {
                Text(stringResource(R.string.familie_sos_waiting_ack), style = MaterialTheme.typography.bodySmall, color = extra.muted)
            }
            GcOutlineButton(text = stringResource(R.string.familie_sos_again), onClick = onReset)
        }
    }
}

/** Red button that sends only after being held for 2 seconds (no accidental SOS). */
@Composable
private fun SosHoldButton(sending: Boolean, onTriggered: () -> Unit, onAccessibleClick: () -> Unit) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val label = stringResource(if (sending) R.string.familie_sos_sending else R.string.familie_sos_hold)
    val red = MaterialTheme.colorScheme.error
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(red.copy(alpha = 0.85f))
            .semantics {
                role = Role.Button
                contentDescription = label
                // Accessibility services cannot hold: a confirmation dialog instead.
                onClick {
                    if (!sending) onAccessibleClick()
                    true
                }
            }
            .pointerInput(sending) {
                if (sending) return@pointerInput
                detectTapGestures(onPress = {
                    val job = scope.launch {
                        progress.snapTo(0f)
                        progress.animateTo(1f, tween(HOLD_MS, easing = LinearEasing))
                        onTriggered()
                    }
                    tryAwaitRelease()
                    if (progress.value < 1f) job.cancel()
                    scope.launch { progress.snapTo(0f) }
                })
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(progress.value).background(Color.Black.copy(alpha = 0.25f)))
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(GcIcons.Siren, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
            Text(label, color = Color.White, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(start = 10.dp))
        }
    }
}

private const val HOLD_MS = 2_000
