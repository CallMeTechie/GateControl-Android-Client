package com.gatecontrol.android.ui.notizen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gatecontrol.android.R
import com.gatecontrol.android.notizen.NMoveOption
import com.gatecontrol.android.notizen.NTask
import com.gatecontrol.android.notizen.NYesterday
import com.gatecontrol.android.notizen.NotizenFormat
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.theme.GateControlTheme

/** What the card „Gestern offen geblieben“ can do. */
internal class YesterdayActions(
    val open: (NTask) -> Unit,
    val done: (NTask) -> Unit,
    /** `today`, `tomorrow`, `weekend` or a date. */
    val move: (NTask, String) -> Unit,
    val allToday: () -> Unit,
)

/** The server's choices of „Verschieben“, or — when it said none (offline) — the two it always has. */
@Composable
private fun moveOptions(options: List<NMoveOption>): List<NMoveOption> = options.ifEmpty {
    listOf(NMoveOption("tomorrow", null, stringResource(R.string.notizen_y_tomorrow)), NMoveOption("weekend", null, stringResource(R.string.notizen_y_weekend)))
}

/**
 * „Gestern offen geblieben“ (plugin 1.3.0, mockup „Dasselbe in ‚Mein Tag‘“): what stayed open yesterday, above „Heute“
 * until each row is decided — Erledigt, Heute or Verschieben; „Alle heute“ at the head. [canWrite] false (a viewer):
 * the rows without buttons.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun YesterdayCard(card: NYesterday, ctx: RowCtx, canWrite: Boolean, actions: YesterdayActions, modifier: Modifier = Modifier) {
    val x = GateControlTheme.extraColors
    var datePick by rememberSaveable { mutableStateOf<Long?>(null) }
    NCard(modifier.padding(top = 12.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 14.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(GcIcons.Flag, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Text(
                card.title.ifBlank { stringResource(R.string.notizen_y_title) },
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.weight(1f),
            )
            if (canWrite && card.items.any { it.canEdit }) {
                Text(
                    stringResource(R.string.notizen_y_all_today),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = actions.allToday).padding(horizontal = 8.dp, vertical = 14.dp),
                )
            }
        }
        card.items.forEach { t ->
            Box(Modifier.fillMaxWidth().height(1.dp).background(x.border))
            Column(Modifier.fillMaxWidth().clickable(role = Role.Button) { actions.open(t) }.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(t.title, style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold), maxLines = 3, overflow = TextOverflow.Ellipsis)
                // a task with a place names the place instead of the list, as the morning hint does (plugin 1.5.0)
                val meta = listOfNotNull(NotizenFormat.due(t.due, ctx.today, ctx.lang, ctx.words), t.place?.name ?: ctx.listPath(t.listId)).joinToString(" · ")
                if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall, color = x.muted)
                if (canWrite && (t.canComplete || t.canEdit)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (t.canComplete) YPill(stringResource(R.string.notizen_y_done), t.title) { actions.done(t) }
                        if (t.canEdit) {
                            YPill(stringResource(R.string.notizen_y_today), t.title) { actions.move(t, "today") }
                            var menu by remember { mutableStateOf(false) }
                            Box {
                                YPill(stringResource(R.string.notizen_y_move), t.title) { menu = true }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    moveOptions(card.moveOptions).forEach { o ->
                                        DropdownMenuItem(text = { Text(o.label) }, onClick = { menu = false; actions.move(t, o.to) })
                                    }
                                    DropdownMenuItem(text = { Text(stringResource(R.string.notizen_y_other_date)) }, onClick = { menu = false; datePick = t.id })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    datePick?.let { id ->
        val tomorrow = runCatching { java.time.LocalDate.parse(ctx.today).plusDays(1).toString() }.getOrNull()
        NDatePicker(tomorrow, ctx.today, { d -> datePick = null; card.items.firstOrNull { it.id == id }?.let { actions.move(it, d) } }, { datePick = null })
    }
}

/** A button of a row: the mockup's soft green pill. */
@Composable
private fun YPill(label: String, title: String, onClick: () -> Unit) {
    val cd = stringResource(R.string.notizen_y_action_cd, label, title)
    Box(
        Modifier
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = cd }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.primary)
    }
}

/**
 * A button of the morning hint opened the app (plugin 1.3.0): „Erledigt“ of several tasks asks once („Alle N
 * abhaken?“), „Verschieben“ offers „Morgen · Am Wochenende · Anderes Datum“.
 */
@Composable
internal fun YesterdayAsk(ask: NotizenHomeViewModel.YAsk, today: String, onAll: (action: String, ids: List<Long>, to: String?) -> Unit, onDismiss: () -> Unit) {
    if (ask.action == com.gatecontrol.android.notizen.NotizenPush.Y_DONE) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.notizen_y_ask_done, ask.ids.size)) },
            text = { Text(stringResource(R.string.notizen_y_ask_done_text)) },
            confirmButton = { TextButton(onClick = { onAll("done", ask.ids, null) }) { Text(stringResource(R.string.notizen_y_ask_done_confirm)) } },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.notizen_cancel)) } },
        )
        return
    }
    var date by rememberSaveable { mutableStateOf(false) }
    if (date) {
        val tomorrow = runCatching { java.time.LocalDate.parse(today).plusDays(1).toString() }.getOrNull()
        NDatePicker(tomorrow, today, { d -> onAll("move", ask.ids, d) }, onDismiss)
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.notizen_y_ask_move)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                moveOptions(ask.options).forEach { o ->
                    Text(o.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { onAll("move", ask.ids, o.to) }.padding(vertical = 14.dp))
                }
                Text(stringResource(R.string.notizen_y_other_date), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { date = true }.padding(vertical = 14.dp))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.notizen_cancel)) } },
    )
}
