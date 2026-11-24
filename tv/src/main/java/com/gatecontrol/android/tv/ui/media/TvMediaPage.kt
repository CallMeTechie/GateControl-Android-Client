package com.gatecontrol.android.tv.ui.media

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.gatecontrol.android.media.MediaLink
import com.gatecontrol.android.media.MediaLogic
import com.gatecontrol.android.media.MediaProblem
import com.gatecontrol.android.media.MediaSource
import com.gatecontrol.android.media.MediaSourceKind
import com.gatecontrol.android.media.MediaTitle
import com.gatecontrol.android.media.jellyfin.JellyfinServerHint
import com.gatecontrol.android.tv.R
import com.gatecontrol.android.tv.media.TvAddUi
import com.gatecontrol.android.tv.media.TvDetailUi
import com.gatecontrol.android.tv.media.TvMediaUi
import com.gatecontrol.android.tv.ui.components.TvButton
import com.gatecontrol.android.tv.ui.components.TvButtonStyle
import com.gatecontrol.android.tv.ui.components.TvPill
import com.gatecontrol.android.tv.ui.components.TvPillTone
import com.gatecontrol.android.tv.ui.drive.requestSoon
import com.gatecontrol.android.tv.ui.home.TvCard
import com.gatecontrol.android.tv.ui.home.TvIcons
import com.gatecontrol.android.tv.ui.theme.GateControlTvTheme

/** Everything the page "Medien" shows. */
data class TvMediaScreen(
    val ui: TvMediaUi,
    val detail: TvDetailUi? = null,
    val add: TvAddUi? = null,
    val link: MediaLink = MediaLink(),
    val pictures: Map<String, Bitmap> = emptyMap(),
)

/** What the page "Medien" does on the keys of the remote. */
class TvMediaActions(
    val onStart: () -> Unit = {},
    val onRetry: () -> Unit = {},
    /** OK (or ☰) on a poster: its details. */
    val onOpen: (MediaTitle) -> Unit = {},
    /** Play [MediaTitle] (true: from the start). */
    val onPlay: (MediaTitle, Boolean) -> Unit = { _, _ -> },
    val onPicture: (MediaTitle, Boolean) -> Unit = { _, _ -> },
    val pictureKey: (MediaTitle, Boolean) -> String = { t, b -> t.id + b },
    /** The next page of "Filme" (true) or "Serien". */
    val onMore: (Boolean) -> Unit = {},
    /** Zurück on a detail page; false: none open. */
    val onBack: () -> Boolean = { false },
    val onSeason: (String, String) -> Unit = { _, _ -> },
    val onAudio: (Int) -> Unit = {},
    val onSubtitle: (Int?) -> Unit = {},
    val onOriginal: (Boolean) -> Unit = {},
    val onPlayed: () -> Unit = {},
    val onSelect: (MediaSource) -> Unit = {},
    val onRemove: (MediaSource) -> Unit = {},
    val onAlwaysOriginal: (MediaSource, Boolean) -> Unit = { _, _ -> },
    val onAdd: () -> Unit = {},
    val onAddCancel: () -> Unit = {},
    val onJellyfin: () -> Unit = {},
    val onSearch: () -> Unit = {},
    val onAddress: (String) -> Unit = {},
    val onPick: (JellyfinServerHint) -> Unit = {},
    val onCheck: () -> Unit = {},
    val onLogin: () -> Unit = {},
    val onQuickConnect: (Boolean) -> Unit = {},
    val onPassword: (String, String) -> Unit = { _, _ -> },
    /** "Quelle hinzufügen": SMB-Freigabe or WebDAV chosen. */
    val onShareKind: (MediaSourceKind) -> Unit = {},
    /** "Verbinden" of a share with name and password. */
    val onShareConnect: (String, String) -> Unit = { _, _ -> },
)

/**
 * The page "Medien" of the rail (mockup 7): Weiterschauen, Zuletzt hinzugefügt, Filme A–Z, Serien of the Jellyfin
 * server shown and of the shares in one ("NAS" on a share's title; one in both shows once, Jellyfin's); OK on a poster
 * opens its details (mockup 8), OK on "Weiterschauen" plays on, ☰ shows the details. "Quellen" (▲ to the top) lists
 * the sources and adds one (mockup 10).
 */
@Composable
fun TvMediaPage(screen: TvMediaScreen, actions: TvMediaActions, modifier: Modifier = Modifier) {
    var sources by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { actions.onStart() }
    val add = screen.add
    val detail = screen.detail
    when {
        add != null -> {
            BackHandler { actions.onAddCancel() }
            TvAddSourcePage(add, actions, modifier)
        }
        sources -> {
            BackHandler { sources = false }
            TvSourcesPage(screen.ui, actions, onClose = { sources = false }, modifier = modifier)
        }
        detail != null -> {
            BackHandler { actions.onBack() }
            TvMediaDetailPage(detail, screen.link, screen.pictures, actions, modifier)
        }
        else -> Rows(screen, actions, onSources = { sources = true }, modifier = modifier)
    }
}

@Composable
private fun Header(screen: TvMediaScreen, onSources: (() -> Unit)?, modifier: Modifier = Modifier) {
    val colors = GateControlTvTheme.colors
    Row(modifier.fillMaxWidth().padding(end = 30.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.tv_nav_media), style = MaterialTheme.typography.titleMedium, color = colors.text)
        screen.ui.source?.let { TvPill(MediaLogic.sourceLabel(it), TvPillTone.Accent) }
        val shareWord = stringResource(R.string.tv_media_share)
        for (share in screen.ui.shares.take(MAX_SHARE_PILLS)) {
            val label = MediaLogic.sourceLabel(share, shareWord)
            if (share.id in screen.ui.shareProblems) {
                TvPill(stringResource(R.string.tv_media_share_unreachable, label), TvPillTone.Warn)
            } else {
                TvPill(label, TvPillTone.Accent)
            }
        }
        LinkPill(screen.link)
        if (screen.ui.loading) TvPill(stringResource(R.string.tv_drive_loading), TvPillTone.Info)
        Spacer(Modifier.weight(1f))
        if (onSources != null) TvButton(stringResource(R.string.tv_media_sources), onClick = onSources)
    }
}

/** "Heimnetz · schnell" or "Unterwegs · 18 Mbit/s". */
@Composable
internal fun LinkPill(link: MediaLink) {
    when (link.atHome) {
        true -> TvPill(stringResource(R.string.tv_media_home_fast), TvPillTone.Accent)
        false -> TvPill(MediaLogic.bandwidth(link)?.let { stringResource(R.string.tv_media_away_bw, it) } ?: stringResource(R.string.tv_media_away), TvPillTone.Info)
        null -> Unit
    }
}

@Composable
private fun Rows(screen: TvMediaScreen, actions: TvMediaActions, onSources: () -> Unit, modifier: Modifier) {
    val colors = GateControlTvTheme.colors
    val ui = screen.ui
    val first = remember { FocusRequester() }
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Header(screen, onSources)
        val problem = ui.problem
        val anyTitles = ui.resume.isNotEmpty() || ui.latest.isNotEmpty() || ui.movies.isNotEmpty() || ui.series.isNotEmpty()
        when {
            !ui.any -> Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 10.dp)) {
                Text(stringResource(R.string.tv_media_none), style = MaterialTheme.typography.bodyLarge, color = colors.muted)
                TvButton(stringResource(R.string.tv_media_add), onClick = actions.onAdd, style = TvButtonStyle.Primary, modifier = Modifier.focusRequester(first))
                LaunchedEffect(Unit) { first.requestSoon() }
            }
            // the Jellyfin server has a problem and no share has titles: only the problem
            problem != null && !anyTitles -> Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 10.dp)) {
                Text(
                    stringResource(
                        when (problem) {
                            MediaProblem.OFFLINE -> R.string.tv_media_offline
                            MediaProblem.SIGNED_OUT -> R.string.tv_media_signed_out
                            MediaProblem.FAILED -> R.string.tv_media_failed
                        },
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.muted,
                )
                if (problem == MediaProblem.SIGNED_OUT) {
                    TvButton(stringResource(R.string.tv_media_sign_in_again), onClick = actions.onAdd, style = TvButtonStyle.Primary, modifier = Modifier.focusRequester(first))
                } else {
                    TvButton(stringResource(R.string.tv_drive_retry), onClick = actions.onRetry, modifier = Modifier.focusRequester(first))
                }
                LaunchedEffect(problem) { first.requestSoon() }
            }
            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                // the Jellyfin server has a problem, the shares' titles are there
                if (problem != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                        Text(
                            stringResource(if (problem == MediaProblem.OFFLINE) R.string.tv_media_offline else if (problem == MediaProblem.SIGNED_OUT) R.string.tv_media_signed_out else R.string.tv_media_failed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.warn,
                        )
                        if (problem == MediaProblem.SIGNED_OUT) TvButton(stringResource(R.string.tv_media_sign_in_again), onClick = actions.onAdd)
                    }
                }
                if (ui.resume.isNotEmpty()) {
                    RowTitle(stringResource(R.string.tv_media_row_resume), pluralStringResource(R.plurals.tv_media_titles, ui.resume.size, ui.resume.size))
                    LazyRow(contentPadding = RowPadding, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                        itemsIndexed(ui.resume, key = { _, t -> t.id }) { i, t ->
                            ResumeCard(
                                t,
                                screen.pictures,
                                actions,
                                modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                            )
                        }
                    }
                }
                if (ui.latest.isNotEmpty()) {
                    RowTitle(stringResource(R.string.tv_media_row_latest), null)
                    PosterRow(ui.latest, screen.pictures, actions, focus = if (ui.resume.isEmpty()) first else null)
                }
                if (ui.movies.isNotEmpty()) {
                    RowTitle(stringResource(R.string.tv_media_row_movies), stringResource(R.string.tv_media_movies_count, ui.moviesTotal))
                    PosterRow(ui.movies, screen.pictures, actions, focus = if (ui.resume.isEmpty() && ui.latest.isEmpty()) first else null, more = ui.moviesNext) { actions.onMore(true) }
                }
                if (ui.series.isNotEmpty()) {
                    RowTitle(stringResource(R.string.tv_media_row_series), ui.seriesTotal.toString())
                    PosterRow(ui.series, screen.pictures, actions, focus = null, more = ui.seriesNext) { actions.onMore(false) }
                }
                if (!ui.loading && ui.resume.isEmpty() && ui.latest.isEmpty() && ui.movies.isEmpty() && ui.series.isEmpty()) {
                    Text(stringResource(R.string.tv_media_empty), style = MaterialTheme.typography.bodyLarge, color = colors.muted)
                }
                Text(stringResource(R.string.tv_media_keys), style = MaterialTheme.typography.labelMedium, color = colors.faint, modifier = Modifier.padding(top = 4.dp, bottom = 20.dp))
                LaunchedEffect(ui.resume.isNotEmpty(), ui.latest.isNotEmpty(), ui.movies.isNotEmpty()) { first.requestSoon() }
            }
        }
    }
}

private val RowPadding = PaddingValues(start = 2.dp, top = 9.dp, end = 30.dp, bottom = 14.dp)

@Composable
private fun RowTitle(title: String, extra: String?) {
    val colors = GateControlTvTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = colors.text)
        if (extra != null) Text(extra, style = MaterialTheme.typography.bodySmall, color = colors.muted, maxLines = 1)
    }
}

@Composable
private fun PosterRow(
    items: List<MediaTitle>,
    pictures: Map<String, Bitmap>,
    actions: TvMediaActions,
    focus: FocusRequester?,
    more: Int? = null,
    onMore: () -> Unit = {},
) {
    LazyRow(contentPadding = RowPadding, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
        itemsIndexed(items, key = { _, t -> t.id }) { i, t ->
            PosterCard(
                t,
                pictures,
                actions,
                modifier = (if (i == 0 && focus != null) Modifier.focusRequester(focus) else Modifier)
                    .onFocusChanged { if (it.isFocused && MediaLogic.wantsMore(i, items.size, more)) onMore() },
            )
        }
    }
}

/** ☰ on a card: the details (OK plays "Weiterschauen"). */
private fun Modifier.menuOpens(onMenu: () -> Unit) = onPreviewKeyEvent { e ->
    if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_MENU) {
        onMenu()
        true
    } else {
        false
    }
}

/** A card of "Weiterschauen": wide picture, the bar of what was watched, "noch 18 Min." / "ab 42:10". */
@Composable
internal fun ResumeCard(t: MediaTitle, pictures: Map<String, Bitmap>, actions: TvMediaActions, modifier: Modifier = Modifier, width: Dp = 190.dp) {
    val colors = GateControlTvTheme.colors
    val wide = t.backdropId != null
    LaunchedEffect(t.id) { actions.onPicture(t, wide) }
    val picture = pictures[actions.pictureKey(t, wide)]
    TvCard(width, width * 9 / 16, onClick = { actions.onPlay(t, false) }, description = t.seriesName ?: t.name, modifier = modifier.menuOpens { actions.onOpen(t) }) {
        Box(Modifier.fillMaxSize().background(PictureBg)) {
            if (picture != null) Image(picture.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (t.fromShare) Box(Modifier.align(Alignment.TopStart).padding(6.dp)) { NasBadge() }
            left(t)?.let { l ->
                Box(Modifier.align(Alignment.TopEnd).padding(6.dp).background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(5.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                    Text(l, style = MaterialTheme.typography.labelMedium, color = Color.White)
                }
            }
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color.Black.copy(alpha = 0.55f))) {
                Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Text(t.seriesName ?: t.name, style = MaterialTheme.typography.labelMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(resumeLine(t), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box(Modifier.fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.25f))) {
                    Box(Modifier.fillMaxWidth(t.progress).fillMaxHeight().background(colors.accent))
                }
            }
        }
    }
}

/** "noch 1:06" / "noch 18 Min."; null without a length. */
@Composable
internal fun left(t: MediaTitle): String? {
    val ms = t.leftMs ?: return null
    return MediaLogic.hoursMinutes(ms)?.let { stringResource(R.string.tv_media_left_hours, it) }
        ?: stringResource(R.string.tv_media_left_minutes, MediaLogic.minutes(ms))
}

/** "Staffel 2 · Folge 4" for an episode, "ab 42:10" for a film. */
@Composable
internal fun resumeLine(t: MediaTitle): String =
    if (t.kind == MediaTitle.Kind.EPISODE && t.season != null && t.episode != null) {
        stringResource(R.string.tv_media_season_episode, t.season!!, t.episode!!)
    } else {
        stringResource(R.string.tv_media_from, com.gatecontrol.android.tv.player.TvPlayerKeys.time(t.positionMs))
    }

/** "2025 · 1:48" for a film, "Serie · 3 Staffeln" for a series. */
@Composable
internal fun posterLine(t: MediaTitle): String? = when (t.kind) {
    MediaTitle.Kind.SERIES -> t.childCount?.let { pluralStringResource(R.plurals.tv_media_series_seasons, it, it) } ?: stringResource(R.string.tv_media_series)
    else -> listOfNotNull(t.year?.toString(), t.runtimeMs?.let { MediaLogic.hoursMinutes(it) ?: stringResource(R.string.tv_media_minutes, MediaLogic.minutes(it)) })
        .joinToString(" · ").ifEmpty { null }
}

/** A poster (2:3) with its name and year; "NEU" when it came this week. */
@Composable
private fun PosterCard(t: MediaTitle, pictures: Map<String, Bitmap>, actions: TvMediaActions, modifier: Modifier = Modifier) {
    val colors = GateControlTvTheme.colors
    LaunchedEffect(t.id) { actions.onPicture(t, false) }
    val picture = pictures[actions.pictureKey(t, false)]
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        TvCard(92.dp, 138.dp, onClick = { actions.onOpen(t) }, description = t.name, modifier = modifier.menuOpens { actions.onOpen(t) }) {
            Box(Modifier.fillMaxSize().background(PictureBg)) {
                if (picture != null) {
                    Image(picture.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Icon(TvIcons.Media, contentDescription = null, tint = colors.faint, modifier = Modifier.align(Alignment.Center).size(26.dp))
                }
                if (t.fromShare || TvMediaUiLogic.isNew(t)) {
                    Row(Modifier.align(Alignment.TopStart).padding(5.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (t.fromShare) NasBadge()
                        if (TvMediaUiLogic.isNew(t)) TvPill(stringResource(R.string.tv_drop_badge_new), TvPillTone.Accent, dot = false)
                    }
                }
                if (t.played) {
                    Box(Modifier.align(Alignment.TopEnd).padding(5.dp).background(colors.accent, RoundedCornerShape(50)).padding(horizontal = 5.dp)) {
                        Text("✓", style = MaterialTheme.typography.labelMedium, color = colors.onAccent)
                    }
                }
            }
        }
        Column(Modifier.size(width = 92.dp, height = 34.dp)) {
            Text(t.name, style = MaterialTheme.typography.labelMedium, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            posterLine(t)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.muted, maxLines = 1) }
        }
    }
}

internal val PictureBg = Color(0xFF0B0F14)

/** At most this many shares in the header (more are in "Quellen"). */
private const val MAX_SHARE_PILLS = 2

/** "NAS" top left on a picture: the title comes from an SMB or WebDAV share (mockup 7). */
@Composable
internal fun NasBadge() {
    TvPill(stringResource(R.string.tv_media_badge_nas), TvPillTone.Info, dot = false)
}

/** Rules of the cards, kept apart from the composables. */
internal object TvMediaUiLogic {
    /** Came within the last week: "NEU". */
    fun isNew(t: MediaTitle, now: Long = System.currentTimeMillis()): Boolean =
        !t.played && t.positionMs == 0L && t.addedAt?.let { now - it in 0..WEEK_MS } == true

    private const val WEEK_MS = 7L * 24 * 3_600_000
}
