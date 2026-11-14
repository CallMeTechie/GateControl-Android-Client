package com.gatecontrol.android.ui.notizen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.R
import com.gatecontrol.android.notizen.NFamilyMoved
import com.gatecontrol.android.notizen.NPerson
import com.gatecontrol.android.notizen.NTask
import com.gatecontrol.android.notizen.NotizenTurns
import com.gatecontrol.android.notizen.TurnWords
import com.gatecontrol.android.ui.components.GcFilterChip
import com.gatecontrol.android.ui.components.GcSegmented
import com.gatecontrol.android.ui.theme.GateControlTheme

/**
 * „Wer ist dran?“ (plugin 1.6.0, mockup „Anlegen“): Eine Person · Reihum · Wer zuerst, with what the two mean. The
 * same in the quick entry and in a task's details.
 */
@Composable
internal fun TurnChoice(mode: String, onMode: (String) -> Unit, modifier: Modifier = Modifier) {
    val x = GateControlTheme.extraColors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.notizen_turn_title), style = MaterialTheme.typography.labelLarge, color = x.muted)
        GcSegmented(
            listOf(
                NotizenTurns.ONE to stringResource(R.string.notizen_turn_one),
                TurnWords.ROTATE to stringResource(R.string.notizen_turn_rotate),
                TurnWords.FIRST to stringResource(R.string.notizen_turn_first),
            ),
            mode,
            onMode,
        )
        Text(stringResource(R.string.notizen_turn_hint), style = MaterialTheme.typography.bodySmall, color = x.muted)
    }
}

/** The value of the row „Wer ist dran?“ in a task's details: „Eine Person“, „Reihum · Lena ist dran“, „Wer zuerst · Mia, Jonas“. */
@Composable
internal fun turnValue(task: NTask, meId: Long?): String = when (task.turn) {
    TurnWords.FIRST -> stringResource(R.string.notizen_turn_first) + " · " + task.people.joinToString(", ") { it.name.orEmpty().substringBefore(' ') }
    TurnWords.ROTATE -> turnText(task.copy(done = false), meId) ?: stringResource(R.string.notizen_turn_rotate)
    else -> stringResource(R.string.notizen_turn_one)
}

/**
 * „Wer ist dran?“ of a task: the people of its list (at least two for „Reihum“ and „Wer zuerst“) and the choice.
 * [onSave]: the mode ([NotizenTurns.ONE], `rotate`, `first`) and the people in the order they stand in the list.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TurnDialog(task: NTask, people: List<NPerson>, meId: Long?, onSave: (String, List<Long>) -> Unit, onDismiss: () -> Unit) {
    val x = GateControlTheme.extraColors
    var mode by remember { mutableStateOf(NotizenTurns.mode(task.turn)) }
    val start = task.people.mapNotNull { it.id }.ifEmpty { listOfNotNull(task.assignee?.id) }
    var picked by remember { mutableStateOf(start) }
    // the people of the task first (their order is the order of the turns), then the others of the list
    val all = (task.people + people).filter { it.id != null }.distinctBy { it.id }
    val enough = mode == NotizenTurns.ONE || picked.size >= 2
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.notizen_turn_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    all.forEach { p ->
                        val id = p.id!!
                        val name = if (id == meId) stringResource(R.string.notizen_me_name, p.name.orEmpty()) else p.name.orEmpty()
                        GcFilterChip(name, id in picked, { picked = if (id in picked) picked - id else picked + id })
                    }
                }
                TurnChoice(mode, { mode = it })
                if (!enough) Text(stringResource(R.string.notizen_turn_two), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                else if (mode == TurnWords.ROTATE) {
                    val first = all.firstOrNull { it.id == (task.assignee?.id?.takeIf { a -> a in picked } ?: picked.firstOrNull()) }?.name
                    if (first != null) Text(stringResource(R.string.notizen_turn_rotate_first, first.substringBefore(' ')), style = MaterialTheme.typography.bodySmall, color = x.muted)
                }
            }
        },
        confirmButton = { TextButton(enabled = enough, onClick = { onSave(mode, picked) }) { Text(stringResource(R.string.notizen_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.notizen_cancel)) } },
    )
}

/** „Sterne“ of a task of the family's list: none … the most the Familie plugin allows — only the parents choose. */
@Composable
internal fun StarsDialog(current: Int, max: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val x = GateControlTheme.extraColors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.notizen_stars_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                (0..max).forEach { n ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.RadioButton) { onPick(n) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (n == 0) stringResource(R.string.notizen_stars_none) else starsText(n),
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = if (n == current) androidx.compose.ui.text.font.FontWeight.Bold else null),
                        )
                    }
                }
                Text(stringResource(R.string.notizen_stars_hint), style = MaterialTheme.typography.bodySmall, color = x.muted, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.notizen_cancel)) } },
    )
}

/** „Abhaken für …“: a parent ticks a task of the family's list off for one of the family (the stars go to that person). */
@Composable
internal fun TickForDialog(people: List<NPerson>, meId: Long?, onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.notizen_tick_for)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                people.forEach { p ->
                    val id = p.id ?: return@forEach
                    Text(
                        if (id == meId) stringResource(R.string.notizen_me_name, p.name.orEmpty()) else p.name.orEmpty(),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { onPick(id) }.padding(vertical = 12.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.notizen_cancel)) } },
    )
}

/**
 * „Umzug abgeschlossen“ (mockup „Umzug“), once per person: what came over from the Familie plugin. „Ansehen“ opens the
 * family's list; both buttons tell the server the note was read.
 */
@Composable
internal fun FamilyMovedDialog(moved: NFamilyMoved, onView: () -> Unit, onOk: () -> Unit) {
    AlertDialog(
        onDismissRequest = onOk,
        title = { Text(stringResource(R.string.notizen_moved_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.notizen_moved_count, moved.tasks, moved.places), style = MaterialTheme.typography.bodyMedium)
                listOf(R.string.notizen_moved_kept, R.string.notizen_moved_places, R.string.notizen_moved_stars, R.string.notizen_moved_copy).forEach {
                    Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = GateControlTheme.extraColors.muted)
                }
            }
        },
        confirmButton = { TextButton(onClick = onView) { Text(stringResource(R.string.notizen_moved_view)) } },
        dismissButton = { TextButton(onClick = onOk) { Text(stringResource(R.string.notizen_ok)) } },
    )
}
