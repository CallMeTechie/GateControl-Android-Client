package com.gatecontrol.android.ui.drive

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.R
import com.gatecontrol.android.drive.DNode
import com.gatecontrol.android.drive.DVaultLocal
import com.gatecontrol.android.drive.DVaultLocalStart
import com.gatecontrol.android.drive.DriveLogic
import com.gatecontrol.android.drive.vault.DriveVaultLocalFolder
import com.gatecontrol.android.drive.vault.DriveVaultLocalJobs
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcIconSquare
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcListRow
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.theme.GateControlTheme
import java.time.Instant
import java.util.Date

// ─── "Nur auf diesem Gerät" (Drive 1.32): a folder of a vault whose content lies on one device only ─────

/** The date of an ISO time or of ms, as the phone writes dates ("27.11.2026"). */
internal fun localDate(context: Context, iso: String?): String? =
    iso?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }?.let { localDate(context, it) }

internal fun localDate(context: Context, ms: Long): String = DateFormat.getDateFormat(context).format(Date(ms))

/** "Nur hier" next to a folder kept only on this phone (blue, the phone). */
@Composable
internal fun VaultLocalChip() {
    val x = GateControlTheme.extraColors
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 6.dp)) {
        Icon(GcIcons.Smartphone, contentDescription = null, tint = x.blue, modifier = Modifier.size(14.dp))
        Text(stringResource(R.string.drive_vault_local_badge), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = x.blue, modifier = Modifier.padding(start = 4.dp))
    }
}

/** The folder's square with a small phone in its corner: blue for "here", grey for another device. */
@Composable
internal fun VaultLocalIcon(node: DNode, here: Boolean, size: Int = 44) {
    val x = GateControlTheme.extraColors
    Box {
        if (here) {
            NodeIcon(node, size)
        } else {
            GcIconSquare(size = size.dp, background = x.panel2) {
                Icon(GcIcons.Folder, contentDescription = null, tint = x.muted, modifier = Modifier.size((size / 2).dp))
            }
        }
        val badge = (size * 0.42f).toInt().coerceAtLeast(16)
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(0.dp)
                .size(badge.dp)
                .clip(CircleShape)
                .background(if (here) x.blue else x.muted)
                .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(GcIcons.Smartphone, contentDescription = null, tint = Color.White, modifier = Modifier.size((badge * 0.55f).dp))
        }
    }
}

/** The device's name of a placeholder ("Pixel 8"), or "einem anderen Gerät". */
@Composable
internal fun localDeviceName(l: DVaultLocal): String = l.deviceName ?: stringResource(R.string.drive_vault_local_other_device)

/**
 * A placeholder of another device (A6): greyed out, the folder with the phone, "Nur auf Pixel 8 · seit 27.11.2026"; a
 * tap shows what it is ([onClick]) — it never opens, it is never a target.
 */
@Composable
internal fun VaultPlaceholderRow(node: DNode, title: String, local: DVaultLocal, onClick: () -> Unit) {
    val x = GateControlTheme.extraColors
    val context = LocalContext.current
    val device = localDeviceName(local)
    val line = if (local.moving) {
        stringResource(R.string.drive_vault_local_other_moving, device)
    } else {
        stringResource(R.string.drive_vault_local_other_line, device, localDate(context, local.since) ?: "–")
    }
    GcListRow(
        title = title,
        description = line,
        titleColor = x.muted,
        onClick = onClick,
        leading = { Box(Modifier.alpha(0.7f)) { VaultLocalIcon(node, here = false) } },
        modifier = Modifier.background(x.panel2.copy(alpha = 0.5f)),
    )
}

/** How a folder of a vault lies (the choice "Wo liegt dieser Ordner?"). */
enum class VaultWhere { SERVER, EXTRA, HERE }

/**
 * "Wo liegt dieser Ordner?" (A2) in the menu of a vault folder, instead of the switch "Offline verfügbar": only on the
 * server, also offline here, or only on this phone. [localOffered]: the third choice is there (`vaultLocal`, "Nur ich").
 * [fixedExtra]: kept offline by a folder above or the whole vault (the server-only choice is off). For a folder kept
 * only here (A8) the first two choices bring it back ([back]).
 */
@Composable
internal fun VaultWhereChoice(
    current: VaultWhere,
    localOffered: Boolean,
    online: Boolean,
    fixedExtra: Boolean,
    since: String? = null,
    onChoose: (VaultWhere) -> Unit,
) {
    val x = GateControlTheme.extraColors
    val back = current == VaultWhere.HERE
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(x.panel2)
            .padding(vertical = 8.dp)
            .selectableGroup(),
    ) {
        Text(
            stringResource(R.string.drive_vault_local_where).uppercase(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = x.muted,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        WhereOption(
            icon = GcIcons.Cloud,
            iconTint = x.accentText,
            iconBg = if (back) MaterialTheme.colorScheme.surface else x.accentBg,
            title = stringResource(if (back) R.string.drive_vault_local_back else R.string.drive_vault_local_server),
            desc = stringResource(if (back) R.string.drive_vault_local_back_desc else R.string.drive_vault_local_server_desc),
            selected = current == VaultWhere.SERVER,
            enabled = online && (back || !fixedExtra),
            accent = x.accentText,
            onClick = { onChoose(VaultWhere.SERVER) },
        )
        WhereOption(
            icon = GcIcons.DownloadTray,
            iconTint = MaterialTheme.colorScheme.onSurface,
            iconBg = MaterialTheme.colorScheme.surface,
            title = stringResource(if (back) R.string.drive_vault_local_back_extra else R.string.drive_vault_local_extra),
            desc = when {
                back -> stringResource(R.string.drive_vault_local_back_extra_desc)
                fixedExtra -> stringResource(R.string.drive_vault_offline_from_folder)
                else -> stringResource(R.string.drive_vault_local_extra_desc)
            },
            selected = current == VaultWhere.EXTRA,
            enabled = online && (back || !fixedExtra),
            accent = x.accentText,
            onClick = { onChoose(VaultWhere.EXTRA) },
        )
        if (localOffered || back) {
            WhereOption(
                icon = GcIcons.Smartphone,
                iconTint = x.blue,
                iconBg = x.blueBg,
                title = stringResource(R.string.drive_vault_local_only),
                desc = if (back && since != null) stringResource(R.string.drive_vault_local_since, since) else stringResource(R.string.drive_vault_local_only_desc),
                selected = current == VaultWhere.HERE,
                enabled = online || back,
                accent = x.blue,
                onClick = { onChoose(VaultWhere.HERE) },
            )
        }
    }
}

@Composable
private fun WhereOption(
    icon: ImageVector,
    iconTint: Color,
    iconBg: Color,
    title: String,
    desc: String,
    selected: Boolean,
    enabled: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .clip(shape)
            .then(if (selected) Modifier.border(2.dp, accent, shape).background(MaterialTheme.colorScheme.surface) else Modifier)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 64.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .alpha(if (enabled || selected) 1f else 0.5f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GcIconSquare(size = 40.dp, background = iconBg) { Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp)) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = GateControlTheme.extraColors.muted)
        }
        RadioButton(selected = selected, onClick = null, enabled = enabled)
    }
}

/**
 * The confirmation before a folder leaves the server (A3): the numbers of the server's manifest, the free room, what it
 * means — and the tick without which nothing happens.
 */
@Composable
internal fun VaultLocalAskDialog(name: String, start: DVaultLocalStart?, free: Long, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val x = GateControlTheme.extraColors
    var ticked by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            GcIconSquare(size = 48.dp, background = x.blueBg) { Icon(GcIcons.Smartphone, contentDescription = null, tint = x.blue, modifier = Modifier.size(24.dp)) }
        },
        title = { Text(stringResource(R.string.drive_vault_local_ask_title, name), textAlign = TextAlign.Center) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (start == null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.drive_vault_local_ask_loading))
                    }
                } else {
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(x.panel2).padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        AskRow(stringResource(R.string.drive_vault_local_ask_files), "${start.files} · ${DriveLogic.size(start.bytes)}")
                        AskRow(stringResource(R.string.drive_vault_local_ask_versions), pluralStringResource(R.plurals.drive_vault_local_ask_versions_value, start.versions, start.versions))
                        AskRow(stringResource(R.string.drive_vault_local_ask_trash), pluralStringResource(R.plurals.drive_vault_local_ask_trash_value, start.trashEntries, start.trashEntries))
                        AskRow(stringResource(R.string.drive_vault_local_ask_free), DriveLogic.size(free))
                    }
                    Warn(GcIcons.Alert, MaterialTheme.colorScheme.error) {
                        Text(
                            buildAnnotatedString {
                                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(stringResource(R.string.drive_vault_local_ask_warn1_bold)) }
                                append(" ")
                                append(stringResource(R.string.drive_vault_local_ask_warn1))
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Warn(GcIcons.Alert, MaterialTheme.colorScheme.error) { Text(stringResource(R.string.drive_vault_local_ask_warn2), style = MaterialTheme.typography.bodyMedium) }
                    Warn(GcIcons.Info, x.muted) { Text(stringResource(R.string.drive_vault_local_ask_warn3), style = MaterialTheme.typography.bodyMedium) }
                    Row(
                        Modifier.fillMaxWidth().toggleable(value = ticked, role = Role.Checkbox, onValueChange = { ticked = it }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = ticked, onCheckedChange = null)
                        Text(stringResource(R.string.drive_vault_local_ask_check), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 10.dp))
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.Button(
                onClick = onConfirm,
                enabled = start != null && ticked,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
            ) { Text(stringResource(R.string.drive_vault_local_only)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.drive_cancel)) } },
    )
}

@Composable
private fun AskRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = GateControlTheme.extraColors.muted)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun Warn(icon: ImageVector, tint: Color, content: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp).padding(top = 2.dp))
        Box(Modifier.weight(1f)) { content() }
    }
}

/**
 * A move onto this phone or back to the server, as a card at the top of the vault (A4): the three steps, "Abbrechen"
 * until the server is asked (a move only), "Weiter" when it stopped.
 */
@Composable
internal fun VaultLocalJobCard(p: DriveVaultLocalJobs.Progress, onCancel: () -> Unit, onRetry: () -> Unit, onClose: () -> Unit) {
    val x = GateControlTheme.extraColors
    val move = p.kind == DriveVaultLocalJobs.Kind.MOVE
    GcCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GcIconSquare(size = 44.dp, background = x.blueBg) {
                Icon(if (move) GcIcons.Smartphone else GcIcons.CloudUp, contentDescription = null, tint = x.blue, modifier = Modifier.size(22.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(if (move) R.string.drive_vault_local_job_move_title else R.string.drive_vault_local_job_back_title, p.name),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(stringResource(R.string.drive_vault_local_job_sub), style = MaterialTheme.typography.bodySmall, color = x.muted)
            }
        }
        val step = p.step
        val fetched = p.total > 0 && p.done >= p.total
        Step(
            n = 1,
            state = if (step == DriveVaultLocalJobs.Step.DOWNLOAD && !fetched) StepState.NOW else StepState.DONE,
            title = stringResource(if (move) R.string.drive_vault_local_step_download else R.string.drive_vault_local_step_upload),
            line = if (p.total > 0) pluralStringResource(R.plurals.drive_vault_local_step_files, p.total, p.done, p.total) + if (p.bytes > 0) " · " + DriveLogic.size(p.bytes) else "" else null,
            progress = if (step == DriveVaultLocalJobs.Step.DOWNLOAD && p.total > 0 && !fetched) p.done.toFloat() / p.total else null,
        )
        Step(
            n = 2,
            state = when {
                step == DriveVaultLocalJobs.Step.FINISH -> StepState.DONE
                p.checked > 0 || step == DriveVaultLocalJobs.Step.CHECK -> StepState.NOW
                else -> StepState.LATER
            },
            title = stringResource(if (move) R.string.drive_vault_local_step_check else R.string.drive_vault_local_step_check_back),
            line = if (p.total > 0) pluralStringResource(R.plurals.drive_vault_local_step_files, p.total, p.checked, p.total) else null,
            progress = if (step != DriveVaultLocalJobs.Step.FINISH && p.total > 0 && p.checked > 0) p.checked.toFloat() / p.total else null,
        )
        Step(
            n = 3,
            state = if (step == DriveVaultLocalJobs.Step.FINISH) (if (p.finished) StepState.DONE else StepState.NOW) else StepState.LATER,
            title = stringResource(if (move) R.string.drive_vault_local_step_finish else R.string.drive_vault_local_step_finish_back),
            line = if (move) stringResource(R.string.drive_vault_local_step_finish_desc) else null,
            progress = null,
        )
        p.error?.let { code ->
            GcBanner(tone = if (code == "offline" || code == "stopped" || p.needsAuth) GcTone.Warn else GcTone.Error, icon = GcIcons.Alert) {
                Text(localJobText(LocalContext.current, code, p.file), style = MaterialTheme.typography.bodyMedium)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                p.error != null -> {
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.drive_vault_local_retry)) }
                    TextButton(onClick = if (move) onCancel else onClose) { Text(stringResource(if (move) R.string.drive_cancel else R.string.drive_vault_local_close)) }
                }
                move && step != DriveVaultLocalJobs.Step.FINISH -> TextButton(onClick = onCancel) { Text(stringResource(R.string.drive_cancel)) }
            }
        }
    }
}

private enum class StepState { DONE, NOW, LATER }

@Composable
private fun Step(n: Int, state: StepState, title: String, line: String?, progress: Float?) {
    val x = GateControlTheme.extraColors
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val (bg, fg) = when (state) {
            StepState.DONE -> MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.onPrimary
            StepState.NOW -> MaterialTheme.colorScheme.surface to x.blue
            StepState.LATER -> MaterialTheme.colorScheme.surface to x.muted
        }
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(bg)
                .then(if (state != StepState.DONE) Modifier.border(2.dp, if (state == StepState.NOW) x.blue else x.border2, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            if (state == StepState.DONE) Icon(GcIcons.Check, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
            else Text(n.toString(), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = fg)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = if (state == StepState.LATER) x.muted else MaterialTheme.colorScheme.onSurface)
            line?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = x.muted) }
            progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp), color = x.blue, trackColor = x.panel2) }
        }
    }
}

/** What stopped a job, for the card. */
internal fun localJobText(context: Context, code: String, file: String?): String = when (code) {
    "offline" -> context.getString(R.string.drive_vault_local_job_offline)
    "stopped" -> context.getString(R.string.drive_vault_local_job_stopped)
    "verify" -> context.getString(R.string.drive_vault_local_job_verify, file ?: "…")
    "decrypt" -> context.getString(R.string.drive_vault_local_job_decrypt)
    "changed" -> context.getString(R.string.drive_vault_local_job_changed)
    "auth", "proof" -> context.getString(R.string.drive_vault_local_job_auth)
    "count" -> context.getString(R.string.drive_vault_local_job_count)
    "no_space" -> context.getString(R.string.drive_vault_local_msg_no_space)
    else -> vaultLocalText(context, code) ?: context.getString(R.string.drive_vault_local_job_failed, code)
}

/** The messages of "Nur auf diesem Gerät" (null: not one of them). */
internal fun vaultLocalText(context: Context, code: String): String? = when (code) {
    "local_busy" -> context.getString(R.string.drive_vault_local_msg_busy)
    "local_no_space" -> context.getString(R.string.drive_vault_local_msg_no_space)
    "local_nested" -> context.getString(R.string.drive_vault_local_msg_nested)
    "local_shared" -> context.getString(R.string.drive_vault_local_msg_shared)
    "local_only" -> context.getString(R.string.drive_vault_local_msg_only)
    "local_moving" -> context.getString(R.string.drive_vault_local_msg_moving)
    "local_too_large" -> context.getString(R.string.drive_vault_local_msg_too_large)
    "local_off" -> context.getString(R.string.drive_vault_local_msg_off)
    "local_auth" -> context.getString(R.string.drive_vault_local_msg_auth)
    "local_undone", "local_back_done" -> context.getString(R.string.drive_vault_local_msg_back)
    "local_forgotten" -> context.getString(R.string.drive_vault_local_msg_forgotten)
    "local_deleted" -> context.getString(R.string.drive_vault_local_msg_deleted)
    "changed_here" -> context.getString(R.string.drive_vault_local_msg_changed_here)
    "local_locked" -> context.getString(R.string.drive_vault_local_msg_locked)
    else -> null
}

/**
 * At the top of a folder kept only here (A5): what it is and what is in it, "Auf den Server", and while the 24 hours
 * run "Rückgängig". A folder of a device removed from the vault says it is locked.
 */
@Composable
internal fun VaultLocalCard(folder: DriveVaultLocalFolder, undoUntil: Long?, onBack: (() -> Unit)?, onUndo: (() -> Unit)?, extra: @Composable () -> Unit = {}) {
    val x = GateControlTheme.extraColors
    val context = LocalContext.current
    val files = folder.files
    GcCard(borderColor = x.blue.copy(alpha = 0.45f), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GcIconSquare(size = 44.dp, background = x.blueBg) { Icon(GcIcons.Smartphone, contentDescription = null, tint = x.blue, modifier = Modifier.size(22.dp)) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.drive_vault_local_card_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(pluralStringResource(R.plurals.drive_vault_local_card_line, files.size, files.size, DriveLogic.size(folder.bytes)), style = MaterialTheme.typography.bodySmall, color = x.muted)
                undoUntil?.let { Text(stringResource(R.string.drive_vault_local_undo_until, android.text.format.DateUtils.formatDateTime(context, it, android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_SHOW_TIME or android.text.format.DateUtils.FORMAT_ABBREV_MONTH)), style = MaterialTheme.typography.bodySmall, color = x.muted) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            extra()
            onBack?.let {
                TextButton(onClick = it) {
                    Icon(GcIcons.Cloud, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.drive_vault_local_to_server), modifier = Modifier.padding(start = 6.dp))
                }
            }
            onUndo?.let { TextButton(onClick = it) { Text(stringResource(R.string.drive_vault_local_undo)) } }
        }
    }
    if (folder.revoked) {
        GcBanner(tone = GcTone.Warn, icon = GcIcons.Lock) { Text(stringResource(R.string.drive_vault_local_revoked), style = MaterialTheme.typography.bodyMedium) }
    }
}

/** What a placeholder of another device is (A6): its device, since when; for its owner "Platzhalter entfernen …". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VaultPlaceholderSheet(title: String, local: DVaultLocal, onForget: (() -> Unit)?, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        VaultPlaceholderInfo(title, local, onForget)
    }
}

@Composable
internal fun VaultPlaceholderInfo(title: String, local: DVaultLocal, onForget: (() -> Unit)?) {
    val x = GateControlTheme.extraColors
    val context = LocalContext.current
    val device = localDeviceName(local)
    Column(Modifier.padding(bottom = 24.dp)) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.drive_vault_local_other_line, device, localDate(context, local.since) ?: "–"), style = MaterialTheme.typography.bodyMedium, color = x.muted)
        }
        Row(Modifier.padding(horizontal = 24.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            GcIconSquare(size = 44.dp, background = x.blueBg) { Icon(GcIcons.Smartphone, contentDescription = null, tint = x.blue, modifier = Modifier.size(22.dp)) }
            Text(
                when {
                    local.moving -> stringResource(R.string.drive_vault_local_info_moving, device)
                    local.mine -> stringResource(R.string.drive_vault_local_info_mine, device)
                    else -> stringResource(R.string.drive_vault_local_info_other, device)
                },
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
        }
        GcRowDivider()
        GcListRow(
            title = stringResource(R.string.drive_vault_local_open),
            description = stringResource(R.string.drive_vault_local_open_desc, device),
            titleColor = x.muted,
            leading = { Icon(GcIcons.Eye, contentDescription = null, tint = x.muted) },
        )
        onForget?.let {
            GcListRow(
                title = stringResource(R.string.drive_vault_local_forget),
                description = stringResource(R.string.drive_vault_local_forget_desc, device),
                titleColor = MaterialTheme.colorScheme.error,
                onClick = it,
                leading = { Icon(GcIcons.Trash, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            )
        }
    }
}

/** "Zurück auf den Server" (A8), with "Kopie auf diesem Handy behalten" ([keep] first as chosen in the menu). */
@Composable
internal fun VaultLocalBackDialog(name: String, keep: Boolean, onConfirm: (Boolean) -> Unit, onDismiss: () -> Unit) {
    var keepCopy by remember { mutableStateOf(keep) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(GcIcons.CloudUp, contentDescription = null) },
        title = { Text(stringResource(R.string.drive_vault_local_back_title, name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.drive_vault_local_back_text))
                Row(Modifier.fillMaxWidth().toggleable(value = keepCopy, role = Role.Checkbox, onValueChange = { keepCopy = it }), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = keepCopy, onCheckedChange = null)
                    Text(stringResource(R.string.drive_vault_local_back_keep), modifier = Modifier.padding(start = 10.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(keepCopy) }) { Text(stringResource(R.string.drive_vault_local_to_server)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.drive_cancel)) } },
    )
}

/** Deleting something that exists only here: said plainly. */
@Composable
internal fun VaultLocalDeleteDialog(name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.drive_vault_local_delete_title, name)) },
        text = { Text(stringResource(R.string.drive_vault_local_delete_text)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.drive_vault_local_delete_go), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.drive_cancel)) } },
    )
}

/** "Platzhalter entfernen?" — the content of a lost device is gone for good then. */
@Composable
internal fun VaultForgetDialog(name: String, device: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.drive_vault_local_forget_title)) },
        text = { Text(stringResource(R.string.drive_vault_local_forget_text, name, device)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.drive_vault_local_forget_go), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.drive_cancel)) } },
    )
}
