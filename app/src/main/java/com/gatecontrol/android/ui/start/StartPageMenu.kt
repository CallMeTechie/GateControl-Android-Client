package com.gatecontrol.android.ui.start

import android.content.res.Resources
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.R
import com.gatecontrol.android.layout.StartPages
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.theme.GateControlTheme

/**
 * What the "⋯" of a page beside Start offers: [label] the page's name,
 * [widgets] a widget page ("Seite entfernen" instead of "Als Tab
 * anzeigen"), [locked] the admin fixes the order (both off, with the hint).
 */
class StartPageMenu(
    val label: String,
    val widgets: Boolean,
    val locked: Boolean,
    val onUnpin: () -> Unit,
    val onArrange: () -> Unit,
)

/**
 * The menu of the page beside Start a tab's screen is shown on (null: it
 * is shown as a tab). The screen's own title row shows [StartPageMenuButton].
 */
val LocalStartPageMenu = compositionLocalOf<StartPageMenu?> { null }

/**
 * True while a screen is a page beside Start: swiping sideways changes the
 * page there, so its own segments and tabs change by tapping only (no
 * swiping of their own).
 */
val LocalInStartPager = staticCompositionLocalOf { false }

/** Shows a snackbar with [message] and, given [undo], "Rückgängig" (AppNavigation). */
val LocalPageSnack = staticCompositionLocalOf<(message: String, undo: (() -> Unit)?) -> Unit> { { _, _ -> } }

/** "⋯" with the page's menu, when the screen is a page beside Start ([LocalStartPageMenu]); nothing otherwise. */
@Composable
fun StartPageMenuButton(modifier: Modifier = Modifier) {
    val menu = LocalStartPageMenu.current ?: return
    StartPageMenuButton(menu, modifier)
}

@Composable
fun StartPageMenuButton(menu: StartPageMenu, modifier: Modifier = Modifier) {
    val extra = GateControlTheme.extraColors
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        GcIconButton(GcIcons.MoreHorizontal, contentDescription = stringResource(R.string.pages_menu_desc), onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = {
                    Column {
                        Text(stringResource(if (menu.widgets) R.string.pages_remove else R.string.pages_as_tab))
                        val hint = when {
                            menu.locked -> R.string.arrange_locked
                            menu.widgets -> null
                            else -> R.string.pages_as_tab_desc
                        }
                        if (hint != null) {
                            Text(
                                stringResource(hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = extra.muted,
                                modifier = Modifier.widthIn(max = 220.dp),
                            )
                        }
                    }
                },
                leadingIcon = { Icon(if (menu.widgets) GcIcons.Trash else GcIcons.Pin, contentDescription = null, modifier = Modifier.size(20.dp)) },
                enabled = !menu.locked,
                onClick = { open = false; menu.onUnpin() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.pages_arrange)) },
                leadingIcon = { Icon(StartIcons.Arrange, contentDescription = null, modifier = Modifier.size(20.dp)) },
                enabled = !menu.locked,
                onClick = { open = false; menu.onArrange() },
            )
        }
    }
}

/** Ids of Start and the plus page in the pager (no page of [StartPages] has them: neither is a function or `w<n>`). */
internal const val START_PAGE = "start"
internal const val PLUS_PAGE = "plus"

/** The name of the page [id] ("Netzwerk", "Widgets 2" …). */
@Composable
fun startPageLabel(id: String): String = startPageLabel(LocalResources.current, id)

fun startPageLabel(resources: Resources, id: String): String {
    val widgets = StartPages.widgetsNumber(id)
    return when {
        id == START_PAGE -> resources.getString(R.string.nav_start)
        id == PLUS_PAGE -> resources.getString(R.string.pages_plus_title)
        widgets != null -> resources.getString(R.string.pages_widgets_name, widgets)
        else -> featureLabel(id)?.let { resources.getString(it) } ?: id
    }
}

/** The tab's name of a function page. */
fun featureLabel(id: String): Int? = when (id) {
    StartPages.CHATS -> R.string.nav_chats
    StartPages.MAIL -> R.string.mail_tab
    StartPages.VAULT -> R.string.vault_tab
    StartPages.DRIVE -> R.string.drive_tab
    StartPages.NETWORK -> R.string.nav_network
    StartPages.FAMILIE -> R.string.nav_familie
    else -> null
}

/** The tab's icon of a function page, a grid for a widget page. */
fun startPageIcon(id: String): ImageVector = when (id) {
    START_PAGE -> GcIcons.Shield
    StartPages.CHATS -> Icons.AutoMirrored.Outlined.Chat
    StartPages.MAIL -> GcIcons.Mail
    StartPages.VAULT -> GcIcons.Vault
    StartPages.DRIVE -> GcIcons.Cloud
    StartPages.NETWORK -> GcIcons.Globe
    StartPages.FAMILIE -> GcIcons.Family
    else -> GcIcons.ViewGrid
}
