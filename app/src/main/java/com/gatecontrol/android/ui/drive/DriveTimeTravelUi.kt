package com.gatecontrol.android.ui.drive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.gatecontrol.android.R
import com.gatecontrol.android.drive.DTimeTravelPreview
import com.gatecontrol.android.drive.DTimeTravelResult
import com.gatecontrol.android.drive.DriveTimeTravel
import com.gatecontrol.android.ui.chat.DateTimePickerDialog
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.theme.GateControlTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** A time (ms or ISO 8601) as the phone writes it: "27.10.2026, 14:05". */
internal fun driveDateTime(ms: Long): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(ms))

internal fun driveDateTime(iso: String?): String? = iso?.let { runCatching { driveDateTime(Instant.parse(it).toEpochMilli()) }.getOrNull() }

/**
 * "Auf früheren Stand zurücksetzen…": the time (date, then hour), the preview (what comes back, what goes into the
 * trash, examples, a note when Drive cannot turn back older renames), the run, its result with "Rückgängig".
 */
@Composable
internal fun TimeTravelDialogs(
    t: DriveTimeTravel,
    onPick: (Long) -> Unit,
    onRepick: () -> Unit,
    onRun: () -> Unit,
    onUndo: () -> Unit,
    onClose: () -> Unit,
) {
    val title = stringResource(R.string.drive_tt_title, t.folder.name)
    when (t.step) {
        DriveTimeTravel.Step.PICK -> if (t.error == null) {
            DateTimePickerDialog(t.atMs ?: (System.currentTimeMillis() - 3_600_000L), onPick = onPick, onDismiss = onClose)
        } else {
            AlertDialog(
                onDismissRequest = onClose,
                title = { Text(title) },
                text = { Text(ttError(t.error)) },
                confirmButton = { TextButton(onClick = onRepick) { Text(stringResource(R.string.drive_tt_other_time)) } },
                dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.drive_cancel)) } },
            )
        }
        DriveTimeTravel.Step.PREVIEWING -> Busy(title, stringResource(R.string.drive_tt_previewing))
        DriveTimeTravel.Step.RUNNING -> Busy(title, stringResource(R.string.drive_tt_running))
        DriveTimeTravel.Step.UNDOING -> Busy(title, stringResource(R.string.drive_tt_undoing))
        DriveTimeTravel.Step.PREVIEW -> {
            val p = t.preview ?: return
            AlertDialog(
                onDismissRequest = onClose,
                title = { Text(title) },
                text = { PreviewText(t, p) },
                confirmButton = { TextButton(onClick = onRun, enabled = t.canRun) { Text(stringResource(R.string.drive_tt_run)) } },
                dismissButton = {
                    Row {
                        TextButton(onClick = onRepick) { Text(stringResource(R.string.drive_tt_other_time)) }
                        TextButton(onClick = onClose) { Text(stringResource(R.string.drive_cancel)) }
                    }
                },
            )
        }
        DriveTimeTravel.Step.DONE -> {
            val r = t.result ?: return
            AlertDialog(
                onDismissRequest = onClose,
                title = { Text(stringResource(if (t.undone) R.string.drive_tt_undone_title else R.string.drive_tt_done_title)) },
                text = { ResultText(r, t.error) },
                confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.drive_tt_done)) } },
                dismissButton = if (t.canUndo) ({ TextButton(onClick = onUndo) { Text(stringResource(R.string.drive_tt_undo)) } }) else null,
            )
        }
    }
}

@Composable
private fun ttError(code: String): String = when {
    code == DriveTimeTravel.FUTURE -> stringResource(R.string.drive_tt_future)
    code.startsWith(DriveTimeTravel.TOO_MANY) -> stringResource(R.string.drive_tt_too_many, code.substringAfter(':').toIntOrNull() ?: 0)
    else -> driveMessage(code).orEmpty()
}

@Composable
private fun Busy(title: String, text: String) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun PreviewText(t: DriveTimeTravel, p: DTimeTravelPreview) {
    val x = GateControlTheme.extraColors
    Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        t.atMs?.let { Text(stringResource(R.string.drive_tt_to, driveDateTime(it)), fontWeight = FontWeight.SemiBold) }
        if (p.total == 0) {
            Text(stringResource(R.string.drive_tt_nothing), color = x.muted)
        } else {
            if (p.restore > 0) Text("• " + pluralStringResource(R.plurals.drive_tt_restore, p.restore, p.restore))
            if (p.revert > 0) Text("• " + pluralStringResource(R.plurals.drive_tt_revert, p.revert, p.revert))
            if (p.trash > 0) Text("• " + pluralStringResource(R.plurals.drive_tt_trash, p.trash, p.trash))
            if (p.move > 0) Text("• " + pluralStringResource(R.plurals.drive_tt_move, p.move, p.move))
            if (p.lost > 0) Text("• " + pluralStringResource(R.plurals.drive_tt_lost, p.lost, p.lost), color = x.warn)
            if (p.sample.isNotEmpty()) {
                Text(stringResource(R.string.drive_tt_examples), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
                p.sample.take(6).forEach { s ->
                    val op = when (s.op) {
                        "restore" -> stringResource(R.string.drive_tt_op_restore)
                        "revert" -> stringResource(R.string.drive_tt_op_revert)
                        "trash" -> stringResource(R.string.drive_tt_op_trash)
                        "move" -> stringResource(R.string.drive_tt_op_move)
                        else -> s.op
                    }
                    Text("${s.path?.takeIf { it.isNotBlank() } ?: s.name} · $op", style = MaterialTheme.typography.bodySmall, color = x.muted)
                }
            }
            Text(stringResource(R.string.drive_tt_safe), style = MaterialTheme.typography.bodySmall, color = x.muted)
        }
        if (p.limited) {
            GcBanner(tone = GcTone.Warn, icon = GcIcons.Alert) {
                Text(
                    driveDateTime(p.historySince)?.let { stringResource(R.string.drive_tt_limited, it) } ?: stringResource(R.string.drive_tt_limited_unknown),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        t.error?.let { GcBanner(tone = GcTone.Error, icon = GcIcons.Alert) { Text(ttError(it), style = MaterialTheme.typography.bodySmall) } }
    }
}

@Composable
private fun ResultText(r: DTimeTravelResult, error: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val lines = listOfNotNull(
            r.restored.takeIf { it > 0 }?.let { stringResource(R.string.drive_tt_restored, it) },
            r.reverted.takeIf { it > 0 }?.let { stringResource(R.string.drive_tt_reverted, it) },
            r.trashed.takeIf { it > 0 }?.let { stringResource(R.string.drive_tt_trashed, it) },
            r.moved.takeIf { it > 0 }?.let { stringResource(R.string.drive_tt_moved, it) },
        )
        if (lines.isEmpty()) Text(stringResource(R.string.drive_tt_nothing_done)) else lines.forEach { Text("• $it") }
        if (r.failed > 0) Text(stringResource(R.string.drive_tt_failed, r.failed), color = MaterialTheme.colorScheme.error)
        if (r.lost > 0) Text(pluralStringResource(R.plurals.drive_tt_lost, r.lost, r.lost), color = GateControlTheme.extraColors.warn)
        error?.let { Text(ttError(it), color = MaterialTheme.colorScheme.error) }
    }
}
