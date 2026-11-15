package com.gatecontrol.android.ui.chat.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.chat.ChatBridgeError
import com.gatecontrol.android.chat.ChatBridgeLoginState
import com.gatecontrol.android.chat.ChatBridgeNetwork
import com.gatecontrol.android.chat.ChatBridgeStep
import com.gatecontrol.android.chat.ChatBridges
import com.gatecontrol.android.chat.ChatBridgesInfo
import com.gatecontrol.android.chat.ChatBridgesRepository
import com.gatecontrol.android.chat.ChatQrMatrix
import com.gatecontrol.android.chat.ChatResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** What the page shows. */
sealed interface LinkedView {
    /** Per service its state. */
    data object Overview : LinkedView

    /** The notes before the first link of [network]. */
    data class Notes(val network: String, val saving: Boolean = false) : LinkedView

    /** The link itself: [flow] is the way (a code for this phone, a QR code for a second screen). */
    data class Link(val network: String, val flow: String, val phase: LinkPhase) : LinkedView
}

sealed interface LinkPhase {
    /** The bridge is asked for the first step. */
    data object Starting : LinkPhase

    /** The number is asked for. The number itself never enters this state: the field keeps it until it is sent. */
    data class Phone(val stepId: String, val fieldId: String, val sending: Boolean = false, val error: PhoneError? = null) : LinkPhase

    /** The pairing code, waiting for the phone. */
    class Code(val code: String) : LinkPhase {
        override fun toString(): String = "Code"
    }

    /** The QR code, waiting for the phone. */
    class Qr(val matrix: ChatQrMatrix) : LinkPhase {
        override fun toString(): String = "Qr"
    }

    /** This attempt ended ([error] says why); a new code starts another. */
    data class Failed(val error: ChatBridgeError) : LinkPhase
}

enum class PhoneError { INVALID, REFUSED }

data class LinkedUiState(
    val loading: Boolean = true,
    val info: ChatBridgesInfo? = null,
    /** The last load failed (shown with "Erneut versuchen" when nothing is known yet). */
    val loadError: ChatBridgeError? = null,
    val view: LinkedView = LinkedView.Overview,
    /** The service being unlinked. */
    val unlinking: String? = null,
    /** Messenger 1.17: the choice of older messages is being saved. */
    val historySaving: Boolean = false,
    /**
     * Messenger 1.21: right after WhatsApp was linked — „Dürfen andere auf diesem Server dich an deinem WhatsApp
     * erkennen?“ (once, while `recognition.available && !asked`).
     */
    val askRecognition: Boolean = false,
    val recognitionSaving: Boolean = false,
)

/** A short message of the page after something happened. */
sealed interface LinkedNotice {
    data class Linked(val network: String) : LinkedNotice
    data class Unlinked(val network: String) : LinkedNotice
    data class Failed(val error: ChatBridgeError) : LinkedNotice
    /** Messenger 1.17: the choice of older messages was saved. */
    data object HistorySaved : LinkedNotice
    /** Messenger 1.21: the answer to the recognition question was saved ([allowed]). */
    data class Recognition(val allowed: Boolean) : LinkedNotice
}

/**
 * Chat-Einstellungen › „Verknüpfte Dienste“ (Messenger 1.13.0, the portal's page of the same name): the overview,
 * the notes before the first link, and the link itself — the page asks the server for the next step until the
 * phone confirmed. Number and code live in memory only, for as long as they show.
 */
@HiltViewModel
class LinkedServicesViewModel @Inject constructor(
    private val bridges: ChatBridgesRepository,
) : ViewModel() {

    private val state = MutableStateFlow(LinkedUiState())
    val uiState: StateFlow<LinkedUiState> = state.asStateFlow()

    private val notices = Channel<LinkedNotice>(Channel.BUFFERED)
    val notice: Flow<LinkedNotice> = notices.receiveAsFlow()

    /** The link in progress and the login the bridge holds for it (network, process). */
    private var run: Job? = null
    private var process: Pair<String, String>? = null

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        state.update { it.copy(loading = true) }
        val r = bridges.load()
        state.update {
            when (r) {
                is ChatResult.Ok -> it.copy(loading = false, info = r.value, loadError = null)
                else -> it.copy(loading = false, loadError = ChatBridges.error(r))
            }
        }
    }

    private fun network(id: String): ChatBridgeNetwork? = state.value.info?.networks?.firstOrNull { it.id == id }

    /** "Verknüpfen": the notes first, unless they were confirmed before. */
    fun begin(networkId: String) {
        val n = network(networkId) ?: return
        if (!n.allowed) return
        if (n.consent) link(networkId, ChatBridges.FLOW_PHONE) else state.update { it.copy(view = LinkedView.Notes(networkId)) }
    }

    /** The tick under the notes was set and "Weiter" tapped. */
    fun confirmNotes() {
        val v = state.value.view as? LinkedView.Notes ?: return
        if (v.saving) return
        state.update { it.copy(view = v.copy(saving = true)) }
        viewModelScope.launch {
            when (val r = bridges.consent(v.network)) {
                is ChatResult.Ok -> {
                    state.update { s -> s.copy(info = s.info?.let { i -> i.copy(networks = i.networks.map { n -> if (n.id == v.network) n.copy(consent = true) else n }) }) }
                    if (state.value.view is LinkedView.Notes) link(v.network, ChatBridges.FLOW_PHONE)
                }
                else -> {
                    state.update { if (it.view is LinkedView.Notes) it.copy(view = v.copy(saving = false)) else it }
                    refused(ChatBridges.error(r))
                }
            }
        }
    }

    /** Another way was chosen (code or QR code). */
    fun selectFlow(flow: String) {
        val v = state.value.view as? LinkedView.Link ?: return
        if (v.flow != flow && (flow == ChatBridges.FLOW_PHONE || flow == ChatBridges.FLOW_QR)) link(v.network, flow)
    }

    /** "Neuen Code anfordern" / "Neuen QR-Code anzeigen". */
    fun retry() {
        val v = state.value.view as? LinkedView.Link ?: return
        link(v.network, v.flow)
    }

    private fun link(networkId: String, flow: String) {
        stop(tell = true)
        state.update { it.copy(view = LinkedView.Link(networkId, flow, LinkPhase.Starting)) }
        run = viewModelScope.launch {
            // not cut off half-way: a login the bridge started while the person left is known here and dropped
            val mine = currentCoroutineContext()[Job]
            val r = withContext(NonCancellable) {
                bridges.start(networkId, flow).also { r ->
                    if (mine?.isActive == false) (r as? ChatResult.Ok)?.value?.let { bridges.cancel(networkId, it.processId) }
                }
            }
            currentCoroutineContext().ensureActive()
            follow(networkId, flow, r)
        }
    }

    /** The number was entered ([raw] as typed): checked here, sent once, never kept. */
    fun submitPhone(raw: String) {
        val v = state.value.view as? LinkedView.Link ?: return
        val p = v.phase as? LinkPhase.Phone ?: return
        val pid = process?.second ?: return
        if (p.sending) return
        val number = ChatBridges.phone(raw)
        if (number == null) {
            state.update { it.copy(view = v.copy(phase = p.copy(error = PhoneError.INVALID))) }
            return
        }
        state.update { it.copy(view = v.copy(phase = p.copy(sending = true, error = null))) }
        run = viewModelScope.launch {
            val r = bridges.step(v.network, pid, p.stepId, mapOf(p.fieldId to number))
            val e = if (r is ChatResult.Ok) null else ChatBridges.error(r)
            if (e == ChatBridgeError.FAILED || e == ChatBridgeError.EXPIRED) {
                // a number the service does not take: stay here; everything else ends this attempt
                phase(v.network, v.flow, p.copy(sending = false, error = PhoneError.REFUSED))
            } else {
                follow(v.network, v.flow, r)
            }
        }
    }

    /** Sets the phase of the link that is still shown (a late answer of a link that was left changes nothing). */
    private fun phase(networkId: String, flow: String, phase: LinkPhase) {
        state.update { s ->
            val v = s.view as? LinkedView.Link
            if (v != null && v.network == networkId && v.flow == flow) s.copy(view = v.copy(phase = phase)) else s
        }
    }

    /** Follows the bridge's steps from [first] on: shows what it hands over and asks again until the phone confirmed. */
    private suspend fun follow(networkId: String, flow: String, first: ChatResult<ChatBridgeLoginState>) {
        var r = first
        var waitingOn: String? = null
        var misses = 0
        while (true) {
            when (r) {
                is ChatResult.Ok -> {
                    misses = 0
                    val s = r.value
                    process = networkId to s.processId
                    if (s.waiting) {
                        if (waitingOn == null) return failed(networkId, flow, ChatBridgeError.GENERIC)
                        delay(POLL_MS)
                    } else {
                        when (val step = s.step) {
                            ChatBridgeStep.Complete -> {
                                process = null
                                notices.send(LinkedNotice.Linked(networkId))
                                state.update { it.copy(view = LinkedView.Overview) }
                                load()
                                // Messenger 1.21: asked once right after the link
                                if (state.value.info?.recognition?.let { it.ask && it.network == networkId } == true) state.update { it.copy(askRecognition = true) }
                                return
                            }
                            is ChatBridgeStep.Phone -> return phase(networkId, flow, LinkPhase.Phone(step.id, step.fieldId))
                            // WhatsApp renews the QR code a few times: each one is a new step
                            is ChatBridgeStep.Code -> { phase(networkId, flow, LinkPhase.Code(step.code)); waitingOn = step.id }
                            is ChatBridgeStep.Qr -> { phase(networkId, flow, LinkPhase.Qr(step.matrix)); waitingOn = step.id }
                            ChatBridgeStep.Unsupported, null -> return failed(networkId, flow, ChatBridgeError.UNSUPPORTED)
                        }
                    }
                }
                // a gap in the connection while waiting is no reason to throw a valid code away
                ChatResult.Offline -> if (waitingOn != null && ++misses <= MAX_MISSES) delay(POLL_MS) else return failed(networkId, flow, ChatBridgeError.OFFLINE)
                else -> return failed(networkId, flow, ChatBridges.error(r))
            }
            val pid = process?.second ?: return
            r = bridges.step(networkId, pid, waitingOn ?: return)
        }
    }

    private suspend fun failed(networkId: String, flow: String, error: ChatBridgeError) {
        // the bridge may still hold the login (no answer, a step that cannot be shown): drop it
        if (error == ChatBridgeError.OFFLINE || error == ChatBridgeError.UNSUPPORTED || error == ChatBridgeError.GENERIC) {
            process?.let { bridges.cancel(it.first, it.second) }
        }
        process = null
        when (error) {
            // the notes were not confirmed after all: show them
            ChatBridgeError.CONSENT -> state.update { s -> if ((s.view as? LinkedView.Link)?.network == networkId) s.copy(view = LinkedView.Notes(networkId)) else s }
            // the parents took the permission back, the bridges were switched off: the overview says so
            ChatBridgeError.LOCKED, ChatBridgeError.OFF -> {
                state.update { s -> if (s.view is LinkedView.Link) s.copy(view = LinkedView.Overview) else s }
                notices.send(LinkedNotice.Failed(error))
                load()
            }
            else -> phase(networkId, flow, LinkPhase.Failed(error))
        }
    }

    private suspend fun refused(error: ChatBridgeError) {
        notices.send(LinkedNotice.Failed(error))
        if (error == ChatBridgeError.LOCKED || error == ChatBridgeError.OFF) {
            state.update { it.copy(view = LinkedView.Overview) }
            load()
        }
    }

    /** "Trennen" (after the question) and "Eintrag entfernen" ([quiet]: no message, the link was gone already). */
    fun unlink(networkId: String, quiet: Boolean = false) {
        val l = network(networkId)?.let(ChatBridges::mainLogin) ?: return
        if (state.value.unlinking != null) return
        state.update { it.copy(unlinking = networkId) }
        viewModelScope.launch {
            val r = bridges.unlink(networkId, l.id)
            if (r is ChatResult.Ok) {
                if (!quiet) notices.send(LinkedNotice.Unlinked(networkId))
            } else {
                notices.send(LinkedNotice.Failed(ChatBridges.error(r)))
            }
            state.update { it.copy(unlinking = null) }
            load()
        }
    }

    /**
     * Messenger 1.17 „Älteren Verlauf laden“: what comes over at the next link (`0`, `30`, `-1`). Fewer days drop at
     * once what still waits and is no longer wanted (the server does that). Switched off meanwhile: the page loads anew.
     */
    fun setHistory(days: Int) {
        val h = state.value.info?.history ?: return
        if (!h.available || days == h.days || days !in h.options || state.value.historySaving) return
        state.update { it.copy(historySaving = true) }
        viewModelScope.launch {
            val r = bridges.setHistory(days)
            when (r) {
                is ChatResult.Ok -> {
                    state.update { s -> s.copy(historySaving = false, info = s.info?.copy(history = r.value)) }
                    notices.send(LinkedNotice.HistorySaved)
                }
                else -> {
                    state.update { it.copy(historySaving = false) }
                    val e = ChatBridges.error(r)
                    notices.send(LinkedNotice.Failed(e))
                    if (e == ChatBridgeError.OFF || e == ChatBridgeError.INVALID) load()
                }
            }
        }
    }

    /** The way back inside the page; false on the overview (the page itself is left). */
    /** The answer to „Dürfen andere … erkennen?“ ([allowed]); null: closed without an answer (asked again next time). */
    fun answerRecognition(allowed: Boolean?) {
        if (allowed == null) {
            state.update { it.copy(askRecognition = false) }
            return
        }
        if (state.value.recognitionSaving) return
        state.update { it.copy(recognitionSaving = true) }
        viewModelScope.launch {
            when (val r = bridges.setRecognition(allowed)) {
                is ChatResult.Ok -> {
                    state.update { s -> s.copy(askRecognition = false, recognitionSaving = false, info = s.info?.copy(recognition = r.value)) }
                    notices.send(LinkedNotice.Recognition(r.value.allowed))
                }
                else -> {
                    state.update { it.copy(askRecognition = false, recognitionSaving = false) }
                    notices.send(LinkedNotice.Failed(ChatBridges.error(r)))
                }
            }
        }
    }

    fun back(): Boolean {
        if (state.value.view == LinkedView.Overview) return false
        val wasLink = state.value.view is LinkedView.Link
        stop(tell = true)
        state.update { it.copy(view = LinkedView.Overview) }
        if (wasLink) refresh()
        return true
    }

    /** Ends what is in progress; with [tell] the bridge drops the login as well. */
    private fun stop(tell: Boolean) {
        run?.cancel()
        run = null
        val p = process
        process = null
        if (tell && p != null) bridges.cancel(p.first, p.second)
    }

    override fun onCleared() {
        stop(tell = true)
    }

    companion object {
        /** Between two questions "has the phone confirmed?" (the server itself waits up to 15 s per call). */
        const val POLL_MS = 1_500L

        /** So many calls in a row may stay without an answer while a code shows. */
        const val MAX_MISSES = 3
    }
}
