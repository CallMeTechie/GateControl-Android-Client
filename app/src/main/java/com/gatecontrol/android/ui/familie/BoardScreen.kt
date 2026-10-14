package com.gatecontrol.android.ui.familie

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.familie.FamilieApi
import com.gatecontrol.android.familie.FamilieProfile
import com.gatecontrol.android.familie.FamilieRepository
import com.gatecontrol.android.familie.FamilieResult
import com.gatecontrol.android.familie.board.BoardNote
import com.gatecontrol.android.familie.board.BoardPayload
import com.gatecontrol.android.familie.board.Checklist
import com.gatecontrol.android.familie.board.ChecklistItem
import com.gatecontrol.android.familie.board.NoteDraft
import com.gatecontrol.android.familie.push.FamiliePushEvent
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcChip
import com.gatecontrol.android.ui.components.GcFilterChip
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcSegmented
import com.gatecontrol.android.ui.components.GcSwitchRow
import com.gatecontrol.android.ui.components.GcTextField
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.theme.GateControlTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** Pinnwand & Packlisten. */
@HiltViewModel
class BoardViewModel @Inject constructor(
    private val api: FamilieApi,
    repository: FamilieRepository,
) : FamilieLoadViewModel<BoardPayload>(repository) {

    init {
        start()
    }

    override suspend fun load(): FamilieResult<BoardPayload> = api.board()

    override fun refreshOn(event: FamiliePushEvent): Boolean = event is FamiliePushEvent.Other && event.topicKey == "board"

    fun saveNote(id: Int?, draft: NoteDraft) = act { if (id == null) api.createNote(draft) else api.updateNote(id, draft) }

    fun deleteNote(id: Int) = act { api.deleteNote(id) }

    fun createChecklist(templateId: Int?, title: String?) = act { api.createChecklist(templateId, title) }

    fun deleteChecklist(id: Int) = act { api.deleteChecklist(id) }

    fun resetChecklist(id: Int) = act { api.resetChecklist(id) }

    fun addItem(checklistId: Int, text: String) {
        if (text.isBlank()) return
        act { api.addChecklistItem(checklistId, text) }
    }

    fun toggle(checklistId: Int, item: ChecklistItem) {
        // Shown at once; the reload brings the server's state.
        _data.value = _data.value?.let { b ->
            b.copy(checklists = b.checklists.map { c ->
                if (c.id != checklistId) c else c.copy(items = c.items.map { if (it.id == item.id) it.copy(done = !item.done) else it })
            })
        }
        act { api.checkChecklistItem(checklistId, item.id, !item.done) }
    }

    fun deleteItem(checklistId: Int, itemId: Int) = act { api.deleteChecklistItem(checklistId, itemId) }

    fun saveTemplate(checklistId: Int, title: String) = act(R.string.familie_board_template_saved) { api.createTemplate(checklistId, title) }

    fun deleteTemplate(id: Int) = act { api.deleteTemplate(id) }
}

private enum class BoardTab { NOTES, LISTS }

private sealed interface BoardDialog {
    data class Note(val note: BoardNote?) : BoardDialog
    data object NewList : BoardDialog
    data class SaveTemplate(val list: Checklist) : BoardDialog
    data class DeleteList(val list: Checklist) : BoardDialog
}

/**
 * Pinnwand & Packlisten (no mockup — Familie style): sticky notes in a
 * grid (pinned first, colour, optional end date), checklists with
 * templates such as "Urlaub" or "Schwimmbad" (new list from a template,
 * save a list as template), items like the shopping list.
 */
@Composable
fun BoardScreen(onBack: () -> Unit, viewModel: BoardViewModel = hiltViewModel()) {
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val data by viewModel.data.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(BoardTab.NOTES) }
    var dialog by remember { mutableStateOf<BoardDialog?>(null) }
    FamilieToasts(viewModel)
    val p = profile

    FamiliePageFrame(
        title = stringResource(R.string.familie_board_title),
        onBack = onBack,
        refreshing = busy && data != null,
        onRefresh = { viewModel.refresh() },
        overlay = {
            FamilieFab(
                text = stringResource(if (tab == BoardTab.NOTES) R.string.familie_board_note_add else R.string.familie_board_list_add),
                icon = GcIcons.Plus,
                onClick = { dialog = if (tab == BoardTab.NOTES) BoardDialog.Note(null) else BoardDialog.NewList },
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 24.dp),
            )
        },
    ) {
        val b = data
        GcSegmented(
            options = listOf(
                BoardTab.NOTES to stringResource(R.string.familie_board_notes),
                BoardTab.LISTS to stringResource(R.string.familie_board_lists),
            ),
            selected = tab,
            onSelect = { tab = it },
        )
        when {
            b == null && error != null -> FamilieErrorBanner(error, onRetry = { viewModel.refresh() })
            b == null || p == null -> FamilieLoading()
            else -> {
                if (error != null) FamilieErrorBanner(error)
                if (tab == BoardTab.NOTES) {
                    if (b.notes.isEmpty()) FamilieEmpty(GcIcons.Board, stringResource(R.string.familie_board_notes_none))
                    NotesGrid(p, b.notes, onOpen = { dialog = BoardDialog.Note(it) })
                } else {
                    if (b.checklists.isEmpty()) FamilieEmpty(GcIcons.Tasks, stringResource(R.string.familie_board_lists_none))
                    b.checklists.forEach { c ->
                        ChecklistCard(
                            p = p,
                            list = c,
                            viewModel = viewModel,
                            onSaveTemplate = { dialog = BoardDialog.SaveTemplate(c) },
                            onDelete = { dialog = BoardDialog.DeleteList(c) },
                        )
                    }
                }
            }
        }
    }

    when (val d = dialog) {
        is BoardDialog.Note -> NoteDialog(
            note = d.note,
            canDelete = d.note != null && p != null && (p.me.isParent || d.note.authorId == p.me.userId),
            onDismiss = { dialog = null },
            onSave = { draft ->
                viewModel.saveNote(d.note?.id, draft)
                dialog = null
            },
            onDelete = {
                d.note?.let { viewModel.deleteNote(it.id) }
                dialog = null
            },
        )
        BoardDialog.NewList -> NewListDialog(
            payload = data,
            onDismiss = { dialog = null },
            onCreate = { templateId, title ->
                viewModel.createChecklist(templateId, title)
                dialog = null
            },
            onDeleteTemplate = { viewModel.deleteTemplate(it) },
        )
        is BoardDialog.SaveTemplate -> FamilieInputDialog(
            title = stringResource(R.string.familie_board_template_save),
            fields = listOf(DialogField(stringResource(R.string.familie_board_template_name), d.list.title, Checklist.MAX_TITLE)),
            onConfirm = { v ->
                viewModel.saveTemplate(d.list.id, v[0])
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        is BoardDialog.DeleteList -> FamilieConfirmDialog(
            title = stringResource(R.string.familie_board_list_delete),
            text = stringResource(R.string.familie_board_list_delete_text, d.list.title),
            confirmLabel = stringResource(R.string.familie_delete),
            destructive = true,
            onConfirm = {
                viewModel.deleteChecklist(d.list.id)
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        null -> Unit
    }
}

/** Sticky-note colours (light / dark). */
@Composable
private fun noteColor(color: String): Color {
    val dark = GateControlTheme.extraColors.isDark
    return when (color) {
        "green" -> if (dark) Color(0xFF1F3B2E) else Color(0xFFDDF3E6)
        "blue" -> if (dark) Color(0xFF1E2C47) else Color(0xFFDDE8FB)
        "pink" -> if (dark) Color(0xFF452235) else Color(0xFFFBE0EC)
        "grey" -> if (dark) Color(0xFF2A2F38) else Color(0xFFECEFF3)
        else -> if (dark) Color(0xFF473C17) else Color(0xFFFFF3C4)
    }
}

@Composable
private fun NotesGrid(p: FamilieProfile, notes: List<BoardNote>, onOpen: (BoardNote) -> Unit) {
    val extra = GateControlTheme.extraColors
    notes.chunked(2).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            row.forEach { n ->
                Surface(
                    onClick = { onOpen(n) },
                    color = noteColor(n.color),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.weight(1f).heightIn(min = 120.dp),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                n.authorId?.let { memberName(p, it) } ?: "",
                                style = MaterialTheme.typography.labelMedium,
                                color = extra.muted,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (n.pinned) Icon(GcIcons.Pin, contentDescription = stringResource(R.string.familie_board_pinned), tint = extra.accentText, modifier = Modifier.size(16.dp))
                        }
                        Text(n.text, style = MaterialTheme.typography.bodyMedium, maxLines = 8, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
                        n.expiresAt?.let { Text(stringResource(R.string.familie_board_until, relativeStamp(it)), style = MaterialTheme.typography.labelSmall, color = extra.muted) }
                    }
                }
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun ChecklistCard(p: FamilieProfile, list: Checklist, viewModel: BoardViewModel, onSaveTemplate: () -> Unit, onDelete: () -> Unit) {
    val extra = GateControlTheme.extraColors
    var expanded by rememberSaveable(list.id) { mutableStateOf(list.open > 0) }
    var menu by remember { mutableStateOf(false) }
    var text by rememberSaveable(list.id) { mutableStateOf("") }
    GcCard(contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.Top) {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button) { expanded = !expanded }.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(list.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (list.items.isEmpty()) stringResource(R.string.familie_board_list_empty)
                    else pluralStringResource(R.plurals.familie_board_list_open, list.open, list.open, list.items.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = extra.muted,
                )
            }
            if (list.items.isNotEmpty() && list.open == 0) GcChip(stringResource(R.string.familie_board_list_done), tone = GcTone.Ok, small = true)
            Box {
                GcIconButton(GcIcons.Sliders, contentDescription = stringResource(R.string.familie_more), onClick = { menu = true }, size = 40.dp, iconSize = 18.dp)
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (list.items.any { it.done }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.familie_board_list_reset)) }, onClick = {
                            menu = false
                            viewModel.resetChecklist(list.id)
                        })
                    }
                    DropdownMenuItem(text = { Text(stringResource(R.string.familie_board_template_save)) }, onClick = {
                        menu = false
                        onSaveTemplate()
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.familie_board_list_delete), color = MaterialTheme.colorScheme.error) }, onClick = {
                        menu = false
                        onDelete()
                    })
                }
            }
        }
        if (expanded) {
            list.items.forEach { item ->
                GcRowDivider()
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    GcIconButton(
                        icon = if (item.done) GcIcons.Check else GcIcons.Minus,
                        contentDescription = stringResource(R.string.familie_board_item_check_cd, item.text),
                        onClick = { viewModel.toggle(list.id, item) },
                        tint = if (item.done) MaterialTheme.colorScheme.primary else extra.faint,
                    )
                    Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                        Text(
                            item.text,
                            style = MaterialTheme.typography.bodyLarge.copy(textDecoration = if (item.done) TextDecoration.LineThrough else null),
                            color = if (item.done) extra.muted else MaterialTheme.colorScheme.onSurface,
                        )
                        if (item.done && item.doneBy != null) Text(memberName(p, item.doneBy), style = MaterialTheme.typography.labelSmall, color = extra.faint)
                    }
                    GcIconButton(GcIcons.Close, contentDescription = stringResource(R.string.familie_board_item_delete_cd, item.text), onClick = { viewModel.deleteItem(list.id, item.id) }, size = 40.dp, iconSize = 16.dp)
                }
            }
            GcRowDivider()
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                GcTextField(
                    value = text,
                    onValueChange = { text = it.take(ChecklistItem.MAX_TEXT) },
                    label = stringResource(R.string.familie_board_item_add),
                    modifier = Modifier.weight(1f),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = {
                        viewModel.addItem(list.id, text)
                        text = ""
                    }),
                )
                GcIconButton(GcIcons.Plus, contentDescription = stringResource(R.string.familie_board_item_add), onClick = {
                    viewModel.addItem(list.id, text)
                    text = ""
                }, tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NoteDialog(note: BoardNote?, canDelete: Boolean, onDismiss: () -> Unit, onSave: (NoteDraft) -> Unit, onDelete: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(note?.text.orEmpty()) }
    var color by rememberSaveable { mutableStateOf(note?.color ?: BoardNote.YELLOW) }
    var pinned by rememberSaveable { mutableStateOf(note?.pinned ?: false) }
    var until by rememberSaveable { mutableStateOf(note?.expiresAt?.let { FamilieFormat.parseInstant(it)?.atZone(java.time.ZoneId.systemDefault())?.toLocalDate()?.toString() }) }
    FamilieFormDialog(
        title = stringResource(if (note == null) R.string.familie_board_note_new else R.string.familie_board_note_edit),
        onDismiss = onDismiss,
        saveEnabled = text.isNotBlank(),
        onSave = { onSave(NoteDraft(text, color, pinned, until)) },
        onDelete = if (canDelete) onDelete else null,
    ) {
        GcTextField(value = text, onValueChange = { text = it.take(BoardNote.MAX_TEXT) }, label = stringResource(R.string.familie_board_note_text), singleLine = false)
        Text(stringResource(R.string.familie_board_note_color), style = MaterialTheme.typography.titleSmall, color = GateControlTheme.extraColors.muted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            BoardNote.COLORS.forEach { c ->
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .border(if (c == color) 3.dp else 1.dp, if (c == color) MaterialTheme.colorScheme.primary else GateControlTheme.extraColors.border2, CircleShape)
                        .clickable(role = Role.RadioButton, onClickLabel = c) { color = c }
                        .padding(4.dp)
                        .clip(CircleShape),
                ) { Surface(color = noteColor(c), shape = CircleShape, modifier = Modifier.size(32.dp)) {} }
            }
        }
        GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.Top) {
            GcSwitchRow(
                label = stringResource(R.string.familie_board_note_pin),
                description = stringResource(R.string.familie_board_note_pin_desc),
                checked = pinned,
                onCheckedChange = { pinned = it },
                icon = GcIcons.Pin,
            )
        }
        FamilieDateField(label = stringResource(R.string.familie_board_note_until), value = until, onValueChange = { until = it }, clearable = true)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NewListDialog(payload: BoardPayload?, onDismiss: () -> Unit, onCreate: (Int?, String?) -> Unit, onDeleteTemplate: (Int) -> Unit) {
    var title by rememberSaveable { mutableStateOf("") }
    var template by rememberSaveable { mutableStateOf<Int?>(null) }
    val templates = payload?.templates.orEmpty()
    FamilieFormDialog(
        title = stringResource(R.string.familie_board_list_new),
        onDismiss = onDismiss,
        saveEnabled = template != null || title.isNotBlank(),
        onSave = { onCreate(template, title.takeIf { it.isNotBlank() }) },
    ) {
        GcTextField(value = title, onValueChange = { title = it.take(Checklist.MAX_TITLE) }, label = stringResource(R.string.familie_board_list_title), placeholder = stringResource(R.string.familie_board_list_title_hint))
        if (templates.isNotEmpty()) {
            Text(stringResource(R.string.familie_board_templates), style = MaterialTheme.typography.titleSmall, color = GateControlTheme.extraColors.muted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                templates.forEach { t ->
                    GcFilterChip(
                        text = t.title + " · " + t.items.size,
                        selected = template == t.id,
                        onClick = { template = if (template == t.id) null else t.id },
                    )
                }
            }
            val chosen = templates.firstOrNull { it.id == template }
            if (chosen != null) {
                Text(chosen.items.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = GateControlTheme.extraColors.muted)
                GcOutlineButton(text = stringResource(R.string.familie_board_template_delete), onClick = {
                    onDeleteTemplate(chosen.id)
                    template = null
                }, icon = GcIcons.Trash)
            }
        } else {
            Text(stringResource(R.string.familie_board_templates_none), style = MaterialTheme.typography.bodySmall, color = GateControlTheme.extraColors.muted)
        }
    }
}
