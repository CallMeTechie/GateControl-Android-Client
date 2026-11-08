package com.gatecontrol.android.ui.vault

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.gatecontrol.android.R
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.theme.GateControlTheme
import com.gatecontrol.android.vault.autofill.VaultBrowsers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Under "In anderen Apps ausfüllen": for each installed browser that fills with GateControl only after its own switch
 * (Chrome 135+, Edge 147+, Brave, Vivaldi — [VaultBrowsers.hints]) one short how-to and a button to its settings.
 * Nothing when no such browser is installed or the switch is on already (checked again on each return to the app).
 */
@Composable
fun VaultBrowserHint(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var round by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        round++
        onPauseOrDispose { }
    }
    val hints by produceState(initialValue = emptyList<Pair<VaultBrowsers.Switch, String>>(), round) {
        value = withContext(Dispatchers.IO) { VaultBrowsers.hints(installedBrowsers(context)) }
    }
    if (hints.isEmpty()) return
    val x = GateControlTheme.extraColors
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((kind, pkg) in hints) {
            val name = browserName(context, pkg, kind)
            Text(
                when (kind) {
                    VaultBrowsers.Switch.CHROME -> stringResource(R.string.vault_browser_hint_chrome)
                    VaultBrowsers.Switch.EDGE -> stringResource(R.string.vault_browser_hint_edge)
                    VaultBrowsers.Switch.BRAVE, VaultBrowsers.Switch.VIVALDI -> stringResource(R.string.vault_browser_hint_other, name)
                },
                style = MaterialTheme.typography.bodySmall,
                color = x.muted,
            )
            GcOutlineButton(
                text = stringResource(R.string.vault_browser_open, name),
                onClick = { openBrowserSettings(context, pkg) },
                fillWidth = false,
                minHeight = 40.dp,
            )
        }
    }
}

private fun browserName(context: Context, pkg: String, kind: VaultBrowsers.Switch): String =
    runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString() }
        .getOrNull()?.takeIf { it.isNotBlank() }
        ?: when (kind) {
            VaultBrowsers.Switch.CHROME -> "Chrome"
            VaultBrowsers.Switch.EDGE -> "Edge"
            VaultBrowsers.Switch.BRAVE -> "Brave"
            VaultBrowsers.Switch.VIVALDI -> "Vivaldi"
        }

/** The browsers of [VaultBrowsers.switchPackages] that are installed, with their answer to "another service". */
private fun installedBrowsers(context: Context): List<VaultBrowsers.Installed> {
    val pm = context.packageManager
    return VaultBrowsers.switchPackages.mapNotNull { pkg ->
        val info = runCatching {
            if (Build.VERSION.SDK_INT >= 33) pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0)) else @Suppress("DEPRECATION") pm.getPackageInfo(pkg, 0)
        }.getOrNull() ?: return@mapNotNull null
        if (info.applicationInfo?.enabled == false) return@mapNotNull null
        VaultBrowsers.Installed(pkg, info.longVersionCode, info.versionName, thirdPartyState(context, pkg))
    }
}

/** Chromium's answer to "Autofill using another service" (0 off, 1 on); null when the browser has no such answer. */
private fun thirdPartyState(context: Context, pkg: String): Int? = runCatching {
    context.contentResolver.query(VaultBrowsers.thirdPartyModeUri(pkg).toUri(), arrayOf(VaultBrowsers.THIRD_PARTY_COLUMN), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getInt(0) else null
    }
}.getOrNull()

/** The browser's own settings (Chromium opens its autofill page for this intent), else the browser itself. */
private fun openBrowserSettings(context: Context, pkg: String) {
    val settings = Intent(Intent.ACTION_APPLICATION_PREFERENCES)
        .addCategory(Intent.CATEGORY_DEFAULT)
        .addCategory(Intent.CATEGORY_APP_BROWSER)
        .addCategory(Intent.CATEGORY_PREFERENCE)
        .setPackage(pkg)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (runCatching { context.startActivity(settings) }.isSuccess) return
    runCatching { context.packageManager.getLaunchIntentForPackage(pkg)?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
}
