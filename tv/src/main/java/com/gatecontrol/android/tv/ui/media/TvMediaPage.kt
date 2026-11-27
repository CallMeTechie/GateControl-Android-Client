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
import com.gatecontrol.android.media.MediaTitle
import com.gatecontrol.android.media.MediaGate
import com.gatecontrol.android.media.server.MediaServerState
import com.gatecontrol.android.media.server.MediaSourceStatus
import com.gatecontrol.android.tv.R
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
    val link: MediaLink = MediaLink(),
    val pictures: Map<String, Bitmap> = emptyMap(),
    /** Medien › Quellen: the server's sources with their status. */
    val rows: List<MediaSourceStatus> = emptyList(),
    val server: MediaServerState = MediaServerState(),
    /** Away the VPN for media („VPN wird verbunden …“, „Unterwegs nur über VPN“). */
    val gate: MediaGate = MediaGate.OPEN,
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
    /** The heart of the detail page (Jellyfin). */
    val onFavorite: () -> Unit = {},
    /** „Für heute Nacht vormerken“ of the detail page (plugin Medien 1.6.0). */
    val onConvertRequest: () -> Unit = {},
    val onSelect: (MediaSource) -> Unit = {},
    val onAlwaysOriginal: (MediaSource, Boolean) -> Unit = { _, _ -> },
    /** „Neu laden“ of Medien › Quellen. */
    val onReload: () -> Unit = {},
    /** Jellyfin signed the TV out: signed in again by itself. */
    val onRepair: () -> Unit = {},
    /** Medien › Quellen was opened for a source set up by hand ([TvMediaUi.openSources]). */
    val onSourcesOpened: () -> Unit = {},
    /** „VPN einschalten“ (away, media only through the VPN). */
    val onVpn: () -> Unit = {},
    /** „Verstanden“ of „Medien kommen jetzt vom Server“. */
    val onMigrationDone: () -> Unit = {},
)

/**
 * The page "Medien" of the rail (mockup 7): Weiterschauen, Zuletzt hinzugefügt, Filme A–Z, Serien of the Jellyfin
 * server shown and of the shares in one ("NAS" on a share's title; one in both shows once, Jellyfin's); OK on a poster
 * opens its details (mockup 8), OK on "Weiterschauen" plays on, ☰ shows the details. "Quellen" (▲ to the top) shows
 * the sources of the server with their status; once after the update „Medien kommen jetzt vom Server“.
 */
@Composable
fun TvMediaPage(screen: TvMediaScreen, actions: TvMediaActions, modifier: Modifier = Modifier) {
    var sources by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { actions.onStart() }
    // „Neu anmelden“ of a source set up by hand: its status is on Medien › Quellen
    LaunchedEffect(screen.ui.openSources) {
        if (screen.ui.openSources) {
            sources = true
            actions.onSourcesOpened()
        }
    }
    val detail = screen.detail
    when {
        screen.server.migration.isNotEmpty() -> TvMigrationPage(screen.server.migration, onDone = actions.onMigrationDone, modifier = modifier)
        sources -> {
            BackHandler { sources = false }
            TvSourcesPage(screen.rows, screen.server, screen.ui.source, actions, onClose = { sources = false }, modifier = modifier)
        }
        detail != null -> {
            BackHandler { actions.onBack() }
            TvMediaDetailPage(detail, screen.link, screen.pictures, actions, modifier)
        }
        else -> Rows(screen, actions, onSources = { sources = true }, modifier = modifier)
    }
}

/** „Neu anmelden“, „Anmelden …“ while it runs, „Erneut versuchen“ after it failed (kept focusable for the remote). */
@Composable
private fun signInLabel(ui: TvMediaUi): String = stringResource(
    when {
        ui.signingIn -> R.string.tv_media_signing_in
        ui.signInProblem != null -> R.string.tv_media_sign_in_retry
        else -> R.string.tv_media_sign_in_again
    },
)

@Composable
private fun Header(screen: TvMediaScreen, onSources: (() -> Unit)?, modifier: Modifier = Modifier) {
    val colors = GateControlTvTheme.colors
    Row(modifier.fillMaxWidth().padding(end = 30.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.tv_nav_media), style = MaterialTheme.typography.titleMedium, color = colors.text)
        // only the kind of the sources, never a host or server name (those are on the page of the sources)
        screen.ui.source?.let { TvPill(MediaLogic.kindLabel(it), TvPillTone.Accent) }
        val shareWord = stringResource(R.string.tv_media_share)
        if (screen.ui.shares.isNotEmpty()) {
            if (screen.ui.shares.any { it.id in screen.ui.shareProblems }) {
                TvPill(stringResource(R.string.tv_media_share_unreachable, shareWord), TvPillTone.Warn)
            } else {
                TvPill(shareWord, TvPillTone.Accent)
            }
        }
        LinkPill(screen.link)
        if (screen.gate == MediaGate.CONNECTING || screen.gate == MediaGate.CONSENT) TvPill(stringResource(R.string.tv_media_vpn_connecting), TvPillTone.Info)
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
        val anyTitles = ui.anyTitles
        when {
            !ui.any -> Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 10.dp)) {
                Text(stringResource(R.string.tv_media_none_server), style = MaterialTheme.typography.bodyLarge, color = colors.muted)
                TvButton(stringResource(R.string.tv_media_sources), onClick = onSources, style = TvButtonStyle.Primary, modifier = Modifier.focusRequester(first))
                LaunchedEffect(Unit) { first.requestSoon() }
            }
            // away, the VPN is off and could not be started: nothing goes out
            screen.gate == MediaGate.BLOCKED && !anyTitles -> Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 10.dp)) {
                Text(stringResource(R.string.tv_media_vpn_blocked_text), style = MaterialTheme.typography.bodyLarge, color = colors.muted)
                TvVpnGate(screen.gate, onVpn = actions.onVpn, focus = first)
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
                            MediaProblem.VPN -> R.string.tv_media_vpn_blocked
                            MediaProblem.NO_ROUTE -> R.string.tv_media_no_route
                        },
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.muted,
                )
                if (problem == MediaProblem.SIGNED_OUT) {
                    ui.signInProblem?.takeIf { !ui.signingIn }?.let { Text(tvProblemText(it), style = MaterialTheme.typography.bodyMedium, color = colors.warn) }
                    TvButton(signInLabel(ui), onClick = actions.onRepair, style = TvButtonStyle.Primary, modifier = Modifier.focusRequester(first))
                } else if (problem == MediaProblem.VPN) {
                    TvButton(stringResource(R.string.tv_media_vpn_on), onClick = actions.onVpn, style = TvButtonStyle.Primary, modifier = Modifier.focusRequester(first))
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
                            stringResource(
                                when (problem) {
                                    MediaProblem.OFFLINE -> R.string.tv_media_offline
                                    MediaProblem.SIGNED_OUT -> R.string.tv_media_signed_out
                                    MediaProblem.VPN -> R.string.tv_media_vpn_blocked
                                    MediaProblem.NO_ROUTE -> R.string.tv_media_no_route
                                    MediaProblem.FAILED -> R.string.tv_media_failed
                                },
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.warn,
                        )
                        if (problem == MediaProblem.SIGNED_OUT) {
                            ui.signInProblem?.takeIf { !ui.signingIn }?.let { Text(tvProblemText(it), style = MaterialTheme.typography.bodyMedium, color = colors.warn) }
                            TvButton(signInLabel(ui), onClick = actions.onRepair)
                        }
                        if (problem == MediaProblem.VPN) TvButton(stringResource(R.string.tv_media_vpn_on), onClick = actions.onVpn)
                    }
                }
                // decision 70 A „Ergänzt“: the rows of the phone; the first one shown takes the focus
                val firstRow = listOf(ui.resume, ui.nextUp, ui.latestMovies, ui.latestEpisodes, ui.favorites, ui.movies, ui.series, ui.collections)
                    .indexOfFirst { it.isNotEmpty() }
                fun focusOf(row: Int) = if (row == firstRow) first else null
                if (ui.resume.isNotEmpty()) {
                    RowTitle(stringResource(R.string.tv_media_row_resume), pluralStringResource(R.plurals.tv_media_titles, ui.resume.size, ui.resume.size))
                    WideRow(ui.resume, screen.pictures, actions, focusOf(0))
                }
                if (ui.nextUp.isNotEmpty()) {
                    RowTitle(stringResource(R.string.tv_media_row_next_up), ui.nextUpTotal.coerceAtLeast(ui.nextUp.size).toString())
                    WideRow(ui.nextUp, screen.pictures, actions, focusOf(1))
                }
                if (ui.latestMovies.isNotEmpty()) {
                    RowTitle(stringResource(R.string.tv_media_row_latest_movies), ui.latestMovies.size.toString())
                    PosterRow(ui.latestMovies, screen.pictures, actions, focus = focusOf(2))
                }
                if (ui.latestEpisodes.isNotEmpty()) {
                    RowTitle(stringResource(R.string.tv_media_row_latest_episodes), MediaLogic.newEpisodes(ui.latestEpisodes).toString())
                    PosterRow(ui.latestEpisodes, screen.pictures, actions, focus = focusOf(3), episodes = true)
                }
                if (ui.favorites.isNotEmpty()) {
                    RowTitle(stringResource(R.string.tv_media_row_favorites), ui.favoritesTotal.coerceAtLeast(ui.favorites.size).toString())
                    PosterRow(ui.favorites, screen.pictures, actions, focus = focusOf(4), episodes = true)
                }
                if (ui.movies.isNotEmpty()) {
                    RowTitle(stringResource(R.string.tv_media_row_movies), stringResource(R.string.tv_media_movies_count, ui.moviesTotal))
                    PosterRow(ui.movies, screen.pictures, actions, focus = focusOf(5), more = ui.moviesNext) { actions.onMore(true) }
                }
                if (ui.series.isNotEmpty()) {
                    RowTitle(stringResource(R.string.tv_media_row_series), ui.seriesTotal.toString())
                    PosterRow(ui.series, screen.pictures, actions, focus = focusOf(6), more = ui.seriesNext) { actions.onMore(false) }
                }
                if (ui.collections.isNotEmpty()) {
                    RowTitle(stringResource(R.string.tv_media_row_collections), ui.collectionsTotal.coerceAtLeast(ui.collections.size).toString())
                    PosterRow(ui.collections, screen.pictures, actions, focus = focusOf(7))
                }
                if (!ui.loading && !ui.anyTitles) {
                    Text(stringResource(R.string.tv_media_empty), style = MaterialTheme.typography.bodyLarge, color = colors.muted)
                }
                Text(stringResource(R.string.tv_media_keys), style = MaterialTheme.typography.labelMedium, color = colors.faint, modifier = Modifier.padding(top = 4.dp, bottom = 20.dp))
                LaunchedEffect(firstRow) { if (firstRow >= 0) first.requestSoon() }
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

/** A row of wide cards ("Weiterschauen", "Als Nächstes"): OK plays, ☰ opens the details. */
@Composable
private fun WideRow(items: List<MediaTitle>, pictures: Map<String, Bitmap>, actions: TvMediaActions, focus: FocusRequester?) {
    LazyRow(contentPadding = RowPadding, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
        itemsIndexed(items, key = { _, t -> t.id }) { i, t ->
            ResumeCard(t, pictures, actions, modifier = if (i == 0 && focus != null) Modifier.focusRequester(focus) else Modifier)
        }
    }
}

/** A row of posters; [episodes]: an episode under its series' name, a series with new ones "3 neu". */
@Composable
internal fun PosterRow(
    items: List<MediaTitle>,
    pictures: Map<String, Bitmap>,
    actions: TvMediaActions,
    focus: FocusRequester?,
    more: Int? = null,
    episodes: Boolean = false,
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
                episodes = episodes,
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
    MediaTitle.Kind.EPISODE -> if (t.season != null && t.episode != null) stringResource(R.string.tv_media_season_episode, t.season!!, t.episode!!) else null
    MediaTitle.Kind.COLLECTION -> t.childCount?.let { pluralStringResource(R.plurals.tv_media_collection_titles, it, it) }
    MediaTitle.Kind.SERIES -> t.childCount?.let { pluralStringResource(R.plurals.tv_media_series_seasons, it, it) } ?: stringResource(R.string.tv_media_series)
    else -> listOfNotNull(t.year?.toString(), t.runtimeMs?.let { MediaLogic.hoursMinutes(it) ?: stringResource(R.string.tv_media_minutes, MediaLogic.minutes(it)) })
        .joinToString(" · ").ifEmpty { null }
}

/** A poster (2:3) with its name and year; "NEU" when it came this week, "3 neu" on a series of "Neue Folgen". */
@Composable
private fun PosterCard(t: MediaTitle, pictures: Map<String, Bitmap>, actions: TvMediaActions, modifier: Modifier = Modifier, episodes: Boolean = false) {
    val colors = GateControlTvTheme.colors
    val name = if (episodes && t.kind == MediaTitle.Kind.EPISODE) t.seriesName ?: t.name else t.name
    val newCount = t.newCount?.takeIf { episodes }
    LaunchedEffect(t.id) { actions.onPicture(t, false) }
    val picture = pictures[actions.pictureKey(t, false)]
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        TvCard(92.dp, 138.dp, onClick = { actions.onOpen(t) }, description = name, modifier = modifier.menuOpens { actions.onOpen(t) }) {
            Box(Modifier.fillMaxSize().background(PictureBg)) {
                if (picture != null) {
                    Image(picture.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Icon(TvIcons.Media, contentDescription = null, tint = colors.faint, modifier = Modifier.align(Alignment.Center).size(26.dp))
                }
                if (t.fromShare || TvMediaUiLogic.isNew(t) || newCount != null) {
                    Row(Modifier.align(Alignment.TopStart).padding(5.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (t.fromShare) NasBadge()
                        if (newCount != null) {
                            TvPill(pluralStringResource(R.plurals.tv_media_new_episodes, newCount, newCount), TvPillTone.Accent, dot = false)
                        } else if (TvMediaUiLogic.isNew(t)) {
                            TvPill(stringResource(R.string.tv_drop_badge_new), TvPillTone.Accent, dot = false)
                        }
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
            Text(name, style = MaterialTheme.typography.labelMedium, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            posterLine(t)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.muted, maxLines = 1) }
        }
    }
}

internal val PictureBg = Color(0xFF0B0F14)

/** At most this many shares in the header (more are in "Quellen"). */

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
