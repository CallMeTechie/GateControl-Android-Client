package com.gatecontrol.android.ui.components

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
fun GcDrawerSheet(containerColor: Color = MaterialTheme.colorScheme.background, content: @Composable ColumnScope.() -> Unit) {
    ModalDrawerSheet(drawerContainerColor = containerColor, windowInsets = WindowInsets(0), content = content)
}

/** An icon of the menu's bottom bar: [label] is its description (read out, shown on a long press). */
data class GcDrawerIcon(val icon: ImageVector, val label: Int, val onClick: () -> Unit)

/** The menu's head: the plugin's [title], then the person (a round initial, the name, [subtitle]) and [extra] below. */
@Composable
fun GcDrawerHead(title: String, name: String?, subtitle: String?, extra: (@Composable ColumnScope.() -> Unit)? = null) {
    val x = GateControlTheme.extraColors
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        GcScreenTitle(title)
        val n = name?.takeIf { it.isNotBlank() }
        if (n != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(x.accentBg), contentAlignment = Alignment.Center) {
                    Text(n.trim().take(1).uppercase(), style = MaterialTheme.typography.titleMedium, color = x.accentText, fontWeight = FontWeight.Bold)
                }
                Column {
                    Text(n, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = x.muted) }
                }
            }
        }
        extra?.invoke(this)
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
