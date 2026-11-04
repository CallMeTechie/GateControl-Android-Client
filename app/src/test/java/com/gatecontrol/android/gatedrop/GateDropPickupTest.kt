package com.gatecontrol.android.gatedrop

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * GateDrop 1.5: real pick-ups (`pickedAt`), sends an administrator removed, `incomplete` direct transfers and the
 * retention the administrator set — and older servers without these fields, which keep the old behaviour.
 */
class GateDropPickupTest {

    private fun obj(json: String): JsonObject = JsonParser.parseString(json).asJsonObject

    private fun d(status: String, kind: String = "device", error: String? = null, pickedAt: Long? = null, knows: Boolean = true, via: String? = null, target: String = "pc") =
        GdDelivery(kind = kind, target = target, status = status, via = via, error = error, pickedAt = pickedAt, knowsPickup = knows)

    private fun entry(vararg dels: GdDelivery, removed: Boolean = false, items: List<GdItem> = emptyList()) =
        GdEntry(id = "s1", at = 1L, deliveries = dels.toList(), removed = removed, expired = removed, items = items)

    // ─── reading the new fields ─────────────────────────────────────

    @Test
    fun `pickedAt of a delivery - set, null (known) or missing (older server)`() {
        val picked = GateDropJson.delivery(obj("""{"kind":"device","target":"pc","status":"delivered","pickedAt":"2026-11-04T10:00:00.000Z"}"""))!!
        assertTrue(picked.knowsPickup)
        assertEquals(1793786400000L, picked.pickedAt)
        val provided = GateDropJson.delivery(obj("""{"kind":"device","target":"pc","status":"delivered","pickedAt":null}"""))!!
        assertTrue(provided.knowsPickup)
        assertNull(provided.pickedAt)
        val old = GateDropJson.delivery(obj("""{"kind":"device","target":"pc","status":"delivered"}"""))!!
        assertFalse(old.knowsPickup)
        assertNull(old.pickedAt)
    }

    @Test
    fun `a send - removed, removedAt, pickedAt, local items with their index`() {
        val e = GateDropJson.entry(
            obj(
                """{"id":"aaaaaaaaaaaaaaaaaaaaaaaa","role":"sent","createdAt":"2026-11-01T10:00:00.000Z","expired":false,"removed":true,
                "removedAt":"2026-11-02T10:00:00.000Z","pickedAt":null,
                "items":[{"index":0,"mediaId":null,"name":"a.jpg","bytes":5,"local":true,"available":false},
                         {"index":1,"mediaId":"media_0001","name":"b.jpg","bytes":7,"local":false,"available":true}],
                "deliveries":[{"kind":"device","target":"pc","status":"expired","error":"removed","pickedAt":null}]}""",
            ),
        )!!
        assertTrue(e.removed)
        assertTrue(e.expired, "removed means the files are gone too")
        assertNull(e.pickedAt)
        assertEquals(listOf(0, 1), e.items.map { it.index })
        assertEquals(listOf(true, false), e.items.map { it.local })
        assertEquals(GdDelivery.ERROR_REMOVED, e.deliveries.single().error)

        val old = GateDropJson.entry(obj("""{"id":"aaaaaaaaaaaaaaaaaaaaaaaa","items":[{"mediaId":"media_0001","name":"b.jpg"}]}"""))!!
        assertFalse(old.removed)
        assertFalse(old.expired)
        assertNull(old.items.single().index)
        assertFalse(old.items.single().local)
    }

    @Test
    fun `the answer to a transfer result carries the delivery with pickedAt`() {
        val a = GateDropJson.transferAnswer(
            obj("""{"transfer":{"id":"t","status":"done","side":"sender"},"delivery":{"id":3,"kind":"device","target":"pc","status":"delivered","via":"direct","error":null,"at":"2026-11-04T10:00:00.000Z","pickedAt":null},"fallback":null}"""),
        )
        assertNull(a.fallback)
        assertEquals(GdDelivery.DELIVERED, a.delivery?.status)
        assertTrue(a.delivery!!.knowsPickup)
        assertNull(a.delivery.pickedAt)
        // older plugins: the delivery without pickedAt
        val old = GateDropJson.transferAnswer(obj("""{"delivery":{"kind":"device","target":"pc","status":"delivered","via":"direct"},"fallback":null}"""))
        assertFalse(old.delivery!!.knowsPickup)
        assertNull(GateDropJson.transferAnswer(obj("""{"fallback":"upload","missing":[0]}""")).delivery)
    }

    @Test
    fun `retention from the targets' limits - 1_5, retentionDays only, nothing`() {
        val t15 = GateDropJson.targets(obj("""{"me":{},"limits":{"retentionDays":14,"retention":{"afterPickupDays":0,"unpickedDays":14}}}"""))!!
        assertEquals(GdRetention(unpickedDays = 14, afterPickupDays = 0), t15.retention)
        val t14 = GateDropJson.targets(obj("""{"me":{},"limits":{"retentionDays":7}}"""))!!
        assertEquals(GdRetention(unpickedDays = 7, afterPickupDays = null), t14.retention)
        val none = GateDropJson.targets(obj("""{"me":{}}"""))!!
        assertNull(none.retention)
        // nonsense is ignored
        assertNull(GateDropJson.retention(obj("""{"retentionDays":0,"retention":{"unpickedDays":-3}}""")))
        assertEquals(GdRetention(30, null), GateDropJson.retention(obj("""{"retention":{"afterPickupDays":999,"unpickedDays":30}}""")))
    }

    @Test
    fun `retention falls back to 7 days`() {
        assertEquals(GdRetention(7, null), GateDropLogic.retention(null))
        assertEquals(GdRetention(7, null), GateDropLogic.retention(GdTargets()))
        assertEquals(GdRetention(3, 1), GateDropLogic.retention(GdTargets(retention = GdRetention(3, 1))))
    }

    // ─── "Gesendet" ─────────────────────────────────────────────────

    @Test
    fun `delivered - provided without pickedAt, picked up with it, delivered as before on older servers`() {
        assertEquals(GdSentStatus.PROVIDED, GateDropLogic.sentStatus(d(GdDelivery.DELIVERED)))
        assertEquals(GdSentStatus.PICKED, GateDropLogic.sentStatus(d(GdDelivery.DELIVERED, pickedAt = 5L)))
        assertEquals(GdSentStatus.DELIVERED, GateDropLogic.sentStatus(d(GdDelivery.DELIVERED, knows = false)))
        // a NAS folder, Drive or a link: delivered is all there is
        assertEquals(GdSentStatus.DELIVERED, GateDropLogic.sentStatus(d(GdDelivery.DELIVERED, kind = "nas", pickedAt = 5L)))
        assertEquals(GdSentStatus.DELIVERED, GateDropLogic.sentStatus(d(GdDelivery.DELIVERED, kind = "link")))
        assertEquals(GdSentStatus.DELIVERED, GateDropLogic.sentStatus(d(GdDelivery.DELIVERED, kind = "drive", pickedAt = 5L)))
        assertEquals(GdSentStatus.OPENED, GateDropLogic.sentStatus(d(GdDelivery.OPENED, pickedAt = 5L)))
    }

    @Test
    fun `removed by an administrator instead of expired`() {
        assertEquals(GdSentStatus.REMOVED, GateDropLogic.sentStatus(d(GdDelivery.EXPIRED, error = GdDelivery.ERROR_REMOVED)))
        assertEquals(GdSentStatus.REMOVED, GateDropLogic.sentStatus(d(GdDelivery.EXPIRED), sendRemoved = true))
        assertEquals(GdSentStatus.EXPIRED, GateDropLogic.sentStatus(d(GdDelivery.EXPIRED)))
        assertEquals(GdSentStatus.REMOVED, GateDropLogic.sentStatus(entry(d(GdDelivery.EXPIRED), removed = true)))
        // nothing listed: the send's own flag
        assertEquals(GdSentStatus.REMOVED, GateDropLogic.sentStatus(entry(removed = true)))
        assertNull(GateDropLogic.sentStatus(entry()))
        // who picked it up before keeps it: their delivery still reads picked up
        assertEquals(GdSentStatus.PICKED, GateDropLogic.sentStatus(entry(d(GdDelivery.DELIVERED, pickedAt = 1L), removed = true)))
        assertEquals(GdSentStatus.REMOVED, GateDropLogic.sentStatus(entry(d(GdDelivery.DELIVERED, pickedAt = 1L), d(GdDelivery.EXPIRED, error = "removed", target = "x"), removed = true)))
    }

    @Test
    fun `incomplete - not every file arrived`() {
        assertEquals(GdSentStatus.INCOMPLETE, GateDropLogic.sentStatus(d(GdDelivery.FAILED, error = GdDelivery.ERROR_INCOMPLETE)))
        assertEquals(GdSentStatus.INCOMPLETE, GateDropLogic.sentStatus(d(GdDelivery.PENDING, error = GdDelivery.ERROR_INCOMPLETE)))
        assertEquals(GdSentStatus.FAILED, GateDropLogic.sentStatus(d(GdDelivery.FAILED, error = "unreachable")))
        assertEquals(GdSentStatus.PENDING, GateDropLogic.sentStatus(d(GdDelivery.PENDING, error = GdDelivery.ERROR_DIRECT_FAILED)))
        // delivered over the server after all: informational only
        assertEquals(GdSentStatus.PROVIDED, GateDropLogic.sentStatus(d(GdDelivery.DELIVERED, error = GdDelivery.ERROR_INCOMPLETE, via = "server")))
    }

    @Test
    fun `the most important state of a send wins`() {
        assertEquals(GdSentStatus.PROVIDED, GateDropLogic.sentStatus(entry(d(GdDelivery.DELIVERED, pickedAt = 1L), d(GdDelivery.DELIVERED, target = "x"))))
        assertEquals(GdSentStatus.PROVIDED, GateDropLogic.sentStatus(entry(d(GdDelivery.DELIVERED, kind = "nas"), d(GdDelivery.DELIVERED, target = "x"))))
        assertEquals(GdSentStatus.WAITING, GateDropLogic.sentStatus(entry(d(GdDelivery.DELIVERED), d(GdDelivery.WAITING, target = "x"))))
        assertEquals(GdSentStatus.INCOMPLETE, GateDropLogic.sentStatus(entry(d(GdDelivery.DELIVERED), d(GdDelivery.FAILED, error = "incomplete", target = "x"))))
        assertEquals(GdSentStatus.FAILED, GateDropLogic.sentStatus(entry(d(GdDelivery.FAILED), d(GdDelivery.FAILED, error = "incomplete", target = "x"))))
        assertEquals(GdSentStatus.PICKED, GateDropLogic.sentStatus(entry(d(GdDelivery.DELIVERED, pickedAt = 1L), d(GdDelivery.DELIVERED, pickedAt = 2L, target = "x"))))
    }

    @Test
    fun `410 gone`() {
        assertTrue(GateDropLogic.isGone("gone"))
        assertFalse(GateDropLogic.isGone("not_found"))
        assertFalse(GateDropLogic.isGone(null))
    }

    // ─── the receiving end ──────────────────────────────────────────

    private val items = listOf(GdOfferItem("a.jpg", 5, "image/jpeg"), GdOfferItem("b.jpg", 7, "image/jpeg"))

    @Test
    fun `receiver - ok only with every file, then always with the bytes`() {
        assertEquals(GdDirectReport(true, 12, null), GateDropLogic.directReport(items, GdDirectOutcome.Done(12), listOf("u1", "u2")))
        assertEquals(GdDirectReport(false, null, "incomplete"), GateDropLogic.directReport(items, GdDirectOutcome.Done(5), listOf("u1", "u2")))
        assertEquals(GdDirectReport(false, null, "incomplete"), GateDropLogic.directReport(items, GdDirectOutcome.Done(12), listOf("u1")))
        assertEquals(GdDirectReport(false, null, "save_failed"), GateDropLogic.directReport(items, GdDirectOutcome.Done(12), listOf("u1", null)))
        assertEquals(GdDirectReport(false, null, "push_timeout"), GateDropLogic.directReport(items, GdDirectOutcome.Failed("push_timeout"), emptyList()))
        // zero-byte files count as well
        val empty = listOf(GdOfferItem("e.txt", 0, "text/plain"))
        assertEquals(GdDirectReport(true, 0, null), GateDropLogic.directReport(empty, GdDirectOutcome.Done(0), listOf("u")))
    }

    // ─── the sender after its own ok ────────────────────────────────

    private fun answer(status: String, pickedAt: Long? = null, knows: Boolean = true, via: String? = "direct", error: String? = null, fallback: String? = null) =
        GdTransferAnswer(fallback = fallback, delivery = d(status, pickedAt = pickedAt, knows = knows, via = via, error = error))

    @Test
    fun `sender - asks for the pick-up only while a 1_5 server leaves it open`() {
        assertTrue(GateDropLogic.awaitsPickup(answer(GdDelivery.DELIVERED)))
        assertTrue(GateDropLogic.awaitsPickup(answer(GdDelivery.DIRECT, via = null)))
        // the receiver failed first: the answer to an ok names no fallback, the delivery waits for the files
        assertTrue(GateDropLogic.awaitsPickup(answer(GdDelivery.PENDING, via = null, error = "direct_failed")))
        assertFalse(GateDropLogic.awaitsPickup(answer(GdDelivery.DELIVERED, pickedAt = 3L)))
        assertFalse(GateDropLogic.awaitsPickup(answer(GdDelivery.DELIVERED, knows = false)), "older server: as before")
        assertFalse(GateDropLogic.awaitsPickup(answer(GdDelivery.DELIVERED, via = "server")))
        assertFalse(GateDropLogic.awaitsPickup(answer(GdDelivery.OPENED)))
        assertFalse(GateDropLogic.awaitsPickup(answer(GdDelivery.PENDING, error = "direct_failed", fallback = "upload")), "handled by missingAfterDirect")
        assertFalse(GateDropLogic.awaitsPickup(null))
        assertFalse(GateDropLogic.awaitsPickup(GdTransferAnswer()))
    }

    private val localItems = listOf(
        GdItem(null, "a.jpg", "image/jpeg", 5, available = false, index = 0, local = true),
        GdItem(null, "b.jpg", "image/jpeg", 7, available = false, index = 1, local = true),
    )

    @Test
    fun `sender - pick-up check of one device`() {
        assertEquals(GdPickupCheck.Wait, GateDropLogic.pickupCheck(entry(d(GdDelivery.DELIVERED, via = "direct")), "pc"))
        assertEquals(GdPickupCheck.Done, GateDropLogic.pickupCheck(entry(d(GdDelivery.DELIVERED, via = "direct", pickedAt = 1L)), "pc"))
        assertEquals(GdPickupCheck.Done, GateDropLogic.pickupCheck(entry(d(GdDelivery.DELIVERED, via = "direct", knows = false)), "pc"))
        assertEquals(GdPickupCheck.Done, GateDropLogic.pickupCheck(entry(d(GdDelivery.DELIVERED, via = "server", error = "direct_failed")), "pc"))
        assertEquals(
            GdPickupCheck.Upload(listOf(0, 1)),
            GateDropLogic.pickupCheck(entry(d(GdDelivery.PENDING, error = GdDelivery.ERROR_DIRECT_FAILED), items = localItems), "pc"),
        )
        // only what is still on this device
        val one = listOf(localItems[0].copy(mediaId = "media_0001", local = false, available = true), localItems[1])
        assertEquals(GdPickupCheck.Upload(listOf(1)), GateDropLogic.pickupCheck(entry(d(GdDelivery.PENDING, error = "direct_failed"), items = one), "pc"))
        // another device, a removed send, nothing local
        assertEquals(GdPickupCheck.Done, GateDropLogic.pickupCheck(entry(d(GdDelivery.DELIVERED, via = "direct")), "laptop"))
        assertEquals(GdPickupCheck.Done, GateDropLogic.pickupCheck(entry(d(GdDelivery.PENDING, error = "direct_failed"), removed = true, items = localItems), "pc"))
        assertEquals(GdPickupCheck.Done, GateDropLogic.pickupCheck(entry(d(GdDelivery.PENDING, error = "direct_failed")), "pc"))
    }

    @Test
    fun `sender - picked up after a few polls, nothing to upload`() = runTest {
        var polls = 0
        val missing = GateDropWait.pickup(setOf("pc"), timeoutMs = 30_000, pollMs = 2_000) {
            polls++
            entry(if (polls < 3) d(GdDelivery.DELIVERED, via = "direct") else d(GdDelivery.DELIVERED, via = "direct", pickedAt = 9L))
        }
        assertEquals(emptyList<Int>(), missing)
        assertEquals(3, polls)
        assertEquals(6_000, currentTime)
    }

    @Test
    fun `sender - the receiver reported incomplete, the files go up`() = runTest {
        val missing = GateDropWait.pickup(setOf("pc", "laptop"), timeoutMs = 30_000, pollMs = 2_000) {
            entry(
                d(GdDelivery.PENDING, error = GdDelivery.ERROR_DIRECT_FAILED),
                d(GdDelivery.DELIVERED, via = "direct", pickedAt = 1L, target = "laptop"),
                items = localItems,
            )
        }
        assertEquals(listOf(0, 1), missing)
        assertEquals(2_000, currentTime)
    }

    @Test
    fun `sender - an older server ends the wait at once, no answer ends it after the timeout`() = runTest {
        assertEquals(emptyList<Int>(), GateDropWait.pickup(setOf("pc"), 30_000, 2_000) { entry(d(GdDelivery.DELIVERED, via = "direct", knows = false)) })
        assertEquals(2_000, currentTime)
        assertEquals(emptyList<Int>(), GateDropWait.pickup(setOf("pc"), 10_000, 2_000) { throw IOException("offline") })
        assertEquals(12_000, currentTime)
        assertEquals(emptyList<Int>(), GateDropWait.pickup(setOf("pc"), 10_000, 2_000) { entry(d(GdDelivery.DELIVERED, via = "direct")) })
        assertEquals(22_000, currentTime)
    }
}
