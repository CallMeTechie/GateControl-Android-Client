package com.gatecontrol.android.ui.familie

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.app.ActivityCompat
import androidx.core.net.toUri
import com.gatecontrol.android.R
import com.gatecontrol.android.location.DeviceLocator

/** Steps of the location permission flow (each starts with an explanation, Play "prominent disclosure"). */
private enum class FlowStep { FOREGROUND, BACKGROUND }

/**
 * Location permission with a prominent disclosure right before each system
 * dialog: first "while using the app", then — if [background] — "allow all
 * the time" (Android shows that choice on the app's settings page). The
 * explanation texts name what is collected, why, and that it also happens
 * while the app is closed. Returns the function that starts the flow;
 * [onResult] gets whether everything needed is granted.
 */
@Composable
fun rememberLocationPermissionFlow(
    background: Boolean,
    @StringRes foregroundText: Int,
    @StringRes backgroundText: Int,
    onResult: (Boolean) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    var step by rememberSaveable { mutableStateOf<FlowStep?>(null) }
    var asked by rememberSaveable { mutableStateOf(false) }

    val backgroundLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val activity = context as? Activity
        if (!granted && activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        ) {
            context.openAppSettings()
        }
        onResult(granted)
    }
    val foregroundLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val fine = DeviceLocator.hasPrecisePermission(context)
        when {
            fine && background && !DeviceLocator.hasBackgroundPermission(context) -> step = FlowStep.BACKGROUND
            fine || (DeviceLocator.hasPermission(context) && !background) -> onResult(true)
            else -> {
                val activity = context as? Activity
                // Denied for good: only the app settings help.
                if (asked && result.isNotEmpty() && activity != null &&
                    !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.ACCESS_FINE_LOCATION)
                ) {
                    context.openAppSettings()
                }
                asked = true
                onResult(false)
            }
        }
    }

    when (step) {
        FlowStep.FOREGROUND -> Disclosure(
            title = R.string.familie_perm_title,
            text = foregroundText,
            onContinue = {
                step = null
                foregroundLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            },
            onCancel = {
                step = null
                onResult(false)
            },
        )
        FlowStep.BACKGROUND -> Disclosure(
            title = R.string.familie_perm_bg_title,
            text = backgroundText,
            onContinue = {
                step = null
                backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            },
            onCancel = {
                step = null
                onResult(false)
            },
        )
        null -> Unit
    }

    return {
        step = when {
            !DeviceLocator.hasPrecisePermission(context) -> FlowStep.FOREGROUND
            background && !DeviceLocator.hasBackgroundPermission(context) -> FlowStep.BACKGROUND
            else -> null
        }
        if (step == null) onResult(true)
    }
}

@Composable
private fun Disclosure(@StringRes title: Int, @StringRes text: Int, onContinue: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.extraLarge,
        title = { Text(stringResource(title), style = MaterialTheme.typography.titleLarge) },
        text = { Text(stringResource(text), style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { TextButton(onClick = onContinue) { Text(stringResource(R.string.familie_perm_continue)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.familie_perm_not_now)) } },
    )
}

private fun Context.openAppSettings() {
    runCatching {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
