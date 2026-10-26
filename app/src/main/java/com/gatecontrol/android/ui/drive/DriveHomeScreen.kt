package com.gatecontrol.android.ui.drive

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.gatecontrol.android.drive.DriveLogic
import com.gatecontrol.android.drive.DriveTransfers
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcChevron
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

/** The Drive tab (mockup "Android – Drive"): the areas, the uploads of this session, the newest files. */
@Composable
fun DriveHomeScreen(nav: DriveNav, viewModel: DriveHomeViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val uploads by viewModel.transfers.uploads.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var conflict by remember { mutableStateOf<DriveTransfers.Upload?>(null) }
    var firstResume by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        if (!firstResume) viewModel.refresh(quiet = true)
        firstResume = false
        onPauseOrDispose { }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GcScreenTitle(stringResource(R.string.drive_title), modifier = Modifier.weight(1f))
            GcIconButton(GcIcons.Search, contentDescription = stringResource(R.string.drive_search), onClick = nav.search, tint = MaterialTheme.colorScheme.onBackground)
            GcIconButton(GcIcons.Trash, contentDescription = stringResource(R.string.drive_trash), onClick = nav.trash, tint = MaterialTheme.colorScheme.onBackground)
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            UploadsCard(uploads, onConflict = { conflict = it }, onClear = viewModel.transfers::clearFinished)
            when (val s = state) {
                DriveLoad.Loading -> DriveLoading()
                is DriveLoad.Error -> DriveErrorBox(s.code, onRetry = { viewModel.refresh() })
                is DriveLoad.Ready -> {
                    val home = s.value
                    when {
                        !home.me.licenceValid -> DriveErrorBox("not_licensed", null)
                        home.me.seat == null -> DriveErrorBox("no_seat", null)
                        home.areas.isEmpty() -> Text(stringResource(R.string.drive_no_areas), color = GateControlTheme.extraColors.muted)
                        else -> {
                            AreasCard(home.areas, onOpen = { nav.folder(it.rootId) })
                            if (home.recent.isNotEmpty()) {
                                GcSectionLabel(stringResource(R.string.drive_recent), modifier = Modifier.padding(top = 4.dp, start = 4.dp))
                                home.recent.chunked(3).forEach { row ->
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        row.forEach { n ->
                                            RecentTile(n, modifier = Modifier.weight(1f), onClick = {
                                                Toast.makeText(context, R.string.drive_loading_file, Toast.LENGTH_SHORT).show()
                                                scope.launch {
                                                    fetchThen(context, n, viewModel::fetch, then = { f -> openFile(context, n, f) }) { code ->
                                                        Toast.makeText(context, driveText(context, code), Toast.LENGTH_LONG).show()
                                                    }
                                                }
                                            })
                                        }
                                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    conflict?.let { u -> ConflictDialog(u, onChoice = { viewModel.transfers.resolve(u.id, it); conflict = null }, onDismiss = { conflict = null }) }
}

@Composable
private fun AreasCard(areas: List<DArea>, onOpen: (DArea) -> Unit) {
    GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        areas.forEachIndexed { i, a ->
            if (i > 0) GcRowDivider()
            GcListRow(
                title = a.label,
                description = areaLine(a),
                onClick = { onOpen(a) },
                leading = { AreaIcon(a) },
                trailing = { GcChevron() },
            )
        }
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
