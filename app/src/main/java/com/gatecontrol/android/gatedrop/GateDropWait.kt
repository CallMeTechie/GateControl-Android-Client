package com.gatecontrol.android.gatedrop

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

/** How waiting for the other end of a direct transfer ended. */
sealed interface GdWait {
    data class Done(val bytes: Long) : GdWait

    /** The other end reported that it gave up ([reason]: its error, or the transfer's status). */
    data class PeerGaveUp(val reason: String) : GdWait

    data object TimedOut : GdWait

    /** The listener stopped or a file arrived broken. */
    data object Broken : GdWait
}

/**
 * This device listens and waits for the other end of a direct transfer
 * (it pulls from us, or pushes to us). The other end reports a failure to
 * the plugin at once (`POST transfers/<id>/result`), so the plugin is
 * asked every [pollMs] ([status], `GET transfers/<id>`): as soon as the
 * transfer failed or expired the wait ends and the server path takes
 * over — instead of waiting the whole [timeoutMs] for nothing.
 */
object GateDropWait {
    const val POLL_MS = 2_000L

    suspend fun await(done: Deferred<Long>, timeoutMs: Long, pollMs: Long = POLL_MS, status: suspend () -> GdTransferStatus?): GdWait = coroutineScope {
        val gaveUp = CompletableDeferred<GdTransferStatus>()
        val poller = launch {
            while (true) {
                delay(pollMs)
                val st = try {
                    status()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                if (st?.ended == true) {
                    gaveUp.complete(st)
                    break
                }
            }
        }
        try {
            withTimeoutOrNull(timeoutMs) {
                select<GdWait> {
                    done.onAwait { GdWait.Done(it) }
                    gaveUp.onAwait { GdWait.PeerGaveUp(it.peerError ?: it.status ?: GdTransferStatus.FAILED) }
                }
            } ?: GdWait.TimedOut
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            GdWait.Broken
        } finally {
            poller.cancel()
        }
    }
}
