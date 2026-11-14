package com.gatecontrol.android.ui.notizen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.familie.shopping.ShoppingCompleted
import com.gatecontrol.android.familie.shopping.ShoppingItem
import com.gatecontrol.android.familie.shopping.ShoppingList
import com.gatecontrol.android.familie.shopping.ShoppingLogic
import com.gatecontrol.android.familie.shopping.ShoppingSync
import com.gatecontrol.android.notizen.NotizenShoppingLogic
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcShowOnce
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.components.keyboardPadding
import com.gatecontrol.android.ui.familie.DialogField
import com.gatecontrol.android.ui.familie.FamilieConfirmDialog
import com.gatecontrol.android.ui.familie.FamilieInputDialog
import com.gatecontrol.android.ui.familie.ShoppingAssignSection
import com.gatecontrol.android.ui.familie.ShoppingSyncLine
import com.gatecontrol.android.ui.familie.familieErrorText
import com.gatecontrol.android.ui.theme.GateControlTheme

private sealed interface HubShopDialog {
    data class Rename(val name: String) : HubShopDialog
    data class Delete(val name: String) : HubShopDialog
    data class Edit(val item: ShoppingItem) : HubShopDialog
}

/** What a hub shopping list does (the page's side; the tests call them directly). */
internal class HubShopActions(
    val toggle: (ShoppingItem) -> Unit = {},
    val edit: (ShoppingItem) -> Unit = {},
    val add: (text: String, qty: String) -> Unit = { _, _ -> },
    val clearChecked: () -> Unit = {},
    val openTask: ((Long) -> Unit)? = null,
)

/**
 * A shopping list of „Notizen & Aufgaben“ to tick off (plugin 1.4.0, mockup „Abhaken wie gewohnt“ / „Alles
 * abgehakt“), route `notizen/shopping/<uid>` — and the page the family's card opens once its lists live in the hub.
 * Works without a connection: what is ticked or written waits on the phone and is sent in order.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotizenShoppingScreen(
    uid: String,
    onBack: () -> Unit,
    onTask: ((Long) -> Unit)? = null,
    vm: NotizenShoppingViewModel = hiltViewModel(),
) {
    val view by vm.view.collectAsStateWithLifecycle()
    val sync by vm.sync.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    val loaded by vm.loaded.collectAsStateWithLifecycle()
    val me by vm.me.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val done by vm.done.collectAsStateWithLifecycle()
    val going by vm.going.collectAsStateWithLifecycle()
    val assigning by vm.assigning.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    val x = GateControlTheme.extraColors

    val list = view.lists.firstOrNull { it.id == uid }
    var dialog by remember { mutableStateOf<HubShopDialog?>(null) }
    var menu by remember { mutableStateOf(false) }

    val noticeText = when (val n = notice) {
        HubShopNotice.GoingSent -> stringResource(R.string.familie_shopping_going_sent)
        is HubShopNotice.AssignSent -> when {
            n.userId == null -> stringResource(R.string.familie_shop_unassigned)
            n.delivered == false -> stringResource(R.string.familie_shop_assign_not_delivered, profile?.nameOf(n.userId) ?: "#${n.userId}", n.code ?: "?")
            else -> stringResource(R.string.familie_shop_assign_sent, profile?.nameOf(n.userId) ?: "#${n.userId}")
        }
        is HubShopNotice.Undone -> stringResource(if (n.queued) R.string.notizen_shop_undone_queued else R.string.notizen_shop_undone)
        is HubShopNotice.Error -> if (n.family && n.code != "offline") familieErrorText(n.code) else messageText(n.code)
        null -> null
    }
    // the snackbars run in the page's scope (GcShowOnce): clearing the value restarts an effect keyed by it
    GcShowOnce(noticeText, consume = vm::clearNotice) { snack.showSnackbar(it) }
    // „Alles abgehakt. ‚Bei Lidl einkaufen‘ ist erledigt.“ with „Rückgängig“ (only the tasks of this list)
    val completed = NotizenShoppingLogic.completedFor(done, uid)
    val doneText = allDoneText(completed)
    val undoLabel = stringResource(R.string.notizen_shop_undo)
    GcShowOnce(doneText?.let { it to completed }, consume = vm::clearDone) { (text, tasks) ->
        val r = snack.showSnackbar(text, actionLabel = undoLabel, withDismissAction = true, duration = SnackbarDuration.Long)
        if (r == SnackbarResult.ActionPerformed) vm.undo(tasks)
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize().keyboardPadding()) {
            GcSubpageBar(
                title = list?.name ?: stringResource(R.string.notizen_shop_title),
                onBack = onBack,
                backDescription = stringResource(R.string.notizen_back),
            ) {
                if (list != null) {
                    HubShopShared(list)
                    val canClear = list.editable && view.items.any { it.listId == list.id && it.checked }
                    if (list.manageable || canClear) {
                        Box {
                            GcIconButton(GcIcons.MoreVertical, stringResource(R.string.notizen_menu_of, list.name), { menu = true })
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                if (canClear) DropdownMenuItem(text = { Text(stringResource(R.string.notizen_shop_clear_checked)) }, onClick = { menu = false; vm.clearChecked(list.id) }, leadingIcon = { Icon(GcIcons.Check, null) })
                                if (list.manageable) {
                                    DropdownMenuItem(text = { Text(stringResource(R.string.familie_shopping_rename)) }, onClick = { menu = false; dialog = HubShopDialog.Rename(list.name) }, leadingIcon = { Icon(GcIcons.Edit, null) })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.familie_shopping_delete_list)) }, onClick = { menu = false; dialog = HubShopDialog.Delete(list.name) }, leadingIcon = { Icon(GcIcons.Trash, null) })
                                }
                            }
                        }
                    }
                }
            }
            ShoppingSyncLine(sync, pending, Modifier.padding(start = 24.dp, end = 16.dp))
            if (list == null) {
                if (!loaded || sync == ShoppingSync.Syncing) {
                    NotizenLoading()
                } else {
                    Text(stringResource(R.string.notizen_shop_gone), style = MaterialTheme.typography.bodyMedium, color = x.muted, modifier = Modifier.padding(24.dp))
                }
                return@Column
            }
            PullToRefreshBox(isRefreshing = sync == ShoppingSync.Syncing, onRefresh = vm::refresh, modifier = Modifier.weight(1f)) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    HubShopList(
                        list = list,
                        items = ShoppingLogic.ordered(view, list.id),
                        actions = HubShopActions(
                            toggle = vm::toggle,
                            edit = { dialog = HubShopDialog.Edit(it) },
                            clearChecked = { vm.clearChecked(list.id) },
                            openTask = onTask,
                        ),
                    )
                    // „Ich gehe einkaufen“ / „Kannst du einkaufen gehen?“: family lists, while the Familie plugin offers it
                    if (list.family && me?.familyShopping == true && profile != null) {
                        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            ShoppingAssignSection(
                                list = list,
                                profile = profile,
                                going = going,
                                assigning = assigning,
                                onGoing = { vm.going(list.id) },
                                onAssign = { userId, notify -> vm.assign(list.id, userId, notify) },
                            )
                            Text(stringResource(R.string.familie_shopping_going_hint), style = MaterialTheme.typography.bodySmall, color = x.muted, modifier = Modifier.padding(horizontal = 4.dp))
                        }
                    }
                }
            }
            if (list.editable && me?.readOnly != true) HubShopAddBar { text, qty -> vm.addItem(list.id, text, qty) }
        }
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 84.dp)) { Snackbar(it) }
    }

    when (val d = dialog) {
        is HubShopDialog.Rename -> FamilieInputDialog(
            title = stringResource(R.string.familie_shopping_rename),
            fields = listOf(DialogField(stringResource(R.string.familie_shopping_list_name), d.name, ShoppingLogic.MAX_LIST_NAME)),
            onConfirm = { v ->
                vm.renameList(uid, v[0])
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        is HubShopDialog.Delete -> FamilieConfirmDialog(
            title = stringResource(R.string.familie_shopping_delete_list),
            text = stringResource(if (list?.shared == true || list?.family == true) R.string.notizen_shop_delete_shared_text else R.string.notizen_shop_delete_text, d.name),
            confirmLabel = stringResource(R.string.familie_delete),
            destructive = true,
            onConfirm = {
                dialog = null
                vm.deleteList(uid)
                onBack()
            },
            onDismiss = { dialog = null },
        )
        is HubShopDialog.Edit -> FamilieInputDialog(
            title = stringResource(R.string.notizen_shop_edit_item),
            fields = listOf(
                DialogField(stringResource(R.string.notizen_shop_item), d.item.text, ShoppingLogic.MAX_TEXT),
                DialogField(stringResource(R.string.familie_shopping_qty), d.item.qty.orEmpty(), ShoppingLogic.MAX_QTY),
            ),
            onConfirm = { v ->
                vm.editItem(d.item, v[0], v[1])
                dialog = null
            },
            onDelete = {
                vm.deleteItem(d.item)
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        null -> Unit
    }
}

/** „Alles abgehakt. ‚Bei Lidl einkaufen‘ ist erledigt.“ — null when the tick completed no task. */
@Composable
internal fun allDoneText(completed: List<ShoppingCompleted>): String? = when (completed.size) {
    0 -> null
    1 -> stringResource(R.string.notizen_shop_all_done_task, completed[0].title)
    else -> stringResource(R.string.notizen_shop_all_done_tasks, completed.size)
}

/** Whom the list is shared with, at the head: „Familie“, or the people sign. */
@Composable
private fun HubShopShared(list: ShoppingList) {
    val x = GateControlTheme.extraColors
    when {
        list.family -> Row(
            Modifier
                .padding(end = 4.dp)
                .heightIn(min = 32.dp)
                .clip(RoundedCornerShape(16.dp))
                .border(1.dp, x.border2, RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(GcIcons.User, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
            Text(stringResource(R.string.notizen_shop_family), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
        list.shared -> Icon(GcIcons.Users, stringResource(R.string.notizen_shared_mark), tint = x.muted, modifier = Modifier.padding(end = 8.dp).size(20.dp))
    }
}

/**
 * The list itself (stateless: the page and the tests draw it): which task it belongs to and how far it is, the
 * entries — the open ones first — and „Abgehakte entfernen“. A tap on a row ticks it, a long press changes it.
 */
@Composable
internal fun HubShopList(list: ShoppingList, items: List<ShoppingItem>, actions: HubShopActions) {
    val x = GateControlTheme.extraColors
    val total = items.size
    val checked = items.count { it.checked }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val task = list.tasks.orEmpty().firstOrNull()
        val head = when {
            task != null && total > 0 -> stringResource(R.string.notizen_shop_belongs_progress, task.title, checked, total)
            task != null -> stringResource(R.string.notizen_shop_belongs, task.title)
            total > 0 -> stringResource(R.string.notizen_shop_progress, checked, total)
            else -> null
        }
        if (head != null) {
            val open = actions.openTask
            Row(
                Modifier
                    .padding(horizontal = 12.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .then(if (task != null && open != null) Modifier.clickable(role = Role.Button) { open(task.id) } else Modifier)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(if (task != null) GcIcons.Tasks else GcIcons.Cart, null, tint = x.muted, modifier = Modifier.size(14.dp))
                Text(head, style = MaterialTheme.typography.bodySmall, color = x.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (!list.editable) {
            Text(stringResource(R.string.notizen_shop_read_only), style = MaterialTheme.typography.bodySmall, color = x.muted, modifier = Modifier.padding(horizontal = 20.dp))
        }
        if (items.isEmpty()) {
            Text(
                stringResource(if (list.editable) R.string.notizen_shop_empty else R.string.familie_shopping_list_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = x.muted,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            )
        } else {
            NCard {
                items.forEachIndexed { i, item ->
                    HubShopRow(item, list.editable, { actions.toggle(item) }, { actions.edit(item) })
                    if (i < items.size - 1) Box(Modifier.fillMaxWidth().height(1.dp).background(x.border))
                }
            }
        }
        if (list.editable && checked > 0) {
            Box(Modifier.padding(horizontal = 16.dp)) {
                GcOutlineButton(text = stringResource(R.string.notizen_shop_clear_checked), onClick = actions.clearChecked, icon = GcIcons.Trash)
            }
        }
    }
}

/** One entry: the box, the text (struck through when ticked), the amount at the end. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HubShopRow(item: ShoppingItem, canEdit: Boolean, onToggle: () -> Unit, onEdit: () -> Unit) {
    val x = GateControlTheme.extraColors
    val scheme = MaterialTheme.colorScheme
    val label = stringResource(if (item.checked) R.string.familie_shopping_uncheck_cd else R.string.familie_shopping_check_cd, item.text)
    val state = stringResource(if (item.checked) R.string.notizen_done_state else R.string.notizen_open_state)
    val editLabel = stringResource(R.string.familie_shopping_edit_cd, item.text)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .then(
                if (canEdit) {
                    Modifier
                        .combinedClickable(role = Role.Checkbox, onClickLabel = label, onClick = onToggle, onLongClickLabel = editLabel, onLongClick = onEdit)
                        .semantics { stateDescription = state }
                } else {
                    Modifier.semantics { stateDescription = state }
                },
            )
            .padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (item.checked) scheme.primary else Color.Transparent)
                .border(2.dp, if (item.checked) scheme.primary else x.faint, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (item.checked) Icon(GcIcons.Check, null, tint = scheme.onPrimary, modifier = Modifier.size(14.dp))
        }
        Text(
            item.text,
            style = MaterialTheme.typography.bodyLarge.copy(textDecoration = if (item.checked) TextDecoration.LineThrough else null),
            fontWeight = FontWeight.Medium,
            color = if (item.checked) x.muted else scheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        item.qty?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = x.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 120.dp))
        }
    }
}

/** „Punkt hinzufügen“ at the foot (mockup): the entry and, beside it, its amount; „Fertig“ on the keyboard adds it. */
@Composable
internal fun HubShopAddBar(onAdd: (text: String, qty: String) -> Unit) {
    val x = GateControlTheme.extraColors
    var text by rememberSaveable { mutableStateOf("") }
    var qty by rememberSaveable { mutableStateOf("") }
    val add = {
        if (text.isNotBlank()) {
            onAdd(text, qty)
            text = ""
            qty = ""
        }
    }
    val addLabel = stringResource(R.string.notizen_shop_add)
    val qtyLabel = stringResource(R.string.familie_shopping_qty)
    val style = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface)
    Row(
        Modifier
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .shadow(8.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(start = 4.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        GcIconButton(GcIcons.Plus, addLabel, add, enabled = text.isNotBlank(), tint = MaterialTheme.colorScheme.primary)
        BasicTextField(
            text,
            { text = it.take(ShoppingLogic.MAX_TEXT) },
            singleLine = true,
            textStyle = style,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { add() }),
            decorationBox = { inner -> Box(Modifier.padding(vertical = 12.dp)) { if (text.isEmpty()) Text(addLabel, color = x.muted, style = MaterialTheme.typography.bodyLarge); inner() } },
            modifier = Modifier.weight(1f).semantics { contentDescription = addLabel },
        )
        BasicTextField(
            qty,
            { qty = it.take(ShoppingLogic.MAX_QTY) },
            singleLine = true,
            textStyle = style,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { add() }),
            decorationBox = { inner -> Box(Modifier.padding(vertical = 12.dp)) { if (qty.isEmpty()) Text(qtyLabel, color = x.faint, style = MaterialTheme.typography.bodyLarge); inner() } },
            modifier = Modifier.widthIn(min = 64.dp, max = 96.dp).semantics { contentDescription = qtyLabel },
        )
    }
}
