package com.gatecontrol.android.familie.shopping

import com.gatecontrol.android.familie.FamilieJson
import com.gatecontrol.android.familie.FamilieResult
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The shared offline store with the rules of „Notizen & Aufgaben“ 1.4.0 (contract §§ 2, 3, 6): the same ops and the
 * same outbox as the Familie plugin's lists; the hub names every list in each answer, says what „alles abgehakt“
 * completed, and a batch a reader may never send leaves the outbox.
 */
class ShoppingHubSyncTest {

    private val now = 1_000_000_000_000L
    private val einkauf = ShoppingList("list-einkauf", "Einkauf", icon = "cart", listNo = 12, family = true, shared = true)
    private val drogerie = ShoppingList("list-drogerie", "Drogerie", listNo = 13)
    private val milch = ShoppingItem("item-milch", "list-einkauf", "Milch", qty = "2 l")
    private val brot = ShoppingItem("item-brot", "list-einkauf", "Brot")
    private val seife = ShoppingItem("item-seife", "list-drogerie", "Seife")
    private val server = ShoppingSnapshot(listOf(einkauf, drogerie), listOf(milch, brot, seife), "2026-11-14T10:14:07+01:00")

    /** A little server: applies ops the way the contract says (idempotent, last one wins, unknown things rejected). */
    private class Hub(var state: ShoppingSnapshot) : ShoppingSyncEngine.Remote {
        val batches = mutableListOf<List<ShoppingOp>>()
        val fetched = mutableListOf<String?>()
        var offlineOps = 0
        var completed: List<ShoppingCompleted> = emptyList()
        var answerLost = false

        override suspend fun ops(ops: List<ShoppingOp>): FamilieResult<FamilieJson.OpsResult> {
            if (offlineOps > 0) {
                offlineOps--
                return FamilieResult.Offline
            }
            batches += ops
            val rejected = mutableListOf<Pair<Int, String>>()
            ops.forEachIndexed { i, op ->
                val known = when (op.op) {
                    ShoppingOp.ITEM_ADD -> state.lists.any { it.id == op.listId }
                    ShoppingOp.ITEM_CHECK, ShoppingOp.ITEM_EDIT -> state.items.any { it.id == op.id }
                    else -> true
                }
                if (known) state = ShoppingLogic.apply(state, op) else rejected += i to "not_found"
            }
            if (answerLost) {
                // the request arrived, its answer did not
                answerLost = false
                return FamilieResult.Offline
            }
            return FamilieResult.Ok(FamilieJson.OpsResult(ops.size - rejected.size, rejected, "t", completed))
        }

        override suspend fun fetch(since: String?): FamilieResult<ShoppingSnapshot> {
            fetched += since
            return FamilieResult.Ok(state.copy(serverTime = "t${fetched.size}"))
        }
    }

    private fun sync(start: ShoppingData, remote: ShoppingSyncEngine.Remote, dropOn: Set<Int> = ShoppingSyncEngine.DROP_ON, onAnswer: (FamilieJson.OpsResult) -> Unit = {}): Pair<ShoppingSync, ShoppingData> = runBlocking {
        var data = start
        val result = ShoppingSyncEngine.run({ data }, { t -> data = t(data) }, remote, now, listsComplete = true, dropOn = dropOn, onAnswer = { onAnswer(it) })
        result to data
    }

    private fun synced(s: ShoppingSnapshot = server) = ShoppingData(server = s, lastSyncMs = now - 1_000)

    private fun queue(data: ShoppingData, vararg ops: ShoppingOp) = ops.fold(data) { d, op -> ShoppingLogic.enqueue(d, op) }

    private val checkMilch = ShoppingOp(ShoppingOp.ITEM_CHECK, id = "item-milch", checked = true)
    private val checkBrot = ShoppingOp(ShoppingOp.ITEM_CHECK, id = "item-brot", checked = true)
    private val addHefe = ShoppingOp(ShoppingOp.ITEM_ADD, id = "item-hefe", listId = "list-einkauf", text = "Hefe", qty = "1 Würfel")

    // ── the queue ───────────────────────────────────────────

    @Test
    fun `the queue goes out in the order it was made`() {
        val hub = Hub(server)
        val (r, after) = sync(queue(synced(), checkMilch, addHefe, checkBrot), hub)
        assertEquals(ShoppingSync.Synced, r)
        assertEquals(listOf(listOf(checkMilch, addHefe, checkBrot)), hub.batches)
        assertTrue(after.outbox.isEmpty())
        assertEquals(listOf("item-milch", "item-brot", "item-hefe"), ShoppingLogic.view(after).items.filter { it.listId == "list-einkauf" }.map { it.id })
    }

    @Test
    fun `a batch whose answer got lost is sent again unchanged and nothing is made twice`() {
        val hub = Hub(server).apply { answerLost = true }
        val start = queue(synced(), addHefe, checkMilch)
        val (r1, mid) = sync(start, hub)
        assertEquals(ShoppingSync.Offline, r1)
        assertEquals(2, mid.outbox.size, "what was not confirmed keeps waiting")
        val (r2, after) = sync(mid, hub)
        assertEquals(ShoppingSync.Synced, r2)
        assertEquals(hub.batches[0], hub.batches[1])
        assertEquals(1, hub.state.items.count { it.id == "item-hefe" })
        assertTrue(hub.state.items.first { it.id == "item-milch" }.checked)
        assertTrue(after.outbox.isEmpty())
    }

    @Test
    fun `the same ops in any order and doubled end in the same list`() {
        val ops = listOf(
            addHefe,
            checkMilch,
            ShoppingOp(ShoppingOp.ITEM_EDIT, id = "item-brot", text = "Vollkornbrot"),
            ShoppingOp(ShoppingOp.ITEM_DELETE, id = "item-seife"),
            ShoppingOp(ShoppingOp.LIST_ADD, id = "list-baumarkt", name = "Baumarkt"),
        )
        fun end(order: List<ShoppingOp>) = order.fold(server) { s, op -> ShoppingLogic.apply(s, op) }.let { s -> s.lists.map { it.id }.toSet() to s.items.sortedBy { it.id } }
        val straight = end(ops)
        assertEquals(straight, end(ops.reversed()))
        assertEquals(straight, end(ops + ops), "every op twice")
        assertEquals(straight, end(listOf(ops[3], ops[0], ops[3], ops[4], ops[2], ops[1], ops[0])))
        // deleting what is gone and ticking with the same value change nothing
        val once = ShoppingLogic.apply(server, checkMilch)
        assertEquals(once, ShoppingLogic.apply(once, checkMilch))
        assertEquals(server, ShoppingLogic.apply(server, ShoppingOp(ShoppingOp.ITEM_DELETE, id = "item-unknown")))
        assertEquals(server, ShoppingLogic.apply(server, ShoppingOp(ShoppingOp.LIST_DELETE, id = "list-unknown")))
    }

    @Test
    fun `rejected ops are not sent again`() {
        val hub = Hub(server)
        val start = queue(synced(), ShoppingOp(ShoppingOp.ITEM_CHECK, id = "item-gone", checked = true), checkMilch)
        val (_, after) = sync(start, hub)
        assertTrue(after.outbox.isEmpty())
        val (_, again) = sync(after, hub)
        assertEquals(1, hub.batches.size)
        assertTrue(again.outbox.isEmpty())
    }

    @Test
    fun `a batch a reader may never send leaves the outbox, a failing server keeps it`() {
        val refuse = { code: Int ->
            object : ShoppingSyncEngine.Remote {
                override suspend fun ops(ops: List<ShoppingOp>) = FamilieResult.Failed("forbidden", code)
                override suspend fun fetch(since: String?) = FamilieResult.Ok(server.copy(serverTime = "t2"))
            }
        }
        val start = queue(synced(), checkMilch)
        val (r403, a403) = sync(start, refuse(403), dropOn = ShoppingSyncEngine.DROP_ON + 403)
        assertEquals(ShoppingSync.Synced, r403)
        assertTrue(a403.outbox.isEmpty())
        assertFalse(ShoppingLogic.view(a403).items.first { it.id == "item-milch" }.checked, "the server's state shows again")
        val (r500, a500) = sync(start, refuse(500), dropOn = ShoppingSyncEngine.DROP_ON + 403)
        assertEquals(ShoppingSync.Failed("forbidden"), r500)
        assertEquals(1, a500.outbox.size)
        // an older plugin without the route (404): nothing is thrown away
        val (r404, a404) = sync(start, refuse(404), dropOn = ShoppingSyncEngine.DROP_ON + 403)
        assertEquals(ShoppingSync.Failed("forbidden"), r404)
        assertEquals(1, a404.outbox.size)
    }

    // ── merging with the server's state ─────────────────────

    @Test
    fun `since - every list is named, a missing one was left and goes with its entries`() {
        val answer = ShoppingSnapshot(lists = listOf(einkauf.copy(name = "Einkauf (Sa)")), items = listOf(milch.copy(checked = true), brot.copy(deleted = true)), serverTime = "t2")
        val merged = ShoppingLogic.merge(server, answer, full = false, listsComplete = true)
        assertEquals(listOf("Einkauf (Sa)"), merged.lists.map { it.name })
        assertEquals(listOf("item-milch"), merged.items.map { it.id }, "the tombstone and the entries of the list that is gone")
        assertTrue(merged.items[0].checked)
        assertEquals("t2", merged.serverTime)
        // Familie's answers name only what changed: there a list that is not named stays
        assertEquals(2, ShoppingLogic.merge(server, answer, full = false).lists.size)
    }

    @Test
    fun `since - a deleted list comes as a tombstone and goes`() {
        val answer = ShoppingSnapshot(lists = listOf(einkauf, drogerie.copy(deleted = true)), items = emptyList(), serverTime = "t2")
        val merged = ShoppingLogic.merge(server, answer, full = false, listsComplete = true)
        assertEquals(listOf("list-einkauf"), merged.lists.map { it.id })
        assertTrue(merged.items.none { it.listId == "list-drogerie" })
    }

    @Test
    fun `a list shared with me meanwhile - everything is loaded once more for its entries`() {
        val baumarkt = ShoppingList("list-baumarkt", "Baumarkt", listNo = 14)
        val duebel = ShoppingItem("item-duebel", "list-baumarkt", "Dübel")
        val hub = Hub(server.copy(lists = server.lists + baumarkt, items = server.items + duebel))
        // a `since` answer of the real server would not carry the older entry: only a full load brings it
        val remote = object : ShoppingSyncEngine.Remote {
            override suspend fun ops(ops: List<ShoppingOp>) = hub.ops(ops)
            override suspend fun fetch(since: String?): FamilieResult<ShoppingSnapshot> {
                hub.fetched += since
                return FamilieResult.Ok(if (since == null) hub.state.copy(serverTime = "full") else hub.state.copy(items = emptyList(), serverTime = "delta"))
            }
        }
        val (r, after) = sync(synced(), remote)
        assertEquals(ShoppingSync.Synced, r)
        assertEquals(listOf("2026-11-14T10:14:07+01:00", null), hub.fetched)
        assertEquals(listOf("item-duebel"), after.server.items.filter { it.listId == "list-baumarkt" }.map { it.id })
        assertEquals("full", after.server.serverTime)
        assertEquals(setOf("list-baumarkt"), ShoppingLogic.unknownLists(server, hub.state))
        assertTrue(ShoppingLogic.unknownLists(server, server.copy(lists = listOf(einkauf))).isEmpty())
    }

    @Test
    fun `the next since is the server's time as it sent it`() {
        val hub = Hub(server)
        val (_, first) = sync(synced(), hub)
        assertEquals("t1", first.server.serverTime)
        sync(first.copy(lastSyncMs = now - 1), hub)
        assertEquals(listOf<String?>("2026-11-14T10:14:07+01:00", "t1"), hub.fetched)
    }

    @Test
    fun `what was queued meanwhile shows on top of the server's state`() {
        val hub = Hub(server)
        var data = queue(synced(), checkMilch)
        val (_, after) = sync(data, hub)
        data = queue(after, checkBrot)
        val view = ShoppingLogic.view(data, checkedBy = "1")
        assertTrue(view.items.first { it.id == "item-brot" }.checked)
        assertFalse(data.server.items.first { it.id == "item-brot" }.checked)
        assertEquals(0 to 2, ShoppingLogic.progress(view, "list-einkauf"))
        assertEquals(listOf("item-milch", "item-brot"), ShoppingLogic.ordered(view, "list-einkauf").map { it.id })
        assertEquals(listOf("item-brot", "item-milch"), ShoppingLogic.ordered(ShoppingLogic.view(synced(server.copy(items = listOf(milch.copy(checked = true), brot)))), "list-einkauf").map { it.id }, "open ones first")
    }

    // ── „alles abgehakt“ ────────────────────────────────────

    @Test
    fun `the answer names the tasks the last tick completed`() {
        val done = ShoppingCompleted(41, "Bei Lidl einkaufen", "list-einkauf")
        val hub = Hub(server).apply { completed = listOf(done) }
        val seen = mutableListOf<FamilieJson.OpsResult>()
        val (_, after) = sync(queue(synced(), checkMilch, checkBrot), hub) { seen += it }
        assertEquals(listOf(done), seen.single().completed)
        assertEquals(0, ShoppingLogic.progress(ShoppingLogic.view(after), "list-einkauf").first)
        // nothing waited: no answer, nothing completed
        seen.clear()
        sync(after, hub) { seen += it }
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `completed of the answer is read, an answer of the Familie plugin has none`() {
        val o = JsonParser.parseString(
            """{"ok":true,"applied":1,"rejected":[{"index":3,"code":"not_found"}],"serverTime":"2026-11-14T10:14:07+01:00",
               "completed":[{"taskId":41,"title":"Bei Lidl einkaufen","listId":"6f1c2a90-1111-4c1d-9a00-000000000001"},{"taskId":0,"title":"x","listId":"y"},{"title":"ohne Nummer"}]}""",
        ).asJsonObject
        val r = FamilieJson.opsResult(o)
        assertEquals(listOf(ShoppingCompleted(41, "Bei Lidl einkaufen", "6f1c2a90-1111-4c1d-9a00-000000000001")), r.completed)
        assertEquals(listOf(3 to "not_found"), r.rejected)
        assertEquals("2026-11-14T10:14:07+01:00", r.serverTime)
        assertTrue(FamilieJson.opsResult(JsonParser.parseString("""{"ok":true,"applied":2,"rejected":[],"serverTime":"2026-11-14T09:00:00Z"}""").asJsonObject).completed.isEmpty())
    }

    // ── the store itself ────────────────────────────────────

    private class Files(var stored: ShoppingData? = null, var owner: String = "me") : ShoppingStore.Files {
        override fun ownerKey() = owner
        override fun read() = stored
        override fun write(data: ShoppingData) { stored = data }
    }

    @Test
    fun `the store keeps edits on the phone, sends them and tells what was completed`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val files = Files()
        val hub = Hub(server).apply { offlineOps = 1; completed = listOf(ShoppingCompleted(41, "Bei Lidl einkaufen", "list-einkauf")) }
        var waiting = 0
        val answers = mutableListOf<FamilieJson.OpsResult>()
        val store = ShoppingStore(scope, files, hub, listsComplete = true, debounceMs = 3_600_000, clock = { now }, onWaiting = { waiting++ }, onAnswer = { answers += it })
        assertEquals(ShoppingSync.Synced, store.sync())
        store.edit(checkMilch)
        assertEquals(1, files.stored?.outbox?.size, "written to the phone at once")
        assertTrue(ShoppingLogic.view(store.data.value).items.first { it.id == "item-milch" }.checked)
        assertEquals(ShoppingSync.Offline, store.sync())
        assertEquals(1, waiting)
        assertEquals(1, store.data.value.outbox.size)
        assertEquals(ShoppingSync.Synced, store.sync())
        assertTrue(store.data.value.outbox.isEmpty())
        assertEquals(41L, answers.single().completed.single().taskId)
        // another login: its lists are not ours
        files.owner = "someone else"
        assertTrue(store.current().server.lists.isEmpty())
        assertNull(store.current().server.serverTime)
        scope.cancel()
    }
}
