// FILE: app/src/main/java/com/gatecontrol/android/ui/settings/NetworkGroupListScreen.kt
//
// 替代原来嵌入 SettingsScreen 内的 NetworkPresetsSection。
// 现在 SettingsScreen 中的 "Network Presets" 区域改为一个
// "Manage Network Groups ›" 行，点击导航到这个全屏页面。

package com.gatecontrol.android.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.data.db.NetworkGroupWithCidrs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkGroupListScreen(
    adminLocked: Boolean,
    onNavigateToEdit: (groupId: Long, groupName: String) -> Unit,
    onBack: () -> Unit,
    viewModel: NetworkGroupListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    // ── File picker for import ──
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) viewModel.importGroup(context, uri)
    }

    // ── Show snackbar ──
    LaunchedEffect(state.snackbar) {
        state.snackbar?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearSnackbar()
        }
    }

    // ── New group dialog ──
    var showNewGroupDialog by remember { mutableStateOf(false) }
    if (showNewGroupDialog) {
        NewGroupDialog(
            onDismiss = { showNewGroupDialog = false },
            onCreate = { name ->
                viewModel.createGroup(name)
                showNewGroupDialog = false
            },
        )
    }

    // ── Delete confirmation ──
    var pendingDeleteId by remember { mutableStateOf<Long?>(null) }
    if (pendingDeleteId != null) {
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = { Text("Delete group?") },
            text = { Text("All CIDRs in this group will be permanently deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteGroup(pendingDeleteId!!)
                    pendingDeleteId = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteId = null }) { Text("Cancel") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Network Groups") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (!adminLocked) {
                        // Import button
                        IconButton(onClick = { importLauncher.launch("*/*") }) {
                            Icon(Icons.Default.FileDownload, contentDescription = "Import group")
                        }
                        // New group button
                        IconButton(onClick = { showNewGroupDialog = true }) {
                            Icon(Icons.Default.Add, contentDescription = "New group")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (state.isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        if (state.groups.isEmpty()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Lan,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "No network groups yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Tap + to create a group, or import a .sqlite3 file",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            return@Scaffold
        }

        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 80.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.groups, key = { it.group.id }) { gwc ->
                NetworkGroupCard(
                    gwc = gwc,
                    adminLocked = adminLocked,
                    onToggleEnabled = { viewModel.setGroupEnabled(gwc.group.id, it) },
                    onEdit = { onNavigateToEdit(gwc.group.id, gwc.group.name) },
                    onDelete = { pendingDeleteId = gwc.group.id },
                )
            }
        }
    }
}

// ── Group card ────────────────────────────────────────────────────────────────

@Composable
private fun NetworkGroupCard(
    gwc: NetworkGroupWithCidrs,
    adminLocked: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onEdit,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    gwc.group.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${gwc.cidrs.size} CIDR${if (gwc.cidrs.size != 1) "s" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Preview first 2 CIDRs
                if (gwc.cidrs.isNotEmpty()) {
                    Text(
                        gwc.cidrs.take(2).joinToString("  ") { it.cidr } +
                            if (gwc.cidrs.size > 2) "  …" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = gwc.group.enabled,
                onCheckedChange = { if (!adminLocked) onToggleEnabled(it) },
                enabled = !adminLocked,
            )
            if (!adminLocked) {
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete group",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

// ── New group dialog ──────────────────────────────────────────────────────────

@Composable
private fun NewGroupDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Network Group") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Group name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onCreate(name) },
                enabled = name.isNotBlank(),
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
