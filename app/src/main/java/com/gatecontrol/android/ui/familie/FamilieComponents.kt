package com.gatecontrol.android.ui.familie

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.toColorInt
import com.gatecontrol.android.R
import com.gatecontrol.android.familie.FamilieApi
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.theme.GateControlTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Readable text for an error code of the Familie API. */
@StringRes
fun familieErrorRes(code: String?): Int = when (code) {
    "not_member" -> R.string.familie_error_not_member
    "module_off" -> R.string.familie_error_module_off
    "parents_only" -> R.string.familie_error_parents_only
    "too_soon" -> R.string.familie_error_too_soon
    "daily_limit" -> R.string.familie_error_daily_limit
    "rate_limited" -> R.string.familie_error_rate_limited
    "not_found", "http_404" -> R.string.familie_error_not_found
    FamilieApi.CODE_OFFLINE -> R.string.familie_error_offline
    "no_hits" -> R.string.familie_error_no_hits
    "invalid", "http_400" -> R.string.familie_error_invalid
    "forbidden", "no_user" -> R.string.familie_error_forbidden
    "read_only" -> R.string.familie_error_read_only
    "adult" -> R.string.familie_error_adult
    "locate_off" -> R.string.familie_error_locate_off
    "not_pending" -> R.string.familie_locate_not_open
    "too_many_places" -> R.string.familie_error_too_many_places
    "too_large", "http_413" -> R.string.familie_error_too_large
    "http_502", "http_503", "http_504" -> R.string.familie_error_plugin
    "no_permission" -> R.string.familie_locate_fail_permission
    "location_off" -> R.string.familie_locate_fail_off
    "timeout" -> R.string.familie_locate_fail_timeout
    else -> R.string.familie_error_generic
}

/** Readable text for an error code (the generic text names the code). */
@Composable
fun familieErrorText(code: String?): String {
    val res = familieErrorRes(code)
    return if (res == R.string.familie_error_generic) stringResource(res, code ?: "?") else stringResource(res)
}

/** Round avatar with initials in the member's colour (mockup `.av`). */
@Composable
fun FamilieAvatar(
    name: String,
    color: String?,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    highlighted: Boolean = false,
) {
    val (bg, fg) = avatarColors(color)
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(bg)
            .then(if (highlighted) Modifier.border(2.dp, accent, CircleShape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initials(name),
            color = fg,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold, fontSize = (size.value * 0.34f).sp),
            maxLines = 1,
        )
    }
}

fun initials(name: String): String {
    val parts = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return when {
        parts.isEmpty() -> "?"
        parts.size == 1 -> parts[0].take(2).uppercase()
        else -> (parts[0].take(1) + parts[1].take(1)).uppercase()
    }
}

@Composable
@ReadOnlyComposable
private fun avatarColors(hex: String?): Pair<Color, Color> {
    val extra = GateControlTheme.extraColors
    val base = parseHexColor(hex)
        ?: return extra.accentBg to extra.accentText
    return if (extra.isDark) base.copy(alpha = 0.35f) to Color.White else base.copy(alpha = 0.16f) to base
}

/** Parses `#RRGGBB`, else null. */
fun parseHexColor(hex: String?): Color? = hex?.let { runCatching { Color(it.toColorInt()) }.getOrNull() }

/** Centred spinner while a screen loads for the first time. */
@Composable
fun FamilieLoading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
}

/** Error banner with an optional retry. */
@Composable
fun FamilieErrorBanner(code: String?, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GcBanner(tone = if (code == FamilieApi.CODE_OFFLINE) GcTone.Warn else GcTone.Error, icon = GcIcons.Alert) {
            Text(
                familieErrorText(code),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (onRetry != null) {
            GcOutlineButton(text = stringResource(R.string.familie_retry), onClick = onRetry, icon = GcIcons.Refresh)
        }
    }
}

/** Empty state: icon and one line. */
@Composable
fun FamilieEmpty(icon: ImageVector, text: String, modifier: Modifier = Modifier) {
    val extra = GateControlTheme.extraColors
    Column(
        modifier.fillMaxWidth().padding(vertical = 28.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, contentDescription = null, tint = extra.faint, modifier = Modifier.size(36.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = extra.muted, textAlign = TextAlign.Center)
    }
}

/** The app's UI locale (Settings → language), for dates and times. */
@Composable
@ReadOnlyComposable
fun appLocale(): Locale = LocalConfiguration.current.locales[0] ?: Locale.ROOT

/** Date and time formatting of the Familie screens (device time zone). */
object FamilieFormat {

    fun parseInstant(iso: String?): Instant? = iso?.let {
        runCatching { Instant.parse(it) }.getOrNull()
            ?: runCatching { Instant.parse(it.replace(' ', 'T') + if (it.endsWith("Z")) "" else "Z") }.getOrNull()
    }

    fun parseDate(date: String?): LocalDate? = date?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    /** `16:42` */
    fun time(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofPattern("HH:mm").format(instant.atZone(zone))

    /** "Di., 14. Okt." / "Tue, 14 Oct" */
    fun day(date: LocalDate, locale: Locale): String =
        DateTimeFormatter.ofPattern(if (locale.language == "de") "EEE, d. MMM" else "EEE, d MMM", locale).format(date)

    /** Relative day of [date] against [today]: 0 today, 1 tomorrow, -1 yesterday … */
    fun daysFrom(today: LocalDate, date: LocalDate): Long = ChronoUnit.DAYS.between(today, date)
}

/** "heute 16:42", "gestern 17:48", "Mo., 13. Okt. 13:20". */
@Composable
fun relativeStamp(iso: String?): String {
    val instant = FamilieFormat.parseInstant(iso) ?: return "–"
    val zone = ZoneId.systemDefault()
    val date = instant.atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    val time = FamilieFormat.time(instant, zone)
    return when (FamilieFormat.daysFrom(today, date)) {
        0L -> stringResource(R.string.familie_today_at, time)
        -1L -> stringResource(R.string.familie_yesterday_at, time)
        else -> FamilieFormat.day(date, appLocale()) + " " + time
    }
}

/** "heute", "morgen", "in 6 Tagen", else the date. */
@Composable
fun relativeDay(date: String): String {
    val d = FamilieFormat.parseDate(date) ?: return date
    val today = LocalDate.now()
    return when (val n = FamilieFormat.daysFrom(today, d)) {
        0L -> stringResource(R.string.familie_today)
        1L -> stringResource(R.string.familie_tomorrow)
        in 2L..13L -> pluralStringResource(R.plurals.familie_in_days, n.toInt(), n.toInt())
        else -> FamilieFormat.day(d, appLocale())
    }
}
