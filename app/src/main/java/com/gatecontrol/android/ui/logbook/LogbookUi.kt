package com.gatecontrol.android.ui.logbook

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.R
import com.gatecontrol.android.logbook.LogbookLogic
import com.gatecontrol.android.logbook.LogbookPurpose
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.familie.appLocale
import com.gatecontrol.android.ui.theme.GateControlTheme
import java.text.NumberFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Purposes in the order of the trip screen's selector: offen, privat, geschäftlich, Arbeitsweg (mockup). */
internal val PURPOSE_CHOICES: List<String?> = listOf(null, LogbookPurpose.PRIVATE, LogbookPurpose.BUSINESS, LogbookPurpose.COMMUTE)

/** Quick purpose buttons of a trip without one (list): privat, geschäftlich, Arbeitsweg. */
internal val PURPOSE_QUICK: List<String> = listOf(LogbookPurpose.PRIVATE, LogbookPurpose.BUSINESS, LogbookPurpose.COMMUTE)

/** Colours of a purpose in the app's theme: [fg] text and icon, [bar] the sum bar, [bg] the icon square. */
internal data class PurposeColors(val fg: Color, val bar: Color, val bg: Color)

/** Orange of "Privat" (no theme token): darker on light, lighter on dark, both well readable. */
private val PrivateLight = Color(0xFFB45309)
private val PrivateDark = Color(0xFFFFA552)

@Composable
internal fun purposeColors(purpose: String?): PurposeColors {
    val extra = GateControlTheme.extraColors
    return when (purpose) {
        LogbookPurpose.COMMUTE -> PurposeColors(extra.accentText, MaterialTheme.colorScheme.primary, extra.accentBg)
        LogbookPurpose.BUSINESS -> PurposeColors(extra.blue, extra.blue, extra.blueBg)
        LogbookPurpose.PRIVATE -> {
            val c = if (extra.isDark) PrivateDark else PrivateLight
            PurposeColors(c, c, c.copy(alpha = 0.13f))
        }
        // Open: amber in the list (something to do), grey in the bar.
        else -> PurposeColors(extra.warn, extra.faint, extra.warnBg)
    }
}

/**
 * Colours of the routes on the map. The OpenStreetMap tiles are light in
 * both themes, so the lines keep the dark, well-contrasting tones there.
 */
internal fun mapColor(purpose: String?): Int = when (purpose) {
    LogbookPurpose.COMMUTE -> 0xFF0B8A5C.toInt()
    LogbookPurpose.BUSINESS -> 0xFF1A56C4.toInt()
    LogbookPurpose.PRIVATE -> 0xFFB45309.toInt()
    else -> 0xFF566274.toInt()
}

internal fun purposeIcon(purpose: String?): ImageVector = when (purpose) {
    LogbookPurpose.COMMUTE -> GcIcons.Commute
    LogbookPurpose.BUSINESS -> GcIcons.Briefcase
    LogbookPurpose.PRIVATE -> GcIcons.House
    else -> GcIcons.Alert
}

@Composable
internal fun purposeLabel(purpose: String?): String = stringResource(
    when (purpose) {
        LogbookPurpose.COMMUTE -> R.string.logbook_purpose_commute
        LogbookPurpose.BUSINESS -> R.string.logbook_purpose_business
        LogbookPurpose.PRIVATE -> R.string.logbook_purpose_private
        else -> R.string.logbook_purpose_open
    },
)

/** "64", "4,5" in the app's language. */
@Composable
internal fun km(value: Double): String = LogbookLogic.formatKm(value, decimalComma = appLocale().language == "de")

/** "Oktober 2026". */
@Composable
internal fun monthLabel(month: String?): String {
    val m = LogbookLogic.parseMonth(month) ?: return month ?: ""
    return monthLabel(m, appLocale())
}

internal fun monthLabel(m: YearMonth, locale: Locale): String =
    m.format(DateTimeFormatter.ofPattern("LLLL yyyy", locale)).replaceFirstChar { it.titlecase(locale) }

/** "Dienstag, 21. Oktober" / "Tuesday, October 21". */
internal fun dayLabel(date: String, locale: Locale): String {
    val d = runCatching { LocalDate.parse(date) }.getOrNull() ?: return date
    val pattern = if (locale.language == "de") "EEEE, d. MMMM" else "EEEE, MMMM d"
    return d.format(DateTimeFormatter.ofPattern(pattern, locale))
}

/** Odometer: "23.418". */
internal fun odometer(value: Double, locale: Locale): String = NumberFormat.getIntegerInstance(locale).format(value)

/** "57 Min" / "1 Std. 4 Min". */
@Composable
internal fun durationText(minutes: Long): String =
    if (minutes < 60) stringResource(R.string.logbook_duration_min, minutes.toInt())
    else stringResource(R.string.logbook_duration_hours, (minutes / 60).toInt(), (minutes % 60).toInt())

/** Text of an error code of the logbook API. */
@Composable
internal fun logbookErrorText(code: String?): String = when (code) {
    null, "offline", "http_0" -> stringResource(R.string.logbook_error_offline)
    "not_found", "http_404", "unknown_route", "no_handler" -> stringResource(R.string.logbook_error_not_found)
    "SKODA_NOT_OWNER", "not_owner", "forbidden", "http_403" -> stringResource(R.string.logbook_error_not_owner)
    else -> stringResource(R.string.logbook_error_generic, code)
}

/** An error with "Erneut versuchen". */
@Composable
internal fun LogbookErrorBanner(code: String?, onRetry: (() -> Unit)?, modifier: Modifier = Modifier) {
    GcBanner(modifier = modifier, tone = if (code == null || code == "offline") GcTone.Warn else GcTone.Error, icon = GcIcons.Alert) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(logbookErrorText(code), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            if (onRetry != null) GcOutlineButton(text = stringResource(R.string.logbook_retry), onClick = onRetry, minHeight = 40.dp)
        }
    }
}

/** The purpose's icon on its tinted square (list rows, map sheet). */
@Composable
internal fun PurposeSquare(purpose: String?, size: Dp = 40.dp, description: String? = null) {
    val c = purposeColors(purpose)
    Box(
        Modifier.size(size).clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).background(c.bg),
        contentAlignment = Alignment.Center,
    ) {
        Icon(purposeIcon(purpose), contentDescription = description, tint = c.fg, modifier = Modifier.size(size / 2))
    }
}

/**
 * ‹ Oktober 2026 › — previous month always (down to 2000), next month only
 * up to the current one ([onNext] null: disabled). [framed] gives the
 * arrows their own round frames (the list); without, they sit in a pill
 * (the map's head).
 */
@Composable
internal fun MonthSwitcher(
    label: String,
    onPrevious: (() -> Unit)?,
    onNext: (() -> Unit)?,
    modifier: Modifier = Modifier,
    framed: Boolean = true,
) {
    val extra = GateControlTheme.extraColors
    val scheme = MaterialTheme.colorScheme
    @Composable
    fun Arrow(icon: ImageVector, description: String, onClick: (() -> Unit)?) {
        val frame = if (framed) Modifier.clip(CircleShape).background(scheme.surface).border(1.dp, extra.border, CircleShape) else Modifier
        GcIconButton(
            icon = icon,
            contentDescription = description,
            onClick = { onClick?.invoke() },
            enabled = onClick != null,
            size = if (framed) 44.dp else 36.dp,
            iconSize = 18.dp,
            tint = if (onClick != null) scheme.onSurface else extra.border2,
            modifier = frame,
        )
    }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Arrow(GcIcons.ChevronLeft, stringResource(R.string.logbook_prev_month), onPrevious)
        Text(
            label,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = scheme.onSurface,
            maxLines = 1,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Arrow(GcIcons.ChevronRight, stringResource(R.string.logbook_next_month), onNext)
    }
}
