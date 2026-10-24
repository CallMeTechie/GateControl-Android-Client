package com.gatecontrol.android.ui.vault

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.gatecontrol.android.R
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcListRow
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcSecondaryButton
import com.gatecontrol.android.ui.setup.QrCameraPreview
import com.gatecontrol.android.ui.setup.QrOverlay
import com.gatecontrol.android.vault.VaultTotpQr
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.delay

/** Reads the QR codes in a picked image (a screenshot of the website's 2FA page). */
private fun scanImage(context: android.content.Context, uri: Uri, onCodes: (List<String>) -> Unit) {
    val image = runCatching { InputImage.fromFilePath(context, uri) }.getOrNull() ?: return onCodes(emptyList())
    val scanner = BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
    scanner.process(image)
        .addOnSuccessListener { codes -> onCodes(codes.mapNotNull { it.rawValue }) }
        .addOnFailureListener { onCodes(emptyList()) }
        .addOnCompleteListener { scanner.close() }
}

/**
 * Sets up 2FA from a QR code: the camera, or "Aus Bild" for a screenshot (when the website is
 * open on this phone). Takes `otpauth://totp` codes and Google Authenticator exports (then a list
 * to pick from); other codes only show a hint. [onPick] gets the account for the `totp` field.
 */
@Composable
fun VaultTotpScanDialog(onDismiss: () -> Unit, onPick: (VaultTotpQr.Account) -> Unit) {
    val context = LocalContext.current
    var hasCamera by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var denied by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<Int?>(null) }
    var choices by remember { mutableStateOf<List<VaultTotpQr.Account>>(emptyList()) }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCamera = granted
        denied = !granted
    }
    LaunchedEffect(Unit) { if (!hasCamera) cameraPermission.launch(Manifest.permission.CAMERA) }
    LaunchedEffect(hint) {
        if (hint != null) {
            delay(3_000)
            hint = null
        }
    }

    /** True when the code was taken (one account → done, several → the list). */
    fun take(codes: List<String>): Boolean {
        var reason: Int? = if (codes.isEmpty()) R.string.vault_qr_none_in_image else null
        for (raw in codes) {
            when (val r = VaultTotpQr.parse(raw)) {
                is VaultTotpQr.Result.Accounts -> {
                    if (r.accounts.size == 1) onPick(r.accounts[0]) else choices = r.accounts
                    return true
                }
                VaultTotpQr.Result.Hotp -> reason = R.string.vault_qr_hotp
                VaultTotpQr.Result.NotTotp -> if (reason == null) reason = R.string.vault_qr_not_totp
            }
        }
        hint = reason
        return false
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scanImage(context, uri) { take(it) }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            when {
                hasCamera && choices.isEmpty() -> {
                    QrCameraPreview(onQrScanned = { raw -> take(listOf(raw)) })
                    QrOverlay()
                }
                denied -> Text(
                    stringResource(R.string.vault_qr_camera_denied), color = Color.White, textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge, modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )
            }
            GcIconButton(
                GcIcons.Close, stringResource(R.string.vault_cancel), onClick = onDismiss, size = 48.dp, tint = Color.White,
                modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp),
            )
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val text = hint?.let { stringResource(it) } ?: stringResource(R.string.vault_qr_aim)
                Text(
                    text, color = Color.White, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 14.dp, vertical = 8.dp),
                )
                GcSecondaryButton(
                    stringResource(R.string.vault_qr_from_image),
                    onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    icon = GcIcons.Photo,
                )
                if (denied) GcPrimaryButton(stringResource(R.string.retry), onClick = { cameraPermission.launch(Manifest.permission.CAMERA) })
            }
        }
    }

    if (choices.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { choices = emptyList() },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(stringResource(R.string.vault_qr_pick_account), style = MaterialTheme.typography.titleLarge) },
            text = {
                VaultRowsCard {
                    Column {
                        choices.forEachIndexed { i, a ->
                            if (i > 0) GcRowDivider()
                            GcListRow(title = a.issuer.ifEmpty { a.name }, description = a.name.takeIf { a.issuer.isNotEmpty() }, onClick = { onPick(a) })
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { choices = emptyList() }) { Text(stringResource(R.string.vault_cancel)) } },
        )
    }
}
