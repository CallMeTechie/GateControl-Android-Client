package com.gatecontrol.android.ui.notifications

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.gatecontrol.android.notify.model.PushMessage
import com.gatecontrol.android.notify.model.PushPriority
import com.gatecontrol.android.notify.model.PushTopic
import com.gatecontrol.android.push.PushText
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.theme.GateControlTheme
import androidx.compose.material3.MaterialTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Label of a topic: own strings for core topics, the server label for plugin topics. */
@Composable
fun topicLabel(topic: String, serverLabel: String?): String =
    PushText.topicLabel(topic)?.let { stringResource(it) } ?: serverLabel ?: PushText.fallbackLabel(topic)

fun topicIcon(topic: String): ImageVector = when (topic) {
    PushTopic.SECURITY -> GcIcons.ShieldX
    PushTopic.DEVICES -> GcIcons.Monitor
    PushTopic.SERVICES -> GcIcons.Globe
    PushTopic.SYSTEM -> GcIcons.Sliders
    PushTopic.ADMIN_NOTICE -> GcIcons.Bell
    else -> GcIcons.Zap
}

/** Foreground and tinted background of a message's icon. */
@Composable
fun messageColors(message: PushMessage): Pair<Color, Color> {
    val extra = GateControlTheme.extraColors
    return when {
        message.priorityLevel == PushPriority.CRITICAL -> MaterialTheme.colorScheme.error to extra.errorBg
        message.priorityLevel == PushPriority.HIGH -> extra.warn to extra.warnBg
        message.topic.startsWith("plugin:") -> extra.blue to extra.blueBg
        else -> extra.accentText to extra.accentBg
    }
}

internal fun instantOf(message: PushMessage): Instant? =
    message.createdAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
        ?: message.receivedAt.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it) }

internal fun localDate(message: PushMessage, zone: ZoneId = ZoneId.systemDefault()): LocalDate? =
    instantOf(message)?.atZone(zone)?.toLocalDate()

internal fun timeOf(message: PushMessage, zone: ZoneId = ZoneId.systemDefault()): String =
    instantOf(message)?.atZone(zone)?.format(DateTimeFormatter.ofPattern("HH:mm")).orEmpty()

internal fun dateOf(date: LocalDate): String = date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

/** Heute / Gestern / date sections, newest first. */
internal fun groupByDay(
    items: List<PushMessage>,
    today: LocalDate = LocalDate.now(),
    zone: ZoneId = ZoneId.systemDefault(),
): List<Pair<LocalDate?, List<PushMessage>>> =
    items.groupBy { localDate(it, zone) }.toList().sortedByDescending { it.first ?: today }

/** Duration "2 h 14 min" / "5 min" / "40 s". */
internal fun shortDuration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    return when {
        h > 0 -> "$h h $m min"
        m > 0 -> "$m min"
        else -> "$s s"
    }
}
