// FILE: app/src/main/java/com/gatecontrol/android/ui/settings/NetworkGroupEditScreen.kt
//
// 单个分组的 CIDR 管理页面。功能：
//   • 搜索 / 删除 / 添加 CIDR
//   • 批量粘贴（每行一个 CIDR）
//   • 导出当前分组为 SQLite 文件（系统分享）

package com.gatecontrol.android.ui.settings

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.data.db.NetworkCidrEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkGroupEditScreen(
    groupId: Long,
    groupName: String,
    adminLocked: Boolean,
    onBack: () -> Unit,
    viewModel: NetworkGroupEditViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val keyboardController = LocalSoftwareKeyboardController.current

    // Load group name into VM once
    LaunchedEffect(groupName) {
        viewModel.loadGroupName(groupName)
    }

    // Show snackbar
    LaunchedEffect(state.snackbar) {
        state.snackbar?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearSnackbar()
        }
    }

    // Share export file via system sheet
    LaunchedEffect(state.exportFile) {
        state.exportFile?.let { file ->
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                file,
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Export network group"))
            viewModel.clearExportFile()
        }
    }

    var showAddSingleDialog by remember { mutableStateOf(false) }
    var showBulkDialog by remember { mutableStateOf(false) }
    var pendingDeleteCidr by remember { mutableStateOf<NetworkCidrEntity?>(null) }

    // ── Dialogs ──────────────────────────────────────────────────────────

    if (showAddSingleDialog) {
        AddSingleCidrDialog(
            onDismiss = { showAddSingleDialog = false },
            onAdd = { cidr, label ->
                viewModel.addCidr(cidr, label)
                showAddSingleDialog = false
            },
        )
    }

    if (showBulkDialog) {
        BulkAddCidrDialog(
            onDismiss = { showBulkDialog = false },
            onAdd = { rawText ->
                viewModel.addCidrsBulk(rawText)
                showBulkDialog = false
            },
        )
    }

    pendingDeleteCidr?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDeleteCidr = null },
            title = { Text("Remove CIDR?") },
            text = { Text(target.cidr) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteCidr(target)
                    pendingDeleteCidr = null
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteCidr = null }) { Text("Cancel") }
            },
        )
    }

    // ── Screen ───────────────────────────────────────────────────────────

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(groupName, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${state.allCidrs.size} CIDRs",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Export
                    IconButton(onClick = { viewModel.exportGroup() }) {
                        Icon(Icons.Default.FileUpload, contentDescription = "Export group")
                    }
                    if (!adminLocked) {
                        // Bulk add
                        IconButton(onClick = { showBulkDialog = true }) {
                            Icon(Icons.Default.PlaylistAdd, contentDescription = "Bulk add")
                        }
                        // Single add
                        IconButton(onClick = { showAddSingleDialog = true }) {
                            Icon(Icons.Default.Add, contentDescription = "Add CIDR")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->

        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // ── Search bar ─────────────────────────────────────────────
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = viewModel::onSearchQueryChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Search CIDRs or labels…") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onSearchQueryChanged("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
            )

            if (state.isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@Column
            }

            if (state.filteredCidrs.isEmpty()) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (state.searchQuery.isNotEmpty())
                            "No CIDRs match \"${state.searchQuery}\""
                        else
                            "No CIDRs yet — tap + to add",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@Column
            }

            // ── CIDR list ──────────────────────────────────────────────
            LazyColumn(
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp,
                    top = 4.dp, bottom = 80.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(state.filteredCidrs, key = { it.id }) { entry ->
                    CidrRow(
                        cidr = entry,
                        adminLocked = adminLocked,
                        onDelete = { pendingDeleteCidr = entry },
                    )
                }
            }
        }
    }
}

// ── CIDR row ──────────────────────────────────────────────────────────────────

@Composable
private fun CidrRow(
    cidr: NetworkCidrEntity,
    adminLocked: Boolean,
    onDelete: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        tonalElevation = 1.dp,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Lan,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(cidr.cidr, style = MaterialTheme.typography.bodyMedium)
                if (cidr.label.isNotEmpty()) {
                    Text(
                        cidr.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!adminLocked) {
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Remove",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

// ── Add single CIDR dialog ────────────────────────────────────────────────────

@Composable
private fun AddSingleCidrDialog(
    onDismiss: () -> Unit,
    onAdd: (cidr: String, label: String) -> Unit,
) {
    var cidr by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var cidrError by remember { mutableStateOf<String?>(null) }
    val cidrRegex = Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}/\d{1,2}$""")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add CIDR") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = cidr,
                    onValueChange = { cidr = it; cidrError = null },
                    label = { Text("CIDR") },
                    placeholder = { Text("e.g. 192.168.1.0/24") },
                    singleLine = true,
                    isError = cidrError != null,
                    supportingText = cidrError?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        imeAction = ImeAction.Next,
                    ),
                )
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label (optional)") },
                    placeholder = { Text("e.g. Printer subnet") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val trimmed = cidr.trim()
                if (!cidrRegex.matches(trimmed)) {
                    cidrError = "Invalid CIDR format"
                    return@TextButton
                }
                val prefix = trimmed.split("/")[1].toIntOrNull() ?: 0
                if (prefix > 32) { cidrError = "Prefix must be 0–32"; return@TextButton }
                onAdd(trimmed, label.trim())
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ── Bulk add dialog ───────────────────────────────────────────────────────────

@Composable
private fun BulkAddCidrDialog(
    onDismiss: () -> Unit,
    onAdd: (rawText: String) -> Unit,
) {
    var text by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Bulk Add CIDRs") },
        text = {
            Column {
                Text(
                    "One CIDR per line. Invalid or duplicate entries are skipped automatically.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("CIDRs") },
                    placeholder = { Text("172.16.0.0/12\n192.168.1.0/24\n10.10.0.0/16") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    maxLines = 20,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (text.isNotBlank()) onAdd(text) },
                enabled = text.isNotBlank(),
            ) { Text("Add All") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
