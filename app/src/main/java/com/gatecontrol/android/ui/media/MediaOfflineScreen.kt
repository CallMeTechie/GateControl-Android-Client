package com.gatecontrol.android.ui.media

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.media.MediaPlayerLogic
import com.gatecontrol.android.media.MediaTitle
import com.gatecontrol.android.media.offline.MediaOfflineLogic
import com.gatecontrol.android.media.offline.OfflineFailure
import com.gatecontrol.android.media.offline.OfflineGroup
import com.gatecontrol.android.media.offline.OfflineItem
import com.gatecontrol.android.media.offline.OfflineQuality
import com.gatecontrol.android.media.offline.OfflineState
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.theme.GateControlTheme

/**
 * The page "Offline" (mockup 7, question 22 a): "Belegt 7,4 / 20 GB" with its bar and the rules, the downloads (a
 * film, or the episodes of one season together) with "bereit", "lädt 45 %" or "wartet auf WLAN", and the note on the
 * progress and the encryption. A tap plays a ready one; the menu deletes or cancels.
 */
@Composable
fun MediaOfflineScreen(onBack: () -> Unit, onSettings: () -> Unit, onOpen: (MediaTitle) -> Unit, viewModel: MediaOfflineViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var deleting by remember { mutableStateOf<OfflineGroup?>(null) }
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    CompositionLocalProvider(LocalMediaPictures provides viewModel.pictures) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            GcSubpageBar(
                title = stringResource(R.string.media_offline_title),
                onBack = onBack,
                backDescription = stringResource(R.string.common_back),
                actions = { GcIconButton(GcIcons.Settings, contentDescription = stringResource(R.string.media_offline_settings), onClick = onSettings, tint = MaterialTheme.colorScheme.onBackground) },
            )
            LazyColumn(
                Modifier.fillMaxSize().testTag(OFFLINE_TAG),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "used") { OfflineUsed(ui) }
                if (ui.groups.isEmpty()) {
                    item(key = "empty") {
                        Text(stringResource(R.string.media_offline_empty), style = MaterialTheme.typography.bodyMedium, color = GateControlTheme.extraColors.muted, modifier = Modifier.padding(4.dp))
                    }
                } else {
                    item(key = "list") {
                        GcCard(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp), verticalArrangement = Arrangement.Top) {
                            ui.groups.forEachIndexed { i, g ->
                                if (i > 0) GcRowDivider()
                                OfflineRow(
                                    g,
                                    wifi = ui.wifi,
                                    expanded = open == g.key,
                                    onClick = {
                                        when {
                                            g.episodes && g.items.size > 1 -> open = if (open == g.key) null else g.key
                                            g.ready -> viewModel.play(g.first)
                                            else -> onOpen(g.first.titleNow())
                                        }
                                    },
                                    onPlay = { viewModel.play(it) },
                                    onDelete = { if (g.ready) deleting = g else viewModel.delete(g) },
                                    onRetry = { viewModel.retry(g) },
                                )
                                if (open == g.key) {
                                    g.items.forEach { e -> EpisodeLine(e, ui.wifi, onPlay = { viewModel.play(e) }, onDelete = { viewModel.delete(e) }) }
                                }
                            }
                        }
                    }
                }
                item(key = "note") {
                    GcCard {
                        Text(stringResource(R.string.media_offline_note), style = MaterialTheme.typography.bodySmall, color = GateControlTheme.extraColors.muted)
                    }
                }
            }
        }
        deleting?.let { g ->
            AlertDialog(
                onDismissRequest = { deleting = null },
                title = { Text(stringResource(R.string.media_offline_delete_title)) },
                text = { Text(stringResource(R.string.media_offline_delete_text, groupName(g))) },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.delete(g)
                        deleting = null
                    }) { Text(stringResource(R.string.media_offline_delete)) }
                },
                dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.media_cancel)) } },
            )
        }
    }
}

/** "Belegt 7,4 / 20 GB", the bar, "Nur im WLAN · Qualität 1080p (NAS rechnet vorher um)". */
@Composable
private fun OfflineUsed(ui: MediaOfflineUi) {
    val x = GateControlTheme.extraColors
    GcCard(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.media_offline_used), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(MediaOfflineLogic.usedText(ui.used, ui.limit), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag(OFFLINE_USED_TAG))
        }
        MediaProgressBar(if (ui.limit > 0) (ui.used.toFloat() / ui.limit).coerceIn(0f, 1f) else 0f, Modifier.height(6.dp))
        Text(
            stringResource(if (ui.quality.converted) R.string.media_offline_rules_converted else R.string.media_offline_rules, qualityName(ui.quality)),
            style = MaterialTheme.typography.bodySmall,
            color = x.muted,
        )
    }
}

@Composable
internal fun qualityName(q: OfflineQuality): String = if (q == OfflineQuality.ORIGINAL) stringResource(R.string.media_offline_quality_original) else q.label

@Composable
internal fun failureText(f: OfflineFailure?): String = stringResource(
    when (f) {
        OfflineFailure.LIMIT -> R.string.media_offline_failure_limit
        OfflineFailure.SPACE -> R.string.media_offline_failure_space
        OfflineFailure.NOT_ALLOWED -> R.string.media_offline_failure_not_allowed
        OfflineFailure.GONE -> R.string.media_offline_failure_gone
        OfflineFailure.SIGNED_OUT -> R.string.media_offline_failure_signed_out
        OfflineFailure.FAILED, null -> R.string.media_offline_failure_failed
    },
)

@Composable
private fun groupName(g: OfflineGroup): String {
    val t = g.first.title
    if (!g.episodes) return t.name
    val range = MediaOfflineLogic.episodeRange(g, stringResource(R.string.media_offline_season_letter), stringResource(R.string.media_offline_episode_letter))
    return listOfNotNull(t.seriesName ?: t.name, range).joinToString(" · ")
}

/** "1080p · 3,1 GB · bereit" / "720p · 1,9 GB · lädt 45 %" / "wartet auf WLAN" / "nicht geladen: …". */
@Composable
private fun groupLine(g: OfflineGroup, wifi: Boolean): String {
    val quality = qualityName(g.first.quality)
    g.failed?.let { return stringResource(R.string.media_offline_failed, failureText(it.failure)) }
    return when {
        g.ready -> stringResource(R.string.media_offline_ready, quality, MediaOfflineLogic.sizeText(g.size))
        g.loading != null -> stringResource(R.string.media_offline_loading, quality, MediaOfflineLogic.sizeText(g.items.sumOf { if (it.ready) it.size else it.expected }), (g.progress * 100).toInt())
        else -> restartNote(g.items.any { it.restarted }, stringResource(if (wifi) R.string.media_offline_waiting else R.string.media_offline_waiting_wifi))
    }
}

/** A converted download that was cut off starts over (the NAS converts as it goes): said after the waiting. */
@Composable
internal fun restartNote(restarted: Boolean, text: String): String =
    if (restarted) text + " · " + stringResource(R.string.media_offline_restart) else text

@Composable
internal fun itemLine(item: OfflineItem, wifi: Boolean): String = when (item.state) {
    OfflineState.READY -> stringResource(R.string.media_offline_ready, qualityName(item.quality), MediaOfflineLogic.sizeText(item.size))
    OfflineState.LOADING -> stringResource(R.string.media_offline_loading_short, (item.progress * 100).toInt())
    OfflineState.QUEUED -> restartNote(item.restarted, stringResource(if (wifi) R.string.media_offline_waiting else R.string.media_offline_waiting_wifi))
    OfflineState.FAILED -> stringResource(R.string.media_offline_failed, failureText(item.failure))
}

@Composable
private fun OfflineRow(
    g: OfflineGroup,
    wifi: Boolean,
    expanded: Boolean,
    onClick: () -> Unit,
    onPlay: (OfflineItem) -> Unit,
    onDelete: () -> Unit,
    onRetry: () -> Unit,
) {
    val x = GateControlTheme.extraColors
    var menu by remember { mutableStateOf(false) }
    val poster = rememberMediaPicture(g.first.title)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 76.dp).clickable(role = Role.Button, onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.width(40.dp).height(60.dp).clip(RoundedCornerShape(6.dp)).background(x.blueBg)) {
            if (poster != null) Image(poster, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Icon(GcIcons.Film, contentDescription = null, tint = x.muted, modifier = Modifier.align(Alignment.Center).size(18.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(groupName(g), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(groupLine(g, wifi), style = MaterialTheme.typography.bodySmall, color = if (g.failed != null) x.warn else x.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (g.loading != null) MediaProgressBar(g.progress, Modifier.padding(top = 2.dp))
        }
        if (g.ready) Icon(GcIcons.Check, contentDescription = null, tint = x.accentText, modifier = Modifier.size(20.dp))
        if (g.episodes && g.items.size > 1) Icon(if (expanded) GcIcons.ChevronUp else GcIcons.ChevronDown, contentDescription = null, tint = x.faint, modifier = Modifier.size(18.dp))
        Box {
            GcIconButton(GcIcons.MoreVertical, contentDescription = stringResource(R.string.media_offline_menu), onClick = { menu = true })
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                g.items.firstOrNull { it.ready }?.let { first ->
                    DropdownMenuItem(text = { Text(stringResource(R.string.media_offline_play)) }, onClick = { menu = false; onPlay(first) })
                }
                if (g.failed != null) DropdownMenuItem(text = { Text(stringResource(R.string.media_offline_state_retry)) }, onClick = { menu = false; onRetry() })
                DropdownMenuItem(
                    text = { Text(stringResource(if (g.ready || g.failed != null) R.string.media_offline_delete else R.string.media_offline_cancel)) },
                    onClick = { menu = false; onDelete() },
                )
            }
        }
    }
}

/** An episode of an opened row: "F5 · Name", its state, a tap plays it. */
@Composable
private fun EpisodeLine(e: OfflineItem, wifi: Boolean, onPlay: () -> Unit, onDelete: () -> Unit) {
    val x = GateControlTheme.extraColors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(enabled = e.ready, role = Role.Button, onClick = onPlay).padding(start = 52.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(MediaPlayerLogic.name(e.title).substringAfter(" · "), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(itemLine(e, wifi), style = MaterialTheme.typography.bodySmall, color = if (e.failure != null) x.warn else x.muted, maxLines = 1)
        }
        GcIconButton(GcIcons.Trash, contentDescription = stringResource(if (e.ready) R.string.media_offline_delete else R.string.media_offline_cancel), onClick = onDelete, tint = x.faint)
    }
}

/** Whether the phone is on Wi-Fi now: a waiting download says "wartet auf WLAN" without. */
val LocalOfflineWifi = compositionLocalOf { true }

internal const val OFFLINE_TAG = "media-offline"
internal const val OFFLINE_USED_TAG = "media-offline-used"
