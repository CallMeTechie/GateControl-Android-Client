package com.gatecontrol.android.ui.places

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.R
import com.gatecontrol.android.places.MyPlace
import com.gatecontrol.android.places.MyPlaces
import com.gatecontrol.android.places.PlaceEdit
import com.gatecontrol.android.places.PlaceIcons
import com.gatecontrol.android.places.PlacePicking
import com.gatecontrol.android.places.PlaceUse
import com.gatecontrol.android.places.PlaceVisibility
import com.gatecontrol.android.places.PlacesRepository
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.theme.GateControlTheme
import java.util.Locale

/*
 * The pieces „Meine Orte“ shares with the choice of a place: the seven symbols, the square behind them (green for the own
 * places, blue for the Familie places, amber without a point), the lines under a place and the texts of a refusal.
 */

fun placeIconVector(icon: String): ImageVector = when (icon) {
    PlaceIcons.HOME -> GcIcons.Home
    PlaceIcons.WORK -> GcIcons.Briefcase
    PlaceIcons.SCHOOL -> GcIcons.School
    PlaceIcons.GYM -> GcIcons.Dumbbell
    PlaceIcons.SHOP -> GcIcons.Cart
    PlaceIcons.FAMILY -> GcIcons.Users
    else -> GcIcons.Pin
}

fun placeIconLabel(icon: String): Int = when (icon) {
    PlaceIcons.HOME -> R.string.places_icon_home
    PlaceIcons.WORK -> R.string.places_icon_work
    PlaceIcons.SCHOOL -> R.string.places_icon_school
    PlaceIcons.GYM -> R.string.places_icon_gym
    PlaceIcons.SHOP -> R.string.places_icon_shop
    PlaceIcons.FAMILY -> R.string.places_icon_family
    else -> R.string.places_icon_pin
}

/** The symbol of a place in its rounded square: green (mine), blue (Familie), amber (no point yet). */
@Composable
fun PlaceIconSquare(icon: String, familie: Boolean = false, missing: Boolean = false, size: Dp = 40.dp) {
    val extra = GateControlTheme.extraColors
    val (bg, tint) = when {
        missing -> extra.warnBg to extra.warn
        familie -> extra.blueBg to extra.blue
        else -> extra.accentBg to MaterialTheme.colorScheme.primary
    }
    Box(Modifier.size(size).clip(RoundedCornerShape(12.dp)).background(bg), contentAlignment = Alignment.Center) {
        Icon(placeIconVector(icon), contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.55f))
    }
}

/** A small tag after a name („Familie“, „nur ich“, „kein Standort“). */
@Composable
fun PlaceTag(text: String, warn: Boolean = false) {
    val extra = GateControlTheme.extraColors
    Text(
        text,
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
        color = if (warn) extra.warn else extra.muted,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(if (warn) extra.warnBg else extra.panel2).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

fun visibilityLabel(v: String, short: Boolean): Int = when (v) {
    PlaceVisibility.FAMILY -> R.string.places_vis_family
    PlaceVisibility.ALL -> R.string.places_vis_all
    else -> if (short) R.string.places_vis_self_short else R.string.places_vis_self
}

/** „Ortsnachricht“, „2 Aufgaben“, „3 Plugins“ — what uses a place, in a few words; null: nothing. */
fun usesText(ctx: Context, uses: List<PlaceUse>): String? {
    if (uses.isEmpty()) return null
    val plugins = uses.map { it.plugin }.distinct()
    if (plugins.size > 1) return ctx.resources.getQuantityString(R.plurals.places_uses_plugins, plugins.size, plugins.size)
    return uses.joinToString(", ") { useText(ctx, it) }
}

fun useText(ctx: Context, u: PlaceUse): String = when (u.kind) {
    MyPlaces.KIND_LOCATION_MESSAGE -> ctx.resources.getQuantityString(R.plurals.places_use_location_message, u.count, u.count)
    MyPlaces.KIND_TASK -> ctx.resources.getQuantityString(R.plurals.places_use_task, u.count, u.count)
    MyPlaces.KIND_REMINDER -> ctx.resources.getQuantityString(R.plurals.places_use_reminder, u.count, u.count)
    MyPlaces.KIND_RULE -> ctx.resources.getQuantityString(R.plurals.places_use_rule, u.count, u.count)
    else -> ctx.getString(R.string.places_use_other, u.count, u.pluginName ?: u.plugin)
}

/** The plugin's name for a use („Messenger“, „Notizen“): the server's, else the id without `gatecontrol-`. */
fun pluginLabel(u: PlaceUse): String = u.pluginName ?: u.plugin.removePrefix("gatecontrol-").replaceFirstChar { it.titlecase(Locale.getDefault()) }

/** The line under one of „Meine Orte“: address (or where it came from), radius, what uses it. */
fun placeLine(ctx: Context, p: MyPlace): String = listOfNotNull(
    p.address ?: sourceText(ctx, p.source),
    ctx.getString(R.string.places_radius_short, p.radiusM).takeIf { p.address != null || p.source == MyPlaces.SOURCE_MANUAL },
    usesText(ctx, p.usedBy) ?: ctx.getString(R.string.places_unused),
).joinToString(" · ")

private fun sourceText(ctx: Context, source: String): String? = when (source) {
    "notizen" -> ctx.getString(R.string.places_from, "Notizen")
    "familie" -> ctx.getString(R.string.places_from, "Familie")
    "messenger" -> ctx.getString(R.string.places_from, "Messenger")
    else -> null
}

/** The distance at the end of a row: „hier“ inside the radius, else „3,4 km“. */
@Composable
fun distanceLabel(distanceM: Int?, radiusM: Int?): String? {
    distanceM ?: return null
    if (radiusM != null && distanceM <= radiusM) return stringResource(R.string.places_here)
    val locales = androidx.compose.ui.platform.LocalConfiguration.current.locales
    return PlacePicking.distanceText(distanceM, if (locales.isEmpty) Locale.ROOT else locales[0])
}

/** The sentence for a refused change. */
fun placeErrorText(ctx: Context, r: PlaceEdit.Refused, limit: Int = 100): String = when (r.code) {
    PlacesRepository.CODE_DUPLICATE -> ctx.getString(R.string.places_err_duplicate)
    PlacesRepository.CODE_LIMIT -> ctx.getString(R.string.places_err_limit, limit)
    "NOT_FOUND" -> ctx.getString(R.string.places_err_not_found)
    "RATE_LIMITED" -> ctx.getString(R.string.places_err_rate)
    "NO_USER" -> ctx.getString(R.string.profile_no_user)
    PlacesRepository.CODE_OFFLINE -> ctx.getString(R.string.places_err_offline)
    PlacesRepository.CODE_INVALID -> r.message ?: ctx.getString(R.string.places_err_invalid)
    else -> r.message ?: ctx.getString(R.string.places_err_other)
}
