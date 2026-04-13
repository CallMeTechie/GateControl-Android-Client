package com.gatecontrol.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.util.WifiSubnetDetector
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcSecondaryButton
import com.gatecontrol.android.ui.theme.GateControlTheme

@Composable
fun SettingsScreen(
    onNavigateToLogs: () -> Unit,
    onNavigateToQrScanner: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // File picker for .conf import
    val filePickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) viewModel.importConfigFromUri(context, uri) }

    val requestFilePicker by viewModel.requestFilePicker.collectAsStateWithLifecycle()
    LaunchedEffect(requestFilePicker) {
        if (requestFilePicker) {
            viewModel.onFilePickerLaunched()
            filePickerLauncher.launch(arrayOf("*/*"))
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(20.dp))
        }

        // --- Server Section ---
        item {
            SectionHeader(text = stringResource(R.string.settings_server))
            Spacer(modifier = Modifier.height(8.dp))

            var serverUrlField by remember(uiState.serverUrl) {
                mutableStateOf(uiState.serverUrl.removePrefix("https://").removePrefix("http://"))
            }
            var apiTokenField by remember(uiState.apiToken) { mutableStateOf(uiState.apiToken) }

            OutlinedTextField(
                value = serverUrlField,
                onValueChange = { serverUrlField = it.removePrefix("https://").removePrefix("http://") },
                label = { Text(stringResource(R.string.settings_server_url)) },
                placeholder = { Text(stringResource(R.string.settings_server_url_hint)) },
                prefix = { Text("https://") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next
                )
            )

            Spacer(modifier = Modifier.height(8.dp))

            var settingsTokenVisible by remember { mutableStateOf(false) }
            OutlinedTextField(
                value = apiTokenField,
                onValueChange = { apiTokenField = it },
                label = { Text(stringResource(R.string.settings_api_token)) },
                placeholder = { Text(stringResource(R.string.settings_api_token_hint)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (settingsTokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { settingsTokenVisible = !settingsTokenVisible }) {
                        Icon(
                            imageVector = if (settingsTokenVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (settingsTokenVisible) "Hide" else "Show",
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                )
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GcOutlineButton(
                    text = stringResource(R.string.settings_test_connection),
                    onClick = { viewModel.testConnection("https://$serverUrlField", apiTokenField) },
                    enabled = uiState.connectionTestStatus != ConnectionTestStatus.Testing,
                    modifier = Modifier.weight(1f)
                )
                GcPrimaryButton(
                    text = stringResource(R.string.settings_save_register),
                    onClick = { viewModel.saveServer("https://$serverUrlField", apiTokenField) },
                    loading = uiState.isLoading,
                    modifier = Modifier.weight(1f)
                )
            }

            when (uiState.connectionTestStatus) {
                ConnectionTestStatus.Success -> {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.settings_connection_ok),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                ConnectionTestStatus.Failure -> {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.settings_connection_fail),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                else -> Unit
            }

            Spacer(modifier = Modifier.height(20.dp))
            SectionDivider()
        }

        // --- Split Tunneling ---
        item {
            var showAppPicker by remember { mutableStateOf(false) }
            val wifiSubnet = remember { WifiSubnetDetector.detect(context) }

            Spacer(modifier = Modifier.height(16.dp))
            SectionHeader(text = stringResource(R.string.settings_split_tunnel))
            Spacer(modifier = Modifier.height(8.dp))

            // Mode toggle
            SettingsToggleRow(
                label = stringResource(R.string.split_tunnel_enabled_label),
                description = if (uiState.splitTunnelMode == "off") stringResource(R.string.split_tunnel_off_desc) else null,
                checked = uiState.splitTunnelMode != "off",
                onCheckedChange = { enabled ->
                    viewModel.setSplitTunnelMode(if (enabled) "exclude" else "off")
                }
            )

            if (uiState.splitTunnelMode != "off") {
                // Mode selection
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = uiState.splitTunnelMode == "exclude",
                        onClick = { if (!uiState.splitTunnelAdminLocked) viewModel.setSplitTunnelMode("exclude") },
                        enabled = !uiState.splitTunnelAdminLocked,
                    )
                    Text(stringResource(R.string.split_tunnel_exclude_label), Modifier.padding(start = 4.dp))
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = uiState.splitTunnelMode == "include",
                        onClick = { if (!uiState.splitTunnelAdminLocked) viewModel.setSplitTunnelMode("include") },
                        enabled = !uiState.splitTunnelAdminLocked,
                    )
                    Text(stringResource(R.string.split_tunnel_include_label), Modifier.padding(start = 4.dp))
                }

                Spacer(Modifier.height(12.dp))

                // Context header for networks
                Text(
                    if (uiState.splitTunnelMode == "exclude")
                        stringResource(R.string.split_tunnel_networks_exclude_header)
                    else
                        stringResource(R.string.split_tunnel_networks_include_header),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(bottom = 8.dp),
                )

                NetworkPresetsSection(
                    networks = uiState.splitTunnelNetworks,
                    wifiSubnet = wifiSubnet,
                    adminLocked = uiState.splitTunnelAdminLocked,
                    onNetworksChanged = { viewModel.setSplitTunnelNetworks(it) },
                )

                Spacer(Modifier.height(16.dp))

                // Context header for apps
                Text(
                    if (uiState.splitTunnelMode == "exclude")
                        stringResource(R.string.split_tunnel_apps_exclude_header)
                    else
                        stringResource(R.string.split_tunnel_apps_include_header),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(bottom = 8.dp),
                )

                // Selected apps list (resolve package name → app label + icon)
                val pm = context.packageManager
                uiState.splitTunnelAppsV2.forEach { pkg ->
                    val appLabel = remember(pkg) {
                        try { pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString() } catch (_: Exception) { pkg }
                    }
                    val appIcon = remember(pkg) {
                        try { pm.getApplicationIcon(pkg) } catch (_: Exception) { null }
                    }
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (appIcon != null) {
                            Image(
                                bitmap = appIcon.toBitmap(40, 40).asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.size(32.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(appLabel, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        IconButton(onClick = { viewModel.setSplitTunnelAppsV2(uiState.splitTunnelAppsV2 - pkg) }) {
                            Icon(Icons.Default.Close, contentDescription = "Remove")
                        }
                    }
                }

                TextButton(onClick = { showAppPicker = true }) {
                    Text("+ ${stringResource(R.string.split_tunnel_add_app)}")
                }

                if (showAppPicker) {
                    AppPickerSheet(
                        selectedPackages = uiState.splitTunnelAppsV2.toSet(),
                        onDismiss = { selected ->
                            viewModel.setSplitTunnelAppsV2(selected.toList())
                            showAppPicker = false
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
            SectionDivider()
        }

        // --- Config Import Section ---
        item {
            Spacer(modifier = Modifier.height(16.dp))
            SectionHeader(text = stringResource(R.string.settings_config_import))
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GcOutlineButton(
                    text = stringResource(R.string.settings_import_qr),
                    onClick = onNavigateToQrScanner,
                    modifier = Modifier.weight(1f)
                )
                GcOutlineButton(
                    text = stringResource(R.string.settings_import_file),
                    onClick = { viewModel.requestConfigImport() },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))
            SectionDivider()
        }

        // --- App Section ---
        item {
            Spacer(modifier = Modifier.height(16.dp))
            SectionHeader(text = stringResource(R.string.settings_app))
            Spacer(modifier = Modifier.height(8.dp))

            // Resolve effective theme: "system" follows device setting
            val systemIsDark = androidx.compose.foundation.isSystemInDarkTheme()
            val effectivelyDark = when (uiState.theme) {
                "dark" -> true
                "light" -> false
                else -> systemIsDark
            }
            SettingsToggleRow(
                label = stringResource(R.string.settings_theme),
                description = if (effectivelyDark)
                    stringResource(R.string.settings_theme_dark)
                else
                    stringResource(R.string.settings_theme_light),
                checked = effectivelyDark,
                onCheckedChange = { isDark ->
                    viewModel.setTheme(if (isDark) "dark" else "light")
                }
            )

            Spacer(modifier = Modifier.height(8.dp))

            LocaleDropdown(
                selectedLocale = uiState.locale,
                onLocaleSelected = { viewModel.setLocale(it) }
            )

            Spacer(modifier = Modifier.height(8.dp))

            SettingsToggleRow(
                label = stringResource(R.string.settings_auto_connect),
                description = stringResource(R.string.settings_auto_connect_desc),
                checked = uiState.autoConnect,
                onCheckedChange = { viewModel.setAutoConnect(it) }
            )

            Spacer(modifier = Modifier.height(20.dp))
            SectionDivider()
        }

        // --- License Section ---
        item {
            Spacer(modifier = Modifier.height(16.dp))
            SectionHeader(text = stringResource(R.string.settings_license))
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = if (uiState.isPro) stringResource(R.string.settings_license_pro)
                               else stringResource(R.string.settings_license_community),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (uiState.isPro) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onBackground,
                        fontWeight = if (uiState.isPro) FontWeight.Bold else FontWeight.Normal
                    )
                    Text(
                        text = stringResource(R.string.settings_license_managed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = { viewModel.refreshLicense() }) {
                    Text(
                        if (uiState.isPro) stringResource(R.string.settings_license_refresh)
                        else stringResource(R.string.settings_license_activate)
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
            SectionDivider()
        }

        // --- Logs Section ---
        item {
            Spacer(modifier = Modifier.height(16.dp))
            SectionHeader(text = stringResource(R.string.settings_logs))
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GcOutlineButton(
                    text = stringResource(R.string.settings_logs_view),
                    onClick = onNavigateToLogs,
                    modifier = Modifier.weight(1f)
                )
                GcOutlineButton(
                    text = stringResource(R.string.settings_logs_export),
                    onClick = {
                        viewModel.exportLogs(context.cacheDir)
                    },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))
            SectionDivider()
        }

        // --- About Section ---
        item {
            Spacer(modifier = Modifier.height(16.dp))
            SectionHeader(text = stringResource(R.string.settings_about))
            Spacer(modifier = Modifier.height(8.dp))

            val packageInfo = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0)
            }.getOrNull()
            val versionName = packageInfo?.versionName ?: uiState.appVersion

            Text(
                text = stringResource(R.string.settings_version, versionName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(8.dp))

            val updateInfo = uiState.updateInfo
            when {
                updateInfo?.available == true -> {
                    Text(
                        text = stringResource(
                            R.string.settings_update_available,
                            updateInfo.version ?: ""
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            updateInfo.downloadUrl?.let { url ->
                                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                                context.startActivity(intent)
                            }
                        }) {
                            Text(stringResource(R.string.settings_update_install))
                        }
                        TextButton(onClick = { viewModel.dismissUpdate() }) {
                            Text(stringResource(R.string.settings_update_later))
                        }
                    }
                }
                updateInfo != null -> {
                    Text(
                        text = stringResource(R.string.settings_update_none),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                else -> Unit
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (uiState.error != null) {
                Text(
                    text = uiState.error ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(4.dp))
            }

            GcSecondaryButton(
                text = stringResource(R.string.settings_check_for_updates),
                onClick = { viewModel.checkForUpdate(versionName) },
                enabled = !uiState.isLoading
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
}

@Composable
private fun SettingsToggleRow(
    label: String,
    description: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            if (!description.isNullOrBlank()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocaleDropdown(
    selectedLocale: String,
    onLocaleSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    val localeOptions = mapOf(
        "de" to "Deutsch",
        "en" to "English"
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = stringResource(R.string.settings_language),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(16.dp))
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded },
            modifier = Modifier.weight(1f)
        ) {
            OutlinedTextField(
                value = localeOptions[selectedLocale] ?: selectedLocale,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.menuAnchor()
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                localeOptions.forEach { (code, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            onLocaleSelected(code)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}
