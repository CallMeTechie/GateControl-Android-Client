package com.gatecontrol.android.tv.cast

import android.content.Context
import android.content.Intent
import com.gatecontrol.android.gatedrop.GateDropApi
import com.gatecontrol.android.gatedrop.GateDropDeviceMessages
import com.gatecontrol.android.gatedrop.GdDeviceMessage
import com.gatecontrol.android.gatedrop.GdEntry
import com.gatecontrol.android.media.MediaBrowse
import com.gatecontrol.android.media.MediaHub
import com.gatecontrol.android.media.MediaResult
import com.gatecontrol.android.media.cast.CastMessage
import com.gatecontrol.android.media.cast.CastPhase
import com.gatecontrol.android.media.cast.CastProtocol
import com.gatecontrol.android.media.cast.CastReceiverRules
import com.gatecontrol.android.media.cast.CastReceiverSession
import com.gatecontrol.android.media.cast.CastSnapshot
import com.gatecontrol.android.media.cast.CastSources
import com.gatecontrol.android.media.cast.CastTrack
import com.gatecontrol.android.media.cast.CastWire
import com.gatecontrol.android.tv.drive.TvMediaSelection
import com.gatecontrol.android.tv.pair.TvPairingApi
import com.gatecontrol.android.tv.ui.drive.TvMediaActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the player of a remote-controlled session ([TvCast]) gets: the keys of the phone and where it tells how it
 * plays. Only for the session it was opened for.
 */
interface TvVideoRemote {
    /** The phone's keys for this session (pause, jump, sound, subtitle, stop, status). */
    val commands: Flow<CastMessage.Command>

    /** How it plays now ([TvCast] decides whether that is worth a message: changes, jumps, a heartbeat). */
    fun state(phase: CastPhase, positionMs: Long, durationMs: Long, title: String?, audio: List<CastTrack>, subtitles: List<CastTrack>, force: Boolean = false)

    /** The player closed: by the phone's "Stopp" ([byRemote]), at the end, or on the TV itself. */
    fun closed(positionMs: Long, durationMs: Long, byRemote: Boolean)
}

/**
 * "Auf GateControl TV abspielen" (stage 4, question 23a), the TV's side: a phone of the same account sends the
 * source, the title and the place as a device message over GateDrop (docs/CAST_PROTOCOL.md). The TV plays it from its
 * own source (its own Jellyfin access or share; not set up here: [CastProtocol.ERROR_SOURCE_MISSING]), opens the player
 * at the place and stays remote-controlled by that phone: its keys come in as commands, every change goes back as a
 * state. Only one session at a time; a new start (from any own device) takes over.
 */
@Singleton
class TvCast @Inject constructor(
    @ApplicationContext private val context: Context,
    api: GateDropApi,
    private val hub: MediaHub,
    private val selection: TvMediaSelection,
    private val pairing: TvPairingApi,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val messages = GateDropDeviceMessages(api)
    private val lock = Mutex()
    private val sending = Mutex()
    private val seen = java.util.Collections.synchronizedSet(LinkedHashSet<String>())

    @Volatile
    private var session: CastReceiverSession? = null

    @Volatile
    private var lastTold: CastSnapshot? = null

    @Volatile
    private var lastToldAt: Long = 0

    private val _commands = MutableSharedFlow<CastMessage.Command>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    internal var clock: () -> Long = System::currentTimeMillis

    companion object {
        /** The player must have opened by then. */
        const val OPEN_TIMEOUT_MS = 15_000L
    }

    /** A GateDrop push whose text is a device message: read it (own devices only) and act on it. */
    fun onPush(sendId: String) {
        if (!fresh(sendId)) return
        scope.launch { messages.read(sendId)?.let { handle(it) } }
    }

    /** A device message found in `GET pending` (the TV was away): a start that old is not played any more. */
    fun onPending(entry: GdEntry) {
        if (!fresh(entry.id)) return
        scope.launch { messages.take(entry)?.let { handle(it) } }
    }

    private fun fresh(sendId: String): Boolean = synchronized(seen) {
        if (!seen.add(sendId)) return false
        if (seen.size > 200) seen.remove(seen.first())
        true
    }

    private suspend fun handle(m: GdDeviceMessage) {
        val message = CastWire.parse(m.json) ?: return
        val now = clock()
        when (message) {
            is CastMessage.Play -> if (CastReceiverRules.freshPlay(message, m.at, now)) start(message, m.fromDevice)
            is CastMessage.Command -> lock.withLock {
                val next = CastReceiverRules.accept(session, message, m.fromDevice, m.at, now) ?: return
                session = next
                _commands.tryEmit(message)
            }
            // a state is for the phone, not for the TV
            is CastMessage.State -> Unit
        }
    }

    private suspend fun start(play: CastMessage.Play, from: String) {
        val before = lock.withLock {
            val old = session
            session = CastReceiverRules.begin(play, from)
            lastTold = null
            old
        }
        // whoever had the TV before: it is over for them
        if (before != null && before.session != play.session) {
            send(before, CastMessage.State(before.session, before.nextSeq, clock(), CastPhase.STOPPED, error = CastProtocol.ERROR_TAKEN_OVER, device = pairing.deviceName()))
        }
        tell(CastPhase.LOADING, play.positionMs, play.item.runtimeMs ?: 0, play.item.name, force = true)
        val source = CastSources.match(play.source, hub.store.sources.value)
        if (source == null) return fail(CastProtocol.ERROR_SOURCE_MISSING, play)
        if (!source.ready) return fail(CastProtocol.ERROR_SIGNED_OUT, play)
        val id = CastSources.itemId(source, play.item) ?: return fail(CastProtocol.ERROR_NOT_FOUND, play)
        val title = when (val d = hub.library(source).detail(id)) {
            is MediaResult.Ok -> d.value.title
            MediaResult.Unauthorized -> return fail(CastProtocol.ERROR_SIGNED_OUT, play)
            MediaResult.Offline -> return fail(CastProtocol.ERROR_OFFLINE, play)
            MediaResult.NotFound -> return fail(CastProtocol.ERROR_NOT_FOUND, play)
            is MediaResult.Failed -> return fail(CastProtocol.ERROR_FAILED, play)
        }
        if (!title.playable) return fail(CastProtocol.ERROR_UNSUPPORTED, play)
        val queue = MediaBrowse(hub) {}.queue(title.copy(sourceId = source.id))?.copy(
            startMs = play.positionMs,
            audioIndex = play.audio.takeIf { !source.kind.share },
            subtitleIndex = play.subtitle.takeIf { !source.kind.share },
        ) ?: return fail(CastProtocol.ERROR_SOURCE_MISSING, play)
        if (session?.session != play.session) return
        selection.setMedia(queue, cast = play.session)
        runCatching {
            // a player open already (another film, an earlier session) is closed and opened fresh with this one
            context.startActivity(
                Intent(context, TvMediaActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
        }.onFailure {
            Timber.w("Cast: player not opened (%s)", it.javaClass.simpleName)
            fail(CastProtocol.ERROR_FAILED, play)
            return
        }
        // the system may hold back a start from the background (another app in front): then the phone hears it
        delay(OPEN_TIMEOUT_MS)
        if (session?.session == play.session && opened != play.session) fail(CastProtocol.ERROR_BLOCKED, play)
    }

    @Volatile
    private var opened: String? = null

    private suspend fun fail(error: String, play: CastMessage.Play) {
        val s = lock.withLock { session?.takeIf { it.session == play.session }?.also { session = null } } ?: return
        send(s, CastMessage.State(s.session, s.nextSeq, clock(), CastPhase.FAILED, play.positionMs, title = play.item.name, error = error, device = pairing.deviceName()))
    }

    /** The remote of the player opened for [castSession]; null when that session is no longer the TV's. */
    fun remoteFor(castSession: String?): TvVideoRemote? {
        if (castSession == null || session?.session != castSession) return null
        opened = castSession
        return object : TvVideoRemote {
            override val commands: Flow<CastMessage.Command> = _commands.filter { it.session == castSession }

            override fun state(phase: CastPhase, positionMs: Long, durationMs: Long, title: String?, audio: List<CastTrack>, subtitles: List<CastTrack>, force: Boolean) {
                if (session?.session != castSession) return
                tell(phase, positionMs, durationMs, title, audio, subtitles, force)
            }

            override fun closed(positionMs: Long, durationMs: Long, byRemote: Boolean) {
                val s = session?.takeIf { it.session == castSession } ?: return
                session = null
                scope.launch {
                    val error = if (byRemote) null else CastProtocol.ERROR_CLOSED
                    send(s, CastMessage.State(s.session, s.nextSeq, clock(), CastPhase.STOPPED, positionMs, durationMs, error = error, device = pairing.deviceName()))
                }
            }
        }
    }

    private fun tell(phase: CastPhase, positionMs: Long, durationMs: Long, title: String?, audio: List<CastTrack> = emptyList(), subtitles: List<CastTrack> = emptyList(), force: Boolean = false) {
        val s = session ?: return
        val now = clock()
        val snap = CastSnapshot(phase, positionMs, durationMs, audio.firstOrNull { it.selected }?.id, subtitles.firstOrNull { it.selected }?.id, audio.size + subtitles.size)
        if (!force && !CastReceiverRules.shouldReport(lastTold, lastToldAt, snap, now)) return
        lastTold = snap
        lastToldAt = now
        scope.launch {
            send(s, CastMessage.State(s.session, 0, now, phase, positionMs, durationMs, title, audio, subtitles, device = pairing.deviceName()))
        }
    }

    /** One message after the other, each with the session's next seq. */
    private suspend fun send(s: CastReceiverSession, state: CastMessage.State) = sending.withLock {
        val seq = lock.withLock {
            val current = session
            if (current != null && current.session == s.session) {
                session = current.copy(nextSeq = current.nextSeq + 1)
                current.nextSeq
            } else {
                s.nextSeq
            }
        }
        val r = messages.send(s.controller, CastWire.encode(state.copy(seq = seq)))
        if (r !is com.gatecontrol.android.gatedrop.GdResult.Ok) Timber.d("Cast: state not sent")
    }
}
