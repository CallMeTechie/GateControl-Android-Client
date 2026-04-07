package com.gatecontrol.android.ui.setup

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.hilt.navigation.compose.hiltViewModel
import com.gatecontrol.android.R
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcSecondaryButton
import com.gatecontrol.android.ui.theme.GateControlTheme

@Composable
fun SetupScreen(
    viewModel: SetupViewModel = hiltViewModel(),
    onSetupComplete: () -> Unit,
    onNavigateToQr: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current

    val configFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            try {
                val inputStream = context.contentResolver.openInputStream(it)
                val configText = inputStream?.bufferedReader()?.readText() ?: ""
                inputStream?.close()
                viewModel.importConfig(configText)
            } catch (_: Exception) {
                // Error reading file
            }
        }
    }

    LaunchedEffect(uiState.isSetupComplete) {
        if (uiState.isSetupComplete) {
            onSetupComplete()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Logo
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary,
            )

            Spacer(Modifier.height(8.dp))

            // Title
            Text(
                text = stringResource(R.string.setup_title),
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )

            // Subtitle
            Text(
                text = stringResource(R.string.setup_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(16.dp))

            // Primary action: Scan QR
            GcPrimaryButton(
                text = stringResource(R.string.setup_qr),
                onClick = onNavigateToQr,
                enabled = !uiState.isLoading,
            )

            // Secondary action: Enter Manually
            GcSecondaryButton(
                text = stringResource(R.string.setup_manual),
                onClick = { viewModel.toggleManualExpanded() },
                enabled = !uiState.isLoading,
            )

            // Expandable manual entry section
            AnimatedVisibility(
                visible = uiState.isManualExpanded,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                ManualEntrySection(
                    serverUrl = uiState.serverUrl,
                    apiToken = uiState.apiToken,
                    isLoading = uiState.isLoading,
                    onServerUrlChanged = viewModel::onServerUrlChanged,
                    onApiTokenChanged = viewModel::onApiTokenChanged,
                    onTestConnection = viewModel::testConnection,
                    onSaveAndRegister = viewModel::saveAndRegister,
                )
            }

            // Outline action: Import Config
            GcOutlineButton(
                text = stringResource(R.string.setup_import),
                onClick = { configFileLauncher.launch("*/*") },
                enabled = !uiState.isLoading,
            )

            // Status message
            if (uiState.statusMessage.isNotEmpty()) {
                SetupStatusMessage(
                    message = uiState.statusMessage,
                    type = uiState.statusType,
                )
            }
        }
    }
}

@Composable
private fun ManualEntrySection(
    serverUrl: String,
    apiToken: String,
    isLoading: Boolean,
    onServerUrlChanged: (String) -> Unit,
    onApiTokenChanged: (String) -> Unit,
    onTestConnection: () -> Unit,
    onSaveAndRegister: () -> Unit,
) {
    val focusManager = LocalFocusManager.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = serverUrl,
            onValueChange = onServerUrlChanged,
            label = { Text(stringResource(R.string.settings_server_url)) },
            placeholder = { Text(stringResource(R.string.settings_server_url_hint)) },
            singleLine = true,
            enabled = !isLoading,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Next,
            ),
            keyboardActions = KeyboardActions(
                onNext = { focusManager.moveFocus(FocusDirection.Down) },
            ),
        )

        OutlinedTextField(
            value = apiToken,
            onValueChange = onApiTokenChanged,
            label = { Text(stringResource(R.string.settings_api_token)) },
            placeholder = { Text(stringResource(R.string.settings_api_token_hint)) },
            singleLine = true,
            enabled = !isLoading,
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(
                onDone = { focusManager.clearFocus() },
            ),
        )

        GcSecondaryButton(
            text = stringResource(R.string.settings_test_connection),
            onClick = onTestConnection,
            enabled = !isLoading,
        )

        GcPrimaryButton(
            text = stringResource(R.string.settings_save_register),
            onClick = onSaveAndRegister,
            enabled = !isLoading,
            loading = isLoading,
        )
    }
}

@Composable
private fun SetupStatusMessage(
    message: String,
    type: StatusType,
) {
    val (icon, color) = when (type) {
        StatusType.SUCCESS -> Icons.Filled.CheckCircle to MaterialTheme.colorScheme.primary
        StatusType.ERROR -> Icons.Filled.Warning to MaterialTheme.colorScheme.error
        StatusType.INFO -> Icons.Filled.Info to GateControlTheme.extraColors.blue
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = color,
            textAlign = TextAlign.Center,
        )
    }
}
