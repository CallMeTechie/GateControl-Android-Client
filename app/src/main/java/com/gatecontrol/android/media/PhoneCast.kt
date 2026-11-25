package com.gatecontrol.android.media

import com.gatecontrol.android.gatedrop.GateDropApi
import com.gatecontrol.android.gatedrop.GateDropDeviceMessages
import com.gatecontrol.android.gatedrop.GdDevice
import com.gatecontrol.android.gatedrop.GdDeviceMessage
import com.gatecontrol.android.gatedrop.GdEntry
import com.gatecontrol.android.gatedrop.GdResult
import com.gatecontrol.android.media.cast.CastCmd
import com.gatecontrol.android.media.cast.CastMessage
import com.gatecontrol.android.media.cast.CastProtocol
import com.gatecontrol.android.media.cast.CastRemote
import com.gatecontrol.android.media.cast.CastRemoteState
import com.gatecontrol.android.media.cast.CastSources
import com.gatecontrol.android.media.cast.CastWire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** An own device with GateControl that can play ("Wohnzimmer-TV", GateControl TV; "Büro-PC", the Windows app). */
data class CastTarget(val id: String, val name: String, val kind: Kind, val online: Boolean) {
    enum class Kind { TV, PC }
}

/** Which of GateDrop's devices are cast targets. Pure, unit-tested. */
object CastTargets {
    /** GateControl TV (Fire TV, Android TV) and the Windows app; only the person's own devices, online first. */
    fun of(devices: List<GdDevice>): List<CastTarget> = devices.asSequence()
        .filter { it.owner == null }
        .mapNotNull { d ->
            val kind = when (d.platform?.lowercase(Locale.ROOT)) {
                "android-tv" -> CastTarget.Kind.TV
                "windows" -> CastTarget.Kind.PC
                else -> null
            } ?: return@mapNotNull null
            CastTarget(d.id, d.name, kind, d.online)
        }
        .sortedWith(compareByDescending<CastTarget> { it.online }.thenBy { it.name.lowercase(Locale.ROOT) })
        .toList()
}

/** Why the targets could not be listed. */
enum class CastListProblem { NO_GATEDROP, OFFLINE }

/**
 * "Auf GateControl TV / PC abspielen" (stage 4, question 23a), the phone's side: it sends the source, the title and
 * the place to an own device as device messages over GateDrop (docs/CAST_PROTOCOL.md) and stays the remote — pause and
 * on, jumps, sound and subtitle, stop — while the other device tells how it plays ([state]). No server change: the
 * plugin's ordinary sends, one per start, key and reported change.
 */
@Singleton
class PhoneCast @Inject constructor(
    api: GateDropApi,
    private val hub: MediaHub,
) {
    private val gatedrop = api
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val messages = GateDropDeviceMessages(api)
    private val lock = Mutex()
    private val sending = Mutex()
    private val seen = java.util.Collections.synchronizedSet(LinkedHashSet<String>())
    private var ticker: Job? = null

    private val _state = MutableStateFlow<CastRemoteState?>(null)

    /** The session running now (or the last one, until [dismiss]); null: none. */
    val state: StateFlow<CastRemoteState?> = _state.asStateFlow()

    internal var clock: () -> Long = System::currentTimeMillis

    /** The title of the session ("Auf diesem Handy weiter ab …" once it stopped there). */
    @Volatile
    var title: MediaTitle? = null
        private set

    /** The own devices that can play; a problem when GateDrop does not answer. */
    suspend fun targets(): Pair<List<CastTarget>, CastListProblem?> = when (val r = gatedrop.targets()) {
        is GdResult.Ok -> CastTargets.of(r.value.devices) to null
        is GdResult.Failed -> emptyList<CastTarget>() to if (r.http == 404 || r.http == 403) CastListProblem.NO_GATEDROP else CastListProblem.OFFLINE
        GdResult.NotConfigured -> emptyList<CastTarget>() to CastListProblem.NO_GATEDROP
        GdResult.Offline -> emptyList<CastTarget>() to CastListProblem.OFFLINE
    }

    /**
     * [title] of its source on [target] from [positionMs], with the sound and subtitle chosen here (Jellyfin's stream
     * indexes); false when it cannot be sent (no source, a series, GateDrop refused it).
     */
    suspend fun start(target: CastTarget, title: MediaTitle, positionMs: Long, audio: Int?, subtitle: Int?): Boolean {
        val source = hub.store.source(title.sourceId) ?: return false
        val item = CastSources.item(title, source) ?: return false
        val now = clock()
        val session = CastProtocol.newSession()
        val play = CastMessage.Play(
            session, 0, now, CastSources.ref(source), item, positionMs.coerceAtLeast(0),
            audio = audio.takeIf { !source.kind.share },
            subtitle = subtitle.takeIf { !source.kind.share },
            from = hub.info.device,
        )
        // an earlier session on another device: it stops there
        val stop = lock.withLock {
            _state.value?.let { old -> CastRemote.command(old, CastCmd.STOP, now)?.let { (c, _) -> c to old.deviceId } }
        }
        stop?.let { (c, device) -> sending.withLock { messages.send(device, CastWire.encode(c)) } }
        lock.withLock {
            this.title = title
            _state.value = CastRemote.start(session, target.id, target.name, title.name, positionMs, now)
        }
        val r = sending.withLock { messages.send(target.id, CastWire.encode(play)) }
        if (r !is GdResult.Ok) {
            lock.withLock { if (_state.value?.session == session) _state.value = null }
            return false
        }
        tick()
        return true
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
            sending.withLock { messages.send(target, CastWire.encode(c)) }
        }
    }

    /** The remote closed after the end: nothing is shown any more. */
    fun dismiss() {
        if (_state.value?.phase?.final == true) _state.value = null
    }

    /** A GateDrop push whose text is a device message. */
    fun onPush(sendId: String) {
        if (!fresh(sendId)) return
        scope.launch { messages.read(sendId)?.let { handle(it) } }
    }

    /** A device message found otherwise (`GET pending`, the receive path). */
    fun onEntry(entry: GdEntry) {
        if (!fresh(entry.id)) return
        scope.launch { messages.take(entry)?.let { handle(it) } }
    }

    private fun fresh(sendId: String): Boolean = synchronized(seen) {
        if (!seen.add(sendId)) return false
        if (seen.size > 300) seen.remove(seen.first())
        true
    }

    private suspend fun handle(m: GdDeviceMessage) {
        val state = CastWire.parse(m.json) as? CastMessage.State ?: return
        lock.withLock {
            val s = _state.value ?: return
            _state.value = CastRemote.onState(s, state, m.fromDevice, clock())
        }
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
