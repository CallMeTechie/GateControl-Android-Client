package com.gatecontrol.android.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.R
import com.gatecontrol.android.update.ApkUpdateState
import com.gatecontrol.android.ui.theme.GateControlTheme

/** Where the own APK download stands, under "Update verfügbar" (GitHub build); nothing while idle. */
@Composable
fun ApkUpdateStatus(state: ApkUpdateState, modifier: Modifier = Modifier) {
    val x = GateControlTheme.extraColors
    when (state) {
        ApkUpdateState.Idle -> Unit
        is ApkUpdateState.Downloading -> Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val pct = if (state.total > 0) ((state.done * 100) / state.total).toInt().coerceIn(0, 100) else null
            Text(
                stringResource(if (state.home) R.string.update_apk_loading_home else R.string.update_apk_loading) + (pct?.let { " $it %" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = x.muted,
            )
            if (pct != null) {
                LinearProgressIndicator(progress = { pct / 100f }, modifier = Modifier.fillMaxWidth().height(3.dp))
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(3.dp))
            }
        }
        ApkUpdateState.Verifying -> Text(stringResource(R.string.update_apk_verifying), style = MaterialTheme.typography.bodySmall, color = x.muted, modifier = modifier)
        is ApkUpdateState.Ready -> Text(
            stringResource(
                when {
                    state.needsPermission -> R.string.update_apk_permission
                    state.home -> R.string.update_apk_ready_home
                    else -> R.string.update_apk_ready
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = x.accentText,
            modifier = modifier,
        )
        is ApkUpdateState.Failed -> Text(
            stringResource(
                when (state.code) {
                    "signature", "package" -> R.string.update_apk_failed_signature
                    "sha256" -> R.string.update_apk_failed_sha
                    "version" -> R.string.update_apk_failed_version
                    "invalid" -> R.string.update_apk_failed_invalid
                    else -> R.string.update_apk_failed_network
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = modifier,
        )
    }
}
