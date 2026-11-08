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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.drive.DMe
import com.gatecontrol.android.drive.DNode
import com.gatecontrol.android.drive.DriveLogic
import com.gatecontrol.android.drive.DriveOfflineCopy
import com.gatecontrol.android.drive.DriveOfflineStoreLogic
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcSecondaryButton
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.scan.rememberScanAsk
import com.gatecontrol.android.ui.theme.GateControlTheme
import kotlinx.coroutines.launch

/**
 * The menu entry "Offline verfügbar" / "Nicht mehr offline verfügbar" of a file or folder (Drive 1.20, feature
 * `offline`); null while Drive does not have it. The mark keeps a copy on this phone ([DriveOfflineStore]) and, with
 * GateControl's home cache, the file ready on the home gateway too — the description says which.
 */
@Composable
internal fun offlineItem(me: DMe?, node: DNode, marked: Boolean, onSet: (DNode, Boolean) -> Unit): MoreItem? {
    if (me?.has(DMe.FEATURE_OFFLINE) != true) return null
    return if (marked) {
        MoreItem(GcIcons.DownloadTray, stringResource(R.string.drive_offline_unmark), stringResource(R.string.drive_offline_unmark_desc)) { onSet(node, false) }
    } else {
        val desc = if (me.has(DMe.FEATURE_HOME_CACHE)) R.string.drive_offline_mark_desc else R.string.drive_offline_mark_desc_later
        MoreItem(GcIcons.DownloadTray, stringResource(R.string.drive_offline_mark), stringResource(desc)) { onSet(node, true) }
    }
}

/**
 * "Offline verfügbar" (Drive 1.20): the files and folders the person marked, newest first, each with the state of its
 * copy on this phone (on the phone, waiting, older version, failed). A folder opens, a file opens in an app (from the
 * copy here when there is one), the cross takes the mark off and deletes the copy. Without a connection the list is
 * the copies on this phone, which open and share as usual.
 */
@Composable
fun DriveOfflineScreen(onBack: () -> Unit, onFolder: (Long) -> Unit, viewModel: DriveOfflineViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val me by viewModel.live.me.collectAsStateWithLifecycle()
    val copies by viewModel.store.copies.collectAsStateWithLifecycle()
    val running by viewModel.store.running.collectAsStateWithLifecycle()
    val progress by viewModel.store.progress.collectAsStateWithLifecycle()
    val noConnection by viewModel.noConnection.collectAsStateWithLifecycle()
    val values by viewModel.values.collectAsStateWithLifecycle()
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
    val open: (DNode, Boolean) -> Unit = { n, send ->
        ask(n.scan) {
            scope.launch {
                fetchThen(context, n, viewModel::fetch, then = { f -> if (send) sendFile(context, n, f) else openFile(context, n, f) }) { code ->
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
            if (noConnection) {
                GcBanner(tone = GcTone.Warn, icon = GcIcons.Info) {
                    Text(stringResource(R.string.drive_offline_no_connection), style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                GcBanner(tone = GcTone.Info, icon = GcIcons.DownloadTray) {
                    val intro = if (me?.has(DMe.FEATURE_HOME_CACHE) == false) R.string.drive_offline_intro_later else R.string.drive_offline_intro
                    Text(stringResource(intro), style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (copies.isNotEmpty()) {
                OfflineSummary(
                    used = DriveOfflineStoreLogic.used(copies.values),
                    limitMb = values.offlineLimitMb,
                    running = running,
                    waitsForWifi = copies.values.any { it.reason == DriveOfflineStoreLogic.WAIT_WIFI },
                    onRefresh = if (noConnection) null else viewModel.store::request,
                )
            }
            if (noConnection) {
                val list = copies.values.filter { it.hasFile }.sortedBy { it.name.lowercase() }
                GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    list.forEachIndexed { i, c ->
                        if (i > 0) GcRowDivider()
                        val n = c.asNode()
                        NodeRow(
                            node = n,
                            onClick = { open(n, false) },
                            description = listOfNotNull(c.path, copyLine(c)).joinToString(" · "),
                            trailing = {
                                GcIconButton(GcIcons.Share, contentDescription = stringResource(R.string.drive_send) + ": " + c.name, onClick = { open(n, true) }, size = 44.dp, iconSize = 18.dp)
                            },
                        )
                    }
                }
            } else when (val s = state) {
                DriveLoad.Loading -> DriveLoading()
                is DriveLoad.Error -> DriveErrorBox(s.code, onRetry = viewModel::refresh)
                is DriveLoad.Ready -> if (s.value.isEmpty()) {
                    Text(stringResource(R.string.drive_offline_empty), color = x.muted, modifier = Modifier.padding(4.dp))
                } else {
                    GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                        s.value.forEachIndexed { i, n ->
                            if (i > 0) GcRowDivider()
                            val place = n.path?.substringBeforeLast('/', "")?.takeIf { it.isNotEmpty() }
                            NodeRow(
                                node = n,
                                onClick = {
                                    when {
                                        // a vault's content opens on the vault's own pages only
                                        n.encrypted -> Unit
                                        n.isDir -> onFolder(n.id)
                                        else -> open(n, false)
                                    }
                                },
                                description = listOfNotNull(nodeLine(n, place), markLine(n, copies.values)).joinToString(" · "),
                                progress = progress?.takeIf { (id, _) -> id == n.id || copies[id]?.roots?.contains(n.id) == true }?.second,
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

/** "1,2 GB von 2 GB auf diesem Handy", what is going on, and "Jetzt aktualisieren". */
@Composable
private fun OfflineSummary(used: Long, limitMb: Int, running: Boolean, waitsForWifi: Boolean, onRefresh: (() -> Unit)?) {
    val x = GateControlTheme.extraColors
    GcCard(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            stringResource(R.string.drive_offline_used, DriveLogic.size(used), DriveLogic.size(limitMb.toLong() * 1024 * 1024)),
            style = MaterialTheme.typography.titleSmall,
        )
        val status = when {
            running -> R.string.drive_offline_running
            waitsForWifi -> R.string.drive_offline_wait_wifi_all
            else -> null
        }
        status?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = x.muted) }
        if (onRefresh != null) {
            GcSecondaryButton(text = stringResource(R.string.drive_offline_refresh), onClick = onRefresh, enabled = !running, fillWidth = false)
        }
    }
}

/** The state of one copy as a few words. */
@Composable
private fun copyLine(c: DriveOfflineCopy): String = when (c.state) {
    DriveOfflineCopy.State.STORED -> stringResource(R.string.drive_offline_state_stored)
    DriveOfflineCopy.State.OUTDATED -> stringResource(if (c.reason == DriveOfflineStoreLogic.WAIT_WIFI) R.string.drive_offline_state_outdated_wifi else R.string.drive_offline_state_outdated)
    DriveOfflineCopy.State.FAILED -> stringResource(R.string.drive_offline_state_failed)
    DriveOfflineCopy.State.PENDING -> stringResource(
        when (c.reason) {
            DriveOfflineStoreLogic.WAIT_WIFI -> R.string.drive_offline_state_wifi
            DriveOfflineStoreLogic.WAIT_SPACE -> R.string.drive_offline_state_space
            DriveOfflineStoreLogic.WAIT_DEVICE_SPACE -> R.string.drive_offline_state_device_space
            else -> R.string.drive_offline_state_pending
        },
    )
}

/** How a mark stands on this phone: a file like its copy, a folder "12 von 14 Dateien auf dem Gerät"; null: not known yet. */
@Composable
private fun markLine(n: DNode, copies: Collection<DriveOfflineCopy>): String? {
    if (n.encrypted) return stringResource(R.string.drive_offline_state_vault)
    val m = DriveOfflineStoreLogic.markState(n.id, copies) ?: return if (n.isDir) null else stringResource(R.string.drive_offline_state_pending)
    if (!n.isDir) return copies.firstOrNull { it.id == n.id }?.let { copyLine(it) }
    val head = pluralStringResource(R.plurals.drive_offline_folder_stored, m.total, m.stored, m.total)
    val more = when {
        m.outdated > 0 -> stringResource(R.string.drive_offline_state_outdated)
        m.pending > 0 && m.reason == DriveOfflineStoreLogic.WAIT_WIFI -> stringResource(R.string.drive_offline_state_wifi)
        m.pending > 0 && m.reason == DriveOfflineStoreLogic.WAIT_SPACE -> stringResource(R.string.drive_offline_state_space)
        m.pending > 0 && m.reason == DriveOfflineStoreLogic.WAIT_DEVICE_SPACE -> stringResource(R.string.drive_offline_state_device_space)
        m.failed > 0 -> stringResource(R.string.drive_offline_state_failed)
        else -> null
    }
    return listOfNotNull(head, more).joinToString(", ")
}
