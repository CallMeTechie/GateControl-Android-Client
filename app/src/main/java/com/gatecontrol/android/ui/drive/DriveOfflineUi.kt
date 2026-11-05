package com.gatecontrol.android.ui.drive

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.drive.DMe
import com.gatecontrol.android.drive.DNode
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.scan.rememberScanAsk
import com.gatecontrol.android.ui.theme.GateControlTheme
import kotlinx.coroutines.launch

/**
 * The menu entry "Offline verfügbar" / "Nicht mehr offline verfügbar" of a file or folder (Drive 1.20, feature
 * `offline`); null while Drive does not have it. The app keeps no copies of its own: the mark means "kept ready in the
 * home network's cache", which the description says (and, without GateControl's cache yet, "vorgemerkt").
 */
@Composable
internal fun offlineItem(me: DMe?, node: DNode, marked: Boolean, onSet: (DNode, Boolean) -> Unit): MoreItem? {
    if (me?.has(DMe.FEATURE_OFFLINE) != true) return null
    return if (marked) {
        MoreItem(GcIcons.House, stringResource(R.string.drive_offline_unmark), stringResource(R.string.drive_offline_unmark_desc)) { onSet(node, false) }
    } else {
        val desc = if (me.has(DMe.FEATURE_HOME_CACHE)) R.string.drive_offline_mark_desc else R.string.drive_offline_mark_desc_later
        MoreItem(GcIcons.House, stringResource(R.string.drive_offline_mark), stringResource(desc)) { onSet(node, true) }
    }
}

/**
 * "Offline verfügbar" (Drive 1.20): the files and folders the person marked, newest first. A folder opens, a file
 * opens in an app (at home from the gateway's cache), the cross takes the mark off.
 */
@Composable
fun DriveOfflineScreen(onBack: () -> Unit, onFolder: (Long) -> Unit, viewModel: DriveOfflineViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val me by viewModel.live.me.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val x = GateControlTheme.extraColors
    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, driveText(context, it), Toast.LENGTH_SHORT).show()
            viewModel.clearMessage()
        }
    }
    val ask = rememberScanAsk()
    val open: (DNode) -> Unit = { n ->
        ask(n.scan) {
            scope.launch {
                fetchThen(context, n, viewModel::fetch, then = { f -> openFile(context, n, f) }) { code ->
                    Toast.makeText(context, driveText(context, code), Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        GcSubpageBar(stringResource(R.string.drive_offline_title), onBack = onBack, backDescription = stringResource(R.string.drive_back))
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GcBanner(tone = GcTone.Info, icon = GcIcons.House) {
                val intro = if (me?.has(DMe.FEATURE_HOME_CACHE) == false) R.string.drive_offline_intro_later else R.string.drive_offline_intro
                Text(stringResource(intro), style = MaterialTheme.typography.bodyMedium)
            }
            when (val s = state) {
                DriveLoad.Loading -> DriveLoading()
                is DriveLoad.Error -> DriveErrorBox(s.code, onRetry = viewModel::refresh)
                is DriveLoad.Ready -> if (s.value.isEmpty()) {
                    Text(stringResource(R.string.drive_offline_empty), color = x.muted, modifier = Modifier.padding(4.dp))
                } else {
                    GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                        s.value.forEachIndexed { i, n ->
                            if (i > 0) GcRowDivider()
                            NodeRow(
                                node = n,
                                onClick = {
                                    when {
                                        // a vault's content opens on the vault's own pages only
                                        n.encrypted -> Unit
                                        n.isDir -> onFolder(n.id)
                                        else -> open(n)
                                    }
                                },
                                description = nodeLine(n, n.path?.substringBeforeLast('/', "")?.takeIf { it.isNotEmpty() }),
                                trailing = {
                                    GcIconButton(
                                        GcIcons.Close,
                                        contentDescription = stringResource(R.string.drive_offline_unmark) + ": " + nodeTitle(n),
                                        onClick = { viewModel.unmark(n) },
                                        size = 44.dp,
                                        iconSize = 18.dp,
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
