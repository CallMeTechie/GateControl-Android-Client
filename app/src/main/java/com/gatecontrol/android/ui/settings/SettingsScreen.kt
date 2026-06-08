package com.gatecontrol.android.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
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
    onNavigateToNetworkGroups: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

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

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GcOutlineButton(
                    text = stringResource(R.string.settings_test_connection),
                    onClick = { viewModel.testConnection(serverUrlField, apiTokenField) },
                    modifier = Modifier.weight(1f)
                )
                GcPrimaryButton(
                    text = stringResource(R.string.settings_save),
                    onClick = { viewModel.saveServer(serverUrlField, apiTokenField) },
                    modifier = Modifier.weight(1f)
                )
            }

            when (uiState.connectionTestStatus) {
                ConnectionTestStatus.Testing -> {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.settings_testing),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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
                        text = stringResource(R.string.settings_connection_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                else -> {}
            }

            Spacer(modifier = Modifier.height(20.dp))
            SectionDivider()
        }

        // --- Split Tunneling ---
        item {
            var showAppPicker by remember { mutableStateOf(false) }

            Spacer(modifier = Modifier.height(16.dp))
            SectionHeader(text = stringResource(R.string.settings_split_tunnel))
            Spacer(modifier = Modifier.height(8.dp))

            SettingsToggleRow(
                label = stringResource(R.string.split_tunnel_enabled_label),
                description = if (uiState.splitTunnelMode == "off") stringResource(R.string.split_tunnel_off_desc) else null,
                checked = uiState.splitTunnelMode != "off",
                onCheckedChange = { enabled ->
                    viewModel.setSplitTunnelMode(if (enabled) "exclude" else "off")
                }
            )

            if (uiState.splitTunnelMode != "off") {
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

                Text(
                    if (uiState.splitTunnelMode == "exclude")
                        stringResource(R.string.split_tunnel_networks_exclude_header)
                    else
                        stringResource(R.string.split_tunnel_networks_include_header),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(bottom = 8.dp),
                )

                val activeCount = uiState.splitTunnelNetworks.size
                Surface(
                    onClick = onNavigateToNetworkGroups,
                    shape = MaterialTheme.shapes.small,
                    tonalElevation = 1.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Lan,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Manage Network Groups",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                "$activeCount active CIDR${if (activeCount != 1) "s" else ""}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(Icons.Default.ChevronRight, contentDescription = null)
                    }
                }

                Spacer(Modifier.height(16.dp))

                Text(
                    if (uiState.splitTunnelMode == "exclude")
                        stringResource(R.string.split_tunnel_apps_exclude_header)
                    else
                        stringResource(R.string.split_tunnel_apps_include_header),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(bottom = 8.dp),
                )

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

        // ── 防检测设置区块 ──────────────────────────────────────────────────
        item {
            Spacer(modifier = Modifier.height(16.dp))
            SectionHeader(text = "防检测 (Anti-Detection)")
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "所有功能在客户端独立运行，无需服务端配合",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))

            // 1. 端口跳变
            SettingsToggleRow(
                label = "端口跳变 (Port Hopping)",
                description = "每次连接从候选端口列表随机选择，优先使用 443/80 等常见端口",
                checked = uiState.stealthPortHopping,
                onCheckedChange = { viewModel.setStealthPortHopping(it) }
            )

            if (uiState.stealthPortHopping) {
                Spacer(modifier = Modifier.height(8.dp))
                var portsField by remember(uiState.stealthCandidatePorts) {
                    mutableStateOf(uiState.stealthCandidatePorts)
                }
                OutlinedTextField(
                    value = portsField,
                    onValueChange = { portsField = it },
                    label = { Text("候选端口（逗号分隔）") },
                    placeholder = { Text("443,80,8080,8443,53,51820") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = { Text("推荐：443,80,8080,8443") },
                    trailingIcon = {
                        TextButton(onClick = { viewModel.setStealthCandidatePorts(portsField) }) {
                            Text("保存")
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 2. 时序抖动
            SettingsToggleRow(
                label = "时序抖动 (Timing Jitter)",
                description = "握手前注入随机延迟，打乱流量时序指纹，对抗 ML 分类器",
                checked = uiState.stealthTimingJitter,
                onCheckedChange = { viewModel.setStealthTimingJitter(it) }
            )

            if (uiState.stealthTimingJitter) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "最小延迟: ${uiState.stealthJitterMinMs} ms",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = uiState.stealthJitterMinMs.toFloat(),
                    onValueChange = { viewModel.setStealthJitterMinMs(it.toInt()) },
                    valueRange = 0f..2000f,
                    steps = 39,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "最大延迟: ${uiState.stealthJitterMaxMs} ms",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = uiState.stealthJitterMaxMs.toFloat(),
                    onValueChange = { viewModel.setStealthJitterMaxMs(it.toInt()) },
                    valueRange = 50f..5000f,
                    steps = 49,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 3. 包长混淆（MTU 调整）
            SettingsToggleRow(
                label = "包长混淆 (MTU Obfuscation)",
                description = "通过 MTU 调整影响包长分布，使加密包长偏离 WireGuard 默认特征值 (1420)",
                checked = uiState.stealthPacketPadding,
                onCheckedChange = { viewModel.setStealthPacketPadding(it) }
            )

            if (uiState.stealthPacketPadding) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "目标 MTU: ${uiState.stealthPaddingMtu}（会加 ±8 字节随机噪声）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = uiState.stealthPaddingMtu.toFloat(),
                    onValueChange = { viewModel.setStealthPaddingMtu(it.toInt()) },
                    valueRange = 1024f..1500f,
                    steps = 47,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "推荐: 1280（模拟 IPv6 最小 MTU）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 4. Keepalive 随机化
            SettingsToggleRow(
                label = "Keepalive 随机化",
                description = "在 PersistentKeepalive 基础上添加随机偏移，破坏固定 25s 间隔指纹",
                checked = uiState.stealthKeepaliveRandom,
                onCheckedChange = { viewModel.setStealthKeepaliveRandom(it) }
            )

            if (uiState.stealthKeepaliveRandom) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "抖动范围: ± ${uiState.stealthKeepaliveJitterSec} 秒",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = uiState.stealthKeepaliveJitterSec.toFloat(),
                    onValueChange = { viewModel.setStealthKeepaliveJitterSec(it.toInt()) },
                    valueRange = 1f..30f,
                    steps = 28,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 5. 诱饵 DNS 查询
            SettingsToggleRow(
                label = "诱饵 DNS 查询 (Decoy DNS)",
                description = "连接前向 Google/Apple CDN 发起无害 DNS 解析，制造正常浏览前置流量",
                checked = uiState.stealthDecoyDns,
                onCheckedChange = { viewModel.setStealthDecoyDns(it) }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 6. 自动端口跳变重连
            SettingsToggleRow(
                label = "自动端口跳变重连",
                description = "握手超时（疑似端口被封）时，自动切换候选端口重新连接（需启用端口跳变）",
                checked = uiState.stealthAutoReconnect,
                onCheckedChange = { viewModel.setStealthAutoReconnect(it) }
            )

            if (uiState.stealthAutoReconnect && !uiState.stealthPortHopping) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "⚠ 建议同时开启「端口跳变」以获得可用端口列表",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 防检测强度提示
            val enabledCount = listOf(
                uiState.stealthPortHopping,
                uiState.stealthTimingJitter,
                uiState.stealthPacketPadding,
                uiState.stealthKeepaliveRandom,
                uiState.stealthDecoyDns,
                uiState.stealthAutoReconnect
            ).count { it }

            if (enabledCount > 0) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "已启用 $enabledCount / 6 项防检测功能  " +
                            when {
                                enabledCount >= 5 -> "★★★★★ 最强防护"
                                enabledCount >= 4 -> "★★★★☆ 强力防护"
                                enabledCount >= 3 -> "★★★☆☆ 中等防护"
                                enabledCount >= 2 -> "★★☆☆☆ 基础防护"
                                else -> "★☆☆☆☆ 轻微防护"
                            },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
            SectionDivider()
        }
        // ─── 防检测设置区块结束 ────────────────────────────────────────────

        // --- App Section ---
        item {
            Spacer(modifier = Modifier.height(16.dp))
            SectionHeader(text = stringResource(R.string.settings_app))
            Spacer(modifier = Modifier.height(8.dp))

            val systemIsDark = isSystemInDarkTheme()
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
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Status: ${uiState.licenseStatus}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                GcOutlineButton(
                    text = "Refresh",
                    onClick = { viewModel.refreshLicense() }
                )
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
        "en" to "English",
        "zh" to "中文 (简体)"
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
