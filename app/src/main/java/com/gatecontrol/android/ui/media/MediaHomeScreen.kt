package com.gatecontrol.android.ui.media

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.media.MediaLink
import com.gatecontrol.android.media.MediaLogic
import com.gatecontrol.android.media.MediaOrder
import com.gatecontrol.android.media.MediaSource
import com.gatecontrol.android.media.MediaTitle
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcFilterChip
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcSegmented
import com.gatecontrol.android.ui.drive.TabBar
import com.gatecontrol.android.ui.theme.GateControlTheme

/** Where the Medien tab leads. */
data class MediaHomeNav(
    val detail: (MediaTitle) -> Unit,
    val search: () -> Unit,
    val sources: () -> Unit,
    /** The page "Offline" (question 22 a). */
    val offline: () -> Unit = {},
    /** Medien › Quellen with the dialog of one source (by id) open. */
    val source: (String) -> Unit = { sources() },
)

/**
 * The Medien tab (mockups 2 and 3): the source and the network on top, then "Übersicht · Filme · Serien · Ordner".
 * Shown as the tab or as a page beside Start.
 */
@Composable
fun MediaHomeScreen(nav: MediaHomeNav, viewModel: MediaHomeViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val link by viewModel.link.collectAsStateWithLifecycle()
    val anyOffline by viewModel.anyOffline.collectAsStateWithLifecycle()
    val gate by viewModel.gate.collectAsStateWithLifecycle()
    val migration by viewModel.migration.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(MediaTab.OVERVIEW) }
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    // „Neu anmelden“ of a source set up by hand: its sign-in is on Medien › Quellen
    LaunchedEffect(ui.openSource) {
        ui.openSource?.let { id ->
            viewModel.sourceOpened()
            nav.source(id)
        }
    }
    CompositionLocalProvider(LocalMediaPictures provides viewModel.pictures) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            TabBar(
                title = stringResource(R.string.media_tab),
                onMenu = null,
                onSearch = if (ui.any) nav.search else null,
                actions = {
                    if (ui.servers.isNotEmpty() || anyOffline) {
                        GcIconButton(GcIcons.DownloadTray, contentDescription = stringResource(R.string.media_offline_title), onClick = nav.offline, tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.testTag(OFFLINE_OPEN_TAG))
                    }
                    GcIconButton(GcIcons.Sliders, contentDescription = stringResource(R.string.media_sources), onClick = nav.sources, tint = MaterialTheme.colorScheme.onBackground)
                },
            )
            MediaVpnGate(gate, onConsent = viewModel::consent, onRetry = viewModel::refresh)
            if (!ui.any) {
                MediaEmpty()
                return@Column
            }
            SourceLine(ui, link, onSelect = viewModel::select)
            GcSegmented(
                options = listOf(
                    MediaTab.OVERVIEW to stringResource(R.string.media_tab_overview),
                    MediaTab.MOVIES to stringResource(R.string.media_tab_movies),
                    MediaTab.SERIES to stringResource(R.string.media_tab_series),
                    MediaTab.FOLDERS to stringResource(R.string.media_tab_folders),
                ),
                selected = tab,
                onSelect = { tab = it },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            val signIn = viewModel::repair
            when (tab) {
                MediaTab.OVERVIEW -> MediaOverview(ui, viewModel, nav, onSeries = { tab = MediaTab.SERIES }, onSignIn = signIn)
                MediaTab.MOVIES -> MediaGridPage(movies = true, viewModel = viewModel, nav = nav, onSignIn = signIn)
                MediaTab.SERIES -> MediaGridPage(movies = false, viewModel = viewModel, nav = nav, onSignIn = signIn)
                MediaTab.FOLDERS -> MediaFolderPage(onDetail = nav.detail)
            }
        }
    }
    if (migration.isNotEmpty()) MediaMigrationSheet(migration, onDone = viewModel::acknowledgeMigration)
}

/** No source yet (a page beside Start can show before the tab does): the admin sets them up in the server. */
@Composable
private fun MediaEmpty() {
    GcCard(Modifier.padding(16.dp)) {
        Text(stringResource(R.string.media_empty_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.media_empty_server_text), style = MaterialTheme.typography.bodyMedium, color = GateControlTheme.extraColors.muted)
    }
}

/**
 * The status dot and "Zuhause · direkt"; with several Jellyfin servers the chip "● Jellyfin ▾" to switch. Only the
 * kind of the source, never its host or server name (those are on Medien › Quellen and in the switch's list).
 */
@Composable
internal fun SourceLine(ui: MediaHomeUi, link: MediaLink, onSelect: (MediaSource) -> Unit) {
    val x = GateControlTheme.extraColors
    var open by remember { mutableStateOf(false) }
    val shareWord = stringResource(R.string.media_share)
    val shown = ui.source ?: ui.shares.firstOrNull()
    val kind = shown?.let { MediaLogic.kindLabel(it, shareWord) }
    val net = linkText(link)
    val dotColor = if (ui.source?.token == null && ui.source != null) x.warn else MaterialTheme.colorScheme.primary
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (kind != null) {
            val several = ui.servers.size > 1
            if (several) {
                Box {
                    Row(
                        Modifier.clip(RoundedCornerShape(10.dp)).background(x.accentBg)
                            .clickable(role = Role.Button) { open = true }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(dotColor))
                        Text(kind, style = MaterialTheme.typography.labelLarge, color = x.accentText, maxLines = 1)
                        Icon(GcIcons.ChevronDown, contentDescription = stringResource(R.string.media_switch_source), tint = x.accentText, modifier = Modifier.size(16.dp))
                    }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        ui.servers.forEach { s ->
                            DropdownMenuItem(
                                text = { Text(MediaLogic.sourceLabel(s, shareWord)) },
                                onClick = {
                                    open = false
                                    onSelect(s)
                                },
                                trailingIcon = if (s.id == ui.source?.id) { { Icon(GcIcons.Check, contentDescription = null, modifier = Modifier.size(18.dp)) } } else null,
                            )
                        }
                    }
                }
            } else {
                // one source: only the dot (its kind for TalkBack), then the way
                Box(
                    Modifier.padding(start = 2.dp).size(8.dp).clip(RoundedCornerShape(4.dp)).background(dotColor)
                        .semantics { contentDescription = kind }
                        .testTag(SOURCE_DOT_TAG),
                )
            }
        }
        if (net != null) Text(net, style = MaterialTheme.typography.labelLarge, color = x.muted, maxLines = 1)
        if (ui.loading) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
    }
    if (ui.shareProblems.isNotEmpty()) {
        val names = ui.shares.filter { it.id in ui.shareProblems }.joinToString(", ") { MediaLogic.shareAddress(it) }
        Text(
            stringResource(R.string.media_share_unreachable, names),
            style = MaterialTheme.typography.bodySmall,
            color = x.warn,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}

/** "Übersicht": Weiterschauen, Zuletzt hinzugefügt, Serien (mockup 2). */
@Composable
private fun MediaOverview(ui: MediaHomeUi, viewModel: MediaHomeViewModel, nav: MediaHomeNav, onSeries: () -> Unit, onSignIn: () -> Unit) {
    val offline by viewModel.offlineReady.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize().testTag(OVERVIEW_TAG), contentPadding = PaddingValues(bottom = 24.dp)) {
        ui.problem?.let { p -> item(key = "problem") { MediaProblemCard(p, onRetry = viewModel::refresh, onSignIn = onSignIn, signIn = ui.signIn) } }
        // the downloads first without a connection, else after "Weiterschauen"
        val offlineFirst = ui.problem == com.gatecontrol.android.media.MediaProblem.OFFLINE || ui.problem == com.gatecontrol.android.media.MediaProblem.NO_ROUTE
        if (offlineFirst && offline.isNotEmpty()) offlineRow(offline, nav)
        if (ui.resume.isNotEmpty()) {
            item(key = "resume-head") {
                MediaRowHead(stringResource(R.string.media_row_resume), end = pluralStringResource(R.plurals.media_titles, ui.resume.size, ui.resume.size))
            }
            item(key = "resume") {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(ui.resume, key = { "${it.sourceId}:${it.id}" }) { t ->
                        MediaResumeCard(t, onPlay = { viewModel.play(t) }, onDetail = { nav.detail(t) })
                    }
                }
            }
        }
        if (!offlineFirst && offline.isNotEmpty()) offlineRow(offline, nav)
        if (ui.latest.isNotEmpty()) {
            item(key = "latest-head") { MediaRowHead(stringResource(R.string.media_row_latest)) }
            item(key = "latest") {
                val week = System.currentTimeMillis() - NEW_MS
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(ui.latest, key = { "${it.sourceId}:${it.id}" }) { t ->
                        MediaPoster(t, onClick = { nav.detail(t) }, width = 112.dp, new = (t.addedAt ?: 0) > week)
                    }
                }
            }
        }
        if (ui.series.isNotEmpty()) {
            item(key = "series-head") {
                MediaRowHead(stringResource(R.string.media_row_series), end = ui.seriesTotal.takeIf { it > 0 }?.toString(), onEnd = onSeries)
            }
            item(key = "series") {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(ui.series.take(SERIES_ROW), key = { "${it.sourceId}:${it.id}" }) { t ->
                        MediaPoster(t, onClick = { nav.detail(t) }, width = 112.dp)
                    }
                }
            }
        }
        if (!ui.loading && ui.problem == null && ui.resume.isEmpty() && ui.latest.isEmpty() && ui.series.isEmpty() && offline.isEmpty()) {
            item(key = "nothing") {
                Text(
                    stringResource(R.string.media_library_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = GateControlTheme.extraColors.muted,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

/** "Offline verfügbar": the ready downloads as posters, "Alle" opens the page "Offline". */
private fun androidx.compose.foundation.lazy.LazyListScope.offlineRow(titles: List<MediaTitle>, nav: MediaHomeNav) {
    item(key = "offline-head") {
        MediaRowHead(stringResource(R.string.media_offline_row), end = titles.size.toString(), onEnd = nav.offline)
    }
    item(key = "offline") {
        LazyRow(Modifier.testTag(OFFLINE_ROW_TAG), contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(titles, key = { "off:${it.sourceId}:${it.id}" }) { t -> MediaPoster(t, onClick = { nav.detail(t) }, width = 112.dp) }
        }
    }
}

/** "Filme" or "Serien" as a grid (three columns on a phone, more on a tablet), the chips above, page by page. */
@Composable
private fun MediaGridPage(movies: Boolean, viewModel: MediaHomeViewModel, nav: MediaHomeNav, onSignIn: () -> Unit) {
    val g by (if (movies) viewModel.movies else viewModel.series).collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    LaunchedEffect(movies) { viewModel.openGrid(movies) }
    val state = rememberLazyGridState()
    val nearEnd by remember(g.items.size) {
        derivedStateOf { (state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= g.items.size - MediaLogic.PAGE_AHEAD * 2 }
    }
    LaunchedEffect(nearEnd, g.hasMore) { if (nearEnd && g.hasMore) viewModel.more(movies) }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 104.dp),
        state = state,
        modifier = Modifier.fillMaxSize().testTag(if (movies) MOVIES_TAG else SERIES_TAG),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "chips", span = { GridItemSpan(maxLineSpan) }) {
            OrderChips(movies, g, onOrder = { viewModel.setOrder(movies, it) })
        }
        g.problem?.let { p ->
            item(key = "problem", span = { GridItemSpan(maxLineSpan) }) { MediaProblemCard(p, onRetry = { viewModel.reloadGrid(movies) }, onSignIn = onSignIn, signIn = ui.signIn) }
        }
        items(g.items, key = { "${it.sourceId}:${it.id}" }) { t -> MediaPoster(t, onClick = { nav.detail(t) }) }
        if (g.loading || g.hasMore) {
            item(key = "loading", span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) }
            }
        } else if (g.loaded && g.items.isEmpty() && g.problem == null) {
            item(key = "nothing", span = { GridItemSpan(maxLineSpan) }) {
                Text(stringResource(R.string.media_grid_empty), style = MaterialTheme.typography.bodyMedium, color = GateControlTheme.extraColors.muted, modifier = Modifier.padding(vertical = 16.dp))
            }
        }
    }
}

/** "A–Z ▾", "Ungesehen", "Genre ▾" and the count (mockup 3). */
@Composable
private fun OrderChips(movies: Boolean, g: MediaGridUi, onOrder: (MediaOrder) -> Unit) {
    var sortOpen by remember { mutableStateOf(false) }
    var genreOpen by remember { mutableStateOf(false) }
    val sorts = listOf(
        MediaOrder.Sort.NAME to stringResource(R.string.media_sort_name),
        MediaOrder.Sort.ADDED to stringResource(R.string.media_sort_added),
        MediaOrder.Sort.YEAR to stringResource(R.string.media_sort_year),
    )
    Column(Modifier.padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "sort") {
                Box {
                    GcFilterChip(
                        text = sorts.first { it.first == g.order.sort }.second + " ▾",
                        selected = true,
                        onClick = { sortOpen = true },
                    )
                    DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                        sorts.forEach { (sort, label) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = {
                                sortOpen = false
                                onOrder(g.order.copy(sort = sort))
                            })
                        }
                    }
                }
            }
            item(key = "unplayed") {
                GcFilterChip(stringResource(R.string.media_filter_unplayed), selected = g.order.unplayed, onClick = { onOrder(g.order.copy(unplayed = !g.order.unplayed)) })
            }
            if (g.genres.isNotEmpty() || g.order.genre != null) {
                item(key = "genre") {
                    Box {
                        GcFilterChip(
                            text = (g.order.genre ?: stringResource(R.string.media_filter_genre)) + " ▾",
                            selected = g.order.genre != null,
                            onClick = { genreOpen = true },
                        )
                        DropdownMenu(expanded = genreOpen, onDismissRequest = { genreOpen = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.media_filter_all_genres)) }, onClick = {
                                genreOpen = false
                                onOrder(g.order.copy(genre = null))
                            })
                            g.genres.forEach { genre ->
                                DropdownMenuItem(text = { Text(genre) }, onClick = {
                                    genreOpen = false
                                    onOrder(g.order.copy(genre = genre))
                                })
                            }
                        }
                    }
                }
            }
        }
        if (g.loaded) {
            Text(
                pluralStringResource(if (movies) R.plurals.media_movies_count else R.plurals.media_series_count, g.total, g.total),
                style = MaterialTheme.typography.bodySmall,
                color = GateControlTheme.extraColors.muted,
            )
        }
    }
}

internal const val OVERVIEW_TAG = "media-overview"
internal const val SOURCE_DOT_TAG = "media-source-dot"
internal const val OFFLINE_ROW_TAG = "media-offline-row"
internal const val OFFLINE_OPEN_TAG = "media-offline-open"
internal const val MOVIES_TAG = "media-movies"
internal const val SERIES_TAG = "media-series"

/** "NEU" on a poster added within this long. */
private const val NEW_MS = 7 * 24 * 3600_000L
private const val SERIES_ROW = 20
