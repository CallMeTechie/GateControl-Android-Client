package com.gatecontrol.android.ui.chat

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DoorFront
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gatecontrol.android.R
import com.gatecontrol.android.chat.ChatActionCard
import com.gatecontrol.android.chat.ChatArrival
import com.gatecontrol.android.chat.ChatCardButton
import com.gatecontrol.android.chat.ChatConversation
import com.gatecontrol.android.chat.ChatEventInfo
import com.gatecontrol.android.chat.ChatMessage
import com.gatecontrol.android.chat.ChatPoll
import com.gatecontrol.android.chat.ChatReplyStory
import com.gatecontrol.android.chat.ChatRichLogic
import com.gatecontrol.android.chat.ChatRsvp
import com.gatecontrol.android.chat.ChatTimeline
import com.gatecontrol.android.quickactions.QuickActionIcons
import com.gatecontrol.android.ui.familie.LatLon
import com.gatecontrol.android.ui.familie.OsmMap
import com.gatecontrol.android.ui.theme.GateControlTheme
import com.gatecontrol.android.ui.theme.MonoFontFamily
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Looks of this device for the conversation (font size, own bubble colour). */
data class ChatLookState(
    val fontScale: Float = 1f,
    val ownBubble: Color? = null,
    /** Automatic downloads on this network: full pictures, everything (videos, voice, files ahead). */
    val autoPhotos: Boolean = true,
    val autoAll: Boolean = false,
)

val LocalChatLook = compositionLocalOf { ChatLookState() }

/** What the Messenger 1.4 cards report to the screen. */
class RichActions(
    val onVote: (ChatMessage, List<String>) -> Unit = { _, _ -> },
    val onAddOption: (ChatMessage) -> Unit = {},
    val onShowVoters: (ChatMessage) -> Unit = {},
    val onClosePoll: (ChatMessage) -> Unit = {},
    val onNudge: (ChatMessage) -> Unit = {},
    val onRsvp: (ChatMessage, String?, Int?) -> Unit = { _, _, _ -> },
    val onChecklist: (ChatMessage, String, Boolean) -> Unit = { _, _, _ -> },
    val onOpenPlace: (Double, Double, String?) -> Unit = { _, _, _ -> },
    val onCancelEvent: (ChatMessage) -> Unit = {},
    val onShareArrival: (ChatMessage) -> Unit = {},
    val onStopArrival: (ChatMessage) -> Unit = {},
    val onActionRun: (ChatMessage) -> Unit = {},
    val onActionApprove: (ChatMessage) -> Unit = {},
    val onActionDeny: (ChatMessage) -> Unit = {},
    val onCardButton: (ChatMessage, ChatCardButton) -> Unit = { _, _ -> },
    val onUpdateApp: () -> Unit = {},
    /** A plugin card's picture (base64) from the server. */
    val loadCardImage: suspend (ChatMessage) -> String? = { null },
    val features: RichFeatures = RichFeatures(),
)

/** What the server switches on (cards hide what is not there). */
data class RichFeatures(val arrival: Boolean = false)

private fun locale(ctx: android.content.Context): Locale = ctx.resources.configuration.locales[0] ?: Locale.getDefault()

/** "Sa. 18:00". */
internal fun shortDayTime(ctx: android.content.Context, iso: String?): String {
    val ms = ChatTimeline.epochMs(iso) ?: return ""
    return DateTimeFormatter.ofPattern("EEE HH:mm", locale(ctx)).format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
}

@Composable
private fun Header(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = 0.5.sp),
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CardTextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color = MaterialTheme.colorScheme.primary) {
    Box(
        modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp)).clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold), color = if (enabled) color else GateControlTheme.extraColors.faint, maxLines = 1)
    }
}

// ─── polls ──────────────────────────────────────────────────────────

@Composable
fun PollContent(m: ChatMessage, poll: ChatPoll, conversation: ChatConversation?, myId: Long?, actions: RichActions) {
    val ctx = LocalContext.current
    val extra = GateControlTheme.extraColors
    val scheme = MaterialTheme.colorScheme
    val meta = if (m.mine) extra.accentText else extra.muted
    val admin = conversation?.me?.role == com.gatecontrol.android.chat.ChatPerson.ROLE_ADMIN
    Column(Modifier.width(276.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Header(
            Icons.Outlined.BarChart,
            when {
                poll.closed -> stringResource(R.string.chat_poll_header_closed)
                poll.closesAt != null -> stringResource(R.string.chat_poll_header_ends, shortDayTime(ctx, poll.closesAt))
                else -> stringResource(R.string.chat_poll_header)
            },
            meta,
        )
        Text(poll.question, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold), color = scheme.onSurface)
        val sub = buildList {
            add(stringResource(if (poll.multi) R.string.chat_poll_multi else R.string.chat_poll_one))
            poll.memberCount?.let { add(stringResource(R.string.chat_poll_voted, poll.voterCount, it)) }
            if (poll.anonymous) add(stringResource(R.string.chat_poll_anonymous))
        }.joinToString(" · ")
        Text(sub, style = MaterialTheme.typography.bodySmall, color = meta)
        val total = poll.options.maxOfOrNull { it.count }?.coerceAtLeast(1) ?: 1
        poll.options.forEach { op ->
            val mineVote = op.id in poll.myVotes
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(if (mineVote) extra.accentBg else Color.Transparent)
                    .border(1.dp, if (mineVote) scheme.primary else extra.border, RoundedCornerShape(12.dp))
                    .clickable(enabled = !poll.closed && !m.deleted, role = Role.Checkbox) { actions.onVote(m, ChatRichLogic.vote(poll, op.id)) }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        Modifier.size(20.dp).clip(if (poll.multi) RoundedCornerShape(5.dp) else CircleShape)
                            .background(if (mineVote) scheme.primary else Color.Transparent)
                            .border(2.dp, if (mineVote) scheme.primary else extra.border2, if (poll.multi) RoundedCornerShape(5.dp) else CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (mineVote) Icon(Icons.Outlined.Check, contentDescription = null, tint = scheme.onPrimary, modifier = Modifier.size(14.dp))
                    }
                    Text(op.text, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold), color = scheme.onSurface, modifier = Modifier.weight(1f))
                    op.voters?.take(3)?.let { ids ->
                        Row {
                            ids.forEachIndexed { i, uid ->
                                val p = conversation?.members?.firstOrNull { it.userId == uid }
                                ChatPersonAvatar(p, 20.dp, Modifier.offset(x = (-6 * i).dp))
                            }
                        }
                    }
                    Text(op.count.toString(), style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold), color = scheme.onSurface)
                }
                Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(extra.panel2)) {
                    Box(Modifier.fillMaxWidth(ChatRichLogic.share(op.count, total)).height(4.dp).clip(RoundedCornerShape(2.dp)).background(scheme.primary))
                }
            }
        }
        if (poll.allowAdd && !poll.closed && poll.options.size < ChatPoll.MAX_OPTIONS) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { actions.onAddOption(m) }.padding(horizontal = 10.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null, tint = meta, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.chat_poll_add_option), style = MaterialTheme.typography.bodyMedium, color = meta)
            }
        }
        val members = conversation?.members?.map { it.userId }.orEmpty()
        val nonVoters = ChatRichLogic.nonVoters(poll, members, myId)
        val nudge = ChatRichLogic.canNudge(poll, myId, m.mine) && nonVoters?.isNotEmpty() != false
        val close = ChatRichLogic.canClose(poll, myId, admin, m.mine)
        if (!poll.anonymous || nudge || close) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(extra.border))
            Row(Modifier.fillMaxWidth()) {
                if (!poll.anonymous) CardTextButton(stringResource(R.string.chat_poll_votes), { actions.onShowVoters(m) }, Modifier.weight(1f))
                if (nudge) {
                    val label = nonVoters?.singleOrNull()?.let { uid -> stringResource(R.string.chat_poll_nudge_one, ChatText.nameOf(ctx, conversation, uid, myId)) }
                        ?: stringResource(R.string.chat_poll_nudge)
                    CardTextButton(label, { actions.onNudge(m) }, Modifier.weight(1f))
                }
                if (close) CardTextButton(stringResource(R.string.chat_poll_close), { actions.onClosePoll(m) }, Modifier.weight(0.7f))
            }
        }
    }
}

// ─── events ─────────────────────────────────────────────────────────

@Composable
fun EventContent(m: ChatMessage, event: ChatEventInfo, conversation: ChatConversation?, myId: Long?, actions: RichActions) {
    val ctx = LocalContext.current
    val extra = GateControlTheme.extraColors
    val scheme = MaterialTheme.colorScheme
    val zone = ZoneId.systemDefault()
    val loc = locale(ctx)
    val startMs = ChatTimeline.epochMs(event.startsAt)
    val start = startMs?.let { Instant.ofEpochMilli(it).atZone(zone) }
    val endMs = ChatTimeline.epochMs(event.endsAt)
    val admin = conversation?.me?.role == com.gatecontrol.android.chat.ChatPerson.ROLE_ADMIN
    Column(Modifier.width(276.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            // Date tile: OKT · 26 · SO.
            Column(
                Modifier.width(52.dp).clip(RoundedCornerShape(12.dp)).background(if (event.cancelled) extra.panel2 else extra.accentBg).padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(start?.let { DateTimeFormatter.ofPattern("MMM", loc).format(it).uppercase(loc).trimEnd('.') } ?: "", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold), color = extra.accentText)
                Text(start?.dayOfMonth?.toString() ?: "?", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold), color = scheme.onSurface)
                Text(start?.let { DateTimeFormatter.ofPattern("EEE", loc).format(it).uppercase(loc).trimEnd('.') } ?: "", style = MaterialTheme.typography.labelSmall, color = extra.muted)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Header(Icons.Outlined.Event, stringResource(R.string.chat_kind_event).uppercase(loc), extra.muted)
                Text(
                    event.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold, textDecoration = if (event.cancelled) TextDecoration.LineThrough else null),
                    color = scheme.onSurface,
                )
                val time = when {
                    event.allDay -> stringResource(R.string.chat_event_all_day)
                    start == null -> ""
                    endMs != null -> DateTimeFormatter.ofPattern("HH:mm", loc).format(start) + " – " +
                        DateTimeFormatter.ofPattern("HH:mm", loc).format(Instant.ofEpochMilli(endMs).atZone(zone))
                    else -> DateTimeFormatter.ofPattern("HH:mm", loc).format(start)
                }
                val days = ChatRichLogic.daysUntil(event.startsAt, LocalDate.now(zone), zone)
                val rel = when {
                    days == null -> null
                    days < 0 -> stringResource(R.string.chat_event_past)
                    days == 0L -> stringResource(R.string.chat_event_today)
                    days == 1L -> stringResource(R.string.chat_event_tomorrow)
                    else -> pluralStringResource(R.plurals.chat_event_in_days, days.toInt(), days.toInt())
                }
                Text(listOfNotNull(time.takeIf { it.isNotEmpty() }, rel).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = extra.muted)
            }
        }
        if (event.cancelled) {
            Text(
                stringResource(R.string.chat_event_cancelled),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold),
                color = scheme.error,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(extra.errorBg).padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        event.place?.let { place ->
            val lat = place.lat
            val lon = place.lon
            Row(
                Modifier.clip(RoundedCornerShape(10.dp))
                    .then(if (lat != null && lon != null) Modifier.clickable(role = Role.Button, onClickLabel = stringResource(R.string.chat_event_place_open)) { actions.onOpenPlace(lat, lon, place.label) } else Modifier)
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Outlined.Place, contentDescription = null, tint = extra.blue, modifier = Modifier.size(18.dp))
                Text(place.label, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
            }
        }
        event.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface) }
        if (event.checklist.isNotEmpty()) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(scheme.background).padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(stringResource(R.string.chat_event_checklist), style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily, letterSpacing = 1.sp), color = extra.faint)
                event.checklist.forEach { item ->
                    val done = item.doneBy != null
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 40.dp).clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = !event.cancelled, role = Role.Checkbox) { actions.onChecklist(m, item.id, !done) },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            Modifier.size(20.dp).clip(RoundedCornerShape(6.dp)).background(if (done) scheme.primary else Color.Transparent)
                                .border(2.dp, if (done) scheme.primary else extra.border2, RoundedCornerShape(6.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (done) Icon(Icons.Outlined.Check, contentDescription = null, tint = scheme.onPrimary, modifier = Modifier.size(14.dp))
                        }
                        Text(
                            item.text + (item.doneBy?.let { " – " + ChatText.nameOf(ctx, conversation, it, myId) } ?: ""),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (done) extra.muted else scheme.onSurface,
                        )
                    }
                }
            }
        }
        if (!event.cancelled && actions.features.arrival && ChatRichLogic.offerArrival(event, System.currentTimeMillis()) && event.place?.lat != null) {
            CardTextButton(stringResource(R.string.chat_event_share_arrival), { actions.onShareArrival(m) })
        }
        val rsvp = event.rsvp
        if (rsvp != null && !event.cancelled) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RsvpButton(stringResource(R.string.chat_event_yes), if (rsvp.mine == ChatRsvp.YES) stringResource(R.string.chat_event_incl_you, rsvp.yesCount) else rsvp.yesCount.toString(), rsvp.mine == ChatRsvp.YES, Modifier.weight(1f)) {
                    actions.onRsvp(m, ChatRichLogic.rsvp(rsvp.mine, ChatRsvp.YES), null)
                }
                RsvpButton(stringResource(R.string.chat_event_maybe), rsvp.maybe.size.toString(), rsvp.mine == ChatRsvp.MAYBE, Modifier.weight(1f)) {
                    actions.onRsvp(m, ChatRichLogic.rsvp(rsvp.mine, ChatRsvp.MAYBE), null)
                }
                RsvpButton(stringResource(R.string.chat_event_no), rsvp.no.size.toString(), rsvp.mine == ChatRsvp.NO, Modifier.weight(1f)) {
                    actions.onRsvp(m, ChatRichLogic.rsvp(rsvp.mine, ChatRsvp.NO), null)
                }
            }
            if (event.guests && rsvp.mine == ChatRsvp.YES) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.chat_event_guests), style = MaterialTheme.typography.bodyMedium, color = extra.muted, modifier = Modifier.weight(1f))
                    CardTextButton("−", { actions.onRsvp(m, ChatRsvp.YES, (rsvp.myGuests - 1).coerceAtLeast(0)) }, enabled = rsvp.myGuests > 0)
                    Text(rsvp.myGuests.toString(), style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
                    CardTextButton("+", { actions.onRsvp(m, ChatRsvp.YES, (rsvp.myGuests + 1).coerceAtMost(ChatEventInfo.GUESTS_MAX)) }, enabled = rsvp.myGuests < ChatEventInfo.GUESTS_MAX)
                }
            }
        }
        if (ChatRichLogic.canManageEvent(event, myId, admin, m.mine)) {
            CardTextButton(stringResource(R.string.chat_event_cancel), { actions.onCancelEvent(m) }, color = scheme.error)
        }
    }
}

@Composable
private fun RsvpButton(label: String, count: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val extra = GateControlTheme.extraColors
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier.heightIn(min = 52.dp).clip(RoundedCornerShape(12.dp))
            .background(if (selected) scheme.primary else extra.panel2)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold), color = if (selected) scheme.onPrimary else scheme.onSurface, maxLines = 1)
        Text(count, style = MaterialTheme.typography.labelSmall, color = if (selected) scheme.onPrimary else extra.muted, maxLines = 1)
    }
}

// ─── live arrival ───────────────────────────────────────────────────

@Composable
fun ArrivalContent(m: ChatMessage, arrival: ChatArrival, actions: RichActions) {
    val ctx = LocalContext.current
    val extra = GateControlTheme.extraColors
    val scheme = MaterialTheme.colorScheme
    val loc = locale(ctx)
    Column(Modifier.width(276.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val pos = if (arrival.lat != null && arrival.lon != null) LatLon(arrival.lat, arrival.lon)
        else if (arrival.dest.lat != null && arrival.dest.lon != null) LatLon(arrival.dest.lat, arrival.dest.lon) else null
        if (pos != null && arrival.live) {
            OsmMap(
                point = pos,
                contentDescription = stringResource(R.string.chat_arrival_map_cd, arrival.dest.label),
                modifier = Modifier.fillMaxWidth().height(140.dp),
                zoom = 14.0,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.Navigation, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(18.dp))
            Text(
                when (arrival.state) {
                    ChatArrival.STATE_ARRIVED -> stringResource(R.string.chat_arrival_arrived, arrival.dest.label)
                    ChatArrival.STATE_LIVE -> arrival.etaAt?.let { stringResource(R.string.chat_arrival_to, arrival.dest.label, ChatText.clock(ctx, it)) }
                        ?: stringResource(R.string.chat_arrival_to_plain, arrival.dest.label)
                    else -> stringResource(R.string.chat_arrival_to_plain, arrival.dest.label)
                },
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold),
                color = scheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (arrival.live) {
                Text(
                    stringResource(R.string.chat_arrival_live),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold, fontFamily = MonoFontFamily),
                    color = scheme.onPrimary,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(scheme.error).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        if (arrival.live) {
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(extra.panel2)) {
                Box(Modifier.fillMaxWidth(arrival.progress ?: 0f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(scheme.primary))
            }
            val dist = ChatRichLogic.distance(arrival.distanceM, loc)
            val mins = ChatRichLogic.minutesUntil(arrival.etaAt, System.currentTimeMillis())
            Text(
                when {
                    mins != null && dist != null -> stringResource(R.string.chat_arrival_left, mins, dist)
                    dist != null -> stringResource(R.string.chat_arrival_left_short, dist)
                    else -> stringResource(R.string.chat_arrival_left_short, arrival.dest.label)
                },
                style = MaterialTheme.typography.bodySmall,
                color = extra.muted,
            )
        } else if (arrival.state == ChatArrival.STATE_STOPPED) {
            Text(stringResource(R.string.chat_arrival_stopped), style = MaterialTheme.typography.bodySmall, color = extra.muted)
        }
        Row(Modifier.fillMaxWidth()) {
            if (pos != null) CardTextButton(stringResource(R.string.chat_arrival_map), { actions.onOpenPlace(pos.lat, pos.lon, arrival.dest.label) }, Modifier.weight(1f))
            arrival.onArriveLabel?.let {
                Row(Modifier.weight(1.4f).heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Outlined.Lightbulb, contentDescription = null, tint = extra.warn, modifier = Modifier.size(16.dp))
                    Text(stringResource(R.string.chat_arrival_on_arrive, it), style = MaterialTheme.typography.labelMedium, color = extra.muted, maxLines = 2)
                }
            }
            if (m.mine && arrival.live) CardTextButton(stringResource(R.string.chat_arrival_stop_button), { actions.onStopArrival(m) }, Modifier.weight(1.2f), color = scheme.error)
        }
    }
}

// ─── home actions and plugin cards ──────────────────────────────────

@Composable
fun ActionContent(m: ChatMessage, a: ChatActionCard, conversation: ChatConversation?, myId: Long?, actions: RichActions) {
    val ctx = LocalContext.current
    val extra = GateControlTheme.extraColors
    val scheme = MaterialTheme.colorScheme
    val icon = remember(a.icon) { QuickActionIcons.vector(a.icon) }
    val sender = ChatText.nameOf(ctx, conversation, m.senderId, myId)
    Column(Modifier.width(276.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Header(
            icon,
            when (a.mode) {
                ChatActionCard.MODE_REQUEST -> stringResource(R.string.chat_action_request_header, sender.uppercase(locale(ctx)))
                ChatActionCard.MODE_GRANT -> stringResource(R.string.chat_action_grant_header)
                else -> stringResource(R.string.chat_action_header)
            } + " · " + ChatText.clock(ctx, m.createdAt),
            extra.muted,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(extra.warnBg), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = extra.warn, modifier = Modifier.size(22.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(a.label, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold), color = scheme.onSurface)
                when (a.mode) {
                    ChatActionCard.MODE_REQUEST -> {
                        a.note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface) }
                        Text(
                            stringResource(R.string.chat_action_request_text) +
                                (a.expiresAt?.takeIf { a.state == ChatActionCard.STATE_OPEN }?.let { " · " + stringResource(R.string.chat_action_expires, ChatText.clock(ctx, it)) } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = extra.muted,
                        )
                    }
                    ChatActionCard.MODE_GRANT -> if (a.grantTo != null) {
                        Text(
                            stringResource(R.string.chat_action_grant_text, ChatText.nameOf(ctx, conversation, a.grantTo, myId), shortDayTime(ctx, a.grantUntil)) +
                                if (a.offAtEnd) " · " + stringResource(R.string.chat_action_grant_off_end) else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = extra.muted,
                        )
                    }
                    else -> Unit
                }
            }
        }
        when (a.state) {
            ChatActionCard.STATE_DONE -> StateLine(stringResource(R.string.chat_action_done) + (a.by?.let { " · " + ChatText.nameOf(ctx, conversation, it, myId) } ?: ""), extra.accentText)
            ChatActionCard.STATE_DENIED -> StateLine(stringResource(R.string.chat_action_denied) + (a.by?.let { " · " + ChatText.nameOf(ctx, conversation, it, myId) } ?: ""), scheme.error)
            ChatActionCard.STATE_EXPIRED -> StateLine(stringResource(R.string.chat_action_expired), extra.muted)
            else -> Unit
        }
        if (a.resultOk == false) StateLine(stringResource(R.string.chat_action_failed, a.result ?: "–"), scheme.error)
        if (a.state == ChatActionCard.STATE_OPEN) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (a.canApprove) ActionButton(stringResource(R.string.chat_action_approve), Modifier.weight(1f), primary = true) { actions.onActionApprove(m) }
                if (a.canDeny) ActionButton(stringResource(R.string.chat_action_deny), Modifier.weight(1f), primary = false) { actions.onActionDeny(m) }
                if (a.canRun) ActionButton(stringResource(R.string.chat_action_run), Modifier.weight(1f), primary = true) { actions.onActionRun(m) }
            }
        }
    }
}

@Composable
private fun StateLine(text: String, color: Color) {
    Text(text, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold), color = color)
}

@Composable
private fun ActionButton(label: String, modifier: Modifier, primary: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val extra = GateControlTheme.extraColors
    Box(
        modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp))
            .background(if (primary) scheme.primary else extra.panel2)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold), color = if (primary) scheme.onPrimary else scheme.onSurface, maxLines = 1)
    }
}

/** A plugin's card (doorbell with the camera picture, notice) with its buttons. */
@Composable
fun PluginCardContent(m: ChatMessage, actions: RichActions) {
    val card = m.card ?: return
    val ctx = LocalContext.current
    val extra = GateControlTheme.extraColors
    val scheme = MaterialTheme.colorScheme
    val doorbell = card.kind == "doorbell"
    Column(Modifier.width(276.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // A doorbell names the door in the header; a notice (shopping, task, request of a plugin) shows its title as the headline.
        // Messenger 1.6 notice cards name their notification: "<sourceLabel> · <topicLabel>".
        val notice = card.notice?.takeIf { it.sourceLabel != null || it.topicLabel != null }
        Header(
            if (doorbell) Icons.Outlined.DoorFront else Icons.Outlined.Info,
            if (notice != null) {
                listOfNotNull(notice.sourceLabel?.uppercase(locale(ctx)), notice.topicLabel?.uppercase(locale(ctx)), ChatText.clock(ctx, m.createdAt)).joinToString(" · ")
            } else {
                listOfNotNull(stringResource(if (doorbell) R.string.chat_card_doorbell else R.string.chat_card_notice), card.title?.takeIf { doorbell }?.uppercase(locale(ctx)), ChatText.clock(ctx, m.createdAt)).joinToString(" · ")
            },
            if (doorbell) extra.warn else extra.blue,
        )
        val inline by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, card.imageBase64, card.hasImage, m.id) {
            val b64 = card.imageBase64 ?: if (card.hasImage) actions.loadCardImage(m) else null
            value = b64?.let {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    runCatching { Base64.decode(it, Base64.DEFAULT).let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size) } }.getOrNull()?.asImageBitmap()
                }
            }
        }
        val px = with(LocalDensity.current) { 276.dp.roundToPx() }
        // Notice cards: the notification's picture on the core (`/notify-media/…`), loaded like push pictures.
        val pushPicture = com.gatecontrol.android.ui.notifications.rememberPushPicture(card.imageUrl, px)
        val picture = inline ?: rememberChatBitmap(card.imageMediaId, px) ?: pushPicture
        if (picture != null) {
            Image(
                picture,
                contentDescription = stringResource(R.string.chat_card_picture),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(12.dp)),
            )
        }
        if (doorbell || card.title == null) {
            (card.text ?: m.text)?.let { Text(it, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold), color = scheme.onSurface) }
        } else {
            Text(card.title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold), color = scheme.onSurface)
            (card.text ?: m.text)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = extra.muted) }
        }
        if (card.buttons.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                card.buttons.forEachIndexed { i, b ->
                    ActionButton(
                        b.label ?: b.actionId ?: "",
                        Modifier.weight(1f),
                        primary = i == 0 && b.route == null,
                        enabled = b.canRun || b.route != null,
                    ) { actions.onCardButton(m, b) }
                }
            }
        }
    }
}

/** A kind of a newer messenger: says that a newer app shows it. */
@Composable
fun UnknownKindContent(m: ChatMessage, actions: RichActions) {
    val extra = GateControlTheme.extraColors
    Column(Modifier.widthIn(max = 260.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.AutoMirrored.Outlined.HelpOutline, contentDescription = null, tint = extra.muted, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.chat_needs_newer_app), style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic), color = extra.muted)
        }
        m.text?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface) }
        TextButton(onClick = actions.onUpdateApp) { Text(stringResource(R.string.chat_needs_newer_app_action)) }
    }
}

/** The status a message answers (`replyStory`): its text or picture. */
@Composable
fun ReplyStoryQuote(r: ChatReplyStory, conversation: ChatConversation?, myId: Long?) {
    val ctx = LocalContext.current
    val extra = GateControlTheme.extraColors
    val px = with(LocalDensity.current) { 44.dp.roundToPx() }
    val thumb = rememberChatBitmap(r.thumbMediaId, px)
    val replyLabel = stringResource(R.string.chat_story_reply)
    Row(
        Modifier.padding(bottom = 6.dp).clip(RoundedCornerShape(10.dp)).background(extra.panel2).heightIn(min = 44.dp)
            .semantics { contentDescription = replyLabel },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).height(44.dp).background(MaterialTheme.colorScheme.primary))
        Column(Modifier.weight(1f, fill = false).padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(
                stringResource(R.string.chat_story_reply_of, ChatText.nameOf(ctx, conversation, r.ownerId, myId)),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = extra.accentText,
                maxLines = 1,
            )
            Text(r.text ?: stringResource(R.string.chat_story_reply), style = MaterialTheme.typography.bodySmall, color = extra.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (thumb != null) {
            Image(thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(44.dp).clip(RoundedCornerShape(topEnd = 10.dp, bottomEnd = 10.dp)))
        }
    }
}
