package com.gatecontrol.android.ui.notizen

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.gatecontrol.android.ui.components.GcShowOnce
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.notizen.NFeature
import com.gatecontrol.android.notizen.NGroup
import com.gatecontrol.android.notizen.NList
import com.gatecontrol.android.notizen.NMe
import com.gatecontrol.android.notizen.NNav
import com.gatecontrol.android.notizen.NNotebook
import com.gatecontrol.android.notizen.NotizenJson
import com.gatecontrol.android.notizen.NotizenLists

/** Which menu or window of [NotizenManageHost] is open (one at a time). */
@Stable
class NotizenManageState {
    var listSheet by mutableStateOf<NList?>(null)
    var listEdit by mutableStateOf<NList?>(null)
    var listMove by mutableStateOf<NList?>(null)
    var listDelete by mutableStateOf<NList?>(null)
    var listLeave by mutableStateOf<NList?>(null)
    var listShare by mutableStateOf<NList?>(null)
    var newListOrGroup by mutableStateOf(false)
    var newInGroup by mutableStateOf<Long?>(null)
    var groupSheet by mutableStateOf<NGroup?>(null)
    var groupRename by mutableStateOf<NGroup?>(null)
    var groupDissolve by mutableStateOf<NGroup?>(null)
    var nbSheet by mutableStateOf<NNotebook?>(null)
    var nbEdit by mutableStateOf<NNotebook?>(null)
    var nbShare by mutableStateOf<NNotebook?>(null)
    var nbDelete by mutableStateOf<NNotebook?>(null)
    var nbLeave by mutableStateOf<NNotebook?>(null)

    fun newList(group: Long? = null) {
        newInGroup = group
        newListOrGroup = true
    }
}

/**
 * The menus and windows for lists, groups and notebooks, shared by the tab (its side menu) and a list's page: the
 * sheets, „Liste bearbeiten“, „Teilen“, the delete questions, „Neue Liste oder Gruppe“. Results and „Rückgängig“ show
 * in [snack]. [onGone]: the list or notebook shown was deleted or left (a list's page goes back).
 */
@Composable
internal fun NotizenManageHost(
    state: NotizenManageState,
    vm: NotizenManageViewModel,
    me: NMe?,
    nav: NNav?,
    area: String,
    snack: SnackbarHostState,
    notebookName: (NNotebook) -> String?,
    onGone: (kind: String, id: Long) -> Unit = { _, _ -> },
    /** „Rückgängig“ of this deletion shows here (a list's page leaves its own deletion to the tab it goes back to). */
    showsUndo: (NotizenManageViewModel.Undo) -> Boolean = { true },
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val undo by vm.undo.collectAsStateWithLifecycle()
    val text = message?.let { messageText(it) }
    LaunchedEffect(message) {
        if (text != null) {
            snack.showSnackbar(text)
            vm.consumeMessage()
        }
    }
    val undoText = undo?.let { stringResource(R.string.notizen_deleted_x, it.name) }
    val undoLabel = stringResource(R.string.notizen_undo)
    // the snackbar runs in the host's scope (GcShowOnce): taking the undo restarts an effect keyed by it
    GcShowOnce(undo?.takeIf(showsUndo), consume = vm::consumeUndo) { u ->
        val r = snack.showSnackbar(undoText.orEmpty(), actionLabel = undoLabel, withDismissAction = true, duration = SnackbarDuration.Long)
        if (r == SnackbarResult.ActionPerformed) vm.restore(u)
    }
    val m = me ?: ui.me
    val groups = nav?.groups.orEmpty()
    val roles = m?.has(NFeature.SHARE_ROLES) == true
    val keep = m?.has(NFeature.DELETE_KEEP) == true
    /** The list as the server has it now (after a change in the share window). */
    fun fresh(l: NList): NList = ui.list?.takeIf { it.id == l.id } ?: nav?.lists?.firstOrNull { it.id == l.id } ?: l
    fun freshNb(nb: NNotebook): NNotebook = ui.notebook?.takeIf { it.id == nb.id } ?: nav?.notebooks?.firstOrNull { it.id == nb.id } ?: nb

    // ── lists ──
    state.listSheet?.let { l0 ->
        val l = fresh(l0)
        ListSheet(
            l, m,
            onDismiss = { state.listSheet = null },
            onEdit = { state.listEdit = l },
            onShare = { vm.shareList(l); state.listShare = l },
            onMove = { state.listMove = l },
            onMyDay = { off -> state.listSheet = l.copy(mydayOff = !off); vm.patchList(l, NotizenJson.listPatch(mydayOff = !off)) },
            onDelete = { vm.loadListUsage(l); state.listDelete = l },
            onLeave = { state.listLeave = l },
        )
    }
    state.listEdit?.let { l ->
        ListEditDialog(
            l, m, groups.filter { it.area == l.area },
            onDismiss = { state.listEdit = null },
            onSave = { body -> vm.patchList(l, body, "saved") },
            onDelete = if (l.isOwner && !l.isDefault) ({ vm.loadListUsage(l); state.listDelete = l }) else null,
        )
    }
    state.listMove?.let { l ->
        MoveToGroupDialog(l, groups.filter { it.area == l.area }, { state.listMove = null }) { gid ->
            vm.patchList(l, if (gid == null) NotizenJson.listPatch(clearGroup = true) else NotizenJson.listPatch(groupId = gid))
        }
    }
    state.listDelete?.let { l ->
        ListDeleteDialog(l, ui.listUsage?.takeIf { it.first == l.id }?.second, keep, m?.userId, { state.listDelete = null }) { mode ->
            vm.deleteList(l, mode) { onGone("list", l.id) }
        }
    }
    state.listLeave?.let { l ->
        LeaveDialog(
            stringResource(R.string.notizen_list_leave_q, l.name),
            stringResource(R.string.notizen_list_leave_text, l.owner?.name.orEmpty()),
            { state.listLeave = null },
        ) { vm.leaveList(l) { onGone("list", l.id) } }
    }
    state.listShare?.let { l0 ->
        val l = fresh(l0)
        ShareDialog(
            l.name, l.owner, l.members, ui.people, roles, m?.userId, ui.busy, notebook = false,
            onDismiss = { state.listShare = null },
        ) { members -> vm.setListMembers(l, members) }
    }
    if (state.newListOrGroup) {
        NewListOrGroupDialog(
            groups.filter { it.area == area },
            nav?.lists.orEmpty().filter { it.area == area },
            state.newInGroup,
            onDismiss = { state.newListOrGroup = false },
            onList = { name, gid -> vm.createList(name, area, gid) },
            onGroup = { name, lists -> vm.createGroup(name, area, lists) },
        )
    }

    // ── groups ──
    state.groupSheet?.let { g ->
        val lists = nav?.lists.orEmpty().filter { it.groupId == g.id }
        GroupSheet(
            g, lists.size, NotizenLists.groupOpen(g, nav?.lists.orEmpty()),
            onDismiss = { state.groupSheet = null },
            onNewList = { state.newList(g.id) },
            onRename = { state.groupRename = g },
            onDissolve = { state.groupDissolve = g },
        )
    }
    state.groupRename?.let { g ->
        NameDialog(stringResource(R.string.notizen_group_rename), g.name, onDismiss = { state.groupRename = null }, confirm = stringResource(R.string.notizen_save)) { name ->
            state.groupRename = null
            vm.renameGroup(g, name)
        }
    }
    state.groupDissolve?.let { g ->
        DissolveDialog(g, nav?.lists.orEmpty().filter { it.groupId == g.id }.map { it.name }, { state.groupDissolve = null }) { vm.dissolveGroup(g) }
    }

    // ── notebooks ──
    state.nbSheet?.let { nb0 ->
        val nb = freshNb(nb0)
        NotebookSheet(
            nb, notebookName(nb) ?: stringResource(R.string.notizen_tresor), m,
            onDismiss = { state.nbSheet = null },
            onEdit = { state.nbEdit = nb },
            onShare = { vm.shareNotebook(nb); state.nbShare = nb },
            onDelete = { vm.loadNotebookUsage(nb); state.nbDelete = nb },
            onLeave = { state.nbLeave = nb },
        )
    }
    state.nbEdit?.let { nb0 ->
        val nb = freshNb(nb0)
        val actions = NotizenLists.notebookActions(nb, m)
        NotebookEditDialog(
            nb,
            canShare = NotizenLists.NotebookAction.SHARE in actions,
            roles = roles,
            onDismiss = { state.nbEdit = null },
            onSave = { body -> vm.patchNotebook(nb, body) },
            onShare = { vm.shareNotebook(nb); state.nbShare = nb },
            onDelete = if (NotizenLists.NotebookAction.DELETE in actions) ({ vm.loadNotebookUsage(nb); state.nbDelete = nb }) else null,
        )
    }
    state.nbShare?.let { nb0 ->
        val nb = freshNb(nb0)
        ShareDialog(
            notebookName(nb) ?: stringResource(R.string.notizen_tresor), nb.owner, nb.members, ui.people, roles, m?.userId, ui.busy, notebook = true,
            onDismiss = { state.nbShare = null },
        ) { members -> vm.setNotebookMembers(nb, members) }
    }
    state.nbDelete?.let { nb ->
        val name = notebookName(nb)
        NotebookDeleteDialog(nb, name, ui.notebookUsage?.takeIf { it.first == nb.id }?.second, keep, m?.userId, { state.nbDelete = null }) { mode ->
            vm.deleteNotebook(nb, name ?: "", mode) { onGone("notebook", nb.id) }
        }
    }
    state.nbLeave?.let { nb ->
        LeaveDialog(
            stringResource(R.string.notizen_nb_leave_q, notebookName(nb) ?: stringResource(R.string.notizen_tresor)),
            stringResource(R.string.notizen_nb_leave_text, nb.owner?.name.orEmpty()),
            { state.nbLeave = null },
        ) { vm.leaveNotebook(nb) { onGone("notebook", nb.id) } }
    }
}
