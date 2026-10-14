package com.gatecontrol.android.familie.shopping

import com.gatecontrol.android.familie.FamilieJson
import com.gatecontrol.android.familie.FamilieResult
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ShoppingSyncTest {

    private val list = ShoppingList("l1", "Supermarkt", sort = 1)
    private val milk = ShoppingItem("i1", "l1", "Milch", qty = "2×")
    private val bread = ShoppingItem("i2", "l1", "Brot", checked = true, checkedBy = "5")
    private val server = ShoppingSnapshot(listOf(list), listOf(milk, bread), "2026-10-14T12:00:00Z")

    @Test
    fun `the view is the server state with the outbox on top`() {
        var data = ShoppingData(server = server)
        data = ShoppingLogic.enqueue(data, ShoppingOp(ShoppingOp.ITEM_ADD, id = "u1", listId = "l1", text = "  Bananen  "))
        data = ShoppingLogic.enqueue(data, ShoppingOp(ShoppingOp.ITEM_CHECK, id = "i1", checked = true))
        data = ShoppingLogic.enqueue(data, ShoppingOp(ShoppingOp.CLEAR_CHECKED, listId = "l1"))
        val view = ShoppingLogic.view(data, checkedBy = "3")
        assertEquals(listOf("Bananen"), view.items.map { it.text })
        assertEquals(mapOf("l1" to 1), ShoppingLogic.openCounts(view))
        // The server snapshot itself is untouched.
        assertEquals(2, data.server.items.size)
        assertEquals(listOf(1L, 2L, 3L), data.outbox.map { it.seq })
    }

    @Test
    fun `adding twice with the same uuid gives one entry, edits and deletes apply`() {
        var s = ShoppingLogic.apply(server, ShoppingOp(ShoppingOp.ITEM_ADD, id = "u1", listId = "l1", text = "Eier"))
        s = ShoppingLogic.apply(s, ShoppingOp(ShoppingOp.ITEM_ADD, id = "u1", listId = "l1", text = "Eier"))
        assertEquals(1, s.items.count { it.id == "u1" })
        s = ShoppingLogic.apply(s, ShoppingOp(ShoppingOp.ITEM_EDIT, id = "u1", qty = "10"))
        assertEquals("10", s.items.first { it.id == "u1" }.qty)
        s = ShoppingLogic.apply(s, ShoppingOp(ShoppingOp.ITEM_EDIT, id = "u1", text = "Bio-Eier", qty = ""))
        assertEquals("Bio-Eier", s.items.first { it.id == "u1" }.text)
        assertNull(s.items.first { it.id == "u1" }.qty)
        s = ShoppingLogic.apply(s, ShoppingOp(ShoppingOp.LIST_ADD, id = "l2", name = "Drogerie"))
        s = ShoppingLogic.apply(s, ShoppingOp(ShoppingOp.LIST_RENAME, id = "l2", name = "dm"))
        assertEquals("dm", s.lists.first { it.id == "l2" }.name)
        s = ShoppingLogic.apply(s, ShoppingOp(ShoppingOp.LIST_DELETE, id = "l1"))
        assertTrue(s.items.none { it.listId == "l1" })
    }

    @Test
    fun `a since answer upserts and drops tombstones, a full answer replaces`() {
        val delta = ShoppingSnapshot(
            lists = emptyList(),
            items = listOf(
                milk.copy(checked = true, checkedBy = "6", updatedAt = "2026-10-14T12:05:00Z"),
                bread.copy(deleted = true),
                ShoppingItem("i3", "l1", "Käse"),
            ),
            serverTime = "2026-10-14T12:06:00Z",
        )
        val merged = ShoppingLogic.merge(server, delta, full = false)
        assertEquals(listOf("i1", "i3"), merged.items.map { it.id })
        assertTrue(merged.items[0].checked)
        assertEquals("2026-10-14T12:06:00Z", merged.serverTime)

        val full = ShoppingLogic.merge(server, ShoppingSnapshot(listOf(list), listOf(ShoppingItem("i9", "l1", "Saft")), "t2"), full = true)
        assertEquals(listOf("i9"), full.items.map { it.id })

        val listGone = ShoppingLogic.merge(server, ShoppingSnapshot(listOf(list.copy(deleted = true)), emptyList(), "t3"), full = false)
        assertTrue(listGone.lists.isEmpty())
        assertTrue(listGone.items.isEmpty(), "items of a deleted list go with it")
    }

    @Test
    fun `texts are trimmed and capped as the contract limits them`() {
        val d = ShoppingLogic.enqueue(ShoppingData(), ShoppingOp(ShoppingOp.LIST_ADD, id = "x", name = "n".repeat(60)))
        assertEquals(ShoppingLogic.MAX_LIST_NAME, d.outbox.single().op.name!!.length)
        val e = ShoppingLogic.enqueue(d, ShoppingOp(ShoppingOp.ITEM_ADD, id = "y", listId = "x", text = "t".repeat(200), qty = "q".repeat(50)))
        assertEquals(ShoppingLogic.MAX_TEXT, e.outbox.last().op.text!!.length)
        assertEquals(ShoppingLogic.MAX_QTY, e.outbox.last().op.qty!!.length)
    }

    @Test
    fun `a stale since loads everything again`() {
        val now = 1_000_000_000_000L
        assertTrue(ShoppingLogic.needsFull(ShoppingData(), now))
        assertFalse(ShoppingLogic.needsFull(ShoppingData(server = server, lastSyncMs = now - 60_000), now))
        assertTrue(ShoppingLogic.needsFull(ShoppingData(server = server, lastSyncMs = now - 26L * 24 * 3600 * 1000), now))
    }

    // ─── Sync engine ─────────────────────────────────────────────────

    private class FakeRemote(
        var opsResult: (List<ShoppingOp>) -> FamilieResult<FamilieJson.OpsResult> = { FamilieResult.Ok(FamilieJson.OpsResult(it.size, emptyList(), "t")) },
        var fetchResult: (String?) -> FamilieResult<ShoppingSnapshot> = { FamilieResult.Ok(ShoppingSnapshot(serverTime = "t2")) },
    ) : ShoppingSyncEngine.Remote {
        val sent = mutableListOf<List<ShoppingOp>>()
        val fetched = mutableListOf<String?>()
        override suspend fun ops(ops: List<ShoppingOp>): FamilieResult<FamilieJson.OpsResult> {
            sent += ops
            return opsResult(ops)
        }

        override suspend fun fetch(since: String?): FamilieResult<ShoppingSnapshot> {
            fetched += since
            return fetchResult(since)
        }
    }

    private fun sync(start: ShoppingData, remote: FakeRemote, now: Long = 1_000_000_000_000L, onUpdate: (ShoppingData) -> ShoppingData = { it }): Pair<ShoppingSync, ShoppingData> = runBlocking {
        var data = start
        val result = ShoppingSyncEngine.run({ data }, { t -> data = onUpdate(t(data)) }, remote, now)
        result to data
    }

    @Test
    fun `outbox is replayed in order, then the server state wins`() {
        var data = ShoppingData(server = server, lastSyncMs = 1_000_000_000_000L - 1000)
        data = ShoppingLogic.enqueue(data, ShoppingOp(ShoppingOp.ITEM_CHECK, id = "i1", checked = true))
        data = ShoppingLogic.enqueue(data, ShoppingOp(ShoppingOp.ITEM_ADD, id = "u1", listId = "l1", text = "Eier"))
        // The server rejected the check (someone deleted the item meanwhile).
        val remote = FakeRemote(
            opsResult = { FamilieResult.Ok(FamilieJson.OpsResult(1, listOf(0 to "not_found"), "t")) },
            fetchResult = { FamilieResult.Ok(ShoppingSnapshot(items = listOf(milk.copy(deleted = true), ShoppingItem("u1", "l1", "Eier")), serverTime = "t2")) },
        )
        val (result, after) = sync(data, remote)
        assertEquals(ShoppingSync.Synced, result)
        assertEquals(listOf(listOf(ShoppingOp.ITEM_CHECK, ShoppingOp.ITEM_ADD)), remote.sent.map { b -> b.map { it.op } })
        assertEquals(listOf<String?>("2026-10-14T12:00:00Z"), remote.fetched)
        assertTrue(after.outbox.isEmpty())
        assertEquals(listOf("i2", "u1"), ShoppingLogic.view(after).items.map { it.id })
        assertEquals("t2", after.server.serverTime)
    }

    @Test
    fun `offline keeps the outbox and the snapshot`() {
        val data = ShoppingLogic.enqueue(ShoppingData(server = server), ShoppingOp(ShoppingOp.ITEM_DELETE, id = "i1"))
        val (result, after) = sync(data, FakeRemote(opsResult = { FamilieResult.Offline }))
        assertEquals(ShoppingSync.Offline, result)
        assertEquals(1, after.outbox.size)
        assertEquals(server, after.server)
    }

    @Test
    fun `ops queued while sending go out in the next batch`() {
        var data = ShoppingLogic.enqueue(ShoppingData(server = server), ShoppingOp(ShoppingOp.ITEM_DELETE, id = "i1"))
        var injected = false
        val (_, after) = sync(data, FakeRemote(fetchResult = { FamilieResult.Offline })) { d ->
            if (!injected) {
                injected = true
                ShoppingLogic.enqueue(d, ShoppingOp(ShoppingOp.ITEM_ADD, id = "late", listId = "l1", text = "Saft"))
            } else d
        }
        // The late op was sent in the second loop round; the fetch then failed.
        assertTrue(after.outbox.isEmpty())
        data = after
        assertEquals(server, data.server)
    }

    @Test
    fun `a batch the server can never accept is dropped, other errors keep it`() {
        val data = ShoppingLogic.enqueue(ShoppingData(server = server), ShoppingOp(ShoppingOp.ITEM_DELETE, id = "i1"))
        val (r1, a1) = sync(data, FakeRemote(opsResult = { FamilieResult.Failed("invalid", 400) }))
        assertEquals(ShoppingSync.Synced, r1)
        assertTrue(a1.outbox.isEmpty())
        val (r2, a2) = sync(data, FakeRemote(opsResult = { FamilieResult.Failed("module_off", 403) }))
        assertEquals(ShoppingSync.Failed("module_off"), r2)
        assertEquals(1, a2.outbox.size)
    }

    @Test
    fun `more than 200 ops go in several calls`() {
        var data = ShoppingData(server = server)
        repeat(450) { i -> data = ShoppingLogic.enqueue(data, ShoppingOp(ShoppingOp.ITEM_ADD, id = "u$i", listId = "l1", text = "x$i")) }
        val remote = FakeRemote()
        val (_, after) = sync(data, remote)
        assertEquals(listOf(200, 200, 50), remote.sent.map { it.size })
        assertTrue(after.outbox.isEmpty())
        assertEquals(listOf<String?>(null), remote.fetched, "never synced before: full load")
    }
}
