package com.gatecontrol.android.media

import com.gatecontrol.android.media.cast.CastCmd
import com.gatecontrol.android.media.cast.CastEnvelope
import com.gatecontrol.android.media.cast.CastMessage
import com.gatecontrol.android.media.cast.CastProtocol
import com.gatecontrol.android.media.cast.CastRemote
import com.gatecontrol.android.media.cast.CastRemoteState
import com.gatecontrol.android.media.cast.CastSendResult
import com.gatecontrol.android.media.cast.CastSources
import com.gatecontrol.android.media.cast.CastTarget
import com.gatecontrol.android.media.cast.MediaCastChannel
import com.gatecontrol.android.media.server.MediaServerResult
import com.gatecontrol.android.media.server.MediaServerSources
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** Why the targets could not be listed. */
enum class CastListProblem {
    /** The plugin does not allow „Auf TV/PC abspielen“ (switched off, no right „Medien“). */
    NOT_ALLOWED,
    OFFLINE,
}

/** Why a start did not go out. */
enum class CastStartProblem { FAILED, RATE_LIMITED }

/**
 * "Auf GateControl TV / PC abspielen" (cast contract sections 2 and 3), the phone's side: it sends the plugin's source
 * id, the title and the place to an own TV or PC through the plugin „Medien“ ([MediaCastChannel]) and stays the remote
 * — pause and on, jumps, sound and subtitle, stop — while the other device tells how it plays ([state]). Offered only
 * while the plugin allows it (`cast.enabled`) and an own TV or PC is there ([available]).
 */
@Singleton
class PhoneCast internal constructor(
    private val hub: MediaHub,
    private val servers: MediaServerSources,
    private val channel: MediaCastChannel,
    private val scope: CoroutineScope,
) {
    @Inject
    constructor(hub: MediaHub, servers: MediaServerSources, channel: MediaCastChannel) :
        this(hub, servers, channel, CoroutineScope(SupervisorJob() + Dispatchers.IO))

    private val lock = Mutex()
    private var ticker: Job? = null

    private val _state = MutableStateFlow<CastRemoteState?>(null)

    /** The session running now (or the last one, until [dismiss]); null: none. */
    val state: StateFlow<CastRemoteState?> = _state.asStateFlow()

    private val _targets = MutableStateFlow<List<CastTarget>>(emptyList())

    /** The own TVs and PCs as last listed. */
    val targets: StateFlow<List<CastTarget>> = _targets.asStateFlow()

    /** „Abspielen auf …“ is offered: the plugin allows it and an own TV or PC is there. */
    val available: StateFlow<Boolean> = combine(servers.state, _targets) { s, t -> s.castAllowed && t.isNotEmpty() }
        .stateIn(scope, SharingStarted.Eagerly, false)

    private val _limited = MutableStateFlow(false)

    /** The plugin refused a key a moment ago: too many messages from this phone (the remote says so for a minute). */
    val limited: StateFlow<Boolean> = _limited.asStateFlow()

    internal var clock: () -> Long = System::currentTimeMillis

    /** The title of the session ("Auf diesem Handy weiter ab …" once it stopped there). */
    @Volatile
    var title: MediaTitle? = null
        private set

    init {
        scope.launch { channel.incoming.collect { onMessage(it) } }
    }

    /** Only a title of a source of the plugin can be cast (the receiver knows the source by the plugin's id). */
    fun canCast(title: MediaTitle): Boolean {
        val source = hub.store.source(title.sourceId) ?: return false
        return CastSources.ref(source) != null && CastSources.item(title, source) != null
    }

    /** The detail page or the player opened: the targets again (only while the plugin allows it). */
    fun refreshTargets() {
        if (!servers.state.value.castAllowed) {
            _targets.value = emptyList()
            return
        }
        scope.launch { targets() }
    }

    /** The own devices that can play; a problem when the plugin does not allow it or does not answer. */
    suspend fun targets(): Pair<List<CastTarget>, CastListProblem?> {
        if (!servers.state.value.castAllowed) {
            _targets.value = emptyList()
            return emptyList<CastTarget>() to CastListProblem.NOT_ALLOWED
        }
        return when (val r = channel.targets()) {
            is MediaServerResult.Ok -> {
                _targets.value = r.value
                r.value to null
            }
            is MediaServerResult.Refused -> {
                val offline = r.error.code == "offline" || r.error.http >= 500
                if (!offline) _targets.value = emptyList()
                _targets.value to if (offline) CastListProblem.OFFLINE else CastListProblem.NOT_ALLOWED
            }
        }
    }

    /**
     * [title] of its source on [target] from [positionMs], with the sound and subtitle chosen here (Jellyfin's stream
     * indexes); null when it went out, else why not (no source of the plugin, a series, the plugin refused it).
     */
    suspend fun start(target: CastTarget, title: MediaTitle, positionMs: Long, audio: Int?, subtitle: Int?): CastStartProblem? {
        val source = hub.store.source(title.sourceId) ?: return CastStartProblem.FAILED
        val ref = CastSources.ref(source) ?: return CastStartProblem.FAILED
        val item = CastSources.item(title, source) ?: return CastStartProblem.FAILED
        val now = clock()
        val session = CastProtocol.newSession()
        val play = CastMessage.Play(
            session, 0, now, ref, item, positionMs.coerceAtLeast(0),
            audio = audio.takeIf { !source.kind.share },
            subtitle = subtitle.takeIf { !source.kind.share },
            from = hub.info.device,
        )
        // an earlier session on another device: it stops there
        val stop = lock.withLock {
            _state.value?.let { old -> CastRemote.command(old, CastCmd.STOP, now)?.let { (c, _) -> c to old.deviceId } }
        }
        stop?.let { (c, device) -> channel.send(device, c) }
        lock.withLock {
            _state.value?.session?.let(channel::release)
            this.title = title
            channel.own(session)
            _state.value = CastRemote.start(session, target.id, target.name, title.name, positionMs, now)
        }
        val r = channel.send(target.id, play)
        if (r != CastSendResult.OK) {
            lock.withLock {
                if (_state.value?.session == session) _state.value = null
                channel.release(session)
            }
            return if (r == CastSendResult.RATE_LIMITED) CastStartProblem.RATE_LIMITED else CastStartProblem.FAILED
        }
        tick()
        return null
    }

    /** A key of the remote. */
    fun command(cmd: CastCmd, positionMs: Long? = null, deltaMs: Long? = null, track: String? = null) {
        scope.launch {
            val (c, target) = lock.withLock {
                val s = _state.value ?: return@launch
                val (c, shown) = CastRemote.command(s, cmd, clock(), positionMs, deltaMs, track) ?: return@launch
                _state.value = shown
                c to s.deviceId
            }
            if (channel.send(target, c) == CastSendResult.RATE_LIMITED) {
                _limited.value = true
                // the player there did not get it: what it does now comes with its next state
                delay(LIMIT_HINT_MS)
                _limited.value = false
            }
        }
    }

    /** The remote closed after the end: nothing is shown any more. */
    fun dismiss() {
        val s = _state.value ?: return
        if (s.phase.final) {
            channel.release(s.session)
            _state.value = null
        }
    }

    /** A message of the plugin for this device: a state of the running session from its device. */
    internal suspend fun onMessage(e: CastEnvelope) {
        val m = e.message as? CastMessage.State ?: return
        lock.withLock {
            val s = _state.value ?: return
            _state.value = CastRemote.onState(s, m, e.from, clock())
        }
    }

    private companion object {
        const val LIMIT_HINT_MS = 60_000L
    }

    /** Once a second: the place runs on in the remote by itself, no answer in time ends it. */
    private fun tick() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                delay(1_000)
                val done = lock.withLock {
                    val s = _state.value ?: return@withLock true
                    _state.value = CastRemote.tick(s, clock())
                    _state.value?.phase?.final == true
                }
                if (done) break
            }
        }
    }
}
