package com.gatecontrol.android.ui.places

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument

/** Routes of Profil › Meine Orte: the list, a place, the editor (`id` -1: a new place). */
object PlacesRoutes {
    const val LIST = "places"
    const val DETAIL = "places/{id}"
    const val EDIT = "places/edit?id={id}"
    const val ARG_ID = "id"

    fun detail(id: Long): String = "places/$id"

    fun edit(id: Long? = null): String = "places/edit?id=${id ?: -1}"
}

@Composable
private fun Width(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = 720.dp).fillMaxSize()) { content() }
    }
}

/** Adds „Meine Orte“ to the app's navigation graph. */
fun NavGraphBuilder.placesGraph(navController: NavController) {
    composable(PlacesRoutes.LIST) {
        Width {
            MyPlacesScreen(
                onBack = { navController.popBackStack() },
                onOpen = { id -> navController.navigate(PlacesRoutes.detail(id)) { launchSingleTop = true } },
                onNew = { navController.navigate(PlacesRoutes.edit()) { launchSingleTop = true } },
                onSet = { id -> navController.navigate(PlacesRoutes.edit(id)) { launchSingleTop = true } },
            )
        }
    }
    composable(PlacesRoutes.DETAIL, arguments = listOf(navArgument(PlacesRoutes.ARG_ID) { type = NavType.LongType })) { entry ->
        val id = entry.arguments?.getLong(PlacesRoutes.ARG_ID) ?: -1L
        Width {
            PlaceDetailScreen(
                id = id,
                onBack = { navController.popBackStack() },
                onEdit = { navController.navigate(PlacesRoutes.edit(it)) { launchSingleTop = true } },
            )
        }
    }
    composable(
        PlacesRoutes.EDIT,
        arguments = listOf(navArgument(PlacesRoutes.ARG_ID) {
            type = NavType.LongType
            defaultValue = -1L
        }),
    ) { entry ->
        val id = entry.arguments?.getLong(PlacesRoutes.ARG_ID)?.takeIf { it > 0 }
        Width { PlaceEditorScreen(id = id, onBack = { navController.popBackStack() }) }
    }
}
