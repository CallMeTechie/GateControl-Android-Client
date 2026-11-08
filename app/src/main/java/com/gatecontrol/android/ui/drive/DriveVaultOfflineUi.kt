package com.gatecontrol.android.ui.drive

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.R
import com.gatecontrol.android.drive.DNode
import com.gatecontrol.android.drive.DriveLogic
import com.gatecontrol.android.drive.DriveOfflineCopy
import com.gatecontrol.android.drive.vault.DriveVaultOfflineLogic
import com.gatecontrol.android.drive.vault.DriveVaultOfflineLogic.Mark
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcIconSquare
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcListRow
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcSectionLabel
import com.gatecontrol.android.ui.components.GcSwitch
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.theme.GateControlTheme

// ─── "Tresor-Dateien offline": only the ciphertext is on this phone, it opens as always in memory ─────

/** "Offline" (on this phone, opens without a connection) or "Wird geladen" next to a vault row. */
@Composable
internal fun VaultOfflineChip(loading: Boolean) {
    val x = GateControlTheme.extraColors
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 6.dp)) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = x.blue, trackColor = x.blueBg)
            Text(stringResource(R.string.drive_vault_offline_loading), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = x.blue, modifier = Modifier.padding(start = 6.dp))
        } else {
            Icon(GcIcons.Download, contentDescription = null, tint = x.accentText, modifier = Modifier.size(14.dp))
            Text(stringResource(R.string.drive_vault_offline_badge), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = x.accentText, modifier = Modifier.padding(start = 4.dp))
        }
    }
}

/** The line under a vault row: as always, a waiting or failed copy says so, a folder how many of its files are offline. */
@Composable
internal fun vaultRowLine(node: DNode, copy: DriveOfflineCopy?, offlineInside: Int): String {
    val extra = when {
        node.isDir && offlineInside > 0 -> stringResource(R.string.drive_vault_offline_in_folder, offlineInside)
        copy == null || node.isDir -> null
        copy.state == DriveOfflineCopy.State.OUTDATED || copy.state == DriveOfflineCopy.State.FAILED -> copyLine(copy)
        !copy.hasFile && copy.reason != null -> copyLine(copy)
        else -> null
    }
    return nodeLine(node, extra)
}

/** The summary of the copies: "7 von 19 Dateien offline · 48 MB verschlüsselt auf dem Handy". */
@Composable
private fun summaryLine(summary: DriveVaultOfflineLogic.Summary, marked: Boolean): String = when {
    summary.total == 0 -> stringResource(R.string.drive_vault_offline_none)
    marked -> pluralStringResource(R.plurals.drive_vault_offline_of, summary.total, summary.stored, summary.total, DriveLogic.size(summary.bytes))
    else -> pluralStringResource(R.plurals.drive_vault_offline_count, summary.stored, summary.stored, DriveLogic.size(summary.bytes))
}

/**
 * At the top of a vault page: "Ganzen Tresor offline halten" ([whole], the vault's own folder) or "Diesen Ordner offline
 * halten", with what is on this phone. A folder kept by the whole vault shows the switch on and fixed.
 */
@Composable
internal fun VaultOfflineCard(whole: Boolean, mark: Mark, summary: DriveVaultOfflineLogic.Summary, onSet: (Boolean) -> Unit) {
    val x = GateControlTheme.extraColors
    val checked = mark != Mark.NONE
    val inherited = !whole && (mark == Mark.VAULT || mark == Mark.FOLDER)
    val title = stringResource(if (whole) R.string.drive_vault_offline_all else R.string.drive_vault_offline_folder)
    val line = summaryLine(summary, checked)
    val why = when {
        !inherited -> null
        mark == Mark.VAULT -> stringResource(R.string.drive_vault_offline_from_vault)
        else -> stringResource(R.string.drive_vault_offline_from_folder)
    }
    GcCard(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .toggleable(value = checked, enabled = !inherited, role = Role.Switch, onValueChange = onSet),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GcIconSquare(size = 40.dp, background = x.accentBg) {
                Icon(GcIcons.Download, contentDescription = null, tint = x.accentText, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(line, style = MaterialTheme.typography.bodySmall, color = x.muted)
                why?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = x.muted) }
            }
            GcSwitch(checked = checked, onCheckedChange = null, enabled = !inherited)
        }
    }
}

/**
 * "Offline verfügbar" in the menu of a vault file or folder: on keeps its ciphertext on this phone. Kept by a folder
 * above or the whole vault: on and fixed (switched off there). Without a connection it cannot be changed.
 */
@Composable
internal fun VaultOfflineSwitch(mark: Mark, online: Boolean, onSet: (Boolean) -> Unit) {
    val x = GateControlTheme.extraColors
    val checked = mark != Mark.NONE
    val fixed = mark == Mark.FOLDER || mark == Mark.VAULT
    val enabled = online && !fixed
    val desc = when {
        mark == Mark.VAULT -> stringResource(R.string.drive_vault_offline_from_vault)
        mark == Mark.FOLDER -> stringResource(R.string.drive_vault_offline_from_folder)
        !online -> stringResource(R.string.drive_vault_offline_needs_net)
        else -> stringResource(R.string.drive_vault_offline_item_desc)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(x.accentBg)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onSet)
            .heightIn(min = 56.dp)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(GcIcons.Download, contentDescription = null, tint = x.accentText, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.drive_vault_offline_item), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
        }
        GcSwitch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/**
 * The vault page without a connection: "Kein Netz – n Dateien sind offline da", the files on this phone (they open
 * as always, decrypted in memory from the ciphertext here), the others of the folder greyed out ("Nur mit Netz").
 */
@Composable
internal fun VaultOfflinePage(
    s: DriveVaultViewModel.Ui.Ready,
    onOpen: (DNode, String) -> Unit,
    onMore: (DNode, String) -> Unit,
    onRetry: () -> Unit,
) {
    val x = GateControlTheme.extraColors
    GcBanner(tone = GcTone.Warn, icon = GcIcons.WifiOff) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.drive_vault_no_net_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(pluralStringResource(R.plurals.drive_vault_no_net_text, s.items.size, s.items.size), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onRetry, contentPadding = PaddingValues(0.dp)) { Text(stringResource(R.string.drive_retry)) }
        }
    }
    if (s.items.isNotEmpty()) {
        GcSectionLabel(stringResource(R.string.drive_vault_offline_ready), modifier = Modifier.padding(start = 4.dp, top = 6.dp))
        GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            s.items.forEachIndexed { i, (n, name) ->
                if (i > 0) GcRowDivider()
                NodeRow(
                    node = n,
                    title = name,
                    description = listOfNotNull(s.places[n.id], DriveLogic.size(n.size)).joinToString(" · "),
                    onClick = { onOpen(n, name) },
                    trailing = { VaultOfflineChip(loading = false) },
                    onMore = { onMore(n, name) },
                )
            }
        }
    }
    if (s.netOnly.isNotEmpty()) {
        GcSectionLabel(stringResource(R.string.drive_vault_offline_net_only), modifier = Modifier.padding(start = 4.dp, top = 6.dp))
        GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(0.dp), color = x.panel2) {
            s.netOnly.forEachIndexed { i, (n, name) ->
                if (i > 0) GcRowDivider()
                GcListRow(
                    title = name,
                    description = DriveLogic.size(n.size),
                    titleColor = x.muted,
                    leading = { NodeIcon(n) },
                    modifier = Modifier.alpha(0.6f),
                )
            }
        }
    }
    Text(stringResource(R.string.drive_vault_offline_key_note), style = MaterialTheme.typography.bodySmall, color = x.muted, modifier = Modifier.padding(horizontal = 4.dp))
}
