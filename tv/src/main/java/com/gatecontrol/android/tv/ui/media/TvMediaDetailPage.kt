package com.gatecontrol.android.tv.ui.media

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.gatecontrol.android.media.MediaLink
import com.gatecontrol.android.media.MediaLogic
import com.gatecontrol.android.media.MediaPlay
import com.gatecontrol.android.media.MediaProblem
import com.gatecontrol.android.media.MediaQuality
import com.gatecontrol.android.media.MediaStream
import com.gatecontrol.android.media.MediaTitle
import com.gatecontrol.android.media.MediaTracks
import com.gatecontrol.android.media.server.MediaConvertHint
import com.gatecontrol.android.media.server.MediaConvertLogic
import com.gatecontrol.android.tv.R
import com.gatecontrol.android.tv.media.TvDetailUi
import com.gatecontrol.android.tv.player.TvPlayerKeys
import com.gatecontrol.android.tv.ui.components.TvButton
import com.gatecontrol.android.tv.ui.components.TvButtonStyle
import com.gatecontrol.android.tv.ui.components.TvDialog
import com.gatecontrol.android.tv.ui.components.TvPill
import com.gatecontrol.android.tv.ui.components.TvPillTone
import com.gatecontrol.android.tv.ui.drive.requestSoon
import com.gatecontrol.android.tv.ui.home.TvIcons
import com.gatecontrol.android.tv.ui.theme.GateControlTvTheme
import java.time.ZoneId

/**
 * A title's details (mockup 8): the backdrop, year, length, genre, the file's picture and sound, the text, where it
 * was left; "Weiter ab …", "Von vorn", "Als gesehen markieren"; Ton and Untertitel; away "Original (direkt)" or
 * "Angepasst (NAS rechnet um)" by the measured bandwidth. A series: its seasons and episodes.
 */
@Composable
fun TvMediaDetailPage(
    detail: TvDetailUi,
    link: MediaLink,
    pictures: Map<String, Bitmap>,
    actions: TvMediaActions,
    modifier: Modifier = Modifier,
) {
    val colors = GateControlTvTheme.colors
    val t = detail.title
    val hasBackdrop = t.backdropId != null
    LaunchedEffect(t.id, hasBackdrop) { if (hasBackdrop) actions.onPicture(t, true) }
    val backdrop = pictures[actions.pictureKey(t, true)]
    val first = remember { FocusRequester() }
    var picking by remember(t.id) { mutableStateOf<MediaStream.Type?>(null) }

    Box(modifier.fillMaxSize()) {
        if (backdrop != null) {
            Image(backdrop.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(colors.bg, colors.bg.copy(alpha = 0.85f), colors.bg.copy(alpha = 0.35f)))))
        }
        Column(
            Modifier.fillMaxSize().padding(end = 30.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.tv_nav_media), style = MaterialTheme.typography.titleMedium, color = colors.muted)
                if (t.fromShare) NasBadge()
                LinkPill(link)
                if (detail.loading) TvPill(stringResource(R.string.tv_drive_loading), TvPillTone.Info)
            }
            Text(t.name, style = MaterialTheme.typography.displaySmall, color = colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (t.kind == MediaTitle.Kind.EPISODE && t.seriesName != null) {
                Text(
                    listOfNotNull(t.seriesName, t.season?.let { s -> t.episode?.let { e -> stringResource(R.string.tv_media_season_episode, s, e) } }).joinToString(" · "),
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.muted,
                )
            }
            MetaLine(detail)
            t.overview?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.text, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(560.dp))
            }
            detail.problem?.let {
                Text(stringResource(
                        when (it) {
                            MediaProblem.OFFLINE -> R.string.tv_media_offline
                            MediaProblem.NO_ROUTE -> R.string.tv_media_no_route
                            else -> R.string.tv_media_failed
                        },
                    ), style = MaterialTheme.typography.bodyMedium, color = colors.warn)
            }
            if (t.playable) {
                detail.convert?.let { ConvertHint(detail, it, actions) }
                if (t.resumable) Progress(t)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (t.resumable) {
                        TvButton(
                            stringResource(R.string.tv_media_resume_from, TvPlayerKeys.time(t.positionMs)),
                            onClick = { actions.onPlay(t, false) },
                            style = TvButtonStyle.Primary,
                            modifier = Modifier.focusRequester(first),
                        )
                        TvButton(stringResource(R.string.tv_media_from_start), onClick = { actions.onPlay(t, true) })
                    } else {
                        TvButton(stringResource(R.string.tv_media_play), onClick = { actions.onPlay(t, true) }, style = TvButtonStyle.Primary, modifier = Modifier.focusRequester(first))
                    }
                    TvButton(stringResource(if (t.played) R.string.tv_media_mark_unplayed else R.string.tv_media_mark_played), onClick = actions.onPlayed)
                    FavoriteButton(detail, actions)
                }
                val version = detail.detail?.version
                if (version != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (version.audio.isNotEmpty()) {
                            val a = version.audio.firstOrNull { it.index == detail.audio } ?: version.audio.first()
                            TvButton(stringResource(R.string.tv_media_audio_choice, audioName(a)), onClick = { picking = MediaStream.Type.AUDIO })
                        }
                        if (version.subtitles.isNotEmpty()) {
                            val s = version.subtitles.firstOrNull { it.index == detail.subtitle }
                            TvButton(
                                stringResource(R.string.tv_media_subtitle_choice, s?.let { subtitleName(it) } ?: stringResource(R.string.tv_media_subtitles_off)),
                                onClick = { picking = MediaStream.Type.SUBTITLE },
                            )
                        }
                    }
                }
                QualityChoice(detail, link, actions)
                Text(stringResource(R.string.tv_media_detail_keys), style = MaterialTheme.typography.labelMedium, color = colors.faint)
            } else if (t.kind == MediaTitle.Kind.SERIES) {
                FavoriteButton(detail, actions)
                Seasons(detail, actions, first)
                Episodes(detail, pictures, actions)
            } else if (t.kind == MediaTitle.Kind.COLLECTION) {
                FavoriteButton(detail, actions)
                // a collection's films: OK opens one
                if (detail.items.isNotEmpty()) PosterRow(detail.items, pictures, actions, focus = first)
            }
            Spacer(Modifier.height(20.dp))
        }
    }
    LaunchedEffect(t.id, detail.loading, detail.seasons.isNotEmpty()) { first.requestSoon() }

    val version = detail.detail?.version
    when (picking) {
        MediaStream.Type.AUDIO -> if (version != null) {
            PickDialog(
                title = stringResource(R.string.tv_player_audio),
                options = version.audio.map { it.index to audioName(it) },
                selected = detail.audio,
                onPick = {
                    picking = null
                    if (it != null) actions.onAudio(it)
                },
                onDismiss = { picking = null },
            )
        }
        MediaStream.Type.SUBTITLE -> if (version != null) {
            PickDialog(
                title = stringResource(R.string.tv_player_subtitles),
                options = listOf(MediaPlay.SUBTITLES_OFF to stringResource(R.string.tv_media_subtitles_off)) + version.subtitles.map { it.index to subtitleName(it) },
                selected = detail.subtitle ?: MediaPlay.SUBTITLES_OFF,
                onPick = {
                    picking = null
                    actions.onSubtitle(it)
                },
                onDismiss = { picking = null },
            )
        }
        else -> Unit
    }
}

/** The heart (decision 74): only a Jellyfin title (a share has no favourites), once the details are there. */
@Composable
private fun FavoriteButton(detail: TvDetailUi, actions: TvMediaActions) {
    val t = detail.title
    if (t.fromShare || detail.detail == null) return
    TvButton(stringResource(if (t.favorite) R.string.tv_media_favorite_remove else R.string.tv_media_favorite_add), onClick = actions.onFavorite)
}

/** "Deutsch · DTS 5.1". */
@Composable
internal fun audioName(s: MediaStream): String =
    listOfNotNull(s.language?.let { MediaTracks.languageName(it) } ?: s.title, MediaTracks.audioLabel(s)).joinToString(" · ")

/** "Deutsch · Text (SRT)" / "Englisch · Bild (PGS)". */
@Composable
internal fun subtitleName(s: MediaStream): String {
    val name = s.language?.let { MediaTracks.languageName(it) } ?: s.title ?: stringResource(R.string.tv_player_subtitle)
    val codec = MediaTracks.codecLabel(s.codec) ?: "?"
    val kind = stringResource(if (s.picture) R.string.tv_media_subtitle_kind_picture else R.string.tv_media_subtitle_kind_text, codec)
    val forced = if (s.forced) " · " + stringResource(R.string.tv_media_forced) else ""
    return "$name · $kind$forced"
}

/** "2025 · 1 Std. 48 Min. · Drama" and the chips "4K · HDR10", "HEVC · mkv", "FSK 12". */
@Composable
private fun MetaLine(detail: TvDetailUi) {
    val colors = GateControlTvTheme.colors
    val t = detail.title
    val runtime = t.runtimeMs?.let { ms ->
        val m = ms / 60_000
        if (m >= 60) stringResource(R.string.tv_media_hours_minutes, m / 60, m % 60) else stringResource(R.string.tv_media_minutes, MediaLogic.minutes(ms))
    }
    val series = if (t.kind == MediaTitle.Kind.SERIES) (t.childCount ?: detail.seasons.size.takeIf { it > 0 })?.let { pluralStringResource(R.plurals.tv_media_series_seasons, it, it) } else null
    val line = listOfNotNull(t.year?.toString(), runtime, series, t.genres.firstOrNull()).joinToString(" · ")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (line.isNotEmpty()) Text(line, style = MaterialTheme.typography.bodyMedium, color = colors.muted)
        val v = detail.detail?.version
        MediaQuality.videoLabel(v?.video)?.let { TvPill(it.replace(" ", " · "), TvPillTone.Neutral, dot = false) }
        val codec = v?.video?.codec?.let { MediaTracks.codecLabel(it) }
        listOfNotNull(codec, v?.container).joinToString(" · ").takeIf { it.isNotEmpty() }?.let { TvPill(it, TvPillTone.Neutral, dot = false) }
        t.rating?.let { TvPill(it.replace("-", " "), TvPillTone.Neutral, dot = false) }
    }
}

/**
 * „Videos umwandeln“ before playing (mockup picture 7): the TV does not play the file's picture itself. Tonight (place
 * and start), being converted (with the bar), or „Für heute Nacht vormerken“ (a button the remote reaches). Playing
 * stays possible with the buttons below.
 */
@Composable
private fun ConvertHint(detail: TvDetailUi, hint: MediaConvertHint, actions: TvMediaActions) {
    val colors = GateControlTvTheme.colors
    @Suppress("DEPRECATION")
    val locale = LocalConfiguration.current.locale
    val (title, text) = when (hint) {
        MediaConvertHint.Offer -> stringResource(R.string.tv_media_convert_offer_title) to stringResource(R.string.tv_media_convert_offer_text, detail.convertProblem ?: "")
        MediaConvertHint.Requested -> stringResource(R.string.tv_media_convert_requested_title) to stringResource(R.string.tv_media_convert_requested_text)
        is MediaConvertHint.Queued -> {
            val clock = MediaConvertLogic.startClock(hint.startsAt, System.currentTimeMillis(), ZoneId.systemDefault(), locale)
            val parts = listOfNotNull(
                hint.position?.let { stringResource(R.string.tv_media_convert_queued_place, it) },
                clock?.let { stringResource(R.string.tv_media_convert_queued_from, it) },
            )
            val after = stringResource(R.string.tv_media_convert_queued_after)
            stringResource(R.string.tv_media_convert_queued_title) to (if (parts.isEmpty()) after else parts.joinToString(", ") + ". " + after)
        }
        is MediaConvertHint.Running ->
            (hint.percent?.let { stringResource(R.string.tv_media_convert_running_title_percent, it) } ?: stringResource(R.string.tv_media_convert_running_title)) to
                stringResource(R.string.tv_media_convert_running_text)
    }
    Row(
        Modifier.width(560.dp).background(colors.warnBg, RoundedCornerShape(12.dp)).padding(horizontal = 18.dp, vertical = 14.dp).testTag(TV_CONVERT_TAG),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(if (hint is MediaConvertHint.Running) TvIcons.Clock else TvIcons.Moon, contentDescription = null, tint = colors.warn, modifier = Modifier.padding(top = 2.dp).size(18.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = colors.text)
            Text(text, style = MaterialTheme.typography.bodySmall, color = colors.muted)
            val percent = (hint as? MediaConvertHint.Running)?.percent
            if (percent != null) {
                BoxWithConstraints(Modifier.fillMaxWidth().height(4.dp).background(colors.line2, RoundedCornerShape(50))) {
                    Box(Modifier.width(maxWidth * (percent / 100f)).height(4.dp).background(colors.accent, RoundedCornerShape(50)))
                }
            }
            if (hint == MediaConvertHint.Offer) {
                TvButton(
                    stringResource(R.string.tv_media_convert_request),
                    onClick = actions.onConvertRequest,
                    enabled = !detail.convertBusy,
                    icon = TvIcons.Moon,
                    modifier = Modifier.testTag(TV_CONVERT_REQUEST_TAG),
                )
            }
            if (detail.convertFailed) Text(stringResource(R.string.tv_media_convert_failed), style = MaterialTheme.typography.bodySmall, color = colors.warn)
        }
    }
}

internal const val TV_CONVERT_TAG = "tv-media-convert"
internal const val TV_CONVERT_REQUEST_TAG = "tv-media-convert-request"

@Composable
private fun Progress(t: MediaTitle) {
    val colors = GateControlTvTheme.colors
    Column(Modifier.width(420.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(4.dp).background(colors.line2, RoundedCornerShape(50))) {
            Box(Modifier.width(maxWidth * t.progress).height(4.dp).background(colors.accent, RoundedCornerShape(50)))
        }
        Row(Modifier.fillMaxWidth()) {
            Text(TvPlayerKeys.time(t.positionMs), style = MaterialTheme.typography.labelMedium, color = colors.muted)
            Spacer(Modifier.weight(1f))
            t.runtimeMs?.let { Text(TvPlayerKeys.time(it), style = MaterialTheme.typography.labelMedium, color = colors.muted) }
        }
    }
}

/**
 * At home: "Original (direkt)" and "Heimnetz · schnell". Away: "Original (direkt)" with its bit rate and whether it
 * fits, and "Angepasst (NAS rechnet um)" with the step for the measured line; the suggestion ticked.
 */
@Composable
private fun QualityChoice(detail: TvDetailUi, link: MediaLink, actions: TvMediaActions) {
    val offer = detail.offer ?: return
    val video = MediaQuality.videoLabel(detail.detail?.version?.video)
    val original = offer.originalBps?.let { MediaQuality.label(it) }
    val bps = link.bps
    val originalText = when {
        link.atHome == true -> stringResource(R.string.tv_media_home_fast)
        offer.adaptedBps == null -> listOfNotNull(video, original?.let { stringResource(R.string.tv_media_about, it) }).joinToString(" · ")
        !offer.originalFits && bps != null && original != null ->
            stringResource(R.string.tv_media_original_too_much, listOfNotNull(video, stringResource(R.string.tv_media_about, original)).joinToString(" · "), MediaQuality.label(bps))
        else -> listOfNotNull(video, original?.let { stringResource(R.string.tv_media_about, it) }).joinToString(" · ")
    }
    Column(Modifier.width(560.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        QualityRow(stringResource(R.string.tv_media_original_direct), originalText.ifEmpty { null }, selected = detail.original, onClick = { actions.onOriginal(true) })
        val adapted = offer.adaptedBps
        if (adapted != null) {
            val audio = detail.detail?.version?.audio?.firstOrNull { it.index == detail.audio }
            val keep = audio?.let { stringResource(R.string.tv_media_audio_stays, MediaTracks.codecLabel(it.codec) ?: "") }
            val text = listOfNotNull(MediaQuality.resolution(adapted), MediaQuality.label(adapted), keep).joinToString(" · ") +
                if (offer.suggestAdapted) ". " + stringResource(R.string.tv_media_suggested) else ""
            QualityRow(stringResource(R.string.tv_media_adapted), text, selected = !detail.original, onClick = { actions.onOriginal(false) })
        }
    }
}

@Composable
private fun QualityRow(title: String, text: String?, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = GateControlTvTheme.colors
    val shape = RoundedCornerShape(9.dp)
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) colors.accentBg else colors.panel,
            contentColor = colors.text,
            focusedContainerColor = if (selected) colors.accentBg else colors.panel2,
            focusedContentColor = colors.text,
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, if (selected) colors.accent.copy(alpha = 0.5f) else colors.line), shape = shape),
            focusedBorder = Border(BorderStroke(2.dp, colors.focus), shape = shape),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = colors.text)
                if (text != null) Text(text, style = MaterialTheme.typography.bodySmall, color = colors.muted)
            }
            if (selected) Text("✓", style = MaterialTheme.typography.titleSmall, color = colors.accentText)
        }
    }
}

@Composable
private fun Seasons(detail: TvDetailUi, actions: TvMediaActions, first: FocusRequester) {
    if (detail.seasons.isEmpty()) return
    LazyRow(contentPadding = PaddingValues(start = 2.dp, top = 4.dp, end = 30.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(detail.seasons, key = { _, s -> s.id }) { i, s ->
            TvButton(
                s.name,
                onClick = { actions.onSeason(detail.title.id, s.id) },
                style = if (s.id == detail.seasonId) TvButtonStyle.Primary else TvButtonStyle.Secondary,
                modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
            )
        }
    }
}

@Composable
private fun Episodes(detail: TvDetailUi, pictures: Map<String, Bitmap>, actions: TvMediaActions) {
    val colors = GateControlTvTheme.colors
    if (detail.episodes.isEmpty()) return
    LazyRow(contentPadding = PaddingValues(start = 2.dp, top = 9.dp, end = 30.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
        itemsIndexed(detail.episodes, key = { _, e -> e.id }) { _, e ->
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                EpisodeCard(e, pictures, actions)
                Text(
                    listOfNotNull(e.episode?.let { "$it." }, e.name).joinToString(" "),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(176.dp),
                )
            }
        }
    }
}

/** An episode: its picture, the bar of what was watched; OK opens its details. */
@Composable
private fun EpisodeCard(e: MediaTitle, pictures: Map<String, Bitmap>, actions: TvMediaActions) {
    val colors = GateControlTvTheme.colors
    LaunchedEffect(e.id) { actions.onPicture(e, false) }
    val picture = pictures[actions.pictureKey(e, false)]
    com.gatecontrol.android.tv.ui.home.TvCard(176.dp, 99.dp, onClick = { actions.onOpen(e) }, description = e.name) {
        Box(Modifier.fillMaxSize().background(PictureBg)) {
            if (picture != null) Image(picture.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (e.played) {
                Box(Modifier.align(Alignment.TopEnd).padding(5.dp).background(colors.accent, RoundedCornerShape(50)).padding(horizontal = 5.dp)) {
                    Text("✓", style = MaterialTheme.typography.labelMedium, color = colors.onAccent)
                }
            }
            if (e.resumable) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.25f))) {
                    Box(Modifier.fillMaxWidth(e.progress).height(3.dp).background(colors.accent))
                }
            }
        }
    }
}

/** A list to choose from (Ton, Untertitel): ▲ ▼, OK takes it, Zurück closes. */
@Composable
private fun PickDialog(title: String, options: List<Pair<Int, String>>, selected: Int?, onPick: (Int?) -> Unit, onDismiss: () -> Unit) {
    val colors = GateControlTvTheme.colors
    val first = remember { FocusRequester() }
    TvDialog(onDismiss) {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = colors.text)
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // the remote starts on the one chosen now
            val at = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)
            options.forEachIndexed { i, (index, label) ->
                QualityRow(label, null, selected = index == selected, onClick = { onPick(index) }, modifier = if (i == at) Modifier.focusRequester(first) else Modifier)
            }
        }
        TvButton(stringResource(R.string.tv_cancel), onClick = onDismiss)
    }
    LaunchedEffect(Unit) { first.requestSoon() }
}
