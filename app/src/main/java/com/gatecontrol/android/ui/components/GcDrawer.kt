package com.gatecontrol.android.ui.components

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DrawerState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.R
import com.gatecontrol.android.ui.theme.GateControlTheme

/**
 * The parts of a plugin's side menu (Drive, Notizen & Aufgaben; Mail uses the sheet): the sheet, the head with the
 * title and who is signed in, the section labels, and the bar of icons at the foot. The tabs build their own rows
 * between them.
 */

/**
 * The sheet of a plugin's side menu. The menu is drawn inside the app's Scaffold (AppNavigation), which already keeps
 * it below the status bar and above the navigation or tab bar; Material's default sheet pads by the system bars a
 * second time (the menu's head a status bar too low, v1.102). So no insets of its own: its content starts at the
 * height of the page's head. The sheet's colour still fills its whole height.
 */
@Composable
fun GcDrawerSheet(
    containerColor: Color = MaterialTheme.colorScheme.background,
    /** Closes the menu (the person in its head opens „Profil“ and closes it first). */
    onClose: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    CompositionLocalProvider(LocalGcDrawerClose provides onClose) {
        ModalDrawerSheet(drawerContainerColor = containerColor, windowInsets = WindowInsets(0), content = content)
    }
}

/**
 * A plugin's side menu around its page (Drive, Notizen & Aufgaben, Mail, Chat). Material places the closed sheet
 * right before the page's left edge without clipping it: on a phone that is off screen, but on wide windows the page
 * starts after the navigation rail (or, centred, after an empty margin, or as the second pane beside Start), and the
 * closed menu was drawn over the rail – its right part visible, the rail hidden (foldable, tablet, landscape). The
 * menu stays within the page's bounds: closed it is not seen, opened it slides in at the page's left edge.
 */
@Composable
fun GcNavigationDrawer(
    drawerState: DrawerState,
    drawerContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    gesturesEnabled: Boolean = drawerState.isOpen,
    content: @Composable () -> Unit,
) {
    ModalNavigationDrawer(
        drawerContent = drawerContent,
        modifier = modifier.clipToBounds(),
        drawerState = drawerState,
        gesturesEnabled = gesturesEnabled,
        content = content,
    )
}

/** The signed-in person for the head of every side menu: the own picture and the way to „Profil“. */
class GcDrawerMe(
    val name: String?,
    val initials: String?,
    val colorHex: String?,
    val avatarUrl: String?,
    val onOpenProfile: () -> Unit,
)

/** Provided by AppNavigation; null (tests, previews): the head shows the initial and is no button. */
val LocalGcDrawerMe = compositionLocalOf<GcDrawerMe?> { null }

/** The menu's own close, from [GcDrawerSheet]. */
val LocalGcDrawerClose = compositionLocalOf<(() -> Unit)?> { null }

/** An icon of the menu's bottom bar: [label] is its description (read out, shown on a long press). */
data class GcDrawerIcon(val icon: ImageVector, val label: Int, val onClick: () -> Unit)

/**
 * The menu's head: the plugin's [title], then the person (the name, [subtitle]) and [extra] below. With the own
 * profile ([LocalGcDrawerMe]) the person shows the own picture and is a button: a tap closes the menu and opens
 * „Profil“ (an arrow at the end); without it the round initial as before.
 */
@Composable
fun GcDrawerHead(title: String, name: String?, subtitle: String?, extra: (@Composable ColumnScope.() -> Unit)? = null) {
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        GcScreenTitle(title)
        GcDrawerPerson(name, subtitle)
        extra?.invoke(this)
    }
}

/** The person of a menu's head ([GcDrawerHead]; Mail puts it below its own title). Nothing without a name. */
@Composable
fun GcDrawerPerson(name: String?, subtitle: String?, modifier: Modifier = Modifier) {
    val x = GateControlTheme.extraColors
    val me = LocalGcDrawerMe.current
    val close = LocalGcDrawerClose.current
    val n = name?.takeIf { it.isNotBlank() } ?: me?.name?.takeIf { it.isNotBlank() } ?: return
    val openLabel = stringResource(R.string.drawer_open_profile, n)
    Row(
        modifier
            .fillMaxWidth()
            .then(
                if (me != null) {
                    val open = {
                        close?.invoke()
                        me.onOpenProfile()
                    }
                    // read out as one button: „Jenny Backes, Profil öffnen“
                    Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .clearAndSetSemantics {
                            contentDescription = openLabel
                            role = Role.Button
                            onClick { open(); true }
                        }
                        .clickable(role = Role.Button, onClick = open)
                        .padding(vertical = 4.dp)
                } else {
                    Modifier
                },
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (me != null) {
            GcAvatar(
                url = me.avatarUrl,
                initials = me.initials ?: com.gatecontrol.android.profile.InitialLetters.initial(n),
                colorHex = me.colorHex,
                ring = com.gatecontrol.android.profile.AvatarRing.None,
                size = 40.dp,
            )
        } else {
            Box(Modifier.size(40.dp).clip(CircleShape).background(x.accentBg), contentAlignment = Alignment.Center) {
                Text(com.gatecontrol.android.profile.InitialLetters.initial(n), style = MaterialTheme.typography.titleMedium, color = x.accentText, fontWeight = FontWeight.Bold)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(n, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = x.muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        if (me != null) {
            Icon(GcIcons.ChevronRight, contentDescription = null, tint = x.faint, modifier = Modifier.size(20.dp))
        }
    }
}

/**
 * A section label of the menu („Persönlich“, „Meine Listen“); [action] puts a small button at its end (the „+“ that
 * makes a new list or notebook).
 */
@Composable
fun GcDrawerLabel(text: String, action: (@Composable () -> Unit)? = null) {
    if (action == null) {
        GcSectionLabel(text, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 6.dp))
        return
    }
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        GcSectionLabel(text, modifier = Modifier.weight(1f).semantics { heading() })
        action()
    }
}

/** The bar at the menu's foot: a line, then the icons (the first one at the left). */
@Composable
fun GcDrawerBar(icons: List<GcDrawerIcon>) {
    GcRowDivider()
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 20.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icons.forEach { GcDrawerIconButton(it) }
    }
}

/** A 44 dp icon of the menu's bottom bar, described for TalkBack and on a long press. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GcDrawerIconButton(i: GcDrawerIcon) {
    val label = stringResource(i.label)
    val context = LocalContext.current
    Box(
        Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(GateControlTheme.extraColors.panel2)
            .combinedClickable(
                role = Role.Button,
                onClickLabel = label,
                onLongClick = { Toast.makeText(context, label, Toast.LENGTH_SHORT).show() },
                onClick = i.onClick,
            )
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(i.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
    }
}
