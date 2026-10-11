package com.gatecontrol.android.notify

import com.gatecontrol.android.network.MachineBindingError
import com.google.gson.JsonParser
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import java.util.concurrent.atomic.AtomicLong

/**
 * One connection of the push stream (`GET /api/v1/client/push`) on OkHttp's
 * EventSource: opens it with `Last-Event-ID`, hands every event to the
 * caller in order and returns how it ended.
 *
 * The stream client has no read timeout (it is idle between events), so a
 * watchdog closes connections that went silent: the server writes a
 * keepalive comment every `keepalive_s` seconds, and every byte read — the
 * comments included, which EventSource itself never reports — counts as a
 * sign of life (see [ActivityInterceptor]).
 */
class PushStreamClient(
    private val clock: () -> Long = System::currentTimeMillis,
    private val watchdogTickMs: Long = 5_000L,
    /** Silence allowed before `hello` told the keepalive interval. */
    private val initialStaleMs: Long = INITIAL_STALE_MS,
) {
    companion object {
        const val PATH = "api/v1/client/push"

        /** Before `hello` says otherwise. */
        const val INITIAL_STALE_MS = 60_000L

        /** Silence allowed: two missed keepalives plus slack. */
        fun staleAfterMs(keepaliveS: Int): Long = keepaliveS * 2_000L + 15_000L

        private const val MAX_ERROR_BODY = 4096L
    }

    private sealed interface Signal {
        data object Open : Signal
        data class Event(val id: String?, val type: String?, val data: String) : Signal
        data object Closed : Signal
        data class Failed(val outcome: StreamOutcome) : Signal
    }

    /**
     * Connects and suspends until the stream ends. [onOpen] runs once the
     * server accepted the stream; [onEvent] runs for every event, in order,
     * on the caller's coroutine. Cancelling the caller closes the stream.
     */
    suspend fun connect(
        client: OkHttpClient,
        baseUrl: HttpUrl,
        lastEventId: Long?,
        onOpen: suspend () -> Unit = {},
        onEvent: suspend (PushEvent) -> Unit,
    ): StreamOutcome {
        val lastActivity = AtomicLong(clock())
        val signals = Channel<Signal>(Channel.UNLIMITED)
        val tracked = client.newBuilder()
            .addInterceptor(ActivityInterceptor { lastActivity.set(clock()) })
            .build()
        val url = baseUrl.newBuilder().addPathSegments(PATH).build()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .apply { if (lastEventId != null && lastEventId > 0) header("Last-Event-ID", lastEventId.toString()) }
            .build()

        val listener = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                signals.trySend(Signal.Open)
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                signals.trySend(Signal.Event(id, type, data))
            }

            override fun onClosed(eventSource: EventSource) {
                signals.trySend(Signal.Closed)
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                signals.trySend(Signal.Failed(failure(t, response)))
            }
        }

        val source = EventSources.createFactory(tracked).newEventSource(request, listener)
        var openedAt = 0L
        var sawHello = false
        val staleAfter = AtomicLong(initialStaleMs)
        return coroutineScope {
            // Watchdog in its own coroutine: a timeout around receive() could
            // drop an event that arrives in the same instant.
            val watchdog = launch {
                while (true) {
                    delay(watchdogTickMs)
                    if (clock() - lastActivity.get() > staleAfter.get()) {
                        signals.trySend(Signal.Failed(StreamOutcome.Stale))
                        break
                    }
                }
            }
            try {
                var outcome: StreamOutcome? = null
                while (outcome == null) {
                    when (val signal = signals.receive()) {
                        Signal.Open -> {
                            openedAt = clock()
                            onOpen()
                        }
                        is Signal.Event -> {
                            val event = PushEventParser.parse(signal.type, signal.id, signal.data)
                            if (event is PushEvent.Hello) {
                                sawHello = true
                                staleAfter.set(staleAfterMs(event.keepaliveS))
                            }
                            onEvent(event)
                        }
                        Signal.Closed -> outcome = StreamOutcome.Ended(openedFor(openedAt), sawHello)
                        // A stream that was open and then broke is an end, not a refusal.
                        is Signal.Failed -> outcome = when {
                            signal.outcome == StreamOutcome.Stale -> StreamOutcome.Stale
                            openedAt > 0 -> StreamOutcome.Ended(openedFor(openedAt), sawHello)
                            else -> signal.outcome
                        }
                    }
                }
                outcome
            } finally {
                watchdog.cancel()
                source.cancel()
                signals.close()
            }
        }
    }

    private fun openedFor(openedAt: Long): Long = if (openedAt == 0L) 0L else clock() - openedAt

    private fun failure(t: Throwable?, response: Response?): StreamOutcome {
        if (response != null && !response.isSuccessful) {
            val body = try {
                response.peekBody(MAX_ERROR_BODY).string()
            } catch (_: Exception) {
                null
            }
            val error = try {
                body?.let { JsonParser.parseString(it).asJsonObject.get("error")?.asString?.takeIf { e -> e.isNotEmpty() } }
            } catch (_: Exception) {
                null
            }
            return StreamOutcome.HttpError(
                code = response.code,
                error = error,
                retryAfterS = response.header("Retry-After")?.trim()?.toLongOrNull(),
                bindingRejected = MachineBindingError.fromResponse(response.code, body) != null,
            )
        }
        if (response != null) return StreamOutcome.NotAStream
        return StreamOutcome.NetworkError(t?.javaClass?.simpleName ?: "unknown")
    }

    /** Notes every byte read from the response body — keepalive comments included. */
    internal class ActivityInterceptor(private val onActivity: () -> Unit) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val response = chain.proceed(chain.request())
            onActivity()
            val body = response.body
            return response.newBuilder().body(TrackingBody(body, onActivity)).build()
        }
    }

    private class TrackingBody(
        private val delegate: ResponseBody,
        private val onActivity: () -> Unit,
    ) : ResponseBody() {
        private val tracked: BufferedSource by lazy {
            object : ForwardingSource(delegate.source()) {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    val n = super.read(sink, byteCount)
                    if (n > 0) onActivity()
                    return n
                }
            }.buffer()
        }

        override fun contentType(): MediaType? = delegate.contentType()
        override fun contentLength(): Long = delegate.contentLength()
        override fun source(): BufferedSource = tracked
    }
}
