package com.gatecontrol.android.media.cast

import com.gatecontrol.android.media.MediaHub
import com.gatecontrol.android.media.server.MediaCastApi
import com.gatecontrol.android.media.server.MediaServerResult
import com.google.gson.JsonElement
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** How a message went out. */
enum class CastSendResult {
    OK,

    /** 60 a minute or 600 an hour of this device: later. */
    RATE_LIMITED,

    /** More than [CastProtocol.MAX_BYTES], even made smaller ([CastWire.fit]). */
    TOO_LARGE,

    /** Cast off, no right, not an own device, an invalid message. */
    REFUSED,

    /** No answer of the server. */
    OFFLINE,
}

/**
 * „Auf TV/PC abspielen“ through the plugin „Medien“ (cast contract sections 2 and 3, the GateDrop envelope of section 1
 * is gone): messages go out with `POST v1/cast/{session}/message` ([send]), one after the other; they come in as the
 * push stream's event `media.cast` and as the silent push `data.kind = "media.cast"` ([receive], from
 * [com.gatecontrol.android.media.server.MediaServerSources]), are filtered to this device and taken once ([CastInbox])
 * and handed to the phone's remote or the TV's receiver ([incoming]). Shared by the phone app and GateControl TV.
 */
@Singleton
class MediaCastChannel(
    private val api: MediaCastApi,
    private val loadSelf: () -> String?,
    private val saveSelf: (String) -> Unit,
) {
    @Inject
    constructor(api: MediaCastApi, hub: MediaHub) : this(api, { runCatching { hub.store.castSelf() }.getOrNull() }, { id -> runCatching { hub.store.setCastSelf(id) } })

    private val inbox by lazy { CastInbox(loadSelf()) }
    private val sending = Mutex()

    /** The sessions this device runs now (as the controller or as the receiver). */
    private val sessions = ConcurrentHashMap.newKeySet<String>()

    private val _incoming = MutableSharedFlow<CastEnvelope>(extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** The messages for this device, each once. */
    val incoming: SharedFlow<CastEnvelope> = _incoming.asSharedFlow()

    /** This device's GateControl id, once a delivery told it; null before. */
    val self: String? get() = inbox.self

    /** [session] is this device's (an event for it counts as ours while [self] is not known yet). */
    fun own(session: String) {
        sessions += session
    }

    fun release(session: String) {
        sessions -= session
    }

    /** A delivery of `media.cast`: [push] the silent push, else the realtime event. True when it was taken. */
    fun receive(data: JsonElement?, push: Boolean): Boolean {
        val e = CastEnvelopes.parse(data) ?: return false
        val verdict = inbox.take(e, push, mine = { it in sessions }, onSelf = saveSelf)
        if (verdict != CastInbox.Verdict.TAKE) return false
        _incoming.tryEmit(e)
        return true
    }

    /** The own TVs and PCs (`GET v1/cast/targets`), or the refusal. */
    suspend fun targets(): MediaServerResult<List<CastTarget>> = api.castTargets()

    /** [message] to the device [to]; made smaller first when it is too big for the plugin. */
    suspend fun send(to: String, message: CastMessage): CastSendResult {
        val fitted = CastWire.fit(message) ?: return CastSendResult.TOO_LARGE
        val r = sending.withLock { api.castMessage(to, fitted) }
        return when (r) {
            is MediaServerResult.Ok -> CastSendResult.OK
            is MediaServerResult.Refused -> when (r.error.code) {
                "rate_limited" -> CastSendResult.RATE_LIMITED
                "too_large" -> CastSendResult.TOO_LARGE
                "offline" -> CastSendResult.OFFLINE
                else -> CastSendResult.REFUSED
            }.also { Timber.d("Cast: message not sent (%s)", r.error.code) }
        }
    }

    /** Signed out or unpaired: nothing of this device is kept. */
    fun clear() {
        sessions.clear()
        inbox.clear()
    }
}
