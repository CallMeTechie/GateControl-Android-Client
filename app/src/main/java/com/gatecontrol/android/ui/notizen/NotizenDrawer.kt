package com.gatecontrol.android.ui.notizen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.R
import com.gatecontrol.android.notizen.NArea
import com.gatecontrol.android.notizen.NGroup
import com.gatecontrol.android.notizen.NList
import com.gatecontrol.android.notizen.NMe
import com.gatecontrol.android.notizen.NNav
import com.gatecontrol.android.notizen.NNotebook
import com.gatecontrol.android.notizen.NotizenLists
import com.gatecontrol.android.ui.components.GcDrawerBar
import com.gatecontrol.android.ui.components.GcDrawerHead
import com.gatecontrol.android.ui.components.GcDrawerIcon
import com.gatecontrol.android.ui.components.GcDrawerLabel
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcSegmented
import com.gatecontrol.android.ui.theme.GateControlTheme

/** What the side menu of Notizen & Aufgaben does (the tab's side). */
internal class NotizenDrawerActions(
    val area: (String) -> Unit,
    val myDay: () -> Unit,
    val view: (String) -> Unit,
    val notes: (NNotebook?) -> Unit,
    val toggleGroup: (Long) -> Unit,
    val listMenu: (NList) -> Unit,
    val groupMenu: (NGroup) -> Unit,
    val notebookMenu: (NNotebook) -> Unit,
    val newListOrGroup: () -> Unit,
    val newNotebook: () -> Unit,
    /** Null: Tresore are not allowed here. */
    val newTresor: (() -> Unit)?,
    val settings: () -> Unit,
)

/**
 * The side menu (mockup „Neue Navigation“, like Drive's): who and which area, the fixed views (Mein Tag, Wichtig,
 * Geplant, Mir zugewiesen, Aufgaben), „Meine Listen“ with its „+“ and the groups as folders, „Aus anderen Plugins“,
 * „Notizbücher“ with its „+“, and the gear for the settings at the bottom left. A long press on a list, group or
 * notebook opens its menu.
 */
@Composable
internal fun NotizenDrawer(
    me: NMe?,
    nav: NNav?,
    area: String,
    /** `myday`, `notes`, `nb:<id>`. */
    selected: String,
    collapsed: Set<Long>,
    notebookName: (NNotebook) -> String?,
    actions: NotizenDrawerActions,
) {
    val default = NotizenLists.defaultList(nav)
    val rows = NotizenLists.rows(nav, collapsed)
    val canWrite = me != null && !me.readOnly
    Column(Modifier.fillMaxHeight()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 8.dp)) {
            item(key = "head") {
                GcDrawerHead(stringResource(R.string.notizen_title), me?.userName, areaLabel(area)) {
                    GcSegmented(listOf(NArea.PRIVATE to areaLabel(NArea.PRIVATE), NArea.WORK to areaLabel(NArea.WORK)), area, actions.area)
                }
            }
            item(key = "smart") {
                Column(Modifier.padding(top = 8.dp)) {
                    NavRow(GcIcons.Sun, stringResource(R.string.notizen_seg_day), nav?.smart?.get("myday"), selected == "myday", actions.myDay)
                    NavRow(GcIcons.Star, stringResource(R.string.notizen_smart_important), nav?.smart?.get("important"), false, { actions.view("important") })
                    NavRow(GcIcons.Calendar, stringResource(R.string.notizen_smart_planned), nav?.smart?.get("planned"), false, { actions.view("planned") })
                    NavRow(GcIcons.User, stringResource(R.string.notizen_smart_assigned), nav?.smart?.get("assigned"), false, { actions.view("assigned") })
                    default?.let { d ->
                        NavRow(GcIcons.Tasks, d.name, d.open, false, { actions.view("list:" + d.id) }, onLong = if (canWrite) ({ actions.listMenu(d) }) else null)
                    }
                }
            }
            item(key = "l-lists") {
                GcDrawerLabel(stringResource(R.string.notizen_my_lists), action = if (canWrite) ({ PlusButton(stringResource(R.string.notizen_new_list_or_group_title), actions.newListOrGroup) }) else null)
            }
            items(rows, key = { it.key }) { r ->
                when (r) {
                    is NotizenLists.Row.Group -> GroupRow(r, { actions.toggleGroup(r.group.id) }, if (canWrite) ({ actions.groupMenu(r.group) }) else null)
                    is NotizenLists.Row.ListRow -> ListRow(r.list, r.depth, me?.userId, { actions.view("list:" + r.list.id) }, if (canWrite) ({ actions.listMenu(r.list) }) else null)
                }
            }
            if (rows.isEmpty() && nav != null) {
                item(key = "lists-empty") {
                    Text(stringResource(R.string.notizen_lists_none), style = MaterialTheme.typography.bodySmall, color = GateControlTheme.extraColors.muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
                }
            }
            val sources = nav?.sources.orEmpty()
            if (sources.isNotEmpty()) {
                item(key = "l-sources") { GcDrawerLabel(stringResource(R.string.notizen_from_plugins)) }
                items(sources, key = { "s" + it.plugin }) { s ->
                    NavRow(null, s.label, s.open, false, { actions.view("source:" + s.plugin) }, lead = { SourceMark(s.plugin, s.label) })
                }
            }
            item(key = "l-notebooks") {
                GcDrawerLabel(stringResource(R.string.notizen_notebooks), action = if (canWrite) ({ NotebookPlus(actions) }) else null)
            }
            item(key = "all-notes") { NavRow(GcIcons.Note, stringResource(R.string.notizen_all_notes), nav?.notesTotal, selected == "notes", { actions.notes(null) }) }
            items(nav?.notebooks.orEmpty(), key = { "n" + it.id }) { nb ->
                val name = notebookName(nb) ?: stringResource(R.string.notizen_tresor)
                NavRow(
                    if (nb.vault != null) GcIcons.Lock else GcIcons.Note,
                    name,
                    nb.count,
                    selected == "nb:${nb.id}",
                    { actions.notes(nb) },
                    onLong = if (canWrite) ({ actions.notebookMenu(nb) }) else null,
                    depth = 1,
                    tint = listTint(nb.color),
                    mark = { SharedMark(nb.shared, nb.isOwner, nb.owner?.name) },
                )
            }
        }
        // the gear first: bottom left (mockup)
        GcDrawerBar(listOf(GcDrawerIcon(GcIcons.Settings, R.string.notizen_settings, actions.settings)))
    }
}

@Composable
private fun PlusButton(label: String, onClick: () -> Unit) {
    GcIconButton(GcIcons.Plus, label, onClick, size = 44.dp, iconSize = 20.dp, tint = MaterialTheme.colorScheme.primary)
}

/** „+“ at „Notizbücher“: a notebook, or (where allowed) a Tresor notebook. */
@Composable
private fun NotebookPlus(actions: NotizenDrawerActions) {
    var open by remember { mutableStateOf(false) }
    val tresor = actions.newTresor
    Box {
        PlusButton(stringResource(R.string.notizen_new_notebook)) { if (tresor == null) actions.newNotebook() else open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.notizen_new_notebook)) }, onClick = { open = false; actions.newNotebook() }, leadingIcon = { Icon(GcIcons.Note, null) })
            if (tresor != null) DropdownMenuItem(text = { Text(stringResource(R.string.notizen_new_tresor)) }, onClick = { open = false; tresor() }, leadingIcon = { Icon(GcIcons.Lock, null) })
        }
    }
}

/** A row of the menu: icon, name, its mark, the count; highlighted when it is shown now. A long press opens its menu. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NavRow(
    icon: ImageVector?,
    name: String,
    count: Int?,
    selected: Boolean,
    onClick: () -> Unit,
    onLong: (() -> Unit)? = null,
    depth: Int = 0,
    tint: Color? = null,
    lead: (@Composable () -> Unit)? = null,
    mark: (@Composable () -> Unit)? = null,
) {
    val x = GateControlTheme.extraColors
    val menuLabel = stringResource(R.string.notizen_menu_of, name)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .then(if (selected) Modifier.background(x.accentBg) else Modifier)
            .combinedClickable(role = Role.Button, onClick = onClick, onLongClick = onLong, onLongClickLabel = if (onLong != null) menuLabel else null)
            .heightIn(min = 48.dp)
            .padding(start = (12 + 20 * depth).dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when {
            lead != null -> lead()
            icon != null -> Icon(icon, null, tint = if (selected) x.accentText else tint ?: x.muted, modifier = Modifier.size(20.dp))
        }
        Text(
            name,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) x.accentText else MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        mark?.invoke()
        if (count != null && count > 0) Text(count.toString(), style = MaterialTheme.typography.labelLarge, color = x.muted)
    }
}

@Composable
private fun ListRow(l: NList, depth: Int, meId: Long?, onClick: () -> Unit, onLong: (() -> Unit)?) {
    NavRow(
        listIconVector(l.icon),
        l.name,
        l.open,
        false,
        onClick,
        onLong = onLong,
        depth = depth,
        tint = listTint(l.color),
        mark = { SharedMark(l.shared, l.isOwner, l.owner?.name) },
    )
}

/** Shared: the people icon (I shared it) or the owner's little picture (shared with me). */
@Composable
private fun SharedMark(shared: Boolean, mine: Boolean, owner: String?) {
    if (!shared) return
    if (mine) {
        Icon(GcIcons.Users, stringResource(R.string.notizen_shared_mark), tint = GateControlTheme.extraColors.muted, modifier = Modifier.size(16.dp))
    } else {
        val label = stringResource(R.string.notizen_shared_by, owner.orEmpty())
        NAvatar(owner, 20.dp, modifier = Modifier.semantics { contentDescription = label })
    }
}

/** A group: the arrow and the name open and close it, the count is all its open tasks. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupRow(r: NotizenLists.Row.Group, onToggle: () -> Unit, onLong: (() -> Unit)?) {
    val x = GateControlTheme.extraColors
    val label = stringResource(if (r.expanded) R.string.notizen_group_collapse else R.string.notizen_group_expand, r.group.name)
    val menuLabel = stringResource(R.string.notizen_menu_of, r.group.name)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(role = Role.Button, onClickLabel = label, onClick = onToggle, onLongClick = onLong, onLongClickLabel = if (onLong != null) menuLabel else null)
            .heightIn(min = 48.dp)
            .padding(start = 12.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(if (r.expanded) GcIcons.ChevronDown else GcIcons.ChevronRight, null, tint = x.muted, modifier = Modifier.size(20.dp))
        Text(r.group.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (r.open > 0) Text(r.open.toString(), style = MaterialTheme.typography.labelLarge, color = x.muted)
    }
}

/** The two letters of a source in its colours („Fa“ Familie, „Ma“ Mail). */
@Composable
private fun SourceMark(plugin: String, label: String) {
    val (bg, fg) = sourceColors(plugin)
    Box(Modifier.size(24.dp).clip(RoundedCornerShape(7.dp)).background(bg), contentAlignment = Alignment.Center) {
        Text(label.take(2), color = fg, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}

