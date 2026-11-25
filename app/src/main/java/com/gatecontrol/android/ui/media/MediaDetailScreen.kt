package com.gatecontrol.android.ui.media

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.media.MediaLink
import com.gatecontrol.android.media.MediaLogic
import com.gatecontrol.android.media.MediaPhoneLogic
import com.gatecontrol.android.media.MediaPlay
import com.gatecontrol.android.media.MediaQuality
import com.gatecontrol.android.media.MediaStream
import com.gatecontrol.android.media.MediaTitle
import com.gatecontrol.android.media.MediaTracks
import com.gatecontrol.android.media.jellyfin.JellyfinDeviceCaps
import com.gatecontrol.android.media.offline.MediaOfflineLogic
import com.gatecontrol.android.media.offline.OfflineAllowed
import com.gatecontrol.android.media.offline.OfflineItem
import com.gatecontrol.android.media.offline.OfflineQuality
import com.gatecontrol.android.media.offline.OfflineState
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcChip
import com.gatecontrol.android.ui.components.GcFilterChip
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.theme.GateControlTheme

/**
 * A title's detail page (mockup 3): backdrop and poster, the facts as chips, where it was left, "Weiter ab …",
 * "Von vorn", "Gesehen", sound and subtitle before the start; a series with its seasons and episodes.
 */
@Composable
fun MediaDetailScreen(onBack: () -> Unit, onOpen: (MediaTitle) -> Unit, viewModel: MediaDetailViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val link by viewModel.link.collectAsStateWithLifecycle()
    var first by remember { mutableStateOf(true) }
    LifecycleResumeEffect(viewModel) {
        // back from the player: where it is now
        if (!first) viewModel.load()
        first = false
        onPauseOrDispose { }
    }
    var sheet by remember { mutableStateOf<TrackSheet?>(null) }
    var loadSheet by remember { mutableStateOf<LoadSheet?>(null) }
    var offlineMenu by remember { mutableStateOf(false) }
    val wifi by viewModel.wifi.collectAsStateWithLifecycle()
    val gate by viewModel.gate.collectAsStateWithLifecycle()
    CompositionLocalProvider(LocalMediaPictures provides viewModel.pictures, LocalOfflineWifi provides wifi) {
        LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag(DETAIL_TAG), contentPadding = PaddingValues(bottom = 32.dp)) {
            item(key = "head") { DetailHead(ui.title, onBack) }
            item(key = "vpn") { MediaVpnGate(gate, onConsent = viewModel::consent, onRetry = viewModel::load) }
            when {
                ui.loading && ui.detail == null -> item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
                }
                ui.detail == null -> ui.problem?.let { p -> item(key = "problem") { MediaProblemCard(p, onRetry = viewModel::load) } }
                else -> {
                    item(key = "facts") { Facts(ui, link) }
                    if (ui.title.playable) {
                        item(key = "actions") {
                            Actions(
                                ui,
                                onPlay = viewModel::play,
                                onPlayed = viewModel::togglePlayed,
                                onLoad = { loadSheet = LoadSheet.TITLE },
                                onOffline = { offlineMenu = true },
                            )
                        }
                        item(key = "offline") {
                            OfflineStatus(ui, menu = offlineMenu, onMenu = { offlineMenu = it }, onRemove = viewModel::removeDownload, onRetry = viewModel::retryDownload)
                        }
                        item(key = "tracks") { Tracks(ui, onAudio = { sheet = TrackSheet.AUDIO }, onSubtitle = { sheet = TrackSheet.SUBTITLE }) }
                    }
                    ui.title.overview?.takeIf { it.isNotBlank() }?.let { text ->
                        item(key = "overview") {
                            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        }
                    }
                    if (ui.title.kind == MediaTitle.Kind.SERIES) {
                        if (ui.seasons.isNotEmpty()) item(key = "seasons") { Seasons(ui, onSeason = viewModel::season) }
                        if (ui.canLoad && ui.episodes.any { ui.episodesOffline[it.id] == null }) {
                            item(key = "season-load") {
                                GcOutlineButton(
                                    stringResource(R.string.media_offline_load_season),
                                    onClick = { loadSheet = LoadSheet.SEASON },
                                    icon = GcIcons.Download,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag(LOAD_SEASON_TAG),
                                )
                            }
                        } else if (ui.allowed == com.gatecontrol.android.media.offline.OfflineAllowed.NO && ui.source?.kind == com.gatecontrol.android.media.MediaSourceKind.JELLYFIN) {
                            item(key = "season-not-allowed") { NotAllowed() }
                        }
                        items(ui.episodes, key = { "ep:${it.id}" }) { e ->
                            EpisodeRow(e, offline = ui.episodesOffline[e.id], onPlay = { viewModel.playEpisode(e) }, onOpen = { onOpen(e) }, onPlayed = { viewModel.toggleEpisodePlayed(e) })
                        }
                    }
                }
            }
        }
        sheet?.let { which ->
            TrackChooser(which, ui, onPick = { index ->
                if (which == TrackSheet.AUDIO) index?.let(viewModel::chooseAudio) else viewModel.chooseSubtitle(index)
                sheet = null
            }, onDismiss = { sheet = null })
        }
        loadSheet?.let { which ->
            val quality by viewModel.offlineQuality.collectAsStateWithLifecycle()
            val (used, limit) = remember(which) { viewModel.storage() }
            LoadChooser(which, ui, quality, used, limit, onPick = { q ->
                if (which == LoadSheet.TITLE) viewModel.download(q) else viewModel.downloadSeason(q)
                loadSheet = null
            }, onDismiss = { loadSheet = null })
        }
    }
}

private enum class TrackSheet { AUDIO, SUBTITLE }

/** Backdrop with the way back, then the poster beside the name and the line "2025 · 1 Std. 48 Min. · Drama". */
@Composable
private fun DetailHead(t: MediaTitle, onBack: () -> Unit) {
    val x = GateControlTheme.extraColors
    val backdrop = rememberMediaPicture(t, backdrop = true)
    val poster = rememberMediaPicture(t)
    Column {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(x.panel2)) {
            if (backdrop != null) Image(backdrop, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.background))))
            Box(Modifier.statusBarsPadding().padding(8.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.35f))) {
                GcIconButton(GcIcons.Back, contentDescription = stringResource(R.string.common_back), onClick = onBack, tint = Color.White)
            }
        }
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Bottom) {
            Box(Modifier.width(92.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp)).background(x.blueBg)) {
                if (poster != null) Image(poster, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Icon(GcIcons.Film, contentDescription = null, tint = x.muted, modifier = Modifier.align(Alignment.Center).size(28.dp))
            }
            Column(Modifier.weight(1f).padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (t.kind == MediaTitle.Kind.EPISODE && t.seriesName != null) {
                    Text(t.seriesName!!, style = MaterialTheme.typography.labelLarge, color = x.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(t.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                metaLine(t)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = x.muted) }
            }
        }
    }
}

@Composable
private fun metaLine(t: MediaTitle): String? {
    val parts = buildList {
        if (t.kind == MediaTitle.Kind.EPISODE && t.season != null && t.episode != null) add(stringResource(R.string.media_season_episode, t.season!!, t.episode!!))
        t.year?.let { add(it.toString()) }
        t.runtimeMs?.takeIf { it > 0 && t.kind != MediaTitle.Kind.SERIES }?.let { add(runtimeText(it)) }
        t.genres.firstOrNull()?.let { add(it) }
    }
    return parts.joinToString(" · ").ifEmpty { null }
}

/** "4K · HDR10", "HEVC · mkv", "FSK 12", "Heimnetz · schnell" / "Unterwegs · 18 Mbit/s", the bar "42:10 / 1:48:00". */
@Composable
private fun Facts(ui: MediaDetailUi, link: MediaLink) {
    val v = ui.detail?.version
    val video = v?.video
    val chips = buildList {
        MediaQuality.videoLabel(video)?.let { add(it.replace(" ", " · ") to GcTone.Neutral) }
        listOfNotNull(video?.codec?.let { MediaTracks.codecLabel(it) }, v?.container?.substringBefore(',')).joinToString(" · ").ifEmpty { null }?.let { add(it to GcTone.Neutral) }
        MediaPhoneLogic.rating(ui.title.rating)?.let { add(it to GcTone.Neutral) }
        when (link.atHome) {
            true -> add(stringResource(R.string.media_home_fast) to GcTone.Ok)
            false -> add((MediaLogic.bandwidth(link)?.let { stringResource(R.string.media_link_away_bw, it) } ?: stringResource(R.string.media_link_away)) to GcTone.Info)
            null -> Unit
        }
        ui.mobileLimit?.let { add(stringResource(R.string.media_mobile_limit_chip, MediaQuality.label(it)) to GcTone.Warn) }
    }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (chips.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(chips, key = { it.first }) { (text, tone) -> GcChip(text = text, tone = tone) }
            }
        }
        val t = ui.title
        val length = t.runtimeMs
        if (t.resumable && length != null && length > 0) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                MediaProgressBar(t.progress)
                Row(Modifier.fillMaxWidth()) {
                    Text(MediaPhoneLogic.clock(t.positionMs), style = MaterialTheme.typography.labelSmall, color = GateControlTheme.extraColors.muted, modifier = Modifier.weight(1f))
                    Text(MediaPhoneLogic.clock(length), style = MaterialTheme.typography.labelSmall, color = GateControlTheme.extraColors.muted)
                }
            }
        }
    }
}

/**
 * "Weiter ab 42:10" (or "Abspielen"), then "Von vorn", "Laden" (question 22 a: Jellyfin only, when the user may
 * download) and "Gesehen" (mockup 3).
 */
@Composable
private fun Actions(ui: MediaDetailUi, onPlay: (Boolean) -> Unit, onPlayed: () -> Unit, onLoad: () -> Unit, onOffline: () -> Unit) {
    val t = ui.title
    val o = ui.offline
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GcPrimaryButton(
            text = if (t.resumable) stringResource(R.string.media_resume_from, MediaPhoneLogic.clock(t.positionMs)) else stringResource(R.string.media_play),
            onClick = { onPlay(false) },
            icon = GcIcons.Play,
            modifier = Modifier.testTag(PLAY_TAG),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (t.resumable) GcOutlineButton(stringResource(R.string.media_from_start), onClick = { onPlay(true) }, modifier = Modifier.weight(1f))
            when {
                o != null -> GcOutlineButton(
                    text = when (o.state) {
                        OfflineState.READY -> stringResource(R.string.media_offline_state_ready)
                        OfflineState.LOADING -> "${(o.progress * 100).toInt()} %"
                        OfflineState.QUEUED -> stringResource(R.string.media_offline_state_queued)
                        OfflineState.FAILED -> stringResource(R.string.media_offline_state_retry)
                    },
                    onClick = onOffline,
                    icon = if (o.ready) GcIcons.Check else GcIcons.Download,
                    modifier = Modifier.weight(1f).testTag(LOAD_TAG),
                )
                ui.canLoad -> GcOutlineButton(stringResource(R.string.media_offline_load), onClick = onLoad, icon = GcIcons.Download, modifier = Modifier.weight(1f).testTag(LOAD_TAG))
            }
            GcOutlineButton(
                text = stringResource(if (t.played) R.string.media_played else R.string.media_mark_played),
                onClick = onPlayed,
                icon = GcIcons.Check,
                modifier = Modifier.weight(1f).testTag(PLAYED_TAG),
            )
        }
    }
}

/** Under the buttons: where the download is ("Offline verfügbar · 1080p · 3,1 GB", "Lädt 45 %"), or why there is none. */
@Composable
private fun OfflineStatus(ui: MediaDetailUi, menu: Boolean, onMenu: (Boolean) -> Unit, onRemove: (OfflineItem) -> Unit, onRetry: (OfflineItem) -> Unit) {
    val x = GateControlTheme.extraColors
    val o = ui.offline
    if (o == null) {
        if (ui.fromPhone) return
        if (ui.allowed == OfflineAllowed.NO && ui.source?.kind == com.gatecontrol.android.media.MediaSourceKind.JELLYFIN) NotAllowed()
        return
    }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box {
            val quality = qualityName(o.quality)
            val text = when (o.state) {
                OfflineState.READY -> stringResource(R.string.media_offline_status_ready, quality, MediaOfflineLogic.sizeText(o.size))
                OfflineState.LOADING -> stringResource(R.string.media_offline_status_loading, (o.progress * 100).toInt(), quality)
                OfflineState.QUEUED -> stringResource(if (LocalOfflineWifi.current) R.string.media_offline_status_waiting else R.string.media_offline_status_waiting_wifi, quality)
                OfflineState.FAILED -> stringResource(R.string.media_offline_status_failed, failureText(o.failure))
            }
            Text(
                text + if (ui.fromPhone) " · " + stringResource(R.string.media_offline_from_phone) else "",
                style = MaterialTheme.typography.bodySmall,
                color = if (o.state == OfflineState.FAILED) x.warn else x.muted,
                modifier = Modifier.testTag(OFFLINE_STATUS_TAG),
            )
            DropdownMenu(expanded = menu, onDismissRequest = { onMenu(false) }) {
                if (o.state == OfflineState.FAILED) DropdownMenuItem(text = { Text(stringResource(R.string.media_offline_state_retry)) }, onClick = { onMenu(false); onRetry(o) })
                DropdownMenuItem(
                    text = { Text(stringResource(if (o.active) R.string.media_offline_cancel else R.string.media_offline_delete)) },
                    onClick = { onMenu(false); onRemove(o) },
                )
            }
        }
        if (o.state == OfflineState.LOADING) MediaProgressBar(o.progress)
    }
}

/** Jellyfin does not let the user download: where an admin switches it on. */
@Composable
private fun NotAllowed() {
    Text(
        stringResource(R.string.media_offline_not_allowed),
        style = MaterialTheme.typography.bodySmall,
        color = GateControlTheme.extraColors.warn,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).testTag(NOT_ALLOWED_TAG),
    )
}

private enum class LoadSheet { TITLE, SEASON }

/**
 * "Laden für offline": the original (its size, and a warning when the phone cannot play its sound) or a step the NAS
 * converts to first, each with about its size; "Nur im WLAN · Belegt 7,4 / 20 GB" below.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoadChooser(which: LoadSheet, ui: MediaDetailUi, chosen: OfflineQuality, used: Long, limit: Long, onPick: (OfflineQuality) -> Unit, onDismiss: () -> Unit) {
    val x = GateControlTheme.extraColors
    val version = if (which == LoadSheet.TITLE) ui.detail?.version else null
    val episodes = if (which == LoadSheet.SEASON) ui.episodes.filter { ui.episodesOffline[it.id] == null } else emptyList()
    val runtime = if (which == LoadSheet.TITLE) ui.title.runtimeMs else episodes.sumOf { it.runtimeMs ?: 0L }.takeIf { it > 0 }
    val season = ui.seasons.firstOrNull { it.id == ui.seasonId }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(bottom = 24.dp).testTag(LOAD_SHEET_TAG)) {
            Text(
                if (which == LoadSheet.SEASON) pluralStringResource(R.plurals.media_offline_load_season_in, episodes.size, season?.season ?: season?.episode ?: 1, episodes.size) else stringResource(R.string.media_offline_load_in),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            OfflineQuality.entries.forEach { q ->
                val size = MediaOfflineLogic.estimate(q, runtime, version)?.let { stringResource(R.string.media_offline_about, MediaOfflineLogic.sizeText(it)) }
                val name = if (q == OfflineQuality.ORIGINAL) listOfNotNull(stringResource(R.string.media_offline_quality_original), MediaQuality.videoLabel(version?.video)).joinToString(" · ") else q.label
                val note = if (q == OfflineQuality.ORIGINAL) {
                    val audio = version?.audio?.firstOrNull { it.index == ui.audio } ?: version?.audio?.firstOrNull()
                    val bad = audio?.takeIf { MediaTracks.fate(it, ui.caps) == com.gatecontrol.android.media.MediaAudioFate.CONVERTED }
                    bad?.let { stringResource(R.string.media_offline_audio_warning, MediaTracks.codecLabel(it.codec) ?: "?") } ?: stringResource(R.string.media_offline_quality_original_note)
                } else {
                    stringResource(R.string.media_offline_quality_converted_note)
                }
                ChoiceRow(listOfNotNull(name, size).joinToString(" · "), note, selected = q == chosen) { onPick(q) }
            }
            Text(
                stringResource(R.string.media_offline_sheet_foot, MediaOfflineLogic.usedText(used, limit)),
                style = MaterialTheme.typography.bodySmall,
                color = x.muted,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            Text(stringResource(R.string.media_offline_sheet_load), style = MaterialTheme.typography.bodySmall, color = x.faint, modifier = Modifier.padding(horizontal = 20.dp))
        }
    }
}

/** Ton and Untertitel as they will play (mockup 3: "Handy kann kein DTS: NAS rechnet in AAC 5.1 um"). */
@Composable
private fun Tracks(ui: MediaDetailUi, onAudio: () -> Unit, onSubtitle: () -> Unit) {
    val v = ui.detail?.version ?: return
    val audio = v.audio.firstOrNull { it.index == ui.audio } ?: v.audio.firstOrNull()
    val subtitle = v.subtitles.firstOrNull { it.index == ui.subtitle }
    val canConvert = ui.source?.kind?.canConvert == true
    GcCard(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.Top) {
        TrackRow(
            label = stringResource(R.string.media_audio),
            value = audio?.let { audioName(it) } ?: stringResource(R.string.media_audio_none),
            note = audio?.let { audioNote(it, ui.caps, canConvert) },
            onClick = if (v.audio.size > 1) onAudio else null,
        )
        GcRowDivider()
        TrackRow(
            label = stringResource(R.string.media_subtitles),
            value = subtitle?.let { subtitleName(it) } ?: stringResource(R.string.media_subtitles_off),
            note = subtitle?.takeIf { it.picture && canConvert }?.let { stringResource(R.string.media_subtitle_burn_in) },
            onClick = if (v.subtitles.isNotEmpty()) onSubtitle else null,
        )
    }
}

@Composable
private fun TrackRow(label: String, value: String, note: String?, onClick: (() -> Unit)?) {
    val x = GateControlTheme.extraColors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = x.muted)
            Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = x.muted) }
        }
        if (onClick != null) Icon(GcIcons.ChevronDown, contentDescription = null, tint = x.faint, modifier = Modifier.size(20.dp))
    }
}

@Composable
internal fun audioName(s: MediaStream): String =
    listOfNotNull(s.language?.let { MediaTracks.languageName(it) } ?: s.title, MediaTracks.audioLabel(s)).joinToString(" · ")

@Composable
internal fun subtitleName(s: MediaStream): String {
    val name = s.language?.let { MediaTracks.languageName(it) } ?: s.title ?: stringResource(R.string.media_subtitle)
    val codec = MediaTracks.codecLabel(s.codec) ?: "?"
    val kind = stringResource(if (s.picture) R.string.media_subtitle_picture else R.string.media_subtitle_text, codec)
    val forced = if (s.forced) " · " + stringResource(R.string.media_forced) else ""
    return "$name · $kind$forced"
}

/** What the phone does with the track: plays it, or (Jellyfin) the NAS converts it; a share's file then has no sound. */
@Composable
private fun audioNote(s: MediaStream, caps: JellyfinDeviceCaps, canConvert: Boolean): String? {
    val n = MediaPhoneLogic.audioNote(s, caps)
    if (!n.converted) return null
    val codec = n.codec ?: "?"
    return if (canConvert) stringResource(R.string.media_audio_converted, codec, n.channels ?: "") else stringResource(R.string.media_audio_unplayable, codec)
}

/** The choice of sound or subtitle in a sheet; the series keeps it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrackChooser(which: TrackSheet, ui: MediaDetailUi, onPick: (Int?) -> Unit, onDismiss: () -> Unit) {
    val v = ui.detail?.version ?: return
    val canConvert = ui.source?.kind?.canConvert == true
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(
                stringResource(if (which == TrackSheet.AUDIO) R.string.media_audio else R.string.media_subtitles),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            if (which == TrackSheet.AUDIO) {
                v.audio.forEach { s -> ChoiceRow(audioName(s), audioNote(s, ui.caps, canConvert), selected = s.index == ui.audio) { onPick(s.index) } }
            } else {
                ChoiceRow(stringResource(R.string.media_subtitles_off), null, selected = ui.subtitle == null || ui.subtitle == MediaPlay.SUBTITLES_OFF) { onPick(MediaPlay.SUBTITLES_OFF) }
                v.subtitles.forEach { s ->
                    ChoiceRow(subtitleName(s), if (s.picture && canConvert) stringResource(R.string.media_subtitle_burn_in) else null, selected = s.index == ui.subtitle) { onPick(s.index) }
                }
            }
        }
    }
}

@Composable
private fun ChoiceRow(title: String, note: String?, selected: Boolean, onClick: () -> Unit) {
    val x = GateControlTheme.extraColors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.RadioButton, onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = x.muted) }
        }
        if (selected) Icon(GcIcons.Check, contentDescription = stringResource(R.string.media_chosen), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
    }
}

/** The seasons as chips. */
@Composable
private fun Seasons(ui: MediaDetailUi, onSeason: (String) -> Unit) {
    LazyRow(Modifier.padding(top = 12.dp, bottom = 4.dp), contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(ui.seasons, key = { it.id }) { s ->
            GcFilterChip(text = s.name, selected = s.id == ui.seasonId, onClick = { onSeason(s.id) })
        }
    }
}

/** An episode: a tap plays on from where it was left, the name opens its page, the mark sets "Gesehen". */
@Composable
private fun EpisodeRow(e: MediaTitle, offline: OfflineItem?, onPlay: () -> Unit, onOpen: () -> Unit, onPlayed: () -> Unit) {
    val x = GateControlTheme.extraColors
    val picture = rememberMediaPicture(e, backdrop = false)
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onOpen).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.width(120.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(x.panel2).clickable(role = Role.Button, onClick = onPlay)) {
            if (picture != null) Image(picture, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Icon(GcIcons.Play, contentDescription = stringResource(R.string.media_play), tint = Color.White, modifier = Modifier.align(Alignment.Center).size(22.dp))
            if (e.resumable) MediaProgressBar(e.progress, Modifier.align(Alignment.BottomCenter).padding(horizontal = 6.dp, vertical = 4.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val number = e.episode?.let { "$it. " }.orEmpty()
            Text(number + e.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val line = listOfNotNull(
                e.runtimeMs?.takeIf { it > 0 }?.let { runtimeText(it) },
                e.leftMs?.takeIf { e.resumable }?.let { leftText(it) },
            ).joinToString(" · ")
            if (line.isNotEmpty()) Text(line, style = MaterialTheme.typography.bodySmall, color = x.muted)
            offline?.let { o ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(if (o.ready) GcIcons.Check else GcIcons.Download, contentDescription = null, tint = if (o.ready) x.accentText else x.muted, modifier = Modifier.size(14.dp))
                    Text(itemLine(o, LocalOfflineWifi.current), style = MaterialTheme.typography.labelSmall, color = if (o.ready) x.accentText else x.muted, maxLines = 1)
                }
            }
        }
        GcIconButton(
            GcIcons.Check,
            contentDescription = stringResource(if (e.played) R.string.media_mark_unplayed else R.string.media_mark_played),
            onClick = onPlayed,
            tint = if (e.played) MaterialTheme.colorScheme.primary else x.faint,
        )
    }
}

internal const val DETAIL_TAG = "media-detail"
internal const val PLAY_TAG = "media-play"
internal const val PLAYED_TAG = "media-played"
internal const val LOAD_TAG = "media-load"
internal const val LOAD_SEASON_TAG = "media-load-season"
internal const val LOAD_SHEET_TAG = "media-load-sheet"
internal const val OFFLINE_STATUS_TAG = "media-offline-status"
internal const val NOT_ALLOWED_TAG = "media-not-allowed"
