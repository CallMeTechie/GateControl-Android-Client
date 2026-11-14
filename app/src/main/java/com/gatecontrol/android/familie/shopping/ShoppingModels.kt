package com.gatecontrol.android.familie.shopping

/** A shopping list (`GET shopping` → `lists[]`). Ids are client UUIDs. */
data class ShoppingList(
    val id: String,
    val name: String,
    val icon: String? = null,
    val sort: Int = 0,
    val updatedAt: String? = null,
    val deleted: Boolean = false,
    /** B3: who was asked to do the shopping (null: nobody), by whom, until when. */
    val assigneeId: Int? = null,
    val assigneeName: String? = null,
    val assignedBy: Int? = null,
    val dueAt: String? = null,
    /** open | taken | declined (Familie 1.9 `assignee.state`). */
    val assigneeState: String? = null,
    // ── a list of „Notizen & Aufgaben“ (1.4.0, `shoppingLists`); all unset for a list of the Familie plugin ──
    /** The hub's number of the list (`listId` there): `PATCH lists/<n>`, attaching it to a task. */
    val listNo: Long? = null,
    /** `private` | `work`. */
    val area: String? = null,
    /** false: I may only look (`role: read`). */
    val canEdit: Boolean? = null,
    /** false: renaming and deleting are the owner's (a family list: the parents'). */
    val canManage: Boolean? = null,
    /** Shared with the whole family. */
    val family: Boolean = false,
    val shared: Boolean = false,
    val ownerName: String? = null,
    /** The names of the others it is shared with. */
    val memberNames: List<String>? = null,
    /** My open tasks this list hangs on („gehört zu ‚Bei Lidl einkaufen‘“). */
    val tasks: List<ShoppingTaskRef>? = null,
) {
    /** Entries may be added, ticked and changed (a list of the Familie plugin: always). */
    val editable: Boolean get() = canEdit != false

    /** The list may be renamed and deleted. */
    val manageable: Boolean get() = canManage != false
}

/** A task a shopping list hangs on (`lists[].tasks[]` of the hub). */
data class ShoppingTaskRef(val id: Long, val title: String)

/** A task „alles abgehakt“ completed (`completed[]` of the hub's `POST shopping/ops`). */
data class ShoppingCompleted(val taskId: Long, val title: String, val listId: String)

/** An entry of a list (`GET shopping` → `items[]`). */
data class ShoppingItem(
    val id: String,
    val listId: String,
    val text: String,
    val qty: String? = null,
    val checked: Boolean = false,
    /** User id (as text) or name of whoever ticked it; null when open. */
    val checkedBy: String? = null,
    val updatedAt: String? = null,
    val deleted: Boolean = false,
)

/** What the server last said: lists, items and its clock for the next `since`. */
data class ShoppingSnapshot(
    val lists: List<ShoppingList> = emptyList(),
    val items: List<ShoppingItem> = emptyList(),
    val serverTime: String? = null,
    /**
     * Familie 1.21 (`shoppingHome`): `notizen` — the lists live in „Notizen & Aufgaben“ and this is Familie's copy;
     * `familie` or null (an older plugin): they are Familie's own.
     */
    val home: String? = null,
)

/**
 * One change of the contract's `POST shopping/ops` (`op` = list_add,
 * list_rename, list_delete, item_add, item_edit, item_check, item_delete,
 * clear_checked). Null fields are left out of the JSON.
 */
data class ShoppingOp(
    val op: String,
    val id: String? = null,
    val listId: String? = null,
    val name: String? = null,
    val icon: String? = null,
    val text: String? = null,
    val qty: String? = null,
    val checked: Boolean? = null,
    /** `list_add` in the hub: `private` | `work`. */
    val area: String? = null,
    /** `list_add` in the hub: shared with the whole family (parents only). */
    val family: Boolean? = null,
) {
    companion object {
        const val LIST_ADD = "list_add"
        const val LIST_RENAME = "list_rename"
        const val LIST_DELETE = "list_delete"
        const val ITEM_ADD = "item_add"
        const val ITEM_EDIT = "item_edit"
        const val ITEM_CHECK = "item_check"
        const val ITEM_DELETE = "item_delete"
        const val CLEAR_CHECKED = "clear_checked"
    }
}

/** An op waiting in the outbox; [seq] orders and identifies it locally. */
data class PendingOp(val seq: Long, val op: ShoppingOp)

/** The device's shopping state: server snapshot plus local changes not sent yet. */
data class ShoppingData(
    /** Server + token this belongs to (see FamilieStorage.ownerKey). */
    val owner: String = "",
    val server: ShoppingSnapshot = ShoppingSnapshot(),
    val outbox: List<PendingOp> = emptyList(),
    val nextSeq: Long = 1,
    /** Epoch ms of the last successful sync (0 = never). */
    val lastSyncMs: Long = 0,
)
