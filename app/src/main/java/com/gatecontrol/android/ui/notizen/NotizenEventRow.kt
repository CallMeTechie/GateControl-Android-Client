package com.gatecontrol.android.ui.notizen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gatecontrol.android.R
import com.gatecontrol.android.notizen.NItem
import com.gatecontrol.android.notizen.NotizenEvents
import com.gatecontrol.android.notizen.NotizenFormat
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.theme.GateControlTheme

/**
 * A row of an event of the calendar (plugin 1.9.0, Bild 7): the calendar's sign in its colour instead of a tick — an
 * event is never ticked off nor overdue; the second line the day (not today), „ganztägig“, the place, the calendar; the
 * time on the right. Never „Mail“ as where it comes from. A waiting one says „wartet auf Verbindung“.
 */
@Composable
internal fun NEventRow(item: NItem, ctx: RowCtx, onOpen: () -> Unit, divider: Boolean) {
    val x = GateControlTheme.extraColors
    val ev = item.event ?: return
    val day = (item.show ?: item.due)
    val pending = NotizenEvents.isPending(item)
    val color = if (pending) x.muted else calendarColor(ev.color)
    val calendarName = ev.calendarName
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(role = Role.Button, enabled = !pending, onClick = onOpen)
                .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.padding(top = 2.dp).size(28.dp).clip(RoundedCornerShape(8.dp)).background(color),
                contentAlignment = Alignment.Center,
            ) {
                Icon(GcIcons.Calendar, stringResource(R.string.notizen_ev_cd, calendarName.orEmpty()), tint = Color.White, modifier = Modifier.size(16.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                val parts = mutableListOf<String>()
                if (day != null && day.date != ctx.today) parts.add(NotizenFormat.shortDate(day.date, ctx.lang))
                if (item.allDay || day?.time == null) parts.add(stringResource(R.string.notizen_all_day))
                (ev.location ?: item.subtitle)?.let { parts.add(it) }
                calendarName?.takeIf { it != ev.location }?.let { parts.add(it) }
                if (pending) parts.add(stringResource(R.string.notizen_ev_waiting))
                if (parts.isNotEmpty()) Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = x.muted)
            }
            if (!item.allDay) NotizenFormat.time(day?.time)?.let {
                Text(it, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), modifier = Modifier.padding(top = 2.dp))
            }
        }
        if (divider) Box(Modifier.fillMaxWidth().height(1.dp).background(x.border))
    }
}
