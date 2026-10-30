package com.gatecontrol.android.ui.mail

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.gatecontrol.android.mail.MailEntryPoint
import dagger.hilt.android.EntryPointAccessors
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.gatecontrol.android.R
import com.gatecontrol.android.mail.MAccount
import com.gatecontrol.android.mail.MSummary
import com.gatecontrol.android.mail.MTodo
import com.gatecontrol.android.mail.MailLogic
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.theme.GateControlTheme
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Icons of the Mail screens the common set lacks (24×24 stroke paths, as in the mockups). */
internal object MailIcons {
    val Send: ImageVector by lazy { GcIcons.fromPath("MailSend", "M3 11 21 3l-8 18-2-8z") }
    val Inbox: ImageVector by lazy { GcIcons.fromPath("MailInbox", "M3 13h5l2 3h4l2-3h5M5 5h14l2 8v6H3v-6z") }
    val Reply: ImageVector by lazy { GcIcons.fromPath("MailReply", "M9 14 4 9l5-5M4 9h10a6 6 0 0 1 6 6v4") }
    val Forward: ImageVector by lazy { GcIcons.fromPath("MailForward", "m15 14 5-5-5-5M20 9H10a6 6 0 0 0-6 6v4") }
    val Sparkle: ImageVector by lazy {
        GcIcons.fromPath("MailSparkle", "M12 3v3M12 18v3M3 12h3M18 12h3M5.6 5.6l2.1 2.1M16.3 16.3l2.1 2.1M5.6 18.4l2.1-2.1M16.3 7.7l2.1-2.1")
    }
    val Pen: ImageVector by lazy { GcIcons.fromPath("MailPen", "M4 20h4l10-10-4-4L4 16z") }
    val Outbox: ImageVector by lazy { GcIcons.fromPath("MailOutbox", "M12 15V3m0 0-4 4m4-4 4 4M3 13h5l2 3h4l2-3h5v6H3z") }
    val Unread: ImageVector by lazy { GcIcons.fromPath("MailUnread", "M3 5h18v14H3zM3 7l9 6 9-6") }
}

/** The account's color (`#RRGGBB`), else a neutral one. */
@Composable
internal fun accountColor(account: MAccount?): Color =
    MailLogic.color(account?.color)?.let { Color(it) } ?: GateControlTheme.extraColors.muted

internal fun accountColor(hex: String?, fallback: Color): Color = MailLogic.color(hex)?.let { Color(it) } ?: fallback

/** Round dot in the account's color. */
@Composable
internal fun MailDot(color: Color, size: Int = 8) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(color))
}

/** An account's picture ([MAccount.avatar]) as the app keeps it; null without one, while loading or offline. */
@Composable
private fun rememberMailAvatar(mediaId: String?): ImageBitmap? {
    val context = LocalContext.current.applicationContext
    val preview = LocalInspectionMode.current
    val store = remember(context, preview) {
        if (preview) null else runCatching { EntryPointAccessors.fromApplication(context, MailEntryPoint::class.java).mailAvatars() }.getOrNull()
    }
    val bitmap by produceState(mediaId?.let { store?.peek(it) }?.asImageBitmap(), mediaId, store) {
        value = if (mediaId == null || store == null) null else value ?: store.bitmap(mediaId)?.asImageBitmap()
    }
    return bitmap
}

/**
 * An account as the Outlook app shows it: round, its picture (Microsoft) or the provider's mark — own simple signs
 * in the provider's color, no logos ("O", four squares, "G", "GMX", "W", "T", else "@" in the account's color) —
 * with a small dot in the account's color.
 */
@Composable
internal fun MailAccountAvatar(account: MAccount?, size: Dp = 36.dp, dot: Boolean = true, ring: Color = MaterialTheme.colorScheme.surface) {
    val picture = rememberMailAvatar(account?.avatar?.mediaId)
    val color = accountColor(account)
    Box(Modifier.size(size)) {
        Box(Modifier.matchParentSize().clip(CircleShape), contentAlignment = Alignment.Center) {
            if (picture != null) {
                Image(picture, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
            } else {
                ProviderMark(MailLogic.mark(account?.provider), color, size)
            }
        }
        if (dot) {
            val d = (size * 0.34f).coerceAtLeast(9.dp)
            Box(
                Modifier.align(Alignment.BottomEnd).offset(x = 2.dp, y = 2.dp).size(d).clip(CircleShape).background(ring).padding(2.dp).clip(CircleShape).background(color),
            )
        }
    }
}

@Composable
private fun BoxScope.ProviderMark(mark: MailLogic.Mark, accountColor: Color, size: Dp) {
    val density = LocalDensity.current
    @Composable
    fun letter(text: String, bg: Color, fg: Color, scale: Float = 0.46f) {
        Box(Modifier.matchParentSize().background(bg))
        Text(text, color = fg, fontWeight = FontWeight.Bold, fontSize = with(density) { (size * scale).toSp() }, maxLines = 1)
    }
    when (mark) {
        MailLogic.Mark.OUTLOOK -> letter("O", Color(0xFF0F6CBD), Color.White)
        MailLogic.Mark.M365 -> {
            Box(Modifier.matchParentSize().background(Color(0xFFF3F4F6)))
            Canvas(Modifier.size(size * 0.44f)) {
                val gap = this.size.width * 0.08f
                val side = (this.size.width - gap) / 2
                val tiles = listOf(Color(0xFFF25022), Color(0xFF7FBA00), Color(0xFF00A4EF), Color(0xFFFFB900))
                tiles.forEachIndexed { i, c ->
                    drawRect(c, topLeft = Offset((i % 2) * (side + gap), (i / 2) * (side + gap)), size = Size(side, side))
                }
            }
        }
        MailLogic.Mark.GMAIL -> letter("G", Color.White, Color(0xFFEA4335))
        MailLogic.Mark.GMX -> letter("GMX", Color(0xFF1C449B), Color.White, scale = 0.27f)
        MailLogic.Mark.WEBDE -> letter("W", Color(0xFFFFD800), Color(0xFF1A1A1A))
        MailLogic.Mark.TONLINE -> letter("T", Color(0xFFE20074), Color.White)
        MailLogic.Mark.OTHER -> letter("@", accountColor, Color(0xFF0D1015))
    }
}

/** The account's name and below its address (only the address when it has no name). */
@Composable
internal fun MailAccountText(account: MAccount, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface, bold: Boolean = false) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(
            account.label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (account.label != account.email) {
            Text(account.email, style = MaterialTheme.typography.bodySmall, color = GateControlTheme.extraColors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The time column of a mail: the time today, "Gestern", the weekday, else the date. */
@Composable
internal fun mailTime(date: Long): String {
    if (date <= 0) return ""
    val zone = ZoneId.systemDefault()
    val locale = com.gatecontrol.android.ui.update.currentLocale()
    val at = Instant.ofEpochMilli(date).atZone(zone)
    return when (MailLogic.whenOf(date, System.currentTimeMillis(), zone)) {
        MailLogic.When.TIME -> at.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale))
        MailLogic.When.YESTERDAY -> stringResource(R.string.mail_yesterday)
        MailLogic.When.WEEKDAY -> at.format(DateTimeFormatter.ofPattern("EEE", locale))
        MailLogic.When.DATE -> at.format(DateTimeFormatter.ofPattern(if (locale.language == "de") "d. MMM" else "MMM d", locale))
        MailLogic.When.DATE_YEAR -> at.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale))
    }
}

/** Date and time in full (the reading screen, the outbox). */
internal fun mailDateTime(date: Long): String =
    Instant.ofEpochMilli(date).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(Locale.getDefault()))

/** A size in bytes as B / KB / MB / GB (GB from 1 GB on, like Drive). */
internal fun mailSize(bytes: Long): String = com.gatecontrol.android.drive.DriveLogic.size(bytes)

/** The square with a file's type (`PDF`, `DOC` …) in its color. */
@Composable
internal fun FileBadge(name: String, mime: String, size: Int = 40) {
    val label = MailLogic.typeLabel(name, mime)
    val extra = GateControlTheme.extraColors
    val (bg, fg) = when (label) {
        "PDF" -> extra.errorBg to MaterialTheme.colorScheme.error
        "DOC" -> extra.blueBg to extra.blue
        "XLS" -> extra.accentBg to extra.accentText
        "IMG" -> extra.proBg to extra.pro
        else -> extra.panel2 to extra.muted
    }
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(10.dp)).background(bg), contentAlignment = Alignment.Center) {
        Text(label, color = fg, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** The round avatar with the sender's initials in the account's color. */
@Composable
internal fun SenderAvatar(name: String?, email: String, color: Color, size: Dp = 40.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
        Text(MailLogic.initials(name, email), color = Color(0xFF0D1015), fontWeight = FontWeight.Bold, fontSize = (size.value * 0.35f).sp)
    }
}

/** What a swipe does on a mail row (its color, icon and label). */
internal data class SwipeSpec(val action: String, val label: String, val icon: ImageVector, val color: Color)

@Composable
internal fun swipeSpec(action: String?, unread: Boolean): SwipeSpec? {
    val extra = GateControlTheme.extraColors
    return when (MailLogic.swipe(action)) {
        MailLogic.SWIPE_ARCHIVE -> SwipeSpec(MailLogic.SWIPE_ARCHIVE, stringResource(R.string.mail_archive), GcIcons.Archive, extra.accentText)
        MailLogic.SWIPE_DELETE -> SwipeSpec(MailLogic.SWIPE_DELETE, stringResource(R.string.mail_delete), GcIcons.Trash, MaterialTheme.colorScheme.error)
        MailLogic.SWIPE_READ -> SwipeSpec(
            MailLogic.SWIPE_READ,
            stringResource(if (unread) R.string.mail_mark_read else R.string.mail_mark_unread),
            MailIcons.Unread,
            extra.blue,
        )
        else -> null
    }
}

/**
 * A row that can be swiped: to the left does [left], to the right [right] (null: that way does nothing). Past a
 * third of the width the action fires. The actions are also offered to accessibility services.
 */
@Composable
internal fun SwipeRow(
    left: SwipeSpec?,
    right: SwipeSpec?,
    onSwipe: (SwipeSpec) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val width = remember { floatArrayOf(1f) }
    val actions = listOfNotNull(left, right).distinctBy { it.action }.map { s ->
        CustomAccessibilityAction(s.label) { onSwipe(s); true }
    }
    Box(
        modifier
            .fillMaxWidth()
            .onSizeChanged { width[0] = it.width.toFloat().coerceAtLeast(1f) }
            .semantics { if (actions.isNotEmpty()) customActions = actions },
    ) {
        val x = offset.value
        val spec = if (x < 0) left else if (x > 0) right else null
        if (spec != null) {
            Row(
                Modifier.matchParentSize().background(spec.color.copy(alpha = 0.18f)).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (x < 0) Arrangement.End else Arrangement.Start,
            ) {
                Icon(spec.icon, contentDescription = null, tint = spec.color, modifier = Modifier.size(20.dp))
                Text(spec.label, color = spec.color, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 8.dp))
            }
        }
        Box(
            Modifier
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .background(MaterialTheme.colorScheme.background)
                .pointerInput(left, right) {
                    if (left == null && right == null) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val w = width[0]
                            val v = offset.value
                            val fired = if (v < 0) left else right
                            scope.launch {
                                if (fired != null && abs(v) > w / 3f) {
                                    offset.animateTo(if (v < 0) -w else w)
                                    onSwipe(fired)
                                    offset.snapTo(0f)
                                } else {
                                    offset.animateTo(0f)
                                }
                            }
                        },
                        onDragCancel = { scope.launch { offset.animateTo(0f) } },
                    ) { change, drag ->
                        val next = offset.value + drag
                        val allowed = (next < 0 && left != null) || (next > 0 && right != null) || next == 0f
                        if (allowed) {
                            change.consume()
                            scope.launch { offset.snapTo(next.coerceIn(-width[0], width[0])) }
                        }
                    }
                },
        ) { content() }
    }
}

/** One mail in a list (mockup "Posteingang"): avatar, dot, sender, time, subject, snippet. */
@Composable
internal fun MailRow(
    item: MSummary,
    account: MAccount?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
) {
    val extra = GateControlTheme.extraColors
    val color = accountColor(account)
    val weight = if (item.unread) FontWeight.Bold else FontWeight.Medium
    val longLabel = stringResource(R.string.mail_actions)
    Row(
        modifier
            .fillMaxWidth()
            .background(if (selected) extra.accentBg else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = longLabel)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SenderAvatar(item.from.name, item.from.email, color)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MailDot(color)
                Text(
                    item.from.label.ifEmpty { stringResource(R.string.mail_unknown_sender) },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = weight,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.threadCount > 1) Text(item.threadCount.toString(), style = MaterialTheme.typography.labelSmall, color = extra.muted)
                if (item.flagged) Icon(GcIcons.Flag, contentDescription = stringResource(R.string.mail_flagged), tint = extra.warn, modifier = Modifier.size(14.dp))
                Text(mailTime(item.date), style = MaterialTheme.typography.bodySmall, color = if (item.unread) extra.accentText else extra.muted)
            }
            Text(
                item.subject.ifEmpty { stringResource(R.string.mail_no_subject) },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = weight,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val preview = item.snippet.ifBlank { item.aiSummary.orEmpty() }
            if (preview.isNotBlank()) {
                Text(preview, style = MaterialTheme.typography.bodySmall, color = extra.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (item.hasAttachments || item.savedToDrive) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (item.hasAttachments) Icon(GcIcons.Paperclip, contentDescription = stringResource(R.string.mail_has_attachments), tint = extra.muted, modifier = Modifier.size(14.dp))
                    if (item.savedToDrive) MailTag(stringResource(R.string.mail_saved_to_drive), extra.accentBg, extra.accentText)
                }
            }
        }
    }
}

@Composable
internal fun MailTag(text: String, bg: Color, fg: Color) {
    Box(Modifier.clip(RoundedCornerShape(999.dp)).background(bg).padding(horizontal = 7.dp, vertical = 2.dp)) {
        Text(text, color = fg, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** Thin line under a row (the mockup's `border-bottom`). */
@Composable
internal fun MailDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(GateControlTheme.extraColors.panel2))
}

/** A refusal or failure of the Mail plugin as a sentence. */
internal fun mailText(context: Context, code: String?): String = context.getString(
    when (code) {
        "offline", "http_0" -> R.string.mail_err_offline
        "not_configured" -> R.string.mail_err_not_configured
        "not_allowed", "http_403" -> R.string.mail_err_not_allowed
        "limit" -> R.string.mail_err_limit
        "auth_failed" -> R.string.mail_err_auth_failed
        "imap_disabled" -> R.string.mail_err_imap_disabled
        "unreachable" -> R.string.mail_err_unreachable
        "tls" -> R.string.mail_err_tls
        "invalid", "http_400" -> R.string.mail_err_invalid
        "too_large", "http_413" -> R.string.mail_err_too_large
        "ai_off" -> R.string.mail_err_ai_off
        "ai_unavailable" -> R.string.mail_err_ai_unavailable
        "rate_limited", "http_429" -> R.string.mail_err_rate_limited
        "familie_unavailable" -> R.string.mail_err_familie_unavailable
        "not_found", "http_404" -> R.string.mail_err_not_found
        "conflict", "http_409" -> R.string.mail_err_conflict
        "encrypted" -> R.string.mail_err_encrypted
        "forbidden", "login_required" -> R.string.mail_err_not_allowed
        "microsoft_off" -> R.string.mail_err_microsoft_off
        "attachments_unsupported" -> R.string.mail_err_attachments_unsupported
        "images_blocked" -> R.string.mail_err_images_blocked
        "images_off" -> R.string.mail_err_images_off
        "drive_unavailable" -> R.string.mail_err_drive_unavailable
        "no_folder" -> R.string.mail_err_no_folder
        "not_waiting" -> R.string.mail_err_not_waiting
        "exists" -> R.string.mail_err_exists
        "links_disabled" -> R.string.mail_err_links_disabled
        "busy" -> R.string.mail_err_busy
        else -> R.string.mail_err_failed
    },
)

internal fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

/** An address in a Custom Tab (the in-app browser), else the default browser. */
internal fun openLink(context: Context, url: String) {
    val uri = url.toUri()
    val ok = runCatching { CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, uri) }.isSuccess ||
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    if (!ok) toast(context, context.getString(R.string.mail_no_app))
}

/** Another app shows a link (`tel:`, a web page from a mail): never inside the app. */
internal fun openExternal(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        toast(context, context.getString(R.string.mail_no_app))
    } catch (_: SecurityException) {
        toast(context, context.getString(R.string.mail_no_app))
    }
}

/** A downloaded attachment opened in another app (FileProvider `mail_open`). */
internal fun openFile(context: Context, file: File, mime: String) {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".provider", file)
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(Intent.createChooser(intent, file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        toast(context, context.getString(R.string.mail_no_app))
    }
}

/** A to-do of the AI into the device's calendar (the calendar app asks; no permission needed). */
internal fun addToCalendar(context: Context, todo: MTodo, title: String) {
    val (start, allDay) = MailLogic.todoStart(todo, ZoneId.systemDefault()) ?: return
    val intent = Intent(Intent.ACTION_INSERT)
        .setData(CalendarContract.Events.CONTENT_URI)
        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, start + if (allDay) 24 * 3600_000L else 3600_000L)
        .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, allDay)
        .putExtra(CalendarContract.Events.TITLE, title)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        toast(context, context.getString(R.string.mail_no_calendar))
    }
}

/** The context of a composable, for the helpers above. */
@Composable
internal fun mailContext(): Context = LocalContext.current
