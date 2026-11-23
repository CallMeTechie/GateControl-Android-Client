package com.gatecontrol.android.tv.ui.gatedrop

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.gatecontrol.android.gatedrop.GdEntry
import com.gatecontrol.android.tv.R
import com.gatecontrol.android.tv.gatedrop.TvDropEntry
import com.gatecontrol.android.tv.gatedrop.TvDropFile
import com.gatecontrol.android.tv.gatedrop.TvDropLogic
import com.gatecontrol.android.tv.gatedrop.TvDropOffer
import com.gatecontrol.android.tv.gatedrop.TvDropProgress
import com.gatecontrol.android.tv.ui.components.TvButton
import com.gatecontrol.android.tv.ui.components.TvButtonStyle
import com.gatecontrol.android.tv.ui.components.TvDialog
import com.gatecontrol.android.tv.ui.components.TvOptionRow
import com.gatecontrol.android.tv.ui.components.TvPill
import com.gatecontrol.android.tv.ui.components.TvPillTone
import com.gatecontrol.android.tv.ui.components.TvRowEnd
import com.gatecontrol.android.tv.ui.drive.requestSoon
import com.gatecontrol.android.tv.ui.theme.GateControlTvTheme

/**
 * The page "GateDrop" of the rail: what came to the TV (newest first, "NEU" until opened) and what waits to be taken.
 * OK on an entry opens it (show, delete), OK on a waiting send takes it onto the TV.
 */
@Composable
fun TvGateDropPage(
    entries: List<TvDropEntry>,
    waiting: List<GdEntry>,
    available: Boolean?,
    receiving: String?,
    onOpen: (TvDropEntry) -> Unit,
    onTake: (GdEntry) -> Unit,
    onStart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GateControlTvTheme.colors
    LaunchedEffect(Unit) { onStart() }
    val first = remember { FocusRequester() }
    LaunchedEffect(entries.isNotEmpty() || waiting.isNotEmpty()) { first.requestSoon() }
    Column(modifier.fillMaxSize().padding(end = 30.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.tv_nav_gatedrop), style = MaterialTheme.typography.titleMedium, color = colors.text)
            val fresh = entries.count { !it.seen }
            if (fresh > 0) TvPill(pluralStringResource(R.plurals.tv_drop_new_count, fresh, fresh), TvPillTone.Accent)
            if (receiving != null) TvPill(stringResource(R.string.tv_drop_receiving), TvPillTone.Info)
        }
        when {
            available == false -> Text(stringResource(R.string.tv_drop_not_on), style = MaterialTheme.typography.bodyLarge, color = colors.muted)
            entries.isEmpty() && waiting.isEmpty() -> Text(stringResource(R.string.tv_drop_empty), style = MaterialTheme.typography.bodyLarge, color = colors.muted)
            else -> LazyColumn(contentPadding = PaddingValues(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                items(waiting, key = { "w:" + it.id }) { send ->
                    TvOptionRow(
                        title = stringResource(R.string.tv_drop_from, send.from ?: stringResource(R.string.tv_drop_someone)),
                        description = stringResource(R.string.tv_drop_waiting, filesText(send.items.size), TvDropLogic.size(send.items.sumOf { it.bytes })),
                        end = TvRowEnd.Arrow,
                        onClick = { onTake(send) },
                        modifier = if (send == waiting.first()) Modifier.focusRequester(first) else Modifier,
                        badge = { TvPill(stringResource(R.string.tv_drop_badge_waiting), TvPillTone.Warn, dot = false) },
                    )
                }
                items(entries, key = { "e:" + it.sendId }) { e ->
                    TvOptionRow(
                        title = stringResource(R.string.tv_drop_from, e.from ?: stringResource(R.string.tv_drop_someone)),
                        description = listOfNotNull(
                            e.files.joinToString(", ") { it.name }.takeIf { it.isNotEmpty() } ?: e.text ?: e.link,
                            DateUtils.getRelativeTimeSpanString(e.at).toString(),
                        ).joinToString(" · "),
                        end = TvRowEnd.Arrow,
                        onClick = { onOpen(e) },
                        modifier = if (waiting.isEmpty() && e == entries.first()) Modifier.focusRequester(first) else Modifier,
                        badge = if (!e.seen) ({ TvPill(stringResource(R.string.tv_drop_badge_new), TvPillTone.Accent, dot = false) }) else null,
                    )
                }
            }
        }
    }
}

@Composable
private fun filesText(n: Int): String = pluralStringResource(R.plurals.tv_drop_files, n, n)

/**
 * Someone sends something to the TV (mockup 5): who, the files, and "Annehmen und zeigen", "Nur annehmen",
 * "Später". Pictures and videos can be shown at once, the rest lands on the page GateDrop.
 */
@Composable
fun TvDropOfferDialog(offer: TvDropOffer, onShow: () -> Unit, onTake: () -> Unit, onLater: () -> Unit) {
    val colors = GateControlTvTheme.colors
    val first = remember { FocusRequester() }
    val media = offer.files.any { TvDropLogic.media(it.mime) && TvDropLogic.fetchable(it) }
    TvDialog(onDismiss = onLater) {
        val from = offer.entry.from ?: stringResource(R.string.tv_drop_sender)
        Text(
            if (offer.files.isEmpty()) stringResource(R.string.tv_drop_offer_text, from)
            else pluralStringResource(R.plurals.tv_drop_offer_files, offer.files.size, from, offer.files.size),
            style = MaterialTheme.typography.headlineSmall,
            color = colors.text,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(22.dp).background(colors.accentBg, CircleShape), contentAlignment = Alignment.Center) {
                Text(from.take(1).uppercase(), style = MaterialTheme.typography.labelMedium, color = colors.accentText)
            }
            Text(from, style = MaterialTheme.typography.bodyMedium, color = colors.muted)
            if (offer.files.isNotEmpty()) TvPill(TvDropLogic.size(offer.bytes), TvPillTone.Neutral, dot = false)
        }
        offer.entry.text?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.text, maxLines = 4, overflow = TextOverflow.Ellipsis) }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (f in offer.files.take(6)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(f.name, style = MaterialTheme.typography.bodySmall, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(
                        if (TvDropLogic.fetchable(f)) TvDropLogic.size(f.bytes) else stringResource(R.string.tv_drop_not_fetchable),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.muted,
                    )
                }
            }
            if (offer.files.size > 6) Text(pluralStringResource(R.plurals.tv_drop_more, offer.files.size - 6, offer.files.size - 6), style = MaterialTheme.typography.bodySmall, color = colors.muted)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            if (media) TvButton(stringResource(R.string.tv_drop_show), onClick = onShow, style = TvButtonStyle.Primary, modifier = Modifier.focusRequester(first))
            TvButton(
                stringResource(R.string.tv_drop_take),
                onClick = onTake,
                style = if (media) TvButtonStyle.Secondary else TvButtonStyle.Primary,
                modifier = if (media) Modifier else Modifier.focusRequester(first),
            )
            TvButton(stringResource(R.string.tv_drop_later), onClick = onLater)
        }
    }
    LaunchedEffect(Unit) { first.requestSoon() }
}

/**
 * An entry of the page: who sent what, its files (OK on one: show or open it, "Senden an …"), and "Zeigen" (pictures,
 * videos), "Link öffnen", "Löschen" (on the TV only), "Schließen".
 */
@Composable
fun TvDropEntryDialog(
    entry: TvDropEntry,
    onShow: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    onFile: (Int) -> Unit = {},
    onLink: (() -> Unit)? = null,
) {
    val colors = GateControlTvTheme.colors
    val first = remember { FocusRequester() }
    val media = entry.files.any { it.path != null && TvDropLogic.media(it.mime) }
    TvDialog(onDismiss = onDismiss) {
        Text(stringResource(R.string.tv_drop_from, entry.from ?: stringResource(R.string.tv_drop_someone)), style = MaterialTheme.typography.headlineSmall, color = colors.text)
        entry.text?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.text, maxLines = 6, overflow = TextOverflow.Ellipsis) }
        entry.link?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.info, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        if (entry.files.isNotEmpty()) {
            Column(Modifier.heightIn(max = 230.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                entry.files.forEachIndexed { i, f ->
                    if (f.path != null) {
                        TvOptionRow(
                            title = f.name,
                            description = TvDropLogic.size(f.bytes),
                            end = TvRowEnd.Arrow,
                            onClick = { onFile(i) },
                        )
                    } else {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(f.name, style = MaterialTheme.typography.bodySmall, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text(stringResource(R.string.tv_drop_not_fetchable), style = MaterialTheme.typography.bodySmall, color = colors.muted)
                        }
                    }
                }
            }
            Text(stringResource(R.string.tv_drop_file_hint), style = MaterialTheme.typography.bodySmall, color = colors.muted)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            if (media) TvButton(stringResource(R.string.tv_drop_show_short), onClick = onShow, style = TvButtonStyle.Primary, modifier = Modifier.focusRequester(first))
            if (onLink != null) {
                TvButton(
                    stringResource(R.string.tv_drop_open_link),
                    onClick = onLink,
                    style = if (media) TvButtonStyle.Secondary else TvButtonStyle.Primary,
                    modifier = if (media) Modifier else Modifier.focusRequester(first),
                )
            }
            TvButton(stringResource(R.string.tv_drop_delete), onClick = onDelete, style = TvButtonStyle.Warn)
            TvButton(
                stringResource(R.string.tv_player_close_panel),
                onClick = onDismiss,
                modifier = if (media || onLink != null) Modifier else Modifier.focusRequester(first),
            )
        }
    }
    LaunchedEffect(Unit) { first.requestSoon() }
}

/**
 * A file received on the TV: "Zeigen" (a picture or video, in the viewer) or "Öffnen" (another app on the TV, if one
 * takes it), "Senden an …" (GateDrop to another device or person), "Schließen".
 */
@Composable
fun TvDropFileDialog(file: TvDropFile, onShow: () -> Unit, onSend: () -> Unit, onDismiss: () -> Unit) {
    val colors = GateControlTvTheme.colors
    val first = remember { FocusRequester() }
    TvDialog(onDismiss = onDismiss) {
        Text(file.name, style = MaterialTheme.typography.headlineSmall, color = colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(TvDropLogic.size(file.bytes), style = MaterialTheme.typography.bodyMedium, color = colors.muted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            TvButton(
                stringResource(if (TvDropLogic.media(file.mime)) R.string.tv_drop_show_short else R.string.tv_drop_open),
                onClick = onShow,
                style = TvButtonStyle.Primary,
                modifier = Modifier.focusRequester(first),
            )
            TvButton(stringResource(R.string.tv_send_to), onClick = onSend)
            TvButton(stringResource(R.string.tv_player_close_panel), onClick = onDismiss)
        }
    }
    LaunchedEffect(Unit) { first.requestSoon() }
}

/**
 * "Annehmen und zeigen" while the files come (mockup 5's bar): how far, and "Schließen" — it goes on in the
 * background and lands on the page GateDrop.
 */
@Composable
fun TvDropProgressDialog(progress: TvDropProgress?, onDismiss: () -> Unit) {
    val colors = GateControlTvTheme.colors
    val first = remember { FocusRequester() }
    TvDialog(onDismiss = onDismiss) {
        Text(stringResource(R.string.tv_drop_receiving), style = MaterialTheme.typography.headlineSmall, color = colors.text)
        Box(Modifier.fillMaxWidth().height(6.dp).background(colors.panel2, RoundedCornerShape(3.dp))) {
            Box(Modifier.fillMaxWidth(progress?.fraction ?: 0f).height(6.dp).background(colors.accent, RoundedCornerShape(3.dp)))
        }
        if (progress != null && progress.total > 0) {
            Text(
                "${TvDropLogic.size(progress.done)} / ${TvDropLogic.size(progress.total)}",
                style = MaterialTheme.typography.bodySmall,
                color = colors.muted,
            )
        }
        TvButton(stringResource(R.string.tv_player_close_panel), onClick = onDismiss, modifier = Modifier.focusRequester(first))
    }
    LaunchedEffect(Unit) { first.requestSoon() }
}
