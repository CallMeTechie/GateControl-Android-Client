package com.gatecontrol.android.ui.mail.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.R
import com.gatecontrol.android.mail.MFreeBusy
import com.gatecontrol.android.mail.MFreeBusyRow
import com.gatecontrol.android.mail.MResult
import com.gatecontrol.android.mail.MSlot
import com.gatecontrol.android.mail.MSuggestions
import com.gatecontrol.android.mail.MWorkHours
import com.gatecontrol.android.mail.MailPlanLogic
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.mail.mailContext
import com.gatecontrol.android.ui.theme.GateControlTheme
import com.gatecontrol.android.ui.update.currentLocale
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** „Frei/Gebucht“ of the event form (Mail 1.9.0): the day's rows and the suggestions; never kept offline. */
data class PlanState(
    val day: LocalDate,
    val rows: MFreeBusy? = null,
    val suggestions: MSuggestions? = null,
    val loading: Boolean = true,
    val offline: Boolean = false,
    val error: MResult<*>? = null,
)

/**
 * The section „Frei/Gebucht“ (mockup Planer): per person and resource a bar of the day (the working hours, widened to
 * the chosen time) with the chosen time as a frame, who cannot then, „Vorschläge, bei denen alle können“ (a tap takes
 * the time over, the length stays), the note and the legend. Never titles: the server sends none.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlannerSection(
    plan: PlanState?,
    allDay: Boolean,
    start: Long,
    end: Long,
    hours: MWorkHours?,
    zone: ZoneId,
    onPick: (MSlot) -> Unit,
) {
    plan ?: return
    val context = mailContext()
    val extra = GateControlTheme.extraColors
    val locale = currentLocale()
    val sel = if (allDay) null else start to end
    val (from, to) = MailPlanLogic.dayWindow(plan.day, hours, sel?.first, sel?.second, zone)
    val dayFmt = DateTimeFormatter.ofPattern(if (locale.language == "de") "EEE d.M." else "EEE M/d", locale)
    Row(Modifier.fillMaxWidth().padding(top = 6.dp, start = 2.dp, end = 2.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.fb_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).semantics { heading() })
        Text(
            plan.day.format(dayFmt) + " · " + stringResource(R.string.fb_hours, clock(from, zone, locale), clock(to, zone, locale)),
            style = MaterialTheme.typography.bodySmall,
            color = extra.muted,
        )
    }
    if (plan.offline) {
        Text(stringResource(R.string.fb_offline), style = MaterialTheme.typography.bodySmall, color = extra.muted, modifier = Modifier.padding(horizontal = 2.dp))
        return
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface)
            .border(1.dp, extra.border, RoundedCornerShape(16.dp)).padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val rows = plan.rows?.items
        when {
            rows == null && plan.loading -> Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(22.dp)) }
            rows == null -> Text(plan.error?.let { planErrorText(context, it) }.orEmpty(), style = MaterialTheme.typography.bodySmall, color = extra.muted)
            else -> {
                val you = stringResource(R.string.fb_you)
                rows.forEach { r -> PlanRow(r, you, from, to, sel, zone) }
                Row {
                    Box(Modifier.width(ROW_LABEL + 8.dp))
                    PlanScale(from, to, zone, Modifier.weight(1f))
                }
                if (sel != null) {
                    val busyText = legendText(MailPlanLogic.BUSY)
                    val tentativeText = legendText(MailPlanLogic.TENTATIVE)
                    val clashes = MailPlanLogic.clashes(rows, sel.first, sel.second)
                    val unknown = MailPlanLogic.unknown(rows)
                    val text = when {
                        clashes.isNotEmpty() -> stringResource(
                            R.string.fb_clash,
                            clock(sel.first, zone, locale),
                            clashes.joinToString(", ") { c -> (if (c.self) you else c.name) + " (" + (if (c.status == MailPlanLogic.BUSY) busyText else tentativeText) + ")" },
                        )
                        unknown.isNotEmpty() -> stringResource(R.string.fb_all_known_free, unknown.joinToString(", ") { u -> MailPlanLogic.label(u).ifBlank { "?" } })
                        else -> stringResource(R.string.fb_all_free)
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(top = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            if (clashes.isNotEmpty()) GcIcons.Alert else GcIcons.Check,
                            contentDescription = null,
                            tint = if (clashes.isNotEmpty()) PlanColors.warn else extra.accentText,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
    Text(stringResource(R.string.fb_suggestions), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp, start = 2.dp))
    val items = plan.suggestions?.items
    if (items != null && items.isEmpty()) {
        Text(stringResource(R.string.fb_no_suggestions), style = MaterialTheme.typography.bodySmall, color = extra.muted)
    } else if (items != null) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items.forEach { x ->
                val on = !allDay && x.start == start
                val day = Instant.ofEpochMilli(x.start).atZone(zone)
                val label = (if (MailPlanLogic.sameDay(x.start, plan.day, zone)) day.format(DateTimeFormatter.ofPattern("EEE", locale)).removeSuffix(".") else day.format(dayFmt)) +
                    " " + clock(x.start, zone, locale)
                val desc = stringResource(R.string.fb_take, label)
                Box(
                    Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(12.dp))
                        .then(if (on) Modifier.background(extra.accentBg) else Modifier.border(1.dp, extra.border2, RoundedCornerShape(12.dp)))
                        .selectable(selected = on, role = Role.Button) { onPick(x) }
                        .semantics { contentDescription = desc }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = if (on) extra.accentText else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
    Text(stringResource(R.string.fb_note), style = MaterialTheme.typography.bodySmall, color = extra.muted, modifier = Modifier.padding(horizontal = 2.dp))
    PlanLegend(listOf(MailPlanLogic.FREE, MailPlanLogic.BUSY, MailPlanLogic.TENTATIVE, MailPlanLogic.UNKNOWN))
}

private val ROW_LABEL = 96.dp

/** One row: avatar and short name („Du“ first), the bar (grey when unknown) with the chosen time as a frame. */
@Composable
private fun PlanRow(r: MFreeBusyRow, you: String, from: Long, to: Long, sel: Pair<Long, Long>?, zone: ZoneId) {
    val context = mailContext()
    val extra = GateControlTheme.extraColors
    val self = r.kind == MFreeBusyRow.SELF
    val name = if (self) you else MailPlanLogic.label(r)
    val color = when {
        !r.known -> Color(0xFF8C96A5)
        self -> Color(0xFF46E0A0)
        r.kind == MFreeBusyRow.RESOURCE -> PlanColors.resource
        r.kind == MFreeBusyRow.MICROSOFT -> Color(0xFF79B8FF)
        else -> Color(0xFFF5B544)
    }
    val gap = if (r.known) MailPlanLogic.FREE else MailPlanLogic.UNKNOWN
    Row(Modifier.fillMaxWidth().heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.width(ROW_LABEL), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(28.dp).clip(CircleShape).background(color.copy(alpha = if (r.known) 0.16f else 0.14f)), contentAlignment = Alignment.Center) {
                Text(if (self) you else MailPlanLogic.initials(r.name.ifBlank { r.email ?: "" }), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = if (r.known) color else extra.muted, maxLines = 1)
            }
            Text(name, style = MaterialTheme.typography.bodySmall, color = if (r.known) MaterialTheme.colorScheme.onSurface else extra.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        PlanBar(
            blocks = r.blocks,
            from = from,
            to = to,
            description = name + ": " + barDescription(context, r.blocks, from, to, zone, gap),
            modifier = Modifier.weight(1f),
            gap = gap,
            frame = sel,
        )
    }
}
