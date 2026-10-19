package com.gatecontrol.android.ui.rules

import android.net.Uri
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
import com.gatecontrol.android.familie.rules.RuleTemplate

/**
 * Routes of "Regeln und Erinnerungen" (Familie B1): the list, the editor
 * and the reminder for one device — `reminder/new?actionId=<plugin>/<id>`
 * (the long press on a start-tab widget opens it).
 */
object RulesRoutes {
    const val LIST = "rules"
    const val EDIT = "rules/edit?id={id}&personal={personal}&template={template}"
    const val REMINDER = "reminder/new?actionId={actionId}"
    const val ARG_ID = "id"
    const val ARG_PERSONAL = "personal"
    const val ARG_ACTION = "actionId"
    const val ARG_TEMPLATE = "template"

    /** [id] null: a new rule, from [template] (a [com.gatecontrol.android.familie.rules.RuleTemplate] id) when given. */
    fun edit(id: Int? = null, personal: Boolean = false, template: String? = null): String =
        "rules/edit?id=${id ?: -1}&personal=$personal" + (template?.let { "&template=" + Uri.encode(it) } ?: "")

    fun reminder(actionId: String): String = "reminder/new?actionId=" + Uri.encode(actionId)
}

@Composable
private fun Width(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = 720.dp).fillMaxSize()) { content() }
    }
}

/** Adds the rules screens to the app's navigation graph. */
fun NavGraphBuilder.rulesGraph(navController: NavController) {
    composable(RulesRoutes.LIST) {
        Width {
            RulesListScreen(
                onBack = { navController.popBackStack() },
                onOpenRule = { id -> navController.navigate(RulesRoutes.edit(id)) { launchSingleTop = true } },
                onNewRule = { template -> navController.navigate(RulesRoutes.edit(template = template)) { launchSingleTop = true } },
            )
        }
    }
    composable(
        route = RulesRoutes.EDIT,
        arguments = listOf(
            navArgument(RulesRoutes.ARG_ID) {
                type = NavType.IntType
                defaultValue = -1
            },
            navArgument(RulesRoutes.ARG_PERSONAL) {
                type = NavType.BoolType
                defaultValue = false
            },
            navArgument(RulesRoutes.ARG_TEMPLATE) {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            },
        ),
    ) { entry ->
        val id = entry.arguments?.getInt(RulesRoutes.ARG_ID)?.takeIf { it > 0 }
        val personal = entry.arguments?.getBoolean(RulesRoutes.ARG_PERSONAL) == true
        val template = RuleTemplate.of(entry.arguments?.getString(RulesRoutes.ARG_TEMPLATE))
        Width { RuleEditorScreen(ruleId = id, actionId = null, personal = personal, template = template, onBack = { navController.popBackStack() }) }
    }
    composable(
        route = RulesRoutes.REMINDER,
        arguments = listOf(
            navArgument(RulesRoutes.ARG_ACTION) {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            },
        ),
    ) { entry ->
        val actionId = entry.arguments?.getString(RulesRoutes.ARG_ACTION)?.let { Uri.decode(it) }?.takeIf { it.contains('/') }
        Width { RuleEditorScreen(ruleId = null, actionId = actionId, personal = true, onBack = { navController.popBackStack() }) }
    }
}
