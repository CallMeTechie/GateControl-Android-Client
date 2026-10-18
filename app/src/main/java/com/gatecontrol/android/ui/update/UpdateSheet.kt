package com.gatecontrol.android.ui.update

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.BuildConfig
import com.gatecontrol.android.R
import com.gatecontrol.android.update.PlayUpdateState
import com.gatecontrol.android.update.ReleaseNote
import com.gatecontrol.android.update.ReleaseNotesState
import com.gatecontrol.android.update.UpdateFormat
import com.gatecontrol.android.update.UpdateNetwork
import com.gatecontrol.android.update.UpdateSheetPage
import com.gatecontrol.android.update.UpdateVersions
import com.gatecontrol.android.ui.components.GcSwitch
import com.gatecontrol.android.ui.theme.GateControlTheme
import com.gatecontrol.android.ui.theme.MonoFontFamily
import kotlinx.coroutines.launch

/**
 * The update sheet of the Play build, app-wide (hosted by MainActivity):
 * the offer ("Update verfügbar"), the download ("wird geladen") and the
 * restart ("Bereit zum Installieren"). Also starts a download that waited
 * for WLAN once an unmetered network is there.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayUpdateSheetHost(viewModel: PlayUpdateViewModel = hiltViewModel()) {
    val page by viewModel.sheet.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val autoStart by viewModel.autoStart.collectAsStateWithLifecycle()
    val launcher = rememberUpdateLauncher(viewModel)

    // Waiting for WLAN and it is there: Play's flow starts by itself.
    LaunchedEffect(autoStart, state) {
        if (autoStart && state is PlayUpdateState.Available) viewModel.startNow(launcher)
    }
    // The download stopped or the update went away: the cards on the start tab take over.
    LaunchedEffect(page, state) {
        val s = state
        if (page == UpdateSheetPage.Status && s !is PlayUpdateState.Downloading && s != PlayUpdateState.Downloaded &&
            s !is PlayUpdateState.Available && s != PlayUpdateState.Checking
        ) viewModel.closeSheet()
    }

    val current = page ?: return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val hideThen: (() -> Unit) -> Unit = { action ->
        scope.launch { sheetState.hide() }.invokeOnCompletion { action() }
    }
    ModalBottomSheet(
        onDismissRequest = viewModel::closeSheet,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        scrimColor = Color(0x7A09141C),
        dragHandle = { UpdSheetHandle() },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 22.dp),
        ) {
            when {
                current == UpdateSheetPage.Offer -> OfferContent(
                    viewModel = viewModel,
                    onUpdate = { viewModel.requestUpdate(launcher) },
                    onLater = { hideThen { viewModel.remindLater() } },
                )
                state == PlayUpdateState.Downloaded -> ReadyContent(
                    viewModel = viewModel,
                    onRestart = viewModel::restartNow,
                    onLater = { hideThen { viewModel.restartLater() } },
                )
                else -> DownloadingContent(viewModel = viewModel, onBackground = { hideThen { viewModel.closeSheet() } })
            }
        }
    }
}

// --- 2 · Update verfügbar ----------------------------------------------------

@Composable
private fun OfferContent(viewModel: PlayUpdateViewModel, onUpdate: () -> Unit, onLater: () -> Unit) {
    val context = LocalContext.current
    val extra = GateControlTheme.extraColors
    val scheme = MaterialTheme.colorScheme
    val state by viewModel.state.collectAsStateWithLifecycle()
    val target by viewModel.target.collectAsStateWithLifecycle()
    val network by viewModel.network.collectAsStateWithLifecycle()
    val wifiOnly by viewModel.wifiOnly.collectAsStateWithLifecycle()
    val notesState by viewModel.notes.collectAsStateWithLifecycle()
    val code = (state as? PlayUpdateState.Available)?.versionCode ?: target?.versionCode ?: return
    val size = target?.totalBytes?.takeIf { it > 0 }
    val locale = currentLocale()
    LaunchedEffect(code) { viewModel.loadNotes() }
    val notes = (notesState.forStep(BuildConfig.VERSION_CODE, code) as? ReleaseNotesState.Loaded)?.notes.orEmpty()

    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        // App tile · title · 1.45.2 → 1.46.0 · 18 MB
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier.size(60.dp)
                    .shadow(10.dp, RoundedCornerShape(18.dp), ambientColor = scheme.primary, spotColor = scheme.primary)
                    .clip(RoundedCornerShape(18.dp)).background(scheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.VerifiedUser, contentDescription = null, tint = scheme.onPrimary, modifier = Modifier.size(30.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.upd_sheet_title),
                    style = updTitleStyle(),
                    color = scheme.onSurface,
                    modifier = Modifier.semantics { heading() },
                )
                VersionLine(BuildConfig.VERSION_NAME, UpdateVersions.name(code), size?.let { UpdateFormat.size(it, locale) })
            }
        }

        // WAS IST NEU (only with notes) · Alle Änderungen ansehen
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (notes.isNotEmpty()) {
                Text(stringResource(R.string.upd_whats_new), style = UpdMonoLabel, color = extra.faint, modifier = Modifier.semantics { heading() })
                notes.take(3).forEachIndexed { i, note -> NoteItem(i, note) }
            }
            Box(
                Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button) { openReleases(context) },
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    stringResource(R.string.upd_all_changes_link),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp, fontWeight = FontWeight.Bold),
                    color = extra.accentText,
                )
            }
        }

        WifiOnlyRow(wifiOnly, network, viewModel::setWifiOnly)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.Info, contentDescription = null, tint = extra.muted, modifier = Modifier.padding(top = 1.dp).size(16.dp))
            Text(
                stringResource(R.string.upd_info),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp, lineHeight = 18.sp),
                color = extra.muted,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            UpdBigButton(stringResource(R.string.upd_update_now), Icons.Rounded.Download, onUpdate)
            UpdTextButton(stringResource(R.string.upd_remind_later), onClick = onLater)
        }
    }
}

@Composable
private fun VersionLine(from: String, to: String, size: String?) {
    val extra = GateControlTheme.extraColors
    val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily, fontWeight = FontWeight.Medium)
    val description = stringResource(R.string.upd_version_change, from, to) + (size?.let { ", $it" } ?: "")
    Row(
        Modifier.clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(from, style = mono, color = extra.faint)
        Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, tint = extra.faint, modifier = Modifier.size(14.dp))
        Text(
            to,
            style = mono,
            color = extra.accentText,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(extra.accentBg).padding(horizontal = 8.dp, vertical = 2.dp),
        )
        if (size != null) Text("· $size", style = MaterialTheme.typography.bodySmall, color = extra.muted)
    }
}

@Composable
private fun NoteItem(index: Int, note: ReleaseNote) {
    val (bg, fg) = noteTint(index)
    Row(Modifier.semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        UpdIconTile(noteIcon(note), bg, fg, size = 36.dp, iconSize = 18.dp, corner = 12.dp)
        Column(Modifier.weight(1f)) {
            Text(
                note.title,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.5.sp, fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            note.detail?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                    color = GateControlTheme.extraColors.muted,
                )
            }
        }
    }
}

@Composable
private fun WifiOnlyRow(on: Boolean, network: UpdateNetwork, onChange: (Boolean) -> Unit) {
    val extra = GateControlTheme.extraColors
    val sub = stringResource(
        when {
            !network.online -> R.string.upd_net_offline
            network.wifi -> R.string.upd_net_wifi
            network.cellular -> R.string.upd_net_mobile
            else -> R.string.upd_net_other
        },
    )
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(extra.panel2)
            .toggleable(value = on, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Rounded.Wifi, contentDescription = null, tint = extra.muted, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                stringResource(R.string.upd_wifi_only),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(sub, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp), color = extra.muted)
        }
        // The row is the switch (one target, one announcement).
        GcSwitch(checked = on, onCheckedChange = null)
    }
}

// --- 3 · wird geladen ----------------------------------------------------------

@Composable
private fun DownloadingContent(viewModel: PlayUpdateViewModel, onBackground: () -> Unit) {
    val extra = GateControlTheme.extraColors
    val scheme = MaterialTheme.colorScheme
    val state by viewModel.state.collectAsStateWithLifecycle()
    val target by viewModel.target.collectAsStateWithLifecycle()
    val network by viewModel.network.collectAsStateWithLifecycle()
    val eta by viewModel.eta.collectAsStateWithLifecycle()
    val locale = currentLocale()
    val dl = state as? PlayUpdateState.Downloading
    val percent = dl?.percent ?: 0
    val total = dl?.totalBytes?.takeIf { it > 0 } ?: target?.totalBytes?.takeIf { it > 0 }
    val name = target?.versionCode?.let(UpdateVersions::name) ?: ""
    val ringLabel = stringResource(R.string.upd_dl_ring_desc)
    val subline = listOfNotNull(
        etaLong(eta) ?: stringResource(if (dl?.percent == null) R.string.upd_dl_pending else R.string.upd_eta_unknown),
        viaNetwork(network),
    ).joinToString(" · ")

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        UpdRing(
            progress = percent / 100f,
            color = scheme.primary,
            track = extra.border,
            stroke = 12.dp,
            modifier = Modifier.padding(top = 8.dp).size(168.dp).semantics {
                contentDescription = ringLabel
                progressBarRangeInfo = ProgressBarRangeInfo(percent / 100f, 0f..1f)
                stateDescription = "$percent %"
            },
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    buildAnnotatedString {
                        append(percent.toString())
                        withStyle(SpanStyle(fontSize = 20.sp, color = extra.muted)) { append(" %") }
                    },
                    style = MaterialTheme.typography.displayLarge.copy(fontSize = 40.sp, lineHeight = 44.sp, fontWeight = FontWeight.ExtraBold),
                    color = scheme.onSurface,
                )
                if (dl != null && total != null) {
                    Text(
                        UpdateFormat.progress(dl.bytesDownloaded, total, locale),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily, fontSize = 12.sp),
                        color = extra.faint,
                    )
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(R.string.upd_dl_title, name),
                style = updTitleStyle(),
                color = scheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                subline,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp),
                color = extra.muted,
                textAlign = TextAlign.Center,
            )
        }
        UpdSteps(
            listOf(
                stringResource(R.string.upd_step_found) to StepState.Done,
                stringResource(R.string.upd_step_download) to StepState.Active,
                stringResource(R.string.upd_step_restart) to StepState.Pending,
            ),
        )
        // No "Abbrechen": Play offers no way to cancel a running flexible download.
        UpdBigButton(
            stringResource(R.string.upd_continue_background),
            Icons.Rounded.KeyboardArrowDown,
            onBackground,
            container = scheme.inverseSurface,
            content = scheme.inverseOnSurface,
        )
    }
}

// --- 5 · Bereit zum Installieren ---------------------------------------------

@Composable
private fun ReadyContent(viewModel: PlayUpdateViewModel, onRestart: () -> Unit, onLater: () -> Unit) {
    val extra = GateControlTheme.extraColors
    val scheme = MaterialTheme.colorScheme
    val target by viewModel.target.collectAsStateWithLifecycle()
    val vpnConnected by viewModel.vpnConnected.collectAsStateWithLifecycle()
    val locale = currentLocale()
    val name = target?.versionCode?.let(UpdateVersions::name) ?: ""
    val size = target?.totalBytes?.takeIf { it > 0 }?.let { UpdateFormat.size(it, locale) }

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Box(Modifier.padding(top = 8.dp).size(168.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(138.dp).clip(CircleShape).background(scheme.primary.copy(alpha = 0.22f)))
            Box(
                Modifier.size(112.dp).shadow(14.dp, CircleShape, ambientColor = scheme.primary, spotColor = scheme.primary)
                    .clip(CircleShape).background(scheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Check, contentDescription = null, tint = scheme.onPrimary, modifier = Modifier.size(54.dp))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(R.string.upd_ready_title),
                style = updTitleStyle(),
                color = scheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(R.string.upd_ready_text, name),
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
                color = extra.muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 310.dp),
            )
        }
        UpdSteps(
            listOf(
                stringResource(R.string.upd_step_found) to StepState.Done,
                (size?.let { stringResource(R.string.upd_step_downloaded_size, it) } ?: stringResource(R.string.upd_step_downloaded)) to StepState.Done,
                stringResource(R.string.upd_step_restart) to StepState.Active,
            ),
        )
        if (vpnConnected) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(extra.warnBg).padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = extra.warn, modifier = Modifier.padding(top = 1.dp).size(18.dp))
                Text(
                    stringResource(R.string.upd_ready_vpn_note),
                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = 19.sp),
                    color = scheme.onSurface,
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            UpdBigButton(stringResource(R.string.upd_restart_now), Icons.Rounded.RestartAlt, onRestart)
            UpdTextButton(stringResource(R.string.upd_later), sub = stringResource(R.string.upd_later_sub), onClick = onLater)
        }
    }
}

/** Ghost button under the big one ("Später erinnern", "Später – …"). */
@Composable
private fun UpdTextButton(text: String, sub: String? = null, onClick: () -> Unit) {
    val extra = GateControlTheme.extraColors
    Column(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp))
            .clickable(role = Role.Button, onClick = onClick).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge.copy(fontSize = if (sub == null) 15.sp else 14.5.sp, fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (sub != null) {
            Text(
                sub,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
                color = extra.muted,
                textAlign = TextAlign.Center,
            )
        }
    }
}
