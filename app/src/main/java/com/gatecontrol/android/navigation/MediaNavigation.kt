package com.gatecontrol.android.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.gatecontrol.android.media.MediaTitle
import com.gatecontrol.android.ui.media.MediaDetailScreen
import com.gatecontrol.android.ui.media.MediaDetailViewModel
import com.gatecontrol.android.ui.media.MediaHomeNav
import com.gatecontrol.android.ui.media.MediaHomeScreen
import com.gatecontrol.android.ui.media.MediaOfflineScreen
import com.gatecontrol.android.ui.media.MediaSearchScreen
import com.gatecontrol.android.ui.media.MediaSourcesScreen

/**
 * The routes of Medien (stage 4, question 21): the tab (Übersicht, Filme, Serien, Ordner) and its pages on top — a
 * title's detail, the search over all sources and Medien › Quellen (a display: the sources come from the server).
 */
internal object MediaRoutes {
    const val TAB = "media"
    const val DETAIL = "media/title?source={source}&id={id}&name={name}&season={season}"
    const val SEARCH = "media/search"
    const val SOURCES = "media/sources"

    /** Medien › Quellen, with the dialog of one source open (`source`: its id; „Neu anmelden“ of a source set up by hand). */
    const val SOURCES_ROUTE = "media/sources?source={source}"
    const val OFFLINE = "media/offline"

    /** A series with [MediaTitle.seasonId] opens on that season („Andere Folge“ of the hint „Videos umwandeln“). */
    fun detail(t: MediaTitle) = MailRoutes.build(
        "media/title",
        "source" to t.sourceId,
        "id" to t.id,
        "name" to t.name.take(200),
        "season" to t.seasonId?.takeIf { t.kind == MediaTitle.Kind.SERIES },
    )

    fun source(id: String) = MailRoutes.build(SOURCES, "source" to id)
}

/** The ways between the media pages (the tab, a page beside Start, the start card and the routes on top share them). */
internal class MediaNavigation(private val navController: NavHostController) {
    private val go: (String) -> Unit = { navController.navigate(it) { launchSingleTop = true } }
    val detail: (MediaTitle) -> Unit = { t -> navController.navigate(MediaRoutes.detail(t)) }
    val search: () -> Unit = { go(MediaRoutes.SEARCH) }
    val sources: () -> Unit = { go(MediaRoutes.SOURCES) }
    val source: (String) -> Unit = { id -> go(MediaRoutes.source(id)) }
    val offline: () -> Unit = { go(MediaRoutes.OFFLINE) }

    fun home() = MediaHomeNav(detail = detail, search = search, sources = sources, offline = offline, source = source)
}

/** The tab's screen (as the tab or as a page beside Start). */
@Composable
internal fun MediaHome(navController: NavHostController) {
    val nav = remember(navController) { MediaNavigation(navController).home() }
    GcContentWidth(maxWidth = 960.dp) { MediaHomeScreen(nav) }
}

internal fun NavGraphBuilder.mediaRoutes(navController: NavHostController, navigateToTab: (String) -> Unit, home: @Composable () -> Unit) {
    val nav = MediaNavigation(navController)
    val back: () -> Unit = { if (!navController.popBackStack()) navigateToTab(MediaRoutes.TAB) }
    val str = { name: String -> navArgument(name) { type = NavType.StringType; nullable = true; defaultValue = null } }
    composable(MediaRoutes.TAB) { home() }
    composable(MediaRoutes.DETAIL, arguments = listOf(str(MediaDetailViewModel.ARG_SOURCE), str(MediaDetailViewModel.ARG_ID), str(MediaDetailViewModel.ARG_NAME), str(MediaDetailViewModel.ARG_SEASON))) {
        GcContentWidth(maxWidth = 840.dp) { MediaDetailScreen(onBack = back, onOpen = nav.detail, castViewModel = androidx.hilt.navigation.compose.hiltViewModel()) }
    }
    composable(MediaRoutes.SEARCH) { GcContentWidth(maxWidth = 960.dp) { MediaSearchScreen(onBack = back, onOpen = nav.detail) } }
    composable(MediaRoutes.SOURCES_ROUTE, arguments = listOf(str("source"))) { entry ->
        GcContentWidth { MediaSourcesScreen(onBack = back, onOffline = nav.offline, openSource = entry.arguments?.getString("source")) }
    }
    composable(MediaRoutes.OFFLINE) {
        GcContentWidth { MediaOfflineScreen(onBack = back, onSettings = nav.sources, onOpen = nav.detail) }
    }
}
