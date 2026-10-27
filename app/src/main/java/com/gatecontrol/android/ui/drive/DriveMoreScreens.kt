package com.gatecontrol.android.ui.drive

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.drive.DNode
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.components.GcTextField
import com.gatecontrol.android.ui.theme.GateControlTheme
import kotlinx.coroutines.launch

/** The trash of every area the person writes to: restore or delete for good. */
@Composable
fun DriveTrashScreen(onBack: () -> Unit, viewModel: DriveTrashViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var purging by remember { mutableStateOf<DNode?>(null) }
    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, driveText(context, it), Toast.LENGTH_SHORT).show()
            viewModel.clearMessage()
        }
    }
    Column(Modifier.fillMaxSize()) {
        GcSubpageBar(stringResource(R.string.drive_trash), onBack = onBack, backDescription = stringResource(R.string.drive_back))
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (val s = state) {
                DriveLoad.Loading -> DriveLoading()
                is DriveLoad.Error -> DriveErrorBox(s.code, onRetry = viewModel::refresh)
                is DriveLoad.Ready -> if (s.value.isEmpty()) {
                    Text(stringResource(R.string.drive_trash_empty), color = GateControlTheme.extraColors.muted, modifier = Modifier.padding(4.dp))
                } else {
                    GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                        s.value.forEachIndexed { i, n ->
                            if (i > 0) GcRowDivider()
                            val from = n.from?.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.drive_trash_from, it) }
                            NodeRow(
                                node = n,
                                onClick = null,
                                description = nodeLine(n, from),
                                trailing = {
                                    Row {
                                        TextButton(onClick = { viewModel.restore(n) }) { Text(stringResource(R.string.drive_trash_restore)) }
                                        GcIconButton(GcIcons.Trash, contentDescription = stringResource(R.string.drive_trash_purge), onClick = { purging = n }, size = 40.dp, iconSize = 18.dp)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
    purging?.let { n ->
        AlertDialog(
            onDismissRequest = { purging = null },
            text = { Text(stringResource(R.string.drive_trash_purge_confirm, n.name)) },
            confirmButton = { TextButton(onClick = { purging = null; viewModel.purge(n) }) { Text(stringResource(R.string.drive_trash_purge)) } },
            dismissButton = { TextButton(onClick = { purging = null }) { Text(stringResource(R.string.drive_cancel)) } },
        )
    }
}

/** Search by name across the areas; a folder opens, a file opens in an app. */
@Composable
fun DriveSearchScreen(onBack: () -> Unit, onFolder: (Long) -> Unit, viewModel: DriveSearchViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var q by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        GcSubpageBar(stringResource(R.string.drive_search), onBack = onBack, backDescription = stringResource(R.string.drive_back))
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GcTextField(
                value = q,
                onValueChange = { q = it.take(100); viewModel.search(q) },
                label = stringResource(R.string.drive_search_hint),
            )
            when (val s = state) {
                null -> if (q.isNotBlank()) Text(stringResource(R.string.drive_search_min), color = GateControlTheme.extraColors.muted)
                DriveLoad.Loading -> DriveLoading()
                is DriveLoad.Error -> DriveErrorBox(s.code, onRetry = { viewModel.search(q) })
                is DriveLoad.Ready -> if (s.value.isEmpty()) {
                    Text(stringResource(R.string.drive_search_empty), color = GateControlTheme.extraColors.muted)
                } else {
                    GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                        s.value.forEachIndexed { i, n ->
                            if (i > 0) GcRowDivider()
                            NodeRow(
                                node = n,
                                // a hit in a scan's recognised text shows the words around it
                                description = n.snippet?.let { "„$it“" } ?: nodeLine(n, n.path?.substringBeforeLast('/', "")?.takeIf { it.isNotEmpty() }),
                                onClick = {
                                    if (n.isDir) {
                                        onFolder(n.id)
                                    } else {
                                        scope.launch {
                                            fetchThen(context, n, viewModel::fetch, then = { f -> openFile(context, n, f) }) { code ->
                                                Toast.makeText(context, driveText(context, code), Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
