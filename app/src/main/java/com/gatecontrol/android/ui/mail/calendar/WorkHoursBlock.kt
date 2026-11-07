package com.gatecontrol.android.ui.mail.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.R
import com.gatecontrol.android.mail.MWorkHours
import com.gatecontrol.android.mail.MailPlanLogic
import com.gatecontrol.android.ui.theme.GateControlTheme
import com.gatecontrol.android.ui.update.currentLocale
import java.time.DayOfWeek
import java.time.format.TextStyle

/**
 * „Arbeitszeit“ (Mail 1.9.0, `CalPrefs.workHours`): the days (Mo … So) and the time from … to. „Frei/Gebucht“ shows
 * this window and the planner suggests times only within it. Each change goes out at once ([onChange] gets only
 * valid hours: at least one day, „Bis“ after „Von“; midnight as „Bis“ is the end of the day, 24:00).
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun WorkHoursBlock(hours: MWorkHours, onChange: (MWorkHours) -> Unit, modifier: Modifier = Modifier) {
    val extra = GateControlTheme.extraColors
    val locale = currentLocale()
    var pick by remember { mutableStateOf<Boolean?>(null) } // true: „Von“, false: „Bis“
    var problem by remember { mutableStateOf<Int?>(null) }
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.cal_work_hours), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.cal_work_hours_d), style = MaterialTheme.typography.bodySmall, color = extra.muted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            (1..7).forEach { d ->
                val on = d in hours.days
                val name = DayOfWeek.of(d).getDisplayName(TextStyle.SHORT, locale).removeSuffix(".")
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(999.dp))
                        .background(if (on) extra.accentBg else extra.panel2)
                        .selectable(selected = on, role = Role.Checkbox) {
                            val next = MailPlanLogic.toggleDay(hours, d)
                            problem = if (next == null) R.string.cal_work_no_day else null
                            next?.let(onChange)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        name,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (on) extra.accentText else extra.muted,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.cal_work_from), style = MaterialTheme.typography.bodyMedium, color = extra.muted)
            PickerChip(hours.from, stringResource(R.string.cal_work_from) + " " + hours.from) { pick = true }
            Text(stringResource(R.string.cal_work_to), style = MaterialTheme.typography.bodyMedium, color = extra.muted)
            PickerChip(hours.to, stringResource(R.string.cal_work_to) + " " + hours.to) { pick = false }
        }
        problem?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
    pick?.let { from ->
        val cur = if (from) hours.from else hours.to
        val h = cur.substringBefore(':').toIntOrNull()?.let { if (it == 24) 0 else it } ?: 8
        val m = cur.substringAfter(':').toIntOrNull() ?: 0
        val st = rememberTimePickerState(initialHour = h, initialMinute = m, is24Hour = locale.language != "en")
        AlertDialog(
            onDismissRequest = { pick = null },
            text = { TimePicker(st) },
            confirmButton = {
                TextButton(onClick = {
                    pick = null
                    val t = MailPlanLogic.hhmm(st.hour, st.minute, end = !from)
                    val next = if (from) MailPlanLogic.workHours(hours.days, t, hours.to) else MailPlanLogic.workHours(hours.days, hours.from, t)
                    problem = if (next == null) R.string.cal_work_bad else null
                    next?.let(onChange)
                }) { Text(stringResource(R.string.mail_ok)) }
            },
            dismissButton = { TextButton(onClick = { pick = null }) { Text(stringResource(R.string.mail_cancel)) } },
        )
    }
}
