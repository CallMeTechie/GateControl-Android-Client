package com.gatecontrol.android.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * „Verknüpfte Dienste“: the calls of the page (the viewer's own account at another messenger, see [ChatBridges]).
 * Nothing is kept — no number, no code, no state: every visit asks the server.
 */
@Singleton
class ChatBridgesRepository @Inject constructor(private val api: ChatApi) {
    // outlives the page: a login the person walked away from is dropped at the bridge
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun load(): ChatResult<ChatBridgesInfo> = api.bridges()

    suspend fun consent(network: String): ChatResult<Unit> = api.bridgeConsent(network)

    suspend fun start(network: String, flow: String): ChatResult<ChatBridgeLoginState> = api.bridgeLoginStart(network, flow)

    /** The answer to a question of the bridge ([input]), or — without — the wait for the phone. */
    suspend fun step(network: String, processId: String, stepId: String, input: Map<String, String>? = null): ChatResult<ChatBridgeLoginState> =
        api.bridgeLoginStep(network, processId, stepId, input)

    /** Messenger 1.17: what of the older messages comes over at the next link ([ChatBridgeHistory.days]). */
    suspend fun setHistory(days: Int): ChatResult<ChatBridgeHistory> = api.bridgeHistory(days)

    /** Messenger 1.21: „Dürfen andere auf diesem Server dich an deinem WhatsApp erkennen?“ — the answer. */
    suspend fun setRecognition(allowed: Boolean, network: String = ChatBridges.WHATSAPP): ChatResult<ChatRecognition> = api.putRecognition(allowed, network)

    /** Messenger 1.23: the step of a QR-only service (Signal) on a second screen ([ChatBridges.HANDOFF_PORTAL] / [ChatBridges.HANDOFF_WINDOWS]). */
    suspend fun handoff(network: String, target: String): ChatResult<ChatBridgeHandoff> = api.bridgeHandoff(network, target)

    /** Messenger 1.23: the second screen is not needed any more. */
    suspend fun handoffEnd(network: String): ChatResult<Unit> = api.bridgeHandoffEnd(network)

    suspend fun unlink(network: String, loginId: String): ChatResult<Unit> = api.bridgeUnlink(network, loginId)

    /** Messenger 1.15: is [e164] at the service? (Each call is a question to the service — only on "Prüfen".) */
    suspend fun resolve(network: String, e164: String): ChatResult<ChatNumberLookup> = api.bridgeResolve(network, e164)

    /** Messenger 1.23: the second screen is not needed any more; nobody waits for the answer. */
    fun endHandoff(network: String) {
        scope.launch { api.bridgeHandoffEnd(network) }
    }

    /** Drop a login in progress (the page was left, another way was chosen); nobody waits for the answer. */
    fun cancel(network: String, processId: String) {
        scope.launch { api.bridgeLoginCancel(network, processId) }
    }
}
