package com.gatecontrol.android.ui.update

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.BuildConfig
import com.gatecontrol.android.R
import com.gatecontrol.android.update.PlayUpdateState
import com.gatecontrol.android.update.PostUpdateMarker
import com.gatecontrol.android.update.ReleaseNotesState
import com.gatecontrol.android.update.UpdateFormat
import com.gatecontrol.android.update.UpdateSheetPage
import com.gatecontrol.android.update.UpdateToast
import com.gatecontrol.android.update.UpdateVersions
import com.gatecontrol.android.ui.theme.GateControlTheme
import kotlinx.coroutines.delay

/** Snooze key of the "nicht über Google Play" card (no version code). */
private const val NOT_FROM_PLAY_SNOOZE = -1

/**
 * Update cards on the start tab (Google Play build): the offer, the
 * download running in the background, ready to restart, stopped,
 * waiting for WLAN, not installed from Play, and after an update the
 * "Das ist neu" card. [onReconnectVpn] connects the VPN the way the
 * start button does; false when that needs the person (VPN consent).
 */
@Composable
fun PlayUpdateStartSection(onReconnectVpn: () -> Boolean, viewModel: PlayUpdateViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val target by viewModel.target.collectAsStateWithLifecycle()
    val network by viewModel.network.collectAsStateWithLifecycle()
    val eta by viewModel.eta.collectAsStateWithLifecycle()
    val waitingCode by viewModel.waitingCode.collectAsStateWithLifecycle()
    val snooze by viewModel.snooze.collectAsStateWithLifecycle()
    val errorDismissed by viewModel.errorDismissed.collectAsStateWithLifecycle()
    val postUpdate by viewModel.postUpdate.collectAsStateWithLifecycle()
    val launcher = rememberUpdateLauncher(viewModel)
    val locale = currentLocale()

    // Once per visit: ask Play; after an update the card and the VPN.
    LaunchedEffect(Unit) {
        viewModel.check()
        viewModel.onStartShown(onReconnectVpn)
    }

    postUpdate?.let { DoneCard(it, viewModel) }

    val now = remember(state, snooze) { System.currentTimeMillis() }
    val name = target?.versionCode?.let(UpdateVersions::name)
    val size = target?.totalBytes?.takeIf { it > 0 }?.let { UpdateFormat.size(it, locale) }
    when (val s = state) {
        is PlayUpdateState.Available -> when {
            waitingCode != 0 -> WaitingCard(
                name = UpdateVersions.name(s.versionCode),
                size = size,
                offline = !network.online,
                onDownloadAnyway = { viewModel.startNow(launcher) },
            )
            !UpdateFormat.snoozed(now, snooze.second, snooze.first, s.versionCode) -> OfferCard(
                code = s.versionCode,
                size = size,
                viewModel = viewModel,
            )
        }
        is PlayUpdateState.Downloading -> if (!network.online) {
            if (!errorDismissed) {
                PausedCard(s.percent, offline = true, onRetry = { viewModel.retry(launcher) }, onLater = viewModel::dismissError)
            }
        } else {
            BackgroundBar(name ?: "", s.percent, eta) { viewModel.openSheet(UpdateSheetPage.Status) }
        }
        is PlayUpdateState.DownloadFailed -> if (!errorDismissed) {
            PausedCard(s.percent, offline = !network.online, onRetry = { viewModel.retry(launcher) }, onLater = viewModel::dismissError)
        }
        PlayUpdateState.Downloaded -> ReadyCard(
            name = name ?: "",
            onOpen = { viewModel.openSheet(UpdateSheetPage.Status) },
            onRestart = viewModel::restartNow,
        )
        PlayUpdateState.NotFromPlay -> if (!UpdateFormat.snoozed(now, snooze.second, snooze.first, NOT_FROM_PLAY_SNOOZE)) {
            NotFromPlayCard {
                com.gatecontrol.android.ui.chat.openPlayStore(context)
                viewModel.snoozeNotFromPlay(NOT_FROM_PLAY_SNOOZE)
            }
        }
        else -> Unit
    }
}

// --- 1 · Version X ist da ------------------------------------------------------

@Composable
private fun OfferCard(code: Int, size: String?, viewModel: PlayUpdateViewModel) {
    val extra = GateControlTheme.extraColors
    val scheme = MaterialTheme.colorScheme
    val notesState by viewModel.notes.collectAsStateWithLifecycle()
    LaunchedEffect(code) { viewModel.loadNotes() }
    val count = (notesState.forStep(BuildConfig.VERSION_CODE, code) as? ReleaseNotesState.Loaded)?.notes?.size
    val shape = RoundedCornerShape(20.dp)
    val subline = listOfNotNull(
        count?.let { pluralStringResource(R.plurals.upd_card_news, it, it) },
        size,
        stringResource(R.string.upd_via_play),
    ).joinToString(" · ")
    Row(
        Modifier.fillMaxWidth()
            .padding(top = 4.dp)
            .shadow(6.dp, shape, ambientColor = scheme.primary.copy(alpha = 0.25f), spotColor = scheme.primary.copy(alpha = 0.25f))
            .clip(shape)
            .background(scheme.surface)
            .border(1.dp, scheme.primary.copy(alpha = 0.3f), shape)
            .clickable(role = Role.Button) { viewModel.openSheet(UpdateSheetPage.Offer) }
            .padding(start = 14.dp, top = 12.dp, end = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        UpdIconTile(Icons.Rounded.Download, extra.accentBg, extra.accentText)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                stringResource(R.string.upd_card_title, UpdateVersions.name(code)),
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.5.sp, fontWeight = FontWeight.Bold),
                color = scheme.onSurface,
            )
            Text(subline, style = MaterialTheme.typography.bodySmall, color = extra.muted)
        }
        CardPill(stringResource(R.string.upd_card_view))
    }
}

/** "Ansehen" / "Neu starten" pill of the start cards (part of the card's tap target). */
@Composable
private fun CardPill(text: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier.height(36.dp).clip(RoundedCornerShape(18.dp)).background(scheme.primary).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.5.sp, fontWeight = FontWeight.ExtraBold),
            color = scheme.onPrimary,
            maxLines = 1,
        )
    }
}

// --- Update bereit – Neu starten ---------------------------------------------

@Composable
private fun ReadyCard(name: String, onOpen: () -> Unit, onRestart: () -> Unit) {
    val extra = GateControlTheme.extraColors
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier.fillMaxWidth()
            .padding(top = 4.dp)
            .shadow(6.dp, shape, ambientColor = scheme.primary.copy(alpha = 0.25f), spotColor = scheme.primary.copy(alpha = 0.25f))
            .clip(shape)
            .background(scheme.surface)
            .border(1.dp, scheme.primary.copy(alpha = 0.3f), shape)
            .clickable(role = Role.Button, onClick = onOpen)
            .padding(start = 14.dp, top = 12.dp, end = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        UpdIconTile(Icons.Rounded.RestartAlt, extra.accentBg, extra.accentText)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                stringResource(R.string.upd_ready_card_title),
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.5.sp, fontWeight = FontWeight.Bold),
                color = scheme.onSurface,
            )
            Text(stringResource(R.string.upd_ready_card_text, name), style = MaterialTheme.typography.bodySmall, color = extra.muted)
        }
        // Own 48 dp target: restarts right away.
        Box(
            Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp)).clickable(role = Role.Button, onClick = onRestart),
            contentAlignment = Alignment.Center,
        ) {
            CardPill(stringResource(R.string.play_update_restart))
        }
    }
}

// --- 4 · Im Hintergrund weiterladen ------------------------------------------

@Composable
private fun BackgroundBar(name: String, percent: Int?, eta: Int?, onOpen: () -> Unit) {
    val p = percent ?: 0
    val description = pluralStringResource(R.plurals.upd_bar_desc, p, p)
    val text = listOfNotNull("$p %", etaShort(eta), stringResource(R.string.upd_bar_keep_working)).joinToString(" · ")
    val shape = RoundedCornerShape(20.dp)
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            .clip(shape)
            .clickable(role = Role.Button, onClick = onOpen)
            .semantics {
                contentDescription = description
                progressBarRangeInfo = ProgressBarRangeInfo(p / 100f, 0f..1f)
            },
        shape = shape,
        color = updInkSurface(),
        border = updInkBorder(),
    ) {
        Column(Modifier.clearAndSetSemantics { }) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val ink = updInkSurface()
                UpdRing(p / 100f, UpdInkAccent, Color.White.copy(alpha = 0.18f), stroke = 4.dp, modifier = Modifier.size(36.dp)) {
                    Box(Modifier.size(28.dp).clip(CircleShape).background(ink), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.ArrowDownward, contentDescription = null, tint = UpdInkAccent, modifier = Modifier.size(14.dp))
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        stringResource(R.string.upd_bar_title, name),
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.5.sp, fontWeight = FontWeight.Bold),
                        color = Color.White,
                    )
                    Text(text, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp), color = UpdOnInkMuted)
                }
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = UpdOnInkMuted, modifier = Modifier.size(18.dp))
            }
            Box(Modifier.fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.12f))) {
                Box(Modifier.fillMaxWidth(p / 100f).height(3.dp).background(UpdInkAccent))
            }
        }
    }
}

// --- 7 · Fehler und Sonderfälle ----------------------------------------------

@Composable
private fun UpdCompactCard(
    icon: ImageVector,
    iconBg: Color,
    iconTint: Color,
    title: String,
    text: String,
    border: Color = GateControlTheme.extraColors.border,
    buttons: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    val extra = GateControlTheme.extraColors
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, border),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.semantics(mergeDescendants = true) { }, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                UpdIconTile(icon, iconBg, iconTint)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(text, style = MaterialTheme.typography.bodySmall.copy(lineHeight = 19.sp), color = extra.muted)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = buttons)
        }
    }
}

@Composable
private fun PausedCard(percent: Int?, offline: Boolean, onRetry: () -> Unit, onLater: () -> Unit) {
    val extra = GateControlTheme.extraColors
    val error = MaterialTheme.colorScheme.error
    UpdCompactCard(
        icon = Icons.Rounded.WifiOff,
        iconBg = extra.errorBg,
        iconTint = error,
        title = percent?.let { stringResource(R.string.upd_paused_title, it) } ?: stringResource(R.string.upd_paused_title_plain),
        text = stringResource(if (offline) R.string.upd_paused_offline else R.string.upd_paused_failed),
        border = error.copy(alpha = 0.3f),
    ) {
        UpdCardButton(stringResource(R.string.upd_retry), onRetry, filled = true)
        UpdCardButton(stringResource(R.string.upd_later), onLater, filled = false)
    }
}

@Composable
private fun WaitingCard(name: String, size: String?, offline: Boolean, onDownloadAnyway: () -> Unit) {
    val extra = GateControlTheme.extraColors
    UpdCompactCard(
        icon = Icons.Rounded.Wifi,
        iconBg = extra.blueBg,
        iconTint = extra.blue,
        title = stringResource(R.string.upd_wait_title, name),
        text = when {
            offline -> stringResource(R.string.upd_wait_text_offline)
            size != null -> stringResource(R.string.upd_wait_text, size)
            else -> stringResource(R.string.upd_wait_text_plain)
        },
    ) {
        UpdCardButton(stringResource(R.string.upd_download_anyway), onDownloadAnyway, filled = false)
    }
}

@Composable
private fun NotFromPlayCard(onOpenPlay: () -> Unit) {
    val extra = GateControlTheme.extraColors
    val scheme = MaterialTheme.colorScheme
    UpdCompactCard(
        icon = Icons.Outlined.ErrorOutline,
        iconBg = extra.warnBg,
        iconTint = extra.warn,
        title = stringResource(R.string.upd_notplay_title),
        text = stringResource(R.string.upd_notplay_text),
    ) {
        UpdCardButton(stringResource(R.string.upd_open_play), onOpenPlay, filled = true, container = scheme.inverseSurface, content = scheme.inverseOnSurface)
    }
}

// --- 6 · Nach dem Neustart -----------------------------------------------------

@Composable
private fun DoneCard(marker: PostUpdateMarker, viewModel: PlayUpdateViewModel) {
    val context = LocalContext.current
    val extra = GateControlTheme.extraColors
    val scheme = MaterialTheme.colorScheme
    val notesState by viewModel.notes.collectAsStateWithLifecycle()
    LaunchedEffect(marker) { viewModel.loadNotes(marker.fromCode, marker.toCode) }
    val notes = (notesState.forStep(marker.fromCode, marker.toCode) as? ReleaseNotesState.Loaded)?.notes.orEmpty()
    val shape = RoundedCornerShape(24.dp)
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        shape = shape,
        color = scheme.surface,
        border = BorderStroke(1.dp, extra.border),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth()
                    .background(Brush.linearGradient(listOf(UpdGradientStart, UpdGradientEnd)))
                    .padding(start = 16.dp, top = 16.dp, end = 10.dp, bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f).semantics(mergeDescendants = true) { heading() }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.upd_done_label, UpdateVersions.name(marker.toCode)), style = UpdMonoLabel, color = UpdGradientLabel)
                    Text(stringResource(R.string.upd_done_title), style = updTitleStyle(20f), color = Color.White)
                }
                // 36 dp circle inside a 48 dp target.
                Box(
                    Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = viewModel::dismissPostUpdate),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(36.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.upd_close), tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                }
            }
            Column(Modifier.padding(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (notes.isEmpty()) {
                    Text(stringResource(R.string.upd_done_empty), style = MaterialTheme.typography.bodySmall.copy(lineHeight = 19.sp), color = extra.muted)
                } else {
                    notes.take(3).forEachIndexed { i, note ->
                        val (bg, fg) = noteTint(i)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            UpdIconTile(noteIcon(note), bg, fg, size = 28.dp, iconSize = 15.dp, corner = 9.dp)
                            Text(
                                buildAnnotatedString {
                                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(note.title) }
                                    note.detail?.let { append(" · "); append(it) }
                                },
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp),
                                color = scheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    UpdCardButton(
                        stringResource(R.string.upd_done_try),
                        viewModel::dismissPostUpdate,
                        filled = true,
                        container = scheme.inverseSurface,
                        content = scheme.inverseOnSurface,
                    )
                    UpdCardButton(stringResource(R.string.upd_done_all), { openReleases(context) }, filled = false)
                }
            }
        }
    }
}

/** "Update installiert – VPN wieder verbunden" (bottom of the start tab, a few seconds). */
@Composable
fun PlayUpdateToastHost(modifier: Modifier = Modifier, viewModel: PlayUpdateViewModel = hiltViewModel()) {
    val toast by viewModel.toast.collectAsStateWithLifecycle()
    val current = toast ?: return
    LaunchedEffect(current) {
        delay(4_500)
        viewModel.toastShown()
    }
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(16.dp),
        color = updInkSurface(),
        border = updInkBorder(),
        shadowElevation = 10.dp,
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(26.dp).clip(CircleShape).background(UpdInkAccent), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Check, contentDescription = null, tint = UpdInk, modifier = Modifier.size(14.dp))
            }
            Text(
                stringResource(if (current == UpdateToast.InstalledVpnBack) R.string.upd_toast_vpn else R.string.upd_toast_installed),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
