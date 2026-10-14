package com.gatecontrol.android.familie.shopping

/**
 * The offline rules of the shopping list, free of Android and I/O:
 *
 * * what the screen shows = the server snapshot with the outbox applied on
 *   top (optimistic),
 * * an answer of `GET shopping` merges into the snapshot (full answer
 *   replaces, `since` answer upserts and drops tombstones),
 * * after `POST shopping/ops` the sent ops leave the outbox — applied or
 *   rejected, the next `GET` brings the server's truth ("server wins after
 *   outbox replay").
 */
object ShoppingLogic {

    const val MAX_TEXT = 120
    const val MAX_QTY = 40
    const val MAX_LIST_NAME = 40
    const val MAX_OPS_PER_POST = 200

    /** Applies one op to [snap] the way the server would (best effort, for display). */
    fun apply(snap: ShoppingSnapshot, op: ShoppingOp, checkedBy: String? = null): ShoppingSnapshot = when (op.op) {
        ShoppingOp.LIST_ADD -> {
            val id = op.id ?: return snap
            val name = op.name ?: return snap
            if (snap.lists.any { it.id == id }) snap
            else snap.copy(lists = snap.lists + ShoppingList(id, name, op.icon, sort = (snap.lists.maxOfOrNull { it.sort } ?: 0) + 1))
        }
        ShoppingOp.LIST_RENAME -> snap.copy(lists = snap.lists.map { if (it.id == op.id && op.name != null) it.copy(name = op.name) else it })
        ShoppingOp.LIST_DELETE -> snap.copy(
            lists = snap.lists.filter { it.id != op.id },
            items = snap.items.filter { it.listId != op.id },
        )
        ShoppingOp.ITEM_ADD -> {
            val id = op.id ?: return snap
            val listId = op.listId ?: return snap
            val text = op.text ?: return snap
            if (snap.items.any { it.id == id }) snap
            else snap.copy(items = snap.items + ShoppingItem(id, listId, text, op.qty?.takeIf { it.isNotBlank() }))
        }
        ShoppingOp.ITEM_EDIT -> snap.copy(
            items = snap.items.map {
                if (it.id != op.id) it
                else it.copy(text = op.text ?: it.text, qty = if (op.qty != null) op.qty.takeIf { q -> q.isNotBlank() } else it.qty)
            },
        )
        ShoppingOp.ITEM_CHECK -> snap.copy(
            items = snap.items.map {
                if (it.id != op.id || op.checked == null) it
                else it.copy(checked = op.checked, checkedBy = if (op.checked) checkedBy else null)
            },
        )
        ShoppingOp.ITEM_DELETE -> snap.copy(items = snap.items.filter { it.id != op.id })
        ShoppingOp.CLEAR_CHECKED -> snap.copy(items = snap.items.filter { !(it.listId == op.listId && it.checked) })
        else -> snap
    }

    /** What the screen shows: server state, local changes on top, no tombstones. */
    fun view(data: ShoppingData, checkedBy: String? = null): ShoppingSnapshot {
        val base = data.server.copy(
            lists = data.server.lists.filter { !it.deleted },
            items = data.server.items.filter { !it.deleted },
        )
        val applied = data.outbox.fold(base) { s, p -> apply(s, p.op, checkedBy) }
        val listIds = applied.lists.map { it.id }.toSet()
        return applied.copy(
            lists = applied.lists.sortedWith(compareBy<ShoppingList> { it.sort }.thenBy { it.name.lowercase() }),
            items = applied.items.filter { it.listId in listIds },
        )
    }

    /**
     * Merges a `GET shopping` answer. [full] (no `since`): the answer is the
     * whole truth. Else: changed entries replace or join, tombstones go.
     */
    fun merge(server: ShoppingSnapshot, answer: ShoppingSnapshot, full: Boolean): ShoppingSnapshot {
        if (full) {
            return ShoppingSnapshot(
                lists = answer.lists.filter { !it.deleted },
                items = answer.items.filter { !it.deleted },
                serverTime = answer.serverTime ?: server.serverTime,
            )
        }
        val lists = upsert(server.lists, answer.lists, { it.id }, { it.deleted })
        val listIds = lists.map { it.id }.toSet()
        val items = upsert(server.items, answer.items, { it.id }, { it.deleted }).filter { it.listId in listIds }
        return ShoppingSnapshot(lists, items, answer.serverTime ?: server.serverTime)
    }

    private fun <T> upsert(current: List<T>, changes: List<T>, key: (T) -> String, deleted: (T) -> Boolean): List<T> {
        val byKey = changes.associateBy(key)
        val kept = current.mapNotNull { old ->
            val new = byKey[key(old)] ?: return@mapNotNull old
            if (deleted(new)) null else new
        }
        val known = current.map(key).toSet()
        val added = changes.filter { key(it) !in known && !deleted(it) }
        return kept + added
    }

    /** Removes what was sent (by seq); ops queued meanwhile stay. */
    fun afterSend(data: ShoppingData, sent: Collection<Long>): ShoppingData {
        val set = sent.toSet()
        return data.copy(outbox = data.outbox.filter { it.seq !in set })
    }

    /** Queues [op] (texts trimmed and capped as the contract limits them). */
    fun enqueue(data: ShoppingData, op: ShoppingOp): ShoppingData {
        val clean = op.copy(
            name = op.name?.trim()?.take(MAX_LIST_NAME),
            text = op.text?.trim()?.take(MAX_TEXT),
            qty = op.qty?.trim()?.take(MAX_QTY),
        )
        return data.copy(outbox = data.outbox + PendingOp(data.nextSeq, clean), nextSeq = data.nextSeq + 1)
    }

    /** First batch to send (≤ 200 ops, in order). */
    fun nextBatch(data: ShoppingData): List<PendingOp> = data.outbox.take(MAX_OPS_PER_POST)

    /** Open entries per list (for tabs and the start screen). */
    fun openCounts(view: ShoppingSnapshot): Map<String, Int> =
        view.items.filter { !it.checked }.groupingBy { it.listId }.eachCount()

    /** A `since` this old may miss deletions (tombstones live 30 days): load everything. */
    fun needsFull(data: ShoppingData, nowMs: Long): Boolean =
        data.server.serverTime == null || data.lastSyncMs == 0L || nowMs - data.lastSyncMs > TOMBSTONE_SAFE_MS

    private const val TOMBSTONE_SAFE_MS = 25L * 24 * 60 * 60 * 1000
}
