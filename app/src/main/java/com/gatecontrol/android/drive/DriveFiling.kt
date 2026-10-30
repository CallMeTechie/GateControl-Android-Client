package com.gatecontrol.android.drive

import com.gatecontrol.android.support.CrashReports
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Ablegen gleich nach dem Scan" (Drive 1.14, D4; the server as in Drive 1.8): after a scan or a document the person
 * uploaded here, Drive's AI reads it; once its insight is `ready` with a suggested name or folder, the bar
 * "Vorschlag · Ablegen als … in …" shows ([proposal]), with "An die Zahlung am … erinnern" for a payment deadline. It
 * waits for the live event `changed` or asks again now and then, about a minute at most — then the bar stays away
 * without a word. Only with `docAi`, never for a vault, at most a few files at once.
 */
@Singleton
class DriveFiling @Inject constructor(
    private val api: DriveApi,
    private val live: DriveLive,
    transfers: DriveTransfers,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashReports.coroutineHandler)

    /** The bar for [node]: what Drive suggests. */
    data class Proposal(val node: DNode, val filing: DriveKi.Filing)

    private val _proposal = MutableStateFlow<Proposal?>(null)
    val proposal: StateFlow<Proposal?> = _proposal.asStateFlow()

    private val watching = LinkedHashMap<Long, Job>()

    init {
        scope.launch { transfers.committed.collect { watch(it.node, it.scan) } }
    }

    /** Waits for the insight of [node] (a new upload); the newest file's suggestion wins. */
    fun watch(node: DNode, scan: Boolean) {
        if (!DriveKi.fileable(node, scan)) return
        scope.launch {
            val me = live.current() ?: return@launch
            if (!me.has(DMe.FEATURE_DOC_AI)) return@launch
            if (me.areas.firstOrNull { it.id == node.areaId }?.let { it.docAi == false || !it.writable } == true) return@launch
            synchronized(watching) {
                watching.remove(node.id)?.cancel()
                // a whole folder uploaded: only the last few are waited for
                while (watching.size >= MAX_WATCHED) watching.remove(watching.keys.first())?.cancel()
                watching[node.id] = scope.launch { wait(node) }
            }
        }
    }

    private suspend fun wait(node: DNode) {
        try {
            val changed = Channel<Unit>(Channel.CONFLATED)
            val listen = scope.launch { live.changed.filter { DriveLogic.affects(it, node.areaId) }.collect { changed.trySend(Unit) } }
            try {
                for (pause in DriveKi.FILING_POLL_MS) {
                    withTimeoutOrNull(pause) { changed.receive() }
                    val r = api.insight(node.id)
                    if (r is DResult.Failed && r.code in GIVE_UP) return
                    val insight = (r as? DResult.Ok)?.value ?: continue
                    if (insight.status == "failed" || insight.status == "off") return
                    val filing = DriveKi.filing(insight, LocalDate.now())
                    if (filing != null) {
                        _proposal.value = Proposal(node, filing)
                        return
                    }
                    // ready without a suggestion: the file is where it belongs
                    if (insight.status == "ready") return
                }
            } finally {
                listen.cancel()
            }
        } finally {
            synchronized(watching) { if (watching[node.id]?.isActive != true) watching.remove(node.id) }
        }
    }

    /** ✕: the bar goes. */
    fun dismiss() {
        _proposal.value = null
    }

    /**
     * "Übernehmen": renamed and moved as suggested ([folder] false: the name only, "Anderer Ordner" chooses the
     * folder), and with [remind] the reminder of the payment. Null when done, else a code.
     */
    suspend fun accept(p: Proposal, folder: Boolean, remind: Boolean): Pair<DNode?, String?> {
        _proposal.value = null
        val name = p.filing.name != null
        val move = folder && p.filing.folderId != null
        var node: DNode? = p.node
        if (name || move) {
            when (val r = api.insightApply(p.node.id, name, move)) {
                is DResult.Ok -> node = r.value.node
                else -> return null to (r.code() ?: "failed")
            }
        }
        val payment = p.filing.payment
        if (remind && payment != null) {
            val r = api.remind(payment.id, DriveLogic.REMIND_DEFAULT)
            if (r !is DResult.Ok) return node to (r.code() ?: "failed")
        }
        return node to null
    }

    private fun DResult<*>.code(): String? = when (this) {
        is DResult.Ok -> null
        is DResult.Failed -> code
        DResult.Offline -> "offline"
        DResult.NotConfigured -> "not_configured"
    }

    private companion object {
        const val MAX_WATCHED = 3
        /** Nothing will come: the file is gone, may not be read, or Drive has no AI. */
        val GIVE_UP = setOf("not_found", "forbidden", "feature_off", "encrypted", "unsupported")
    }
}
