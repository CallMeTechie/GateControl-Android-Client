package com.gatecontrol.android.ui.mail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.mail.MAccount
import com.gatecontrol.android.mail.MFolder
import com.gatecontrol.android.mail.MMe
import com.gatecontrol.android.mail.MSummary
import com.gatecontrol.android.mail.MailBulk
import com.gatecontrol.android.mail.MailBulk.Action
import com.gatecontrol.android.mail.MailLogic
import com.gatecontrol.android.ui.components.GcBarAction
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcMoreRow
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcSegmented
import com.gatecontrol.android.ui.components.GcSelectionActionBar
import com.gatecontrol.android.ui.components.GcSelectionHead
import com.gatecontrol.android.ui.theme.GateControlTheme

/*
 * Mail 1.13 "Mehrfachauswahl" (mockups 1–3): the head while mails are ticked, the bar of actions, "Mehr", the folder
 * picker (move or copy, "Zuletzt", search, the tree), the question before a delete and the snackbar with "Rückgängig".
 */

/**
 * "N ausgewählt" over the list: [where] below ("Posteingang · Alle Konten"; with "Alle" how many of which folder),
 * the icon "Als gelesen / ungelesen markieren" and "Alle" / "Keine".
 */
@Composable
internal fun MailSelectionHead(
    selection: MailBulk.Selection,
    where: String,
    folderName: String,
    allSelected: Boolean,
    loadingAll: Boolean,
    read: Action,
    onRead: () -> Unit,
    onClose: () -> Unit,
    onAll: () -> Unit,
) {
    val all = selection.all
    val line = when {
        loadingAll -> stringResource(R.string.mail_sel_loading_all)
        all != null && all.truncated -> stringResource(R.string.mail_sel_line_newest, selection.count, all.total, folderName)
        all != null -> pluralStringResource(R.plurals.mail_sel_line_all, selection.count, selection.count, folderName)
        else -> where
    }
    GcSelectionHead(
        count = selection.count,
        line = line,
        allSelected = allSelected,
        onClose = onClose,
        onAll = onAll,
    ) {
        if (loadingAll) CircularProgressIndicator(Modifier.padding(horizontal = 8.dp).size(18.dp), strokeWidth = 2.dp)
        GcIconButton(
            if (read == Action.READ) MailIcons.Read else MailIcons.Unread,
            contentDescription = stringResource(if (read == Action.READ) R.string.mail_mark_read else R.string.mail_mark_unread),
            onClick = onRead,
            tint = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * The bar below the ticked mails: Archivieren, Löschen, Verschieben, Kopieren (only when the server can copy), Mehr.
 * Moving and copying stay in one account: with mails of several they are greyed out and say why.
 */
@Composable
internal fun MailSelectionBar(
    me: MMe?,
    oneAccount: Boolean,
    hasFolders: Boolean,
    onAction: (Action) -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val why = stringResource(R.string.mail_sel_why_accounts)
    val folders = oneAccount && hasFolders
    GcSelectionActionBar(
        buildList {
            add(GcBarAction(GcIcons.Archive, stringResource(R.string.mail_bar_archive)) { onAction(Action.ARCHIVE) })
            add(GcBarAction(GcIcons.Trash, stringResource(R.string.mail_bar_delete), danger = true) { onAction(Action.DELETE) })
            add(GcBarAction(GcIcons.FolderMove, stringResource(R.string.mail_bar_move), enabled = folders, why = why.takeIf { !oneAccount }) { onAction(Action.MOVE) })
            if (MailBulk.canCopy(me)) {
                add(GcBarAction(GcIcons.Copy, stringResource(R.string.mail_bar_copy), enabled = folders, why = why.takeIf { !oneAccount }) { onAction(Action.COPY) })
            }
            add(GcBarAction(GcIcons.MoreHorizontal, stringResource(R.string.mail_bar_more), onClick = onMore))
        },
        modifier,
    )
}

/**
 * "Mehr" for the ticked mails: als gelesen / ungelesen, markieren, nach Sonstige (in an inbox), in Spam; with one mail
 * also the menu of that mail ([onOne]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MailMoreSheet(
    count: Int,
    items: List<MSummary>,
    inbox: Boolean,
    onAction: (Action) -> Unit,
    onOne: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surface) {
        MailMoreContent(count, items, inbox, onAction, onOne, onDismiss)
    }
}

/** The content of [MailMoreSheet]. */
@Composable
internal fun MailMoreContent(
    count: Int,
    items: List<MSummary>,
    inbox: Boolean,
    onAction: (Action) -> Unit,
    onOne: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val x = GateControlTheme.extraColors
    val go: (Action) -> Unit = { onDismiss(); onAction(it) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 8.dp).padding(bottom = 24.dp)) {
        Text(
            pluralStringResource(R.plurals.mail_n_mails, count, count),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 8.dp).semantics { heading() },
        )
        GcMoreRow(MailIcons.Read, stringResource(R.string.mail_mark_read)) { go(Action.READ) }
        GcMoreRow(MailIcons.Unread, stringResource(R.string.mail_mark_unread)) { go(Action.UNREAD) }
        val flag = MailBulk.flagAction(items)
        GcMoreRow(GcIcons.Flag, stringResource(if (flag == Action.FLAG) R.string.mail_flag else R.string.mail_unflag), tint = x.warn) { go(flag) }
        if (inbox) {
            val tab = MailBulk.tabAction(items)
            GcMoreRow(MailIcons.Inbox, stringResource(if (tab == Action.TO_OTHER) R.string.mail_to_other else R.string.mail_to_relevant)) { go(tab) }
        }
        GcMoreRow(GcIcons.ShieldX, stringResource(R.string.mail_more_junk)) { go(Action.JUNK) }
        if (onOne != null) {
            GcMoreRow(GcIcons.MoreVertical, stringResource(R.string.mail_more_row_actions)) { onDismiss(); onOne() }
        }
    }
}

/**
 * The folder picker as a sheet (mockup 2): "N Mails verschieben", Verschieben | Kopieren (copy only when the server
 * can), "Ordner suchen", "Zuletzt", the folders of the mails' account as a tree (the folder they lie in: "hier") and
 * "Nach „X“ verschieben". Only the folders of one account: the server moves and copies within an account.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MailFolderSheet(
    picker: MailPicker,
    account: MAccount?,
    folders: List<MFolder>,
    recent: List<Long>,
    onMode: (Action) -> Unit,
    onPick: (MFolder) -> Unit,
    onDismiss: () -> Unit,
    /** The person has several accounts: a note that the mails stay in theirs. */
    severalAccounts: Boolean = false,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surface) {
        MailFolderContent(picker, account, folders, recent, onMode, onPick, onDismiss, severalAccounts)
    }
}

/** The content of [MailFolderSheet]. */
@Composable
internal fun MailFolderContent(
    picker: MailPicker,
    account: MAccount?,
    folders: List<MFolder>,
    recent: List<Long>,
    onMode: (Action) -> Unit,
    onPick: (MFolder) -> Unit,
    onDismiss: () -> Unit,
    severalAccounts: Boolean = false,
) {
    val x = GateControlTheme.extraColors
    var query by rememberSaveable { mutableStateOf("") }
    var chosen by remember { mutableStateOf<MFolder?>(null) }
    val tree = remember(folders) { MailLogic.folderTree(folders) }
    var folds by remember { mutableStateOf<Map<Long, Boolean>>(emptyMap()) }
    val move = picker.mode == Action.MOVE
    // a move into the folder they lie in does nothing; a copy there makes a second one (allowed)
    val blocked: (MFolder) -> Boolean = { f -> move && f.id == picker.current }
    val context = LocalContext.current
    val labels = remember(folders) { folders.associate { it.id to MailFolders.labelOf(context, it) } }
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Text(
            pluralStringResource(if (move) R.plurals.mail_pick_title_move else R.plurals.mail_pick_title_copy, picker.count, picker.count),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp).semantics { heading() },
        )
        if (picker.copy) {
            GcSegmented(
                options = listOf(Action.MOVE to stringResource(R.string.mail_pick_move), Action.COPY to stringResource(R.string.mail_pick_copy)),
                selected = picker.mode,
                onSelect = onMode,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
        FolderSearchField(query, onChange = { query = it.take(80) }, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
        val recents = MailBulk.recentFolders(recent, folders).filter { !blocked(it) }
        LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 460.dp)) {
            if (query.isBlank() && recents.isNotEmpty()) {
                item(key = "recent-label") { PickerLabel(stringResource(R.string.mail_pick_recent)) }
                item(key = "recent") {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        recents.forEach { f -> RecentChip(labels[f.id] ?: f.name, on = chosen?.id == f.id) { chosen = f } }
                    }
                }
            }
            item(key = "account") {
                PickerLabel(listOfNotNull(account?.name?.takeIf { it.isNotBlank() }, account?.email).joinToString(" · "))
            }
            if (query.isBlank()) {
                items(MailLogic.folderRows(tree, folds, picker.current), key = { "f" + it.folder.id }) { r ->
                    PickerRow(
                        folder = r.folder,
                        label = labels[r.folder.id] ?: r.folder.name,
                        indent = r.indent,
                        row = r,
                        here = r.folder.id == picker.current,
                        enabled = !blocked(r.folder),
                        on = chosen?.id == r.folder.id,
                        onToggle = { folds = MailLogic.toggleFolder(tree, folds, r.folder.id, picker.current) },
                    ) { chosen = r.folder }
                }
            } else {
                val hits = MailBulk.search(folders, query) { labels[it.id] ?: it.name }
                if (hits.isEmpty()) item(key = "none") { Text(stringResource(R.string.mail_pick_none), color = x.muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) }
                items(hits, key = { "s" + it.id }) { f ->
                    PickerRow(f, labels[f.id] ?: f.name, 0, null, here = f.id == picker.current, enabled = !blocked(f), on = chosen?.id == f.id, onToggle = {}) { chosen = f }
                }
            }
        }
        if (severalAccounts) {
            Row(
                Modifier.padding(horizontal = 20.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(x.blueBg).padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(GcIcons.Info, contentDescription = null, tint = x.blue, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.mail_pick_note), style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(Modifier.padding(horizontal = 20.dp).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GcOutlineButton(stringResource(R.string.mail_cancel), onClick = onDismiss, modifier = Modifier.weight(1f))
            val c = chosen?.takeIf { !blocked(it) }
            GcPrimaryButton(
                c?.let { stringResource(if (move) R.string.mail_pick_go_move else R.string.mail_pick_go_copy, labels[it.id] ?: it.name) } ?: stringResource(R.string.mail_pick_choose),
                onClick = { c?.let(onPick) },
                enabled = c != null,
                modifier = Modifier.weight(2f),
            )
        }
    }
}

/** "Ordner suchen" (mockup: a slim field with the magnifier). */
@Composable
private fun FolderSearchField(query: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val x = GateControlTheme.extraColors
    val label = stringResource(R.string.mail_pick_search)
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        modifier = modifier.fillMaxWidth().semantics { contentDescription = label },
        singleLine = true,
        placeholder = { Text(label, color = x.muted) },
        leadingIcon = { Icon(GcIcons.Search, contentDescription = null, tint = x.muted, modifier = Modifier.size(20.dp)) },
        trailingIcon = if (query.isNotEmpty()) ({ GcIconButton(GcIcons.Close, contentDescription = stringResource(R.string.mail_cancel), onClick = { onChange("") }) }) else null,
        shape = RoundedCornerShape(12.dp),
        textStyle = MaterialTheme.typography.bodyMedium,
        colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = x.border, focusedBorderColor = MaterialTheme.colorScheme.primary),
    )
}

@Composable
private fun PickerLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = GateControlTheme.extraColors.faint,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 4.dp).semantics { heading() },
    )
}

@Composable
private fun RecentChip(label: String, on: Boolean, onClick: () -> Unit) {
    val x = GateControlTheme.extraColors
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .heightIn(min = 36.dp)
            .clip(shape)
            .then(if (on) Modifier.background(x.accentBg) else Modifier.border(1.dp, x.border, shape))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { selected = on }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(GcIcons.Folder, contentDescription = null, tint = if (on) x.accentText else x.muted, modifier = Modifier.size(16.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (on) x.accentText else MaterialTheme.colorScheme.onSurface, maxLines = 1)
    }
}

@Composable
private fun PickerRow(
    folder: MFolder,
    label: String,
    indent: Int,
    row: MailLogic.FolderRow?,
    here: Boolean,
    enabled: Boolean,
    on: Boolean,
    onToggle: () -> Unit,
    onClick: () -> Unit,
) {
    val x = GateControlTheme.extraColors
    val fg = when {
        !enabled -> x.faint
        on -> x.accentText
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .background(if (on) x.accentBg else Color.Transparent)
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .semantics { selected = on }
            .padding(start = (8 + indent * 16).dp, end = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (row != null) FolderArrow(row, label, Modifier.size(width = 32.dp, height = 48.dp), onToggle) else Spacer(Modifier.width(12.dp))
        Icon(MailFolders.icon(folder.role), contentDescription = null, tint = if (on) x.accentText else if (enabled) x.muted else x.faint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (here) Text(stringResource(R.string.mail_pick_here), style = MaterialTheme.typography.labelSmall, color = x.faint)
        if (on) Icon(GcIcons.Check, contentDescription = null, tint = x.accentText, modifier = Modifier.size(18.dp))
    }
}

/**
 * The question before a delete (mockup 3): "N Mails löschen?" with "Nicht mehr fragen" (only when the setting asked),
 * or always for mails in the trash: "N Mails endgültig löschen?".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MailDeleteDialog(ask: MailDeleteAsk, onConfirm: (dontAsk: Boolean) -> Unit, onDismiss: () -> Unit) {
    BasicAlertDialog(onDismissRequest = onDismiss) { MailDeleteCard(ask, onConfirm, onDismiss) }
}

/** The card of [MailDeleteDialog] (the dialog's look: icon, title, text, "Nicht mehr fragen", the buttons). */
@Composable
internal fun MailDeleteCard(ask: MailDeleteAsk, onConfirm: (dontAsk: Boolean) -> Unit, onDismiss: () -> Unit) {
    val x = GateControlTheme.extraColors
    var dontAsk by remember(ask) { mutableStateOf(false) }
    Surface(shape = AlertDialogDefaults.shape, color = AlertDialogDefaults.containerColor, tonalElevation = AlertDialogDefaults.TonalElevation) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(x.errorBg), contentAlignment = Alignment.Center) {
                Icon(GcIcons.Trash, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(22.dp))
            }
            Text(
                pluralStringResource(if (ask.purge) R.plurals.mail_purge_title else R.plurals.mail_del_title, ask.count, ask.count),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            Text(stringResource(if (ask.purge) R.string.mail_purge_text else R.string.mail_del_text), style = MaterialTheme.typography.bodyMedium, color = x.muted)
            if (!ask.purge) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(dontAsk, role = Role.Checkbox) { dontAsk = it },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Checkbox(checked = dontAsk, onCheckedChange = null)
                    Text(stringResource(R.string.mail_del_dont_ask), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.mail_cancel)) }
                TextButton(onClick = { onConfirm(dontAsk) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(if (ask.purge) R.string.mail_purge_go else R.string.mail_del_go), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/** The snackbar's sentence: "12 Mails nach „2026“ verschoben", "12 Mails in den Papierkorb gelegt" … */
internal fun snackText(context: android.content.Context, s: MailSnack): String {
    val r = context.resources
    val n = s.count
    val folder = s.folder?.let { MailFolders.labelOf(context, it) }.orEmpty()
    return when {
        s.purged -> r.getQuantityString(R.plurals.mail_snack_purged, n, n)
        s.action == Action.MOVE -> r.getQuantityString(R.plurals.mail_snack_moved, n, n, folder)
        s.action == Action.COPY -> r.getQuantityString(R.plurals.mail_snack_copied, n, n, folder)
        s.action == Action.ARCHIVE -> r.getQuantityString(R.plurals.mail_snack_archived, n, n)
        s.action == Action.DELETE -> r.getQuantityString(R.plurals.mail_snack_deleted, n, n)
        else -> r.getQuantityString(R.plurals.mail_snack_junk, n, n)
    }
}

/**
 * What a list with a selection shows on top: the folder picker, the question before a delete, its failures as toasts
 * and its snackbar (with "Rückgängig" while the server keeps the token) in [snackbar].
 */
@Composable
internal fun MailBulkHost(bulk: MailBulkActions, me: MMe?, folders: Map<Long, List<MFolder>>, recent: List<Long>, snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val picker by bulk.picker.collectAsStateWithLifecycle()
    val ask by bulk.ask.collectAsStateWithLifecycle()
    val snack by bulk.snack.collectAsStateWithLifecycle()
    val message by bulk.message.collectAsStateWithLifecycle()
    val undoLabel = stringResource(R.string.mail_undo)
    val tooLate = stringResource(R.string.mail_undo_late)
    LaunchedEffect(message) {
        val m = message ?: return@LaunchedEffect
        toast(context, if (m == MailBulkActions.UNDO_TOO_LATE) tooLate else mailText(context, m))
        bulk.clearMessage()
    }
    LaunchedEffect(snack) {
        val s = snack ?: return@LaunchedEffect
        bulk.snackShown()
        val undo = s.tokens.isNotEmpty()
        snackbar.currentSnackbarData?.dismiss()
        val result = snackbar.showSnackbar(
            snackText(context, s),
            actionLabel = if (undo) undoLabel else null,
            withDismissAction = !undo,
            duration = if (undo) SnackbarDuration.Long else SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed && undo) bulk.undo(s)
    }
    picker?.let { p ->
        val accountId = p.accounts.singleOrNull()
        MailFolderSheet(
            picker = p,
            account = me?.account(accountId),
            folders = folders[accountId].orEmpty(),
            recent = recent,
            onMode = bulk::setPickerMode,
            onPick = bulk::pick,
            onDismiss = bulk::closePicker,
            severalAccounts = (me?.accounts?.size ?: 0) > 1,
        )
    }
    ask?.let { a -> MailDeleteDialog(a, onConfirm = bulk::deleteConfirmed, onDismiss = bulk::cancelDelete) }
}
