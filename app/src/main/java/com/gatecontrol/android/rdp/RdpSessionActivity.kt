package com.gatecontrol.android.rdp

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.gatecontrol.android.R
import com.gatecontrol.android.rdp.freerdp.RdpSessionController
import com.gatecontrol.android.rdp.freerdp.RdpSessionEvent
import com.gatecontrol.android.service.RdpSessionService
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import timber.log.Timber

/**
 * Fullscreen Activity that hosts a live FreeRDP session. The activity is
 * launched by [RdpEmbeddedClient.launchSession] with connection parameters
 * serialized into Intent extras by [RdpEmbeddedClient.buildSessionIntent].
 *
 * Lifecycle:
 *   - `onCreate` parses params, allocates a [RdpSessionController], starts
 *     the foreground [RdpSessionService], and kicks off the connect thread.
 *   - The Compose screen observes `controller.events` and reacts to
 *     `GraphicsResize` (new Bitmap backbuffer), `GraphicsUpdate` (dirty rect
 *     invalidate), `VerifyCertificate*` (dialog + runBlocking bridge),
 *     `ConnectionFailure` / `Disconnected` (finish).
 *   - `onDestroy` tears down the controller and stops the service.
 */
class RdpSessionActivity : ComponentActivity() {

    companion object {
        val WINDOW_FLAG_SECURE = WindowManager.LayoutParams.FLAG_SECURE

        fun parseConnectionParams(intent: Intent): RdpConnectionParams? {
            val host = intent.getStringExtra("rdp_host") ?: return null
            return RdpConnectionParams(
                host = host,
                port = intent.getIntExtra("rdp_port", 3389),
                username = intent.getStringExtra("rdp_username"),
                password = intent.getStringExtra("rdp_password"),
                domain = intent.getStringExtra("rdp_domain"),
                resolutionWidth = intent.getIntExtra("rdp_resolution_width", 0),
                resolutionHeight = intent.getIntExtra("rdp_resolution_height", 0),
                colorDepth = intent.getIntExtra("rdp_color_depth", 32),
                redirectClipboard = intent.getBooleanExtra("rdp_redirect_clipboard", false),
                redirectPrinters = intent.getBooleanExtra("rdp_redirect_printers", false),
                redirectDrives = intent.getBooleanExtra("rdp_redirect_drives", false),
                audioMode = intent.getStringExtra("rdp_audio_mode") ?: "local",
                adminSession = intent.getBooleanExtra("rdp_admin_session", false),
                routeName = intent.getStringExtra("rdp_route_name") ?: ""
            )
        }
    }

    private lateinit var controller: RdpSessionController
    private lateinit var diagLog: RdpDiagnosticLog
    private val certVerdictChannel = Channel<Int>(capacity = 1)
    private var sessionStartMs: Long = 0L

    @androidx.annotation.VisibleForTesting
    internal val controllerForTest: RdpSessionController
        get() = controller

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Security: prevent screenshots and screen recording
        window.setFlags(WINDOW_FLAG_SECURE, WINDOW_FLAG_SECURE)

        // Immersive fullscreen
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val windowController = WindowInsetsControllerCompat(window, window.decorView)
        windowController.hide(WindowInsetsCompat.Type.systemBars())
        windowController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        val params = parseConnectionParams(intent)
        if (params == null) {
            Timber.e("RdpSessionActivity: no connection params in intent")
            finish()
            return
        }

        sessionStartMs = System.currentTimeMillis()

        // Diagnostic log for RDP debugging — written to Downloads folder
        diagLog = RdpDiagnosticLog(this)
        diagLog.log("=== RDP Session Start ===")
        diagLog.log("Host: ${params.host}:${params.port}")
        diagLog.log("Username: ${if (params.username.isNullOrEmpty()) "EMPTY" else "${params.username} (${params.username?.length} chars)"}")
        diagLog.log("Password: ${if (params.password.isNullOrEmpty()) "EMPTY" else "SET (${params.password?.length} chars)"}")
        diagLog.log("Domain: ${params.domain ?: "null"}")
        diagLog.log("Resolution: ${params.resolutionWidth}x${params.resolutionHeight} @${params.colorDepth}bpp")
        diagLog.log("NLA: see bookmark advancedSettings.security")
        diagLog.log("AdminSession: ${params.adminSession}")

        controller = RdpSessionController(
            context = this,
            params = params,
            verifyCertificate = { unknown, changed ->
                diagLog.log("OnVerifyCertificate: unknown=${unknown != null}, changed=${changed != null}")
                runBlocking { certVerdictChannel.receive() }
            },
            authenticate = { username, password ->
                val uLen = username.length
                val pLen = password.length
                val hasCredentials = uLen > 0 || pLen > 0
                diagLog.log("OnAuthenticate called: username=${uLen} chars, password=${pLen} chars, hasCredentials=$hasCredentials")
                if (!hasCredentials) {
                    Timber.w("OnAuthenticate: no credentials available — rejecting")
                    diagLog.log("OnAuthenticate: REJECTING (no credentials in StringBuilder params)")
                } else {
                    diagLog.log("OnAuthenticate: ACCEPTING (credentials present)")
                }
                hasCredentials
            },
        )

        // Verify session registration
        val inst = controller.instance
        val session = com.freerdp.freerdpcore.application.GlobalApp.getSession(inst)
        diagLog.log("Controller instance=$inst, GlobalApp.getSession=${if (session != null) "FOUND" else "NULL"}")
        diagLog.log("SessionMap size=${com.freerdp.freerdpcore.application.GlobalApp.getSessions()?.size ?: "null"}")

        startRdpService(params.routeName)

        setContent { RdpSessionScreen() }

        diagLog.log("Calling controller.connect()...")
        controller.connect()
        diagLog.log("controller.connect() returned (async thread started)")

        // Monitor events on a background coroutine and log ALL of them
        lifecycleScope.launch {
            controller.events.collect { event ->
                diagLog.log("EVENT: ${event::class.simpleName} — $event")
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finishSession()
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::controller.isInitialized) {
            controller.disconnect()
            controller.release()
        }
        stopRdpService()
    }

    private fun finishSession() {
        if (::controller.isInitialized) {
            controller.disconnect()
        }
        finish()
    }

    private fun startRdpService(routeName: String) {
        val serviceIntent = Intent(this, RdpSessionService::class.java).apply {
            putExtra(RdpSessionService.EXTRA_ROUTE_NAME, routeName)
            putExtra(RdpSessionService.EXTRA_CONNECTED_SINCE, sessionStartMs)
        }
        startForegroundService(serviceIntent)
    }

    private fun stopRdpService() {
        stopService(Intent(this, RdpSessionService::class.java))
    }

    // ------------------------------------------------------------------
    // Compose screen
    // ------------------------------------------------------------------

    @Composable
    private fun RdpSessionScreen() {
        val event by controller.events.collectAsState()
        var canvasView by remember { mutableStateOf<RdpCanvasView?>(null) }
        var surface by remember { mutableStateOf<Bitmap?>(null) }

        // Helper: create bitmap and register with FreeRDP session
        fun initSurface(width: Int, height: Int, bpp: Int) {
            if (width <= 0 || height <= 0) return
            val bmp = if (bpp >= 24)
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            else
                Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
            val session = com.freerdp.freerdpcore.application.GlobalApp.getSession(controller.instance)
            session?.setSurface(android.graphics.drawable.BitmapDrawable(resources, bmp))
            surface = bmp
            canvasView?.surface = bmp
            diagLog.log("Surface created: ${width}x${height} bpp=$bpp, session=${if (session != null) "OK" else "NULL"}")
        }

        LaunchedEffect(event) {
            when (val e = event) {
                is RdpSessionEvent.SettingsChanged -> {
                    // FreeRDP 3.x fires SettingsChanged (not GraphicsResize)
                    // for the initial resolution negotiation.
                    if (surface == null) {
                        initSurface(e.width, e.height, e.bpp)
                    }
                }
                is RdpSessionEvent.GraphicsResize -> {
                    // FreeRDP 2.x path or mid-session resize
                    initSurface(e.width, e.height, e.bpp)
                }
                is RdpSessionEvent.GraphicsUpdate -> {
                    // Create surface on first update if neither SettingsChanged
                    // nor GraphicsResize fired (defensive fallback)
                    if (surface == null && e.width > 0 && e.height > 0) {
                        initSurface(e.width, e.height, 32)
                    }
                    // Copy pixels from FreeRDP native buffer into our bitmap
                    surface?.let { bmp ->
                        com.freerdp.freerdpcore.services.LibFreeRDP.updateGraphics(
                            controller.instance, bmp, e.x, e.y, e.width, e.height
                        )
                    }
                    canvasView?.invalidateSurfaceRegion(e.x, e.y, e.width, e.height)
                }
                is RdpSessionEvent.ConnectionFailure -> {
                    Timber.e("Connection failed: ${e.reason}")
                    finish()
                }
                is RdpSessionEvent.Disconnected -> {
                    Timber.i("Disconnected")
                    finish()
                }
                else -> { /* no-op */ }
            }
        }

        Box(Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    RdpCanvasView(ctx).also {
                        it.surface = surface
                        it.onCursorEvent = { x, y, flags ->
                            controller.sendCursor(x, y, flags)
                        }
                        canvasView = it
                    }
                },
            )

            when (val e = event) {
                is RdpSessionEvent.VerifyCertificate -> CertificateVerifyDialog(
                    unknown = e,
                    changed = null,
                    onVerdict = { verdict ->
                        lifecycleScope.launch { certVerdictChannel.send(verdict) }
                    },
                )
                is RdpSessionEvent.VerifyChangedCertificate -> CertificateVerifyDialog(
                    unknown = null,
                    changed = e,
                    onVerdict = { verdict ->
                        lifecycleScope.launch { certVerdictChannel.send(verdict) }
                    },
                )
                is RdpSessionEvent.AuthenticationRequired -> AuthRequiredDialog {
                    finishSession()
                }
                else -> { /* no-op */ }
            }
        }
    }

    @Composable
    private fun AuthRequiredDialog(onDismiss: () -> Unit) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.rdp_auth_required_title)) },
            text = { Text(stringResource(R.string.rdp_auth_required_message)) },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.rdp_disconnect)) }
            },
        )
    }
}
