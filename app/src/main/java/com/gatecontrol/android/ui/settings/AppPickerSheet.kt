package com.gatecontrol.android.ui.settings

import android.content.pm.ApplicationInfo
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import com.gatecontrol.android.ui.components.GcIconSquare
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcSectionLabel
import com.gatecontrol.android.ui.components.GcSwitchRow
import com.gatecontrol.android.ui.components.GcTextField
import com.gatecontrol.android.ui.theme.GateControlTheme
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

    val extra = GateControlTheme.extraColors
    val toggle: (String) -> Unit = { pkg ->
        currentSelection = if (pkg in currentSelection) currentSelection - pkg else currentSelection + pkg
    }

    ModalBottomSheet(
        onDismissRequest = { onDismiss(currentSelection) },
        containerColor = MaterialTheme.colorScheme.surface,
        scrimColor = extra.scrim,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.split_tunnel_pick_apps),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(R.string.split_tunnel_selected_count, currentSelection.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = extra.muted,
                )
            }
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                GcTextField(
                    value = search,
                    onValueChange = { search = it },
                    label = stringResource(R.string.split_tunnel_search_apps),
                    placeholder = stringResource(R.string.split_tunnel_search_apps),
                )
            }
            GcSwitchRow(
                label = stringResource(R.string.split_tunnel_show_system),
                description = null,
                checked = showSystem,
                onCheckedChange = { showSystem = it },
            )

            if (apps == null) {
                Box(
                    Modifier.fillMaxWidth().height(200.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 420.dp),
                ) {
                    if (recommendedApps.isNotEmpty() && search.isBlank()) {
                        item(key = "_recommended_header") {
                            GcSectionLabel(
                                stringResource(R.string.split_tunnel_recommended_apps),
                                Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                            )
                        }
                        items(recommendedApps, key = { "rec_" + it.packageName }) { app ->
                            AppRow(app, app.packageName in currentSelection, stringResource(R.string.split_tunnel_recommended_hint)) { toggle(app.packageName) }
                        }
                        item(key = "_all_header") {
                            GcSectionLabel(
                                stringResource(R.string.split_tunnel_all_apps),
                                Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                            )
                        }
                    }
                    items(filtered, key = { it.packageName }) { app ->
                        AppRow(app, app.packageName in currentSelection, null) { toggle(app.packageName) }
                    }
                }
            }

            Box(Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp)) {
                GcPrimaryButton(
                    text = stringResource(R.string.common_done),
                    onClick = { onDismiss(currentSelection) },
                )
            }
        }
    }
}

@Composable
private fun AppRow(app: AppInfo, selected: Boolean, hint: String?, onToggle: () -> Unit) {
    val context = LocalContext.current
    val extra = GateControlTheme.extraColors
    val icon = remember(app.packageName) {
        try { context.packageManager.getApplicationIcon(app.packageName) } catch (_: Exception) { null }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = selected, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (icon != null) {
            Image(
                bitmap = icon.toBitmap(80, 80).asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)),
            )
        } else {
            GcIconSquare(size = 40.dp) {
                Text(app.label.take(1).uppercase(), style = MaterialTheme.typography.titleMedium, color = extra.muted)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(app.label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = extra.muted)
        }
        Box(
            Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
                .border(2.dp, if (selected) MaterialTheme.colorScheme.primary else extra.border2, RoundedCornerShape(7.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(GcIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(14.dp))
            }
        }
    }
}
