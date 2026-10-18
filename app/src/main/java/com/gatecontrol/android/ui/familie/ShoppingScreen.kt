package com.gatecontrol.android.ui.familie

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.familie.FamilieDest
import com.gatecontrol.android.familie.FamilieProfile
import com.gatecontrol.android.familie.shopping.ShoppingItem
import com.gatecontrol.android.familie.shopping.ShoppingLogic
import com.gatecontrol.android.familie.shopping.ShoppingSync
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcFilterChip
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcSectionLabel
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.components.GcTextField
import com.gatecontrol.android.ui.theme.GateControlTheme

private sealed interface ShoppingDialog {
    data object NewList : ShoppingDialog
    data class Rename(val listId: String, val name: String) : ShoppingDialog
    data class DeleteList(val listId: String, val name: String) : ShoppingDialog
    data class EditItem(val item: ShoppingItem) : ShoppingDialog
}

/**
 * Einkaufsliste (mockup AppShopping): several lists as tabs, add / tick /
 * edit / delete entries, "Erledigte entfernen", "Ich gehe einkaufen". Works
 * offline: changes wait on the device and are sent when the server is
 * reachable; the line under the title says where things stand.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShoppingScreen(
    onBack: () -> Unit,
    initialListId: String? = null,
    onOpen: (FamilieDest) -> Unit = {},
    viewModel: ShoppingViewModel = hiltViewModel(),
) {
    val captureList by viewModel.captureList.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val captureText = stringResource(R.string.familie_expense_capture)
    val captureAction = stringResource(R.string.familie_expense_capture_action)
    LaunchedEffect(captureList) {
        val listId = captureList ?: return@LaunchedEffect
        viewModel.clearCapture()
        val result = snackbar.showSnackbar(captureText, actionLabel = captureAction, withDismissAction = true, duration = SnackbarDuration.Long)
        if (result == SnackbarResult.ActionPerformed) onOpen(FamilieDest.Expenses(listId))
    }
    val view by viewModel.view.collectAsStateWithLifecycle()
    val sync by viewModel.sync.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val selectedId by viewModel.selected.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val going by viewModel.going.collectAsStateWithLifecycle()
    val assigning by viewModel.assigning.collectAsStateWithLifecycle()
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val extra = GateControlTheme.extraColors
    val context = LocalContext.current

    LaunchedEffect(initialListId) { if (initialListId != null) viewModel.select(initialListId) }

    val list = view.lists.firstOrNull { it.id == selectedId } ?: view.lists.firstOrNull()
    var dialog by remember { mutableStateOf<ShoppingDialog?>(null) }
    var menu by remember { mutableStateOf(false) }
    var text by rememberSaveable { mutableStateOf("") }
    var qty by rememberSaveable { mutableStateOf("") }

    val noticeText = when (val n = notice) {
        ShoppingNotice.GoingSent -> stringResource(R.string.familie_shopping_going_sent)
        is ShoppingNotice.AssignSent -> when {
            n.userId == null -> stringResource(R.string.familie_shop_unassigned)
            n.delivered == false -> stringResource(R.string.familie_shop_assign_not_delivered, profile?.nameOf(n.userId) ?: "#${n.userId}", n.code ?: "?")
            else -> stringResource(R.string.familie_shop_assign_sent, profile?.nameOf(n.userId) ?: "#${n.userId}")
        }
        is ShoppingNotice.Error -> familieErrorText(n.code)
        null -> null
    }
    LaunchedEffect(noticeText) {
        if (noticeText != null) {
            Toast.makeText(context, noticeText, Toast.LENGTH_LONG).show()
            viewModel.clearNotice()
        }
    }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        GcSubpageBar(
            title = stringResource(R.string.familie_shopping_title),
            onBack = onBack,
            backDescription = stringResource(R.string.common_back),
        ) {
            Box {
                GcIconButton(GcIcons.Sliders, contentDescription = stringResource(R.string.familie_more), onClick = { menu = true })
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.familie_shopping_new_list)) }, onClick = {
                        menu = false
                        dialog = ShoppingDialog.NewList
                    })
                    if (list != null) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.familie_shopping_rename)) }, onClick = {
                            menu = false
                            dialog = ShoppingDialog.Rename(list.id, list.name)
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.familie_shopping_clear_checked)) }, onClick = {
                            menu = false
                            viewModel.clearChecked(list.id)
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.familie_shopping_delete_list)) }, onClick = {
                            menu = false
                            dialog = ShoppingDialog.DeleteList(list.id, list.name)
                        })
                    }
                }
            }
        }
        SyncLine(sync, pending, Modifier.padding(start = 24.dp, end = 16.dp))

        PullToRefreshBox(
            isRefreshing = sync == ShoppingSync.Syncing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (view.lists.isEmpty()) {
                    FamilieEmpty(GcIcons.Cart, stringResource(R.string.familie_shopping_none))
                    GcPrimaryButton(text = stringResource(R.string.familie_shopping_new_list), onClick = { dialog = ShoppingDialog.NewList }, icon = GcIcons.Plus)
                    return@Column
                }
                val counts = ShoppingLogic.openCounts(view)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    view.lists.forEach { l ->
                        GcFilterChip(
                            text = "${l.name} · ${counts[l.id] ?: 0}",
                            selected = l.id == list?.id,
                            onClick = { viewModel.select(l.id) },
                        )
                    }
                }
                if (list == null) return@Column

                val add = {
                    viewModel.addItem(list.id, text, qty)
                    text = ""
                    qty = ""
                }
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GcTextField(
                        value = text,
                        onValueChange = { text = it.take(ShoppingLogic.MAX_TEXT) },
                        label = stringResource(R.string.familie_shopping_add_label),
                        placeholder = stringResource(R.string.familie_shopping_add_hint),
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { add() }),
                    )
                    GcTextField(
                        value = qty,
                        onValueChange = { qty = it.take(ShoppingLogic.MAX_QTY) },
                        label = stringResource(R.string.familie_shopping_qty),
                        placeholder = "2×",
                        modifier = Modifier.width(84.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { add() }),
                    )
                    GcIconButton(
                        GcIcons.Plus,
                        contentDescription = stringResource(R.string.familie_shopping_add_cd),
                        onClick = add,
                        enabled = text.isNotBlank(),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }

                val items = view.items.filter { it.listId == list.id }
                val open = items.filter { !it.checked }
                val done = items.filter { it.checked }
                if (items.isEmpty()) {
                    FamilieEmpty(GcIcons.Cart, stringResource(R.string.familie_shopping_list_empty))
                }
                if (open.isNotEmpty()) {
                    ItemsCard(open, profile, onToggle = viewModel::toggle, onEdit = { dialog = ShoppingDialog.EditItem(it) })
                }
                if (done.isNotEmpty()) {
                    GcSectionLabel(stringResource(R.string.familie_shopping_done_section), Modifier.padding(start = 4.dp, top = 4.dp))
                    ItemsCard(done, profile, onToggle = viewModel::toggle, onEdit = { dialog = ShoppingDialog.EditItem(it) })
                    GcOutlineButton(
                        text = stringResource(R.string.familie_shopping_clear_checked),
                        onClick = { viewModel.clearChecked(list.id) },
                        icon = GcIcons.Trash,
                    )
                }
                // B3: who goes shopping, where the request goes, "<Name> bitten" / "Ich gehe einkaufen".
                ShoppingAssignSection(
                    list = list,
                    profile = profile,
                    going = going,
                    assigning = assigning,
                    onGoing = { viewModel.going(list.id) },
                    onAssign = { uid, notify -> viewModel.assign(list.id, uid, notify) },
                )
                Text(
                    stringResource(R.string.familie_shopping_going_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = extra.muted,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
    // 1.2: after "Ich gehe einkaufen" parents with the household book may record the shopping.
    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }

    when (val d = dialog) {
        ShoppingDialog.NewList -> FamilieInputDialog(
            title = stringResource(R.string.familie_shopping_new_list),
            fields = listOf(DialogField(stringResource(R.string.familie_shopping_list_name), maxLength = ShoppingLogic.MAX_LIST_NAME, placeholder = stringResource(R.string.familie_shopping_list_name_hint))),
            onConfirm = { v ->
                viewModel.addList(v[0])
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        is ShoppingDialog.Rename -> FamilieInputDialog(
            title = stringResource(R.string.familie_shopping_rename),
            fields = listOf(DialogField(stringResource(R.string.familie_shopping_list_name), d.name, ShoppingLogic.MAX_LIST_NAME)),
            onConfirm = { v ->
                viewModel.renameList(d.listId, v[0])
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        is ShoppingDialog.DeleteList -> FamilieConfirmDialog(
            title = stringResource(R.string.familie_shopping_delete_list),
            text = stringResource(R.string.familie_shopping_delete_list_text, d.name),
            confirmLabel = stringResource(R.string.familie_delete),
            destructive = true,
            onConfirm = {
                viewModel.deleteList(d.listId)
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        is ShoppingDialog.EditItem -> FamilieInputDialog(
            title = stringResource(R.string.familie_shopping_edit_item),
            fields = listOf(
                DialogField(stringResource(R.string.familie_shopping_add_label), d.item.text, ShoppingLogic.MAX_TEXT),
                DialogField(stringResource(R.string.familie_shopping_qty), d.item.qty.orEmpty(), ShoppingLogic.MAX_QTY),
            ),
            onConfirm = { v ->
                viewModel.editItem(d.item, v[0], v[1])
                dialog = null
            },
            onDelete = {
                viewModel.deleteItem(d.item)
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        null -> Unit
    }
}

@Composable
private fun SyncLine(sync: ShoppingSync, pending: Int, modifier: Modifier = Modifier) {
    val extra = GateControlTheme.extraColors
    val (text, color) = when {
        sync == ShoppingSync.Syncing -> stringResource(R.string.familie_sync_running) to extra.muted
        pending > 0 && sync == ShoppingSync.Offline -> pluralStringResource(R.plurals.familie_sync_offline_pending, pending, pending) to extra.warn
        pending > 0 -> pluralStringResource(R.plurals.familie_sync_pending, pending, pending) to extra.muted
        sync == ShoppingSync.Offline -> stringResource(R.string.familie_sync_offline) to extra.warn
        sync is ShoppingSync.Failed -> familieErrorText(sync.code) to MaterialTheme.colorScheme.error
        sync == ShoppingSync.Synced -> stringResource(R.string.familie_sync_done) to extra.accentText
        else -> "" to extra.muted
    }
    if (text.isNotEmpty()) {
        Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(
                if (sync == ShoppingSync.Synced && pending == 0) GcIcons.Check else GcIcons.Refresh,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(14.dp),
            )
            Text(text, style = MaterialTheme.typography.bodySmall, color = color)
        }
    }
}

@Composable
private fun ItemsCard(items: List<ShoppingItem>, profile: FamilieProfile?, onToggle: (ShoppingItem) -> Unit, onEdit: (ShoppingItem) -> Unit) {
    val extra = GateControlTheme.extraColors
    GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.Top) {
        items.forEachIndexed { i, item ->
            if (i > 0) GcRowDivider()
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GcIconButton(
                    icon = if (item.checked) GcIcons.Check else GcIcons.Minus,
                    contentDescription = stringResource(if (item.checked) R.string.familie_shopping_uncheck_cd else R.string.familie_shopping_check_cd, item.text),
                    onClick = { onToggle(item) },
                    tint = if (item.checked) MaterialTheme.colorScheme.primary else extra.faint,
                )
                Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                    Text(
                        item.text,
                        style = MaterialTheme.typography.titleSmall.copy(textDecoration = if (item.checked) TextDecoration.LineThrough else null),
                        color = if (item.checked) extra.muted else MaterialTheme.colorScheme.onSurface,
                    )
                    val by = item.checkedBy?.let { b -> b.toIntOrNull()?.let { memberName(profile, it) } ?: b }
                    val meta = listOfNotNull(item.qty, if (item.checked && by != null) stringResource(R.string.familie_shopping_checked_by, by) else null)
                    if (meta.isNotEmpty()) {
                        Text(meta.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = extra.muted)
                    }
                }
                GcIconButton(GcIcons.Edit, contentDescription = stringResource(R.string.familie_shopping_edit_cd, item.text), onClick = { onEdit(item) }, iconSize = 18.dp)
            }
        }
    }
}
