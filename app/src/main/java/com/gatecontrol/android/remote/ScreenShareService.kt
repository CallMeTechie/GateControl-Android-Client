package com.gatecontrol.android.remote

import android.app.Activity
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.WindowManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.gatecontrol.android.BuildConfig
import com.gatecontrol.android.support.CrashReports
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * The running screen sharing (Fernzugriff, stage 5 — only watching): a foreground service of the type
 * `mediaProjection`, started after the person allowed the helper's question and Android's question. It holds the
 * MediaProjection, the VirtualDisplay, the H.264 encoder ([ScreenEncoder]) and the WebSocket of the relay
 * `gc-screen.v1` ([ScreenProtocol]); the ongoing notification shows who watches and has „Beenden“. Android's own
 * „Teilen beenden“ ends it as well. A lost connection is tried again with a fresh token for 30 s (the relay keeps
 * the session that long). Never logs a picture, a ref or a token.
 */
@AndroidEntryPoint
class ScreenShareService : Service() {

    @Inject lateinit var repository: ScreenShareRepository
    @Inject lateinit var api: RemoteApi
    @Inject lateinit var notifier: ScreenShareNotifier

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashReports.coroutineHandler)
    private val main = Handler(Looper.getMainLooper())

    private var pending: ScreenPending? = null
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    @Volatile private var encoder: ScreenEncoder? = null
    @Volatile private var socket: WebSocket? = null
    @Volatile private var open = false
    @Volatile private var needKey = true
    @Volatile private var configText: String? = null
    @Volatile private var finished = false
    @Volatile private var everConnected = false
    @Volatile private var viewerJoined = false
    private var viewerName = ""
    private var connectJob: Job? = null
    private var lostSince = 0L
    private var screenReceiver: BroadcastReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            finish("sender", sendBye = true)
            return START_NOT_STICKY
        }
        if (pending != null) return START_NOT_STICKY
        val p = intent?.let(::pendingOf)
        val data = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_DATA, Intent::class.java) }
        val resultCode = intent?.getIntExtra(EXTRA_RESULT, 0) ?: 0
        if (p == null || data == null || resultCode != Activity.RESULT_OK) {
            stopSelf()
            return START_NOT_STICKY
        }
        pending = p
        viewerName = p.viewerName
        running = true
        // the foreground service of the type mediaProjection must run before getMediaProjection
        try {
            ServiceCompat.startForeground(this, ScreenShareNotifier.ID_LIVE, notifier.live(viewerName, false, stopIntent()), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } catch (e: Exception) {
            Timber.w("Fernzugriff: foreground service refused (%s)", e.javaClass.simpleName)
            finish("failed", sendBye = false)
            return START_NOT_STICKY
        }
        if (!startCapture(resultCode, data)) {
            finish("failed", sendBye = false)
            return START_NOT_STICKY
        }
        watchScreen()
        connect()
        return START_NOT_STICKY
    }

    private fun stopIntent(): PendingIntent = PendingIntent.getService(
        this, 2, Intent(this, ScreenShareService::class.java).setAction(ACTION_STOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun size(): Pair<Int, Int> {
        val bounds = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        return ScreenProtocol.encodeSize(bounds.width(), bounds.height())
    }

    private fun startCapture(resultCode: Int, data: Intent): Boolean = try {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val mp = mpm.getMediaProjection(resultCode, data) ?: throw IllegalStateException("no projection")
        projection = mp
        mp.registerCallback(object : MediaProjection.Callback() {
            // Android's „Teilen beenden“ (status bar, lock): the sharing ends
            override fun onStop() {
                finish("sender", sendBye = true)
            }

            // Android 14: only one app is shared and it changed its size
            override fun onCapturedContentResize(width: Int, height: Int) {
                main.post { resize(ScreenProtocol.encodeSize(width, height)) }
            }
        }, main)
        val (w, h) = size()
        val enc = newEncoder(w, h)
        display = mp.createVirtualDisplay("gc-screen", w, h, resources.displayMetrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, enc.surface, null, main)
        true
    } catch (e: Exception) {
        Timber.w("Fernzugriff: capture not started (%s)", e.javaClass.simpleName)
        false
    }

    private fun newEncoder(w: Int, h: Int): ScreenEncoder {
        val enc = ScreenEncoder(w, h, ScreenProtocol.TARGET_FPS, encoder?.bitrate ?: ScreenProtocol.TARGET_BITRATE, object : ScreenEncoder.Sink {
            override fun onConfig(codec: String, width: Int, height: Int) {
                val text = ScreenProtocol.config(codec, width, height)
                configText = text
                if (open) socket?.send(text)
            }

            override fun onFrame(frame: ByteArray, key: Boolean) = sendFrame(frame, key)

            override fun onError() {
                main.post { finish("failed", sendBye = true) }
            }
        })
        encoder = enc
        return enc
    }

    /** Rotation or the shared app changed its size: a new encoder for the new size, the VirtualDisplay follows. */
    private fun resize(size: Pair<Int, Int>) {
        val old = encoder ?: return
        val vd = display ?: return
        if (finished || (old.width == size.first && old.height == size.second)) return
        val enc = newEncoder(size.first, size.second)
        vd.resize(size.first, size.second, resources.displayMetrics.densityDpi)
        vd.surface = enc.surface
        old.release()
        needKey = true
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        main.postDelayed({ resize(size()) }, 300)
    }

    private fun sendFrame(frame: ByteArray, key: Boolean) {
        val ws = socket ?: return
        if (!open) return
        if (needKey && !key) return
        // a slow line: drop pictures until the next key frame and lower the bitrate
        if (ws.queueSize() > BACKLOG_BYTES && !key) {
            needKey = true
            encoder?.let { it.lowerBitrate((it.bitrate * 0.7).toInt()); it.requestKeyframe() }
            return
        }
        needKey = false
        ws.send(frame.toByteString())
    }

    /** Screen off / on: the helper sees „Das Bild ist angehalten“. */
    private fun watchScreen() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val off = intent.action == Intent.ACTION_SCREEN_OFF
                if (open) socket?.send(ScreenProtocol.state(off, if (off) "locked" else null))
                if (!off) encoder?.requestKeyframe()
            }
        }
        screenReceiver = r
        ContextCompat.registerReceiver(this, r, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    // ─── The relay ───

    private fun connect() {
        val p = pending ?: return
        connectJob?.cancel()
        connectJob = scope.launch {
            // after a lost connection: wait a moment first (never a tight loop of tokens)
            var attempt = if (lostSince > 0L) 1 else 0
            while (!finished) {
                if (attempt > 0) delay(BACKOFF_MS[minOf(attempt - 1, BACKOFF_MS.size - 1)])
                attempt++
                when (val t = api.token(p.ticket)) {
                    is RemoteResult.Ok -> if (openSocket(t.value)) return@launch
                    // the ticket is gone (ended, expired): nothing to come back to
                    is RemoteResult.Failed -> if (t.http == 404 || t.http == 403) {
                        main.post { finish("failed", sendBye = false) }
                        return@launch
                    }
                    RemoteResult.Offline -> Unit
                }
                if (lostSince > 0 && System.currentTimeMillis() - lostSince > RECONNECT_WINDOW_MS) {
                    main.post { finish("sender_lost", sendBye = false) }
                    return@launch
                }
                if (!everConnected && attempt >= 4) {
                    main.post { finish("failed", sendBye = false) }
                    return@launch
                }
            }
        }
    }

    private fun openSocket(token: ScreenToken): Boolean {
        val (base, client) = api.relayClient() ?: return false
        val url = base.toHttpUrlOrNull()?.newBuilder()?.encodedPath(token.wsPath)?.build() ?: return false
        val ws = client.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(25, TimeUnit.SECONDS)
            .build()
            .newWebSocket(Request.Builder().url(url).header("Sec-WebSocket-Protocol", ScreenProtocol.protocolHeader(token.token)).build(), Listener())
        socket = ws
        return true
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (webSocket !== socket) return
            open = true
            everConnected = true
            lostSince = 0L
            needKey = true
            val enc = encoder ?: return
            webSocket.send(ScreenProtocol.hello(enc.width, enc.height, ScreenProtocol.TARGET_FPS, false, BuildConfig.VERSION_NAME))
            configText?.let { webSocket.send(it) }
            enc.requestKeyframe()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (webSocket !== socket) return
            when (val m = ScreenProtocol.parse(text)) {
                is ScreenProtocol.Message.Viewer -> main.post { onViewer(m.joined, m.name) }
                ScreenProtocol.Message.Keyframe -> {
                    needKey = true
                    encoder?.requestKeyframe()
                }
                is ScreenProtocol.Message.Rate -> encoder?.lowerBitrate(m.bitrate)
                is ScreenProtocol.Message.End -> main.post { finish(m.reason, sendBye = false) }
                is ScreenProtocol.Message.Error -> main.post { finish(m.code, sendBye = false) }
                is ScreenProtocol.Message.Ready, ScreenProtocol.Message.Pong, null -> Unit
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = lost(webSocket)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = lost(webSocket)

        private fun lost(webSocket: WebSocket) {
            if (webSocket !== socket || finished) return
            open = false
            socket = null
            if (lostSince == 0L) lostSince = System.currentTimeMillis()
            Timber.d("Fernzugriff: relay connection lost, trying again")
            main.post { if (!finished) connect() }
        }
    }

    private fun onViewer(joined: Boolean, name: String?) {
        if (finished) return
        viewerJoined = joined
        name?.let { viewerName = it }
        if (joined) repository.viewerJoined(name) else repository.viewerLeft()
        runCatching {
            getSystemService(android.app.NotificationManager::class.java)
                .notify(ScreenShareNotifier.ID_LIVE, notifier.live(viewerName, joined, stopIntent()))
        }
    }

    /** End the sharing — whoever ended it — and clean up everything. [sendBye]: the phone ends it (the helper hears „sender“). */
    private fun finish(reason: String, sendBye: Boolean) {
        if (finished) return
        finished = true
        val p = pending
        connectJob?.cancel()
        val ws = socket
        socket = null
        if (ws != null) {
            if (sendBye && open) ws.send(ScreenProtocol.BYE)
            ws.close(1000, null)
        }
        open = false
        screenReceiver?.let { runCatching { unregisterReceiver(it) } }
        screenReceiver = null
        runCatching { display?.release() }
        display = null
        encoder?.release()
        encoder = null
        runCatching { projection?.stop() }
        projection = null
        running = false
        Timber.d("Fernzugriff: sharing ended (%s)", ScreenProtocol.cleanCode(reason) ?: "other")
        if (p != null) repository.sessionEnded(p.requestId, failedBeforeStart = !everConnected)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (!finished) finish("sender", sendBye = true)
        scope.cancel()
        super.onDestroy()
    }

    private fun pendingOf(intent: Intent): ScreenPending? {
        val id = intent.getStringExtra(EXTRA_REQUEST) ?: return null
        val ref = intent.getStringExtra(EXTRA_REF) ?: return null
        val name = intent.getStringExtra(EXTRA_NAME) ?: ""
        return ScreenPending(id, ScreenTicket(ref, intent.getLongExtra(EXTRA_EXPIRES, 0L)), name)
    }

    companion object {
        const val ACTION_STOP = "com.gatecontrol.android.remote.ACTION_SCREEN_STOP"
        private const val EXTRA_REQUEST = "request"
        private const val EXTRA_REF = "ref"
        private const val EXTRA_EXPIRES = "expires"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_RESULT = "result"
        private const val EXTRA_DATA = "data"
        private const val BACKLOG_BYTES = 2L * 1024 * 1024
        private const val RECONNECT_WINDOW_MS = 30_000L
        private val BACKOFF_MS = longArrayOf(1_000L, 2_000L, 4_000L, 8_000L)

        /** A sharing runs (the repository stops it only then). */
        @Volatile var running = false
            private set

        /** Start after Android's consent ([data], [resultCode] of the MediaProjection question). The app is in front here. */
        fun start(context: Context, p: ScreenPending, resultCode: Int, data: Intent) {
            val i = Intent(context, ScreenShareService::class.java)
                .putExtra(EXTRA_REQUEST, p.requestId)
                .putExtra(EXTRA_REF, p.ticket.ref)
                .putExtra(EXTRA_EXPIRES, p.ticket.expiresAt)
                .putExtra(EXTRA_NAME, p.viewerName)
                .putExtra(EXTRA_RESULT, resultCode)
                .putExtra(EXTRA_DATA, data)
            ContextCompat.startForegroundService(context, i)
        }
    }
}
