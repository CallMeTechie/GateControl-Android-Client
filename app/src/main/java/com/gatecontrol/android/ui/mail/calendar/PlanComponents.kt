package com.gatecontrol.android.ui.mail.calendar

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gatecontrol.android.R
import com.gatecontrol.android.mail.MBusyBlock
import com.gatecontrol.android.mail.MResource
import com.gatecontrol.android.mail.MViolation
import com.gatecontrol.android.mail.MailPimJson
import com.gatecontrol.android.mail.MailPlanLogic
import com.gatecontrol.android.mail.MResult
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.mail.MailHomeViewModel
import com.gatecontrol.android.ui.mail.mailText
import com.gatecontrol.android.ui.theme.GateControlTheme
import com.google.gson.JsonObject
import java.time.ZoneId

/** The colors of the bars (mockups Planer, Ressource, Anfragen). */
internal object PlanColors {
    val free = Color(0xFF3DDC97).copy(alpha = 0.2f)
    val busy = Color(0xFFFF8080).copy(alpha = 0.32f)
    val tentative = Color(0xFFF5B544).copy(alpha = 0.3f)
    val tentativeStripe = Color(0xFFF5B544).copy(alpha = 0.12f)
    val unknown = Color(0xFF8C96A5).copy(alpha = 0.16f)
    val chosen = Color(0xFF3DDC97)
    val ask = Color(0xFFF5B544).copy(alpha = 0.7f)
    /** Resources among the attendees (chips, rows). */
    val resource = Color(0xFFB79CFF)
    val warn = Color(0xFFF5B544)
    val bad = Color(0xFFFF8A8A)

    fun of(status: String): Color = when (status) {
        MailPlanLogic.BUSY -> busy
        MailPlanLogic.TENTATIVE -> tentative
        MailPlanLogic.UNKNOWN -> unknown
        MailPlanLogic.ASK -> ask
        "sel" -> chosen
        else -> free
    }
}

/**
 * A day's bar of [from, to): free, busy, tentative (striped), unknown; [frame] the chosen time as a frame, [filled]
 * as a filled part („deine Auswahl“). The bar is described as a whole for TalkBack ([description]).
 */
@Composable
internal fun PlanBar(
    blocks: List<MBusyBlock>,
    from: Long,
    to: Long,
    description: String,
    modifier: Modifier = Modifier,
    gap: String = MailPlanLogic.FREE,
    frame: Pair<Long, Long>? = null,
    filled: Pair<Long, Long>? = null,
    height: Dp = 22.dp,
) {
    val segments = MailPlanLogic.bar(blocks, from, to, gap)
    val base = MaterialTheme.colorScheme.background
    BoxWithConstraints(modifier.fillMaxWidth().height(height).clearAndSetSemantics { contentDescription = description }) {
        val w = maxWidth
        Canvas(Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)).background(base)) {
            var x = 0f
            segments.forEach { s ->
                val sw = s.fraction * size.width
                val c = PlanColors.of(s.status)
                drawRect(c, topLeft = Offset(x, 0f), size = androidx.compose.ui.geometry.Size(sw, size.height))
                if (s.status == MailPlanLogic.TENTATIVE) {
                    // stripes like the mockup's vorläufig
                    val left = x
                    clipRect(left = left, top = 0f, right = left + sw, bottom = size.height) {
                        var t = left - size.height
                        val step = 8.dp.toPx()
                        while (t < left + sw) {
                            drawLine(PlanColors.tentativeStripe, Offset(t, size.height), Offset(t + size.height, 0f), strokeWidth = 3.dp.toPx())
                            t += step
                        }
                    }
                }
                x += sw
            }
            filled?.let { (a, z) ->
                MailPlanLogic.frame(a, z, from, to)?.let { (l, fw) ->
                    drawRect(PlanColors.chosen, topLeft = Offset(l * size.width, 0f), size = androidx.compose.ui.geometry.Size(fw * size.width, size.height))
                }
            }
        }
        frame?.let { (a, z) ->
            MailPlanLogic.frame(a, z, from, to)?.let { (l, fw) ->
                Box(
                    Modifier.offset(x = w * l).width((w * fw).coerceAtLeast(4.dp)).height(height)
                        .border(2.dp, PlanColors.chosen, RoundedCornerShape(6.dp)),
                )
            }
        }
    }
}

/** The hours below a bar (8 10 12 …), each at its place. */
@Composable
internal fun PlanScale(from: Long, to: Long, zone: ZoneId, modifier: Modifier = Modifier) {
    val extra = GateControlTheme.extraColors
    val marks = MailPlanLogic.scale(from, to, zone)
    BoxWithConstraints(modifier.fillMaxWidth().height(14.dp).clearAndSetSemantics { }) {
        val w = maxWidth
        marks.forEach { (h, f) ->
            val x = (w * f - 6.dp).coerceIn(0.dp, (w - 14.dp).coerceAtLeast(0.dp))
            Text(h.toString(), fontSize = 10.sp, lineHeight = 12.sp, color = extra.faint, modifier = Modifier.offset(x = x))
        }
    }
}

/** The legend below the bars. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlanLegend(kinds: List<String>) {
    val extra = GateControlTheme.extraColors
    FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(2.dp)) {
        kinds.forEach { k ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(PlanColors.of(k)))
                Text(legendText(k), style = MaterialTheme.typography.bodySmall, color = extra.muted)
            }
        }
    }
}

@Composable
internal fun legendText(status: String): String = androidx.compose.ui.res.stringResource(
    when (status) {
        MailPlanLogic.BUSY -> R.string.fb_legend_busy
        MailPlanLogic.TENTATIVE -> R.string.fb_legend_tentative
        MailPlanLogic.UNKNOWN -> R.string.fb_legend_unknown
        "sel" -> R.string.fb_legend_sel
        else -> R.string.fb_legend_free
    },
)

/** A bar in words: „gebucht 08:00–10:00, vorläufig 14:00–15:00“ (free when nothing is taken). */
internal fun barDescription(context: Context, blocks: List<MBusyBlock>, from: Long, to: Long, zone: ZoneId, gap: String = MailPlanLogic.FREE): String {
    val locale = context.resources.configuration.locales[0]
    val taken = MailPlanLogic.bar(blocks, from, to, gap).filter { it.status != gap }
    if (taken.isEmpty()) return context.getString(if (gap == MailPlanLogic.UNKNOWN) R.string.fb_legend_unknown else R.string.fb_legend_free)
    return taken.joinToString(", ") { s ->
        val label = context.getString(
            when (s.status) {
                MailPlanLogic.TENTATIVE -> R.string.fb_legend_tentative
                MailPlanLogic.ASK -> R.string.fb_legend_sel
                else -> R.string.fb_legend_busy
            },
        )
        "$label ${clock(s.start, zone, locale)}–${clock(s.end, zone, locale)}"
    }
}

/** The symbol of a resource's kind in its color (the mockups' square). */
@Composable
internal fun KindIcon(kind: String, color: String?, size: Dp = 36.dp) {
    val c = calColor(color, fallback = Color(0xFF8C96A5))
    Box(Modifier.size(size).clip(RoundedCornerShape(10.dp)).background(c.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
        Icon(kindIcon(kind), contentDescription = null, tint = c, modifier = Modifier.size(18.dp))
    }
}

internal fun kindIcon(kind: String): ImageVector = when (kind) {
    MResource.ROOM -> GcIcons.Door
    MResource.VEHICLE -> GcIcons.Car
    MResource.DEVICE -> GcIcons.Monitor
    else -> GcIcons.Briefcase
}

/** A small pill (wartet, Konflikt, zugesagt …). */
@Composable
internal fun PlanPill(text: String, color: Color) {
    Box(Modifier.clip(RoundedCornerShape(999.dp)).background(color.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 4.dp)) {
        Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = color)
    }
}

// ─── texts ──────────────────────────────────────────────────────────

/** „1 Tag“, „3 Tage“, „2,5 Std.“. */
internal fun maxText(context: Context, minutes: Int): String {
    val (kind, n) = MailPlanLogic.maxLimit(minutes)
    return when (kind) {
        "day" -> context.getString(R.string.res_day)
        "days" -> context.resources.getQuantityString(R.plurals.res_days, n.toInt(), n.toInt())
        else -> {
            val text = if (n % 1.0 == 0.0) n.toInt().toString() else java.text.NumberFormat.getNumberInstance(context.resources.configuration.locales[0]).format(n)
            context.resources.getQuantityString(R.plurals.res_hours, if (n == 1.0) 1 else 2, text)
        }
    }
}

/** A broken rule in words: „höchstens 1 Tag“, „bis zu 30 Tage im Voraus“, „buchbar 08:00 bis 18:00 Uhr“. */
internal fun ruleText(context: Context, v: MViolation): String = when (v.rule) {
    MViolation.MAX_DURATION -> context.getString(R.string.res_rule_max, maxText(context, v.limit ?: 0))
    MViolation.HORIZON -> context.resources.getQuantityString(R.plurals.res_rule_horizon, v.limit ?: 0, v.limit ?: 0)
    else -> context.getString(R.string.res_rule_hours, v.from.orEmpty(), v.to.orEmpty())
}

/** Who approves a resource („Fuhrpark“, else „die Freigebenden“). */
internal fun approversText(context: Context, r: MResource): String = r.approvers.joinToString(", ").ifBlank { context.getString(R.string.res_approvers) }

/** A resource's line below its name: place · people · equipment · who approves · the longest time. */
internal fun resourceSub(context: Context, r: MResource): String = listOfNotNull(
    r.location,
    r.seats?.let { context.resources.getQuantityString(R.plurals.res_seats, it, it) },
    r.equipment,
    if (r.needsApproval) context.getString(R.string.res_by, approversText(context, r)) else context.getString(R.string.res_auto),
    r.rules.maxMinutes?.let { context.getString(R.string.res_max, maxText(context, it)) },
).joinToString(" · ")

/**
 * A refusal of free/busy, sharing or a booking in words: the rules a booking breaks in brackets, what is not possible
 * in which calendar, a time in the past; else [mailText].
 */
internal fun planErrorText(context: Context, r: MResult<*>): String {
    val code = MailHomeViewModel.code(r)
    val error: JsonObject? = (r as? MResult.Failed)?.error
    fun field(key: String): String? = error?.get(key)?.takeIf { it.isJsonPrimitive }?.asString
    return when {
        code == "rule_violation" -> {
            val rules = MailPimJson.violations(error).map { ruleText(context, it) }
            mailText(context, code) + if (rules.isEmpty()) "" else " (" + rules.joinToString(", ") + ")"
        }
        code == "not_supported" -> when (field("what")) {
            "resource" -> context.getString(R.string.mail_err_res_local)
            "resource_series" -> context.getString(R.string.mail_err_res_series)
            "share" -> context.getString(R.string.mail_err_share_account)
            "shared_attendees" -> context.getString(R.string.share_no_attendees)
            else -> mailText(context, code)
        }
        code == "invalid" && field("reason") == "past" -> context.getString(R.string.mail_err_past)
        else -> mailText(context, code)
    }
}
