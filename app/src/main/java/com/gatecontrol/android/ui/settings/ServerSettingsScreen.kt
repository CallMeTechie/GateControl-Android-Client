package com.gatecontrol.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcSectionLabel
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.components.GcTextField
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.theme.GateControlTheme

/** "Server & Registrierung": setup QR, manual server/token entry, config import. */
@Composable
fun ServerSettingsScreen(
    onBack: () -> Unit,
    onNavigateToQrScanner: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val extra = GateControlTheme.extraColors

    val filePickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) viewModel.importConfigFromUri(context, uri) }

    val requestFilePicker by viewModel.requestFilePicker.collectAsStateWithLifecycle()
    LaunchedEffect(requestFilePicker) {
        if (requestFilePicker) {
            viewModel.onFilePickerLaunched()
            filePickerLauncher.launch(arrayOf("*/*"))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()),
    ) {
        GcSubpageBar(
            title = stringResource(R.string.settings_server_row),
            onBack = onBack,
            backDescription = stringResource(R.string.common_back),
        )
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // One-scan setup: VPN + API access bound to this device's peer.
            GcCard(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.settings_enroll_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = extra.muted,
                )
                GcPrimaryButton(
                    text = stringResource(R.string.settings_enroll_button),
                    onClick = onNavigateToQrScanner,
                    icon = GcIcons.Qr,
                )
            }

            GcSectionLabel(stringResource(R.string.settings_manual), Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
            var serverUrlField by remember(uiState.serverUrl) {
                mutableStateOf(uiState.serverUrl.removePrefix("https://").removePrefix("http://"))
            }
            var apiTokenField by remember(uiState.apiToken) { mutableStateOf(uiState.apiToken) }
            var tokenVisible by remember { mutableStateOf(false) }

            GcCard(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                GcTextField(
                    value = serverUrlField,
                    onValueChange = { serverUrlField = it.removePrefix("https://").removePrefix("http://") },
                    label = stringResource(R.string.settings_server_url),
                    placeholder = stringResource(R.string.settings_server_url_hint),
                    mono = true,
                    prefix = "https://",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                )
                GcTextField(
                    value = apiTokenField,
                    onValueChange = { apiTokenField = it },
                    label = stringResource(R.string.settings_api_token),
                    placeholder = stringResource(R.string.settings_api_token_hint),
                    mono = true,
                    visualTransformation = if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    trailingIcon = {
                        IconButton(onClick = { tokenVisible = !tokenVisible }) {
                            Icon(
                                imageVector = if (tokenVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = stringResource(if (tokenVisible) R.string.common_hide else R.string.common_show),
                                tint = extra.muted,
                            )
                        }
                    },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    GcOutlineButton(
                        text = stringResource(R.string.settings_test_connection),
                        onClick = { viewModel.testConnection("https://$serverUrlField", apiTokenField) },
                        loading = uiState.connectionTestStatus == ConnectionTestStatus.Testing,
                        modifier = Modifier.weight(1f),
                    )
                    GcPrimaryButton(
                        text = stringResource(R.string.settings_save_register),
                        onClick = { viewModel.saveServer("https://$serverUrlField", apiTokenField) },
                        loading = uiState.isLoading,
                        modifier = Modifier.weight(1f),
                    )
                }
                when (uiState.connectionTestStatus) {
                    ConnectionTestStatus.Success -> GcBanner(tone = GcTone.Ok, icon = GcIcons.Check) {
                        Text(stringResource(R.string.settings_connection_ok), style = MaterialTheme.typography.bodyMedium)
                    }
                    ConnectionTestStatus.Failure -> GcBanner(tone = GcTone.Error, icon = GcIcons.Alert) {
                        Text(uiState.error?.asString() ?: stringResource(R.string.settings_connection_fail), style = MaterialTheme.typography.bodyMedium)
                    }
                    else -> Unit
                }
            }

            GcSectionLabel(stringResource(R.string.settings_config_import), Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                GcOutlineButton(
                    text = stringResource(R.string.settings_import_qr),
                    onClick = onNavigateToQrScanner,
                    icon = GcIcons.Qr,
                    modifier = Modifier.weight(1f),
                )
                GcOutlineButton(
                    text = stringResource(R.string.settings_import_file),
                    onClick = { viewModel.requestConfigImport() },
                    icon = GcIcons.File,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
