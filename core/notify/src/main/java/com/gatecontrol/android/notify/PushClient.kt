package com.gatecontrol.android.notify

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** State of the push channel, for the status card and the ongoing notification. */
sealed interface PushStatus {
    /** Switched off or not set up. */
    data object Off : PushStatus

    /** Mode "only with VPN" and the tunnel is down. */
    data object WaitingForVpn : PushStatus

    data object Connecting : PushStatus

    /**
     * Stream open since [since] (epoch ms); [via] = direct | tunnel as the
     * server sees it. [serverControl]: the server sent `hello.state`, so it
     * also pushes `control` events — the heartbeat may slow down.
     */
    data class Connected(val via: String, val since: Long, val serverControl: Boolean = false) : PushStatus

    /** Next attempt at [retryAt] (epoch ms). */
    data class Waiting(val retryAt: Long, val reason: WaitReason) : PushStatus

    /** Given up until the setup changes or the user retries. */
    data class Blocked(val reason: BlockReason) : PushStatus
}

/**
 * Holds the push stream open: connects, hands events to [PushRepository],
 * reconnects by [PushRetryPolicy] and right away when the network changes.
 *
 * Runs in the scope of whoever owns it right now — the push foreground
 * service (mode "always") or the tunnel supervisor while the VPN is up
 * (mode "only with VPN"); see [start]/[stop].
 */
@Singleton
class PushClient @Inject constructor(
    private val endpoint: PushEndpoint,
    private val store: PushPrefsStore,
    private val repository: PushRepository,
    private val platform: PushPlatform,
    private val control: ServerControlSink,
) {
    private data class Kick(val reason: String, val force: Boolean)

    internal var clock: () -> Long = System::currentTimeMillis
    internal var stream = PushStreamClient()
    internal var policy = PushRetryPolicy()

    private val _status = MutableStateFlow<PushStatus>(PushStatus.Off)
    val status: StateFlow<PushStatus> = _status.asStateFlow()

    /**
     * True while the stream is open to a server that pushes `control`
     * events (it sent `hello.state`). Then polling for policy, peer state and
     * support requests is only a fallback.
     */
    private val _controlLive = MutableStateFlow(false)
    val controlLive: StateFlow<Boolean> = _controlLive.asStateFlow()

    private fun setStatus(value: PushStatus) {
        _status.value = value
        _controlLive.value = value is PushStatus.Connected && value.serverControl
    }

    private val kicks = Channel<Kick>(Channel.CONFLATED)
    private val lock = Any()
    private var job: Job? = null
    private var owner: String? = null
    private var sideJob: Job? = null

    val isRunning: Boolean get() = synchronized(lock) { job?.isActive == true }

    fun currentOwner(): String? = synchronized(lock) { owner.takeIf { job?.isActive == true } }

    /** Runs the client in [scope] for [owner]; a run of another owner is replaced. */
    fun start(scope: CoroutineScope, owner: String) {
        synchronized(lock) {
            if (job?.isActive == true && this.owner == owner) return
            job?.cancel()
            this.owner = owner
            job = scope.launch { run() }
        }
    }

    /** Stops the run of [owner] (any run when null) and shows [idle]. */
    fun stop(owner: String? = null, idle: PushStatus = PushStatus.Off) {
        synchronized(lock) {
            if (owner != null && owner != this.owner) return
            job?.cancel()
            job = null
            this.owner = null
        }
        setStatus(idle)
    }

    /** Status while nobody runs the client (e.g. waiting for the VPN). */
    fun setIdleStatus(idle: PushStatus) {
        if (!isRunning) setStatus(idle)
    }

    /**
     * Reconnect now: after a network change, or [force] (user "retry",
     * setup changed) — which also ends a [PushStatus.Blocked] state.
     */
    fun kick(reason: String, force: Boolean = false) {
        kicks.trySend(Kick(reason, force))
    }

    private suspend fun run() = coroutineScope {
        val runScope = this
        val watcher = platform.watchNetwork { reason -> kick(reason) }
        try {
            repository.inbox.load()
            loop(runScope)
        } finally {
            watcher.close()
        }
    }

    private suspend fun loop(runScope: CoroutineScope) {
        var attempt = 0
        while (true) {
            val url = endpoint.serverUrl()
            val base = url.takeIf { it.isNotEmpty() }?.let { (if (it.endsWith("/")) it else "$it/").toHttpUrlOrNull() }
            if (base == null) {
                setStatus(PushStatus.Off)
                kicks.receive()
                continue
            }
            if (store.bindServer(url)) repository.inbox.clear()

            setStatus(PushStatus.Connecting)
            val outcome = connectOnce(url, base, runScope)
            if (outcome is StreamOutcome.Kicked ||
                (outcome is StreamOutcome.Ended && outcome.openForMs >= PushRetryPolicy.STABLE_MS)
            ) {
                attempt = 0
            }
            val decision = policy.decide(outcome, attempt)
            attempt++
            Timber.i("Push: stream ended (%s) → %s", outcome.logName(), decision)
            when (decision) {
                is RetryDecision.After -> if (decision.delayMs > 0) {
                    setStatus(PushStatus.Waiting(clock() + decision.delayMs, decision.reason))
                    waitForRetry(decision)
                }
                is RetryDecision.Blocked -> {
                    setStatus(PushStatus.Blocked(decision.reason))
                    val blockedFor = endpoint.credentialKey()
                    while (true) {
                        val k = kicks.receive()
                        if (k.force || endpoint.credentialKey() != blockedFor) break
                    }
                    attempt = 0
                }
            }
        }
    }

    /** Waits for the retry time; a network change ends the wait early (not a rate limit). */
    private suspend fun waitForRetry(decision: RetryDecision.After) {
        val deadline = clock() + decision.delayMs
        while (true) {
            val left = deadline - clock()
            if (left <= 0) return
            val k = withTimeoutOrNull(left) { kicks.receive() } ?: return
            if (k.force || decision.reason != WaitReason.RATE_LIMITED) return
        }
    }

    private suspend fun connectOnce(url: String, base: okhttp3.HttpUrl, runScope: CoroutineScope): StreamOutcome = coroutineScope {
        var via = "direct"
        val since = store.lastSeq()
        val conn = async {
            stream.connect(
                client = endpoint.streamingClient(url),
                baseUrl = base,
                lastEventId = since,
                onEvent = { event ->
                    when (event) {
                        is PushEvent.Hello -> {
                            via = event.via
                            setStatus(PushStatus.Connected(event.via, clock(), serverControl = event.state != null))
                            repository.onHello(event)
                            event.state?.let { state ->
                                runCatching { control.onServerState(state) }
                                    .onFailure { Timber.w("Push: server state not applied (%s)", it.javaClass.simpleName) }
                            }
                            sideJob?.cancel()
                            sideJob = runScope.launch { repository.afterConnect() }
                        }
                        is PushEvent.Notification ->
                            repository.onNotification(event.message.copy(receivedAt = clock(), via = via))
                        is PushEvent.Read -> repository.onRead(event.ids)
                        is PushEvent.Revoke -> repository.onRevoke(event.ids)
                        is PushEvent.Control -> {
                            Timber.i("Push: control %s", event.control.javaClass.simpleName)
                            runCatching { control.onControl(event.control) }
                                .onFailure { Timber.w("Push: control not applied (%s)", it.javaClass.simpleName) }
                        }
                        is PushEvent.Ignored -> Timber.d("Push: event %s ignored (%s)", event.type, event.reason)
                    }
                },
            )
        }
        var outcome: StreamOutcome? = null
        while (outcome == null) {
            val result: Any = select {
                conn.onAwait { it }
                kicks.onReceive { it }
            }
            if (result is StreamOutcome) {
                outcome = result
            } else {
                val kick = result as Kick
                // A network that merely appeared changes nothing for an open stream.
                if (!kick.force && kick.reason == "network_available" && _status.value is PushStatus.Connected) continue
                conn.cancel()
                outcome = StreamOutcome.Kicked
            }
        }
        outcome
    }

    private fun StreamOutcome.logName(): String = when (this) {
        is StreamOutcome.HttpError -> "HTTP $code ${error.orEmpty()}"
        is StreamOutcome.NetworkError -> "network $kind"
        is StreamOutcome.Ended -> "ended after ${openForMs / 1000}s"
        else -> javaClass.simpleName
    }
}
