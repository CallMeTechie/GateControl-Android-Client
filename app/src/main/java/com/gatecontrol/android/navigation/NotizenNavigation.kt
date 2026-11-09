package com.gatecontrol.android.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.gatecontrol.android.notizen.NotizenOpen
import com.gatecontrol.android.ui.notizen.NotizenHomeScreen
import com.gatecontrol.android.ui.notizen.NotizenImportScreen
import com.gatecontrol.android.ui.notizen.NotizenItemScreen
import com.gatecontrol.android.ui.notizen.NotizenNav
import com.gatecontrol.android.ui.notizen.NotizenNoteScreen
import com.gatecontrol.android.ui.notizen.NotizenQuickScreen
import com.gatecontrol.android.ui.notizen.NotizenSearchScreen
import com.gatecontrol.android.ui.notizen.NotizenSourcesScreen
import com.gatecontrol.android.ui.notizen.NotizenTaskScreen
import com.gatecontrol.android.ui.notizen.NotizenVaultScreen
import com.gatecontrol.android.ui.notizen.NotizenViewScreen

/**
 * The routes of „Notizen & Aufgaben“ (plugin gatecontrol-notizen): the tab „Aufgaben“ (Mein Tag, the lists, the notes)
 * and its pages on top, each a route of its own so the tab bar shows on the tab only. Kept here, not in [Screen], so
 * the plugin's screens stay in their own files.
 */
internal object NotizenRoutes {
    const val TAB = "notizen"
    const val VIEW = "notizen/view/{name}"
    const val TASK = "notizen/task/{id}"
    const val ITEM = "notizen/item/{id}"
    const val QUICK = "notizen/quick?mode={mode}&text={text}"
    const val NOTE = "notizen/note/{id}?notebook={notebook}&text={text}"
    const val SOURCES = "notizen/sources"
    const val IMPORT = "notizen/import"
    const val SEARCH = "notizen/search"
    const val VAULT = "notizen/vault/{vault}?requests={requests}"

    fun view(name: String) = "notizen/view/" + MailRoutes.enc(name)
    fun task(id: Long) = "notizen/task/$id"
    fun item(id: Long) = "notizen/item/$id"
    fun quick(note: Boolean, text: String? = null) = MailRoutes.build("notizen/quick", "mode" to if (note) "note" else null, "text" to text?.take(1000))
    fun note(id: Long, notebook: Long? = null, text: String? = null) = MailRoutes.build("notizen/note/$id", "notebook" to notebook?.toString(), "text" to text?.take(20_000))
    /** A Tresor's page; [requests] (a push „Neues Gerät möchte Zugriff“): it stays after unlocking, with the device to approve. */
    fun vault(id: String, requests: Boolean = false) = "notizen/vault/$id" + if (requests) "?requests=1" else ""

    /** The tab of a plugin this app has (an item's original whose route it has no screen for). */
    fun tabOf(plugin: String): String? = when (plugin) {
        "gatecontrol-mail" -> Screen.Mail.route
        "gatecontrol-drive" -> Screen.Drive.route
        "gatecontrol-familie" -> Screen.Familie.create()
        com.gatecontrol.android.notizen.NOTIZEN_PLUGIN -> TAB
        else -> null
    }
}

/** The ways between the plugin's screens (the tab, a page beside Start and the routes on top share them). */
private fun notizenNav(navController: NavHostController, have: () -> NotizenOpen.Have): NotizenNav {
    val go: (String) -> Unit = { navController.navigate(it) { launchSingleTop = true } }
    return NotizenNav(
        task = { go(NotizenRoutes.task(it)) },
        item = { go(NotizenRoutes.item(it)) },
        view = { go(NotizenRoutes.view(it)) },
        quick = { go(NotizenRoutes.quick(it)) },
        note = { go(NotizenRoutes.note(it)) },
        newNote = { go(NotizenRoutes.note(0, it)) },
        sources = { go(NotizenRoutes.SOURCES) },
        import = { go(NotizenRoutes.IMPORT) },
        search = { go(NotizenRoutes.SEARCH) },
        vault = { go(NotizenRoutes.vault(it)) },
        openSource = { open ->
            when (val t = NotizenOpen.target(open, have())) {
                is NotizenOpen.MailMessage -> { go(Screen.MailMessage.create(t.id)); true }
                is NotizenOpen.DriveFile -> { go(Screen.DriveFile.create(t.id)); true }
                is NotizenOpen.Familie -> { go(Screen.FamiliePage.create(t.dest)); true }
                is NotizenOpen.Note -> { go(NotizenRoutes.note(t.id)); true }
                is NotizenOpen.Task -> { go(NotizenRoutes.task(t.id)); true }
                is NotizenOpen.Tab -> NotizenRoutes.tabOf(t.plugin)?.let { go(it); true } ?: false
                null -> false
            }
        },
        back = { navController.popBackStack() },
    )
}

/** The tab's screen (as the tab or as a page beside Start). */
@Composable
internal fun NotizenHome(navController: NavHostController, have: () -> NotizenOpen.Have) {
    val nav = remember(navController) { notizenNav(navController, have) }
    GcContentWidth { NotizenHomeScreen(nav) }
}

internal fun NavGraphBuilder.notizenRoutes(navController: NavHostController, have: () -> NotizenOpen.Have, home: @Composable () -> Unit) {
    val nav: () -> NotizenNav = { notizenNav(navController, have) }
    val str = { name: String -> navArgument(name) { type = NavType.StringType; nullable = true; defaultValue = null } }
    composable(NotizenRoutes.TAB) { home() }
    composable(NotizenRoutes.VIEW, arguments = listOf(navArgument("name") { type = NavType.StringType })) { GcContentWidth { NotizenViewScreen(remember { nav() }) } }
    composable(NotizenRoutes.TASK, arguments = listOf(navArgument("id") { type = NavType.StringType })) { GcContentWidth { NotizenTaskScreen(remember { nav() }) } }
    composable(NotizenRoutes.ITEM, arguments = listOf(navArgument("id") { type = NavType.StringType })) { GcContentWidth { NotizenItemScreen(remember { nav() }) } }
    composable(NotizenRoutes.QUICK, arguments = listOf(str("mode"), str("text"))) { GcContentWidth { NotizenQuickScreen(remember { nav() }) } }
    composable(NotizenRoutes.NOTE, arguments = listOf(navArgument("id") { type = NavType.StringType }, str("notebook"), str("text"))) { GcContentWidth { NotizenNoteScreen(remember { nav() }) } }
    composable(NotizenRoutes.SOURCES) { GcContentWidth { NotizenSourcesScreen(remember { nav() }) } }
    composable(NotizenRoutes.IMPORT) { GcContentWidth { NotizenImportScreen(remember { nav() }) } }
    composable(NotizenRoutes.SEARCH) { GcContentWidth { NotizenSearchScreen(remember { nav() }) } }
    composable(NotizenRoutes.VAULT, arguments = listOf(navArgument("vault") { type = NavType.StringType }, str("requests"))) { entry ->
        val requests = entry.arguments?.getString("requests") == "1"
        GcContentWidth { NotizenVaultScreen(onBack = { navController.popBackStack() }, onOpened = { navController.popBackStack() }, stayAfterUnlock = requests) }
    }
}
