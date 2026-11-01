package com.gatecontrol.android.ui.drive

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.R
import com.gatecontrol.android.drive.DCalendarEntry
import com.gatecontrol.android.drive.DDeadlineCalendars
import com.gatecontrol.android.drive.DDeadlineTarget
import com.gatecontrol.android.drive.DriveDeadlineTargets
import com.gatecontrol.android.ui.theme.GateControlTheme

// ─── Drive 1.17: where deadlines go — the family calendar or a calendar of the mail plugin ──

/** The value of an area's target: "Nicht eintragen", "Familienkalender" or "Outlook · Kalender". */
@Composable
internal fun deadlineTargetText(target: DDeadlineTarget?, calendars: DDeadlineCalendars): String = when {
    target == null -> stringResource(R.string.drive_dl_target_off)
    target.mail -> DriveDeadlineTargets.mailText(target, calendars) ?: stringResource(R.string.drive_dl_target_mail)
    else -> stringResource(R.string.drive_dl_target_family)
}

/** "Im Kalender · Outlook" of a deadline's entry; the family calendar as before ("Im Familienkalender"). */
@Composable
internal fun calendarDoneText(entry: DCalendarEntry): String = when (val t = DriveDeadlineTargets.entryText(entry)) {
    null -> stringResource(R.string.drive_ki_calendar_done)
    "" -> stringResource(R.string.drive_dl_calendar_done)
    else -> stringResource(R.string.drive_dl_calendar_done_in, t)
}

/**
 * The choice of a target (radio rows ≥ 48 dp): "Nicht eintragen" and the family calendar first, then the mail
 * calendars under their account. Without [confirm] a tap picks at once (the settings); with it the tap marks and the
 * button picks (a single deadline, [selected] the area's target).
 */
@Composable
internal fun DeadlineTargetDialog(
    title: String,
    groups: List<DriveDeadlineTargets.Group>,
    selected: DDeadlineTarget?,
    onSelect: (DDeadlineTarget?) -> Unit,
    onDismiss: () -> Unit,
    confirm: String? = null,
) {
    val x = GateControlTheme.extraColors
    var marked by remember(selected) { mutableStateOf(selected) }
    fun isOn(t: DDeadlineTarget?) = if (t == null) marked == null else t.sameAs(marked)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
                groups.forEach { g ->
                    g.account?.let { a ->
                        Text(
                            a.label.ifBlank { stringResource(R.string.drive_dl_target_mail) },
                            style = MaterialTheme.typography.labelMedium,
                            color = x.muted,
                            modifier = Modifier.padding(top = 12.dp, bottom = 2.dp).semantics { heading() },
                        )
                    }
                    g.choices.forEach { c ->
                        val on = isOn(c.target)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .selectable(selected = on, role = Role.RadioButton, onClick = {
                                    if (confirm == null) onSelect(c.target) else marked = c.target
                                }),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = on, onClick = null)
                            calendarColor(c.calendar?.color)?.let { color ->
                                Box(Modifier.padding(start = 12.dp).size(10.dp).background(color, CircleShape))
                            }
                            Text(
                                when {
                                    c.calendar != null -> c.calendar.name
                                    c.target == null -> stringResource(R.string.drive_dl_target_off)
                                    else -> stringResource(R.string.drive_dl_target_family)
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(start = 12.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (confirm != null) TextButton(onClick = { onSelect(marked) }, enabled = marked != null) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.drive_cancel)) } },
    )
}

/** `#rrggbb` of a mail calendar as a colour (none: no dot). */
private fun calendarColor(hex: String?): Color? = hex?.removePrefix("#")?.toLongOrNull(16)?.let { Color(0xFF000000 or it) }
