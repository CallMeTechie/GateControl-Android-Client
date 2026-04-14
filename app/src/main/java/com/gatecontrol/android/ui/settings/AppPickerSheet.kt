package com.gatecontrol.android.ui.settings

import android.content.pm.ApplicationInfo
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppInfo(
    val packageName: String,
    val label: String,
    val isSystemApp: Boolean,
)

private val RECOMMENDED_EXCLUDE_APPS = listOf(
    "com.google.android.projection.gearhead", // Android Auto
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPickerSheet(
    selectedPackages: Set<String>,
    onDismiss: (Set<String>) -> Unit,
) {
    val context = LocalContext.current
    var search by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }
    var currentSelection by remember { mutableStateOf(selectedPackages) }

    // Load apps on IO thread.
    // Use queryIntentActivities with ACTION_MAIN + CATEGORY_LAUNCHER to get all
    // launchable apps. getInstalledApplications(0) returns almost nothing on
    // Android 11+ due to package visibility restrictions (QUERY_ALL_PACKAGES
    // permission would require Play Store review).
    val apps by produceState<List<AppInfo>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val launcherIntent = android.content.Intent(android.content.Intent.ACTION_MAIN, null)
                .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            val activities = pm.queryIntentActivities(launcherIntent, 0)
            activities
                .mapNotNull { resolveInfo ->
                    val appInfo = resolveInfo.activityInfo?.applicationInfo ?: return@mapNotNull null
                    AppInfo(
                        packageName = appInfo.packageName,
                        label = resolveInfo.loadLabel(pm).toString(),
                        isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    )
                }
                .distinctBy { it.packageName }
                .sortedBy { it.label.lowercase() }
        }
    }

    // Recommended apps — loaded independently via getApplicationInfo() because
    // some (e.g. Android Auto) have no CATEGORY_LAUNCHER and won't appear in
    // the main list from queryIntentActivities().
    val recommendedApps = remember {
        val pm = context.packageManager
        RECOMMENDED_EXCLUDE_APPS.mapNotNull { pkg ->
            try {
                val info = pm.getApplicationInfo(pkg, 0)
                AppInfo(
                    packageName = pkg,
                    label = info.loadLabel(pm).toString(),
                    isSystemApp = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                )
            } catch (_: Exception) { null }
        }
    }

    // Filter (exclude recommended from main list to avoid duplicates)
    val filtered = remember(apps, search, showSystem) {
        apps?.filter { app ->
            app.packageName !in RECOMMENDED_EXCLUDE_APPS &&
                (showSystem || !app.isSystemApp) &&
                (search.isBlank() || app.label.contains(search, ignoreCase = true))
        } ?: emptyList()
    }

    ModalBottomSheet(onDismissRequest = { onDismiss(currentSelection) }) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            // Search bar
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.split_tunnel_search_apps)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
            )

            // System apps toggle
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.split_tunnel_show_system),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = showSystem, onCheckedChange = { showSystem = it })
            }

            // App list
            if (apps == null) {
                // Loading
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (recommendedApps.isNotEmpty() && search.isBlank()) {
                        item(key = "_recommended_header") {
                            Column(Modifier.padding(vertical = 4.dp)) {
                                Text(
                                    stringResource(R.string.split_tunnel_recommended_apps),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    stringResource(R.string.split_tunnel_recommended_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        items(recommendedApps, key = { it.packageName }) { app ->
                            val isSelected = app.packageName in currentSelection
                            val icon = remember(app.packageName) {
                                try {
                                    context.packageManager.getApplicationIcon(app.packageName)
                                } catch (_: Exception) { null }
                            }
                            ListItem(
                                headlineContent = { Text(app.label, maxLines = 1) },
                                leadingContent = {
                                    if (icon != null) {
                                        Image(
                                            bitmap = icon.toBitmap(40, 40).asImageBitmap(),
                                            contentDescription = null,
                                            modifier = Modifier.size(36.dp),
                                        )
                                    }
                                },
                                trailingContent = {
                                    if (isSelected) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                },
                                modifier = Modifier.clickable {
                                    currentSelection = if (isSelected) {
                                        currentSelection - app.packageName
                                    } else {
                                        currentSelection + app.packageName
                                    }
                                },
                            )
                        }
                        item(key = "_divider") {
                            HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        }
                    }
                    items(filtered, key = { it.packageName }) { app ->
                        val isSelected = app.packageName in currentSelection
                        val icon = remember(app.packageName) {
                            try {
                                context.packageManager.getApplicationIcon(app.packageName)
                            } catch (_: Exception) { null }
                        }
                        ListItem(
                            headlineContent = { Text(app.label, maxLines = 1) },
                            leadingContent = {
                                if (icon != null) {
                                    Image(
                                        bitmap = icon.toBitmap(40, 40).asImageBitmap(),
                                        contentDescription = null,
                                        modifier = Modifier.size(36.dp),
                                    )
                                }
                            },
                            trailingContent = {
                                if (isSelected) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            },
                            modifier = Modifier.clickable {
                                currentSelection = if (isSelected) {
                                    currentSelection - app.packageName
                                } else {
                                    currentSelection + app.packageName
                                }
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}
