package com.gatecontrol.android.ui.drive

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.drive.DArea
import com.gatecontrol.android.drive.DNode
import com.gatecontrol.android.drive.DriveLogic
import com.gatecontrol.android.drive.DriveTransfers
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIconSquare
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcListRow
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcScreenTitle
import com.gatecontrol.android.ui.components.GcSectionLabel
import com.gatecontrol.android.ui.theme.GateControlTheme
import kotlinx.coroutines.launch

/** Where the Drive tab leads. */
data class DriveNav(
    val folder: (Long) -> Unit,
    val search: () -> Unit,
    val trash: () -> Unit,
)

/**
 * The Drive tab: the chosen area's files ("Zuletzt" above them), the areas and the trash in the menu top left. The
 * photo backup is in the settings.
 */
@Composable
fun DriveHomeScreen(nav: DriveNav, viewModel: DriveHomeViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val folderViewModel: DriveFolderViewModel = hiltViewModel(key = "drive-tab")
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    var firstResume by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        if (!firstResume) viewModel.refresh(quiet = true)
        firstResume = false
        onPauseOrDispose { }
    }
    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }

    val home = (state as? DriveLoad.Ready)?.value
    val usable = home != null && home.me.licenceValid && home.me.seat != null && home.areas.isNotEmpty()
    val current = if (usable) home?.let { h -> h.areas.firstOrNull { it.id == selected } ?: viewModel.current(h.areas) } else null
    LaunchedEffect(current?.rootId) { current?.let { folderViewModel.open(it.rootId) } }
    val openMenu: () -> Unit = { scope.launch { drawer.open() } }

    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = drawer.isOpen,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.background) {
                DriveDrawer(
                    areas = home?.areas.orEmpty().takeIf { usable }.orEmpty(),
                    current = current,
                    onArea = { a -> viewModel.select(a); scope.launch { drawer.close() } },
                    onTrash = { scope.launch { drawer.close() }; nav.trash() },
                )
            }
        },
    ) {
        if (home != null && current != null) {
            DriveFolderContent(
                viewModel = folderViewModel,
                onFolder = nav.folder,
                topBar = { _, actions -> TabBar(current.label, onMenu = openMenu, onSearch = nav.search, actions = actions) },
                header = {
                    RecentRow(home.recent) { n ->
                        Toast.makeText(context, R.string.drive_loading_file, Toast.LENGTH_SHORT).show()
                        scope.launch {
                            fetchThen(context, n, viewModel::fetch, then = { f -> openFile(context, n, f) }) { code ->
                                Toast.makeText(context, driveText(context, code), Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                },
            )
        } else {
            Column(Modifier.fillMaxSize()) {
                TabBar(stringResource(R.string.drive_title), onMenu = null, onSearch = null)
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    when (val s = state) {
                        DriveLoad.Loading -> DriveLoading()
                        is DriveLoad.Error -> DriveErrorBox(s.code, onRetry = { viewModel.refresh() })
                        is DriveLoad.Ready -> when {
                            !s.value.me.licenceValid -> DriveErrorBox("not_licensed", null)
                            s.value.me.seat == null -> DriveErrorBox("no_seat", null)
                            else -> Text(stringResource(R.string.drive_no_areas), color = GateControlTheme.extraColors.muted)
                        }
                    }
                }
            }
        }
    }
}

/** Menu, title (the area), sort, search. */
@Composable
private fun TabBar(title: String, onMenu: (() -> Unit)?, onSearch: (() -> Unit)?, actions: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onMenu != null) {
            GcIconButton(GcIcons.Menu, contentDescription = stringResource(R.string.drive_menu), onClick = onMenu, tint = MaterialTheme.colorScheme.onBackground)
        } else {
            Spacer(Modifier.width(12.dp))
        }
        GcScreenTitle(title, modifier = Modifier.weight(1f).padding(start = 4.dp))
        actions()
        if (onSearch != null) GcIconButton(GcIcons.Search, contentDescription = stringResource(R.string.drive_search), onClick = onSearch, tint = MaterialTheme.colorScheme.onBackground)
    }
}

/** The menu: the areas (with their storage) and the trash. */
@Composable
private fun DriveDrawer(areas: List<DArea>, current: DArea?, onArea: (DArea) -> Unit, onTrash: () -> Unit) {
    Column(Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = 16.dp)) {
        GcScreenTitle(stringResource(R.string.drive_title), modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        if (areas.isNotEmpty()) {
            GcSectionLabel(stringResource(R.string.drive_move_areas), modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp))
            areas.forEach { a ->
                GcListRow(
                    title = a.label,
                    description = areaLine(a),
                    onClick = { onArea(a) },
                    leading = { AreaIcon(a) },
                    selected = a.id == current?.id,
                )
            }
            GcRowDivider(modifier = Modifier.padding(vertical = 8.dp))
        }
        GcListRow(
            title = stringResource(R.string.drive_trash),
            onClick = onTrash,
            leading = {
                GcIconSquare(size = 40.dp, background = GateControlTheme.extraColors.panel2) {
                    Icon(GcIcons.Trash, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
                }
            },
        )
    }
}

/** "Zuletzt": the newest files as tiles. */
@Composable
private fun RecentRow(recent: List<DNode>, onOpen: (DNode) -> Unit) {
    if (recent.isEmpty()) return
    GcSectionLabel(stringResource(R.string.drive_recent), modifier = Modifier.padding(top = 4.dp, start = 4.dp))
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        recent.forEach { n -> RecentTile(n, modifier = Modifier.width(112.dp), onClick = { onOpen(n) }) }
    }
}

@Composable
internal fun AreaIcon(a: DArea, size: Int = 40) {
    val x = GateControlTheme.extraColors
    val (icon, fg, bg) = when (a.kind) {
        "family" -> Triple(GcIcons.Family, x.accentText, x.accentBg)
        "child" -> Triple(GcIcons.School, x.warn, x.warnBg)
        "team" -> Triple(GcIcons.Briefcase, x.blue, x.blueBg)
        else -> Triple(GcIcons.User, MaterialTheme.colorScheme.onSurface, x.panel2)
    }
    GcIconSquare(size = size.dp, background = bg) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size((size / 2).dp))
    }
}


/** "38 GB · nur für dich" — what an area is and how full it is. */
@Composable
private fun areaLine(a: DArea): String {
    val usage = DriveLogic.usage(a.storage, stringResource(R.string.drive_of))
    val what = when {
        a.archived -> stringResource(R.string.drive_area_archived)
        a.kind == "mine" -> stringResource(R.string.drive_area_mine)
        a.kind == "family" -> stringResource(R.string.drive_area_family)
        a.kind == "child" && a.via == "parent" -> stringResource(R.string.drive_area_child_parent)
        a.kind == "child" -> stringResource(R.string.drive_area_child_own)
        a.kind == "team" && !a.writable -> stringResource(R.string.drive_area_team_read)
        else -> stringResource(R.string.drive_area_team)
    }
    return listOfNotNull(usage, what).joinToString(" · ")
}

/** A picked file's name is taken: new version, keep both or skip. */
@Composable
internal fun ConflictDialog(u: DriveTransfers.Upload, onChoice: (String?) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.drive_conflict_title, u.name)) },
        text = { Text(stringResource(R.string.drive_conflict_text)) },
        confirmButton = {
            Row {
                TextButton(onClick = { onChoice(null) }) { Text(stringResource(R.string.drive_conflict_skip)) }
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = { onChoice(DriveLogic.ON_CONFLICT_RENAME) }) { Text(stringResource(R.string.drive_conflict_keep)) }
                TextButton(onClick = { onChoice(DriveLogic.ON_CONFLICT_VERSION) }) { Text(stringResource(R.string.drive_conflict_version)) }
            }
        },
    )
}
