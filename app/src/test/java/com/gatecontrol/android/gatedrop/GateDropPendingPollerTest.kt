package com.gatecontrol.android.gatedrop

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GateDropPendingPollerTest {

    /** In-memory host: [process] saves unless [failing] names the send. */
    private class FakeHost(var answer: GdResult<List<GdEntry>>) : GateDropPendingPoller.Host {
        val handled = mutableSetOf<String>()
        val local = mutableMapOf<String, GdReceived>()
        val processed = mutableListOf<String>()
        val acked = mutableListOf<String>()
        val failing = mutableSetOf<String>()
        var fetches = 0
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun fetchPending(): GdResult<List<GdEntry>> {
            fetches++
            gate?.await()
            return answer
        }

        override fun isHandled(sendId: String) = sendId in handled
        override fun local(sendId: String) = local[sendId]
        override fun markHandled(sendId: String) {
            handled += sendId
        }

        /** As GateDropRepository.fetchSend: stored, saved (or not), handled and acknowledged per outcome. */
        override suspend fun process(send: GdEntry) {
            processed += send.id
            val ok = send.id !in failing
            val entry = GdReceived(
                "s:${send.id}", send.id, send.from, send.at ?: 0, text = send.text,
                files = send.items.map { GdReceivedFile(it.name, it.mime, it.bytes, it.mediaId, if (ok) "content://${it.mediaId}" else null) },
            )
            local[send.id] = entry
            val outcome = GateDropLogic.receiveOutcome(entry, wantSave = true)
            if (outcome.handled) handled += send.id
            if (outcome.ack) acked += send.id
        }

        override suspend fun ack(sendId: String): Boolean {
            acked += sendId
            return true
        }
    }

    private fun send(id: String, media: String? = "m-$id-123456") =
        GdEntry(id, at = 1, from = "PC", items = media?.let { listOf(GdItem(it, "$id.pdf", "application/pdf", 10)) }.orEmpty())

    private var now = 1_000_000L
    private fun poller(host: FakeHost) = GateDropPendingPoller(host, clock = { now })

    @Test
    fun `waiting sends are processed once and acknowledged`() = runBlocking {
        val host = FakeHost(GdResult.Ok(listOf(send("a"), send("b"), GdEntry("c", at = 1, text = "Hallo"))))
        val p = poller(host)
        assertEquals(GdPollOutcome.Done(listOf("a", "b", "c"), emptyList()), p.poll())
        assertEquals(listOf("a", "b", "c"), host.acked)
        // The server still lists them (acknowledgement not seen yet): nothing is received twice.
        host.acked.clear()
        assertEquals(GdPollOutcome.Done(emptyList(), listOf("a", "b", "c")), p.poll(force = true))
        assertEquals(listOf("a", "b", "c"), host.processed)
    }

    @Test
    fun `a send handled by its push is not received again`() = runBlocking {
        val host = FakeHost(GdResult.Ok(listOf(send("a"), send("b"))))
        host.handled += "a"
        val outcome = poller(host).poll()
        assertEquals(listOf("b"), host.processed)
        assertEquals(GdPollOutcome.Done(listOf("b"), emptyList()), outcome)
    }

    @Test
    fun `older plugin and no device are ignored`() = runBlocking {
        val host = FakeHost(GdResult.Failed("http_404", 404))
        val p = poller(host)
        assertEquals(GdPollOutcome.Unsupported, p.poll())
        host.answer = GdResult.Failed("no_device", 409)
        assertEquals(GdPollOutcome.NoDevice, p.poll(force = true))
        assertTrue(host.processed.isEmpty() && host.acked.isEmpty() && host.handled.isEmpty())
    }

    @Test
    fun `a failed download is tried again by the next poll`() = runBlocking {
        val host = FakeHost(GdResult.Ok(listOf(send("a"))))
        host.failing += "a"
        val p = poller(host)
        p.poll()
        assertTrue("a" !in host.handled)
        assertTrue(host.acked.isEmpty())
        host.failing.clear()
        now += GateDropPendingPoller.MIN_INTERVAL_MS
        assertEquals(GdPollOutcome.Done(listOf("a"), emptyList()), p.poll())
        assertEquals(listOf("a", "a"), host.processed)
        assertTrue("a" in host.handled)
        assertEquals(listOf("a"), host.acked)
    }

    @Test
    fun `offline is not remembered as an answer`() = runBlocking {
        val host = FakeHost(GdResult.Offline)
        val p = poller(host)
        assertEquals(GdPollOutcome.Offline, p.poll())
        host.answer = GdResult.Ok(listOf(send("a")))
        assertEquals(GdPollOutcome.Done(listOf("a"), emptyList()), p.poll())
        // Right after an answer: skipped unless forced.
        assertEquals(GdPollOutcome.TooSoon, p.poll())
        assertEquals(2, host.fetches)
    }

    @Test
    fun `never two polls at once`() = runBlocking {
        val host = FakeHost(GdResult.Ok(listOf(send("a"))))
        val gate = CompletableDeferred<Unit>()
        host.gate = gate
        val p = poller(host)
        val first = async { p.poll(force = true) }
        while (host.fetches == 0) kotlinx.coroutines.yield()
        assertEquals(GdPollOutcome.Busy, p.poll(force = true))
        gate.complete(Unit)
        assertEquals(GdPollOutcome.Done(listOf("a"), emptyList()), first.await())
        assertEquals(1, host.fetches)
    }
}
