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

    suspend fun unlink(network: String, loginId: String): ChatResult<Unit> = api.bridgeUnlink(network, loginId)

    /** Messenger 1.15: is [e164] at the service? (Each call is a question to the service — only on "Prüfen".) */
    suspend fun resolve(network: String, e164: String): ChatResult<ChatNumberLookup> = api.bridgeResolve(network, e164)

    /** Drop a login in progress (the page was left, another way was chosen); nobody waits for the answer. */
    fun cancel(network: String, processId: String) {
        scope.launch { api.bridgeLoginCancel(network, processId) }
    }
}
