package com.gatecontrol.android.rdp

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.gatecontrol.android.R
import com.gatecontrol.android.service.RdpSessionService
import timber.log.Timber

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

    private var params: RdpConnectionParams? = null
    private var sessionStartMs: Long = 0L

    // FreeRDP references — populated when AAR is available
    private var freerdpSession: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Security: prevent screenshots and screen recording
        window.setFlags(WINDOW_FLAG_SECURE, WINDOW_FLAG_SECURE)

        // Immersive fullscreen
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        params = parseConnectionParams(intent)
        if (params == null) {
            Timber.e("RdpSessionActivity: no connection params in intent")
            finish()
            return
        }

        sessionStartMs = System.currentTimeMillis()

        // Create layout: FrameLayout for FreeRDP SessionView + floating toolbar
        val rootLayout = FrameLayout(this)
        setContentView(rootLayout)

        // Start FreeRDP connection
        initFreeRdpSession(rootLayout, params!!)

        // Start foreground service
        startRdpService()

        // Handle back press with confirmation dialog
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                showDisconnectConfirmation()
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnectFreeRdp()
        stopRdpService()
        // Wipe credentials from memory
        params = null
    }

    // ──────────────────────────────────────────────────────────────────────
    // ⚠ PHASE-2 PLACEHOLDER — NOT FUNCTIONAL ⚠
    //
    // The reflection code below targets an imagined LibFreeRDP API and is
    // kept only as a structural reference for the real Phase-2 rewrite:
    //
    //   * `setConnectionInfo(long, String, Int, String, ...)` with 15
    //     primitive parameters does NOT exist in upstream LibFreeRDP.
    //     Real API: `setConnectionInfo(Context, long, BookmarkBase)` or
    //               `setConnectionInfo(Context, long, Uri)`.
    //   * `freeSession(long)` does NOT exist. Real API: `freeInstance(long)`.
    //   * Missing entirely: EventListener wiring, Bitmap/updateGraphics
    //     rendering pipeline, sendCursorEvent / sendKeyEvent input forwarding.
    //
    // This Activity is therefore never reached at runtime —
    // `RdpEmbeddedClient.PHASE_2_ENABLED` gates `isAvailable()` to `false`,
    // so `RdpManager` always routes through `RdpExternalClient`.
    //
    // See `docs/FREERDP_INTEGRATION.md` → "Known Gaps" for the Phase-2 plan.
    // ──────────────────────────────────────────────────────────────────────
    private fun initFreeRdpSession(container: FrameLayout, params: RdpConnectionParams) {
        try {
            // Check if FreeRDP is available
            val libClass = Class.forName("com.freerdp.freerdpcore.services.LibFreeRDP")

            // Initialize FreeRDP session via reflection
            val newInstanceMethod = libClass.getMethod("newInstance", android.content.Context::class.java)
            freerdpSession = newInstanceMethod.invoke(null, this) as Long

            // Configure connection settings
            configureFreeRdpSettings(libClass, params)

            // Create SessionView and add to container
            val sessionViewClass = Class.forName("com.freerdp.freerdpcore.presentation.SessionView")
            val sessionView = sessionViewClass
                .getConstructor(android.content.Context::class.java)
                .newInstance(this) as View
            container.addView(sessionView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))

            // Connect
            val connectMethod = libClass.getMethod("connect", Long::class.javaPrimitiveType)
            connectMethod.invoke(null, freerdpSession)

            Timber.i("FreeRDP session started for ${params.routeName}")
        } catch (e: ClassNotFoundException) {
            Timber.e("FreeRDP library not found — cannot start embedded session")
            Toast.makeText(this, getString(R.string.rdp_embedded_connection_lost), Toast.LENGTH_LONG).show()
            finish()
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize FreeRDP session")
            Toast.makeText(this, getString(R.string.rdp_embedded_connection_lost), Toast.LENGTH_LONG).show()
            finish()
        }

        // Add floating toolbar
        addFloatingToolbar(container)
    }

    private fun configureFreeRdpSettings(libClass: Class<*>, params: RdpConnectionParams) {
        val setMethod = libClass.getMethod(
            "setConnectionInfo",
            Long::class.javaPrimitiveType,     // session
            String::class.java,                 // hostname
            Int::class.javaPrimitiveType,       // port
            String::class.java,                 // username
            String::class.java,                 // password
            String::class.java,                 // domain
            Int::class.javaPrimitiveType,       // width
            Int::class.javaPrimitiveType,       // height
            Int::class.javaPrimitiveType,       // colorDepth
            Boolean::class.javaPrimitiveType,   // redirectClipboard
            Boolean::class.javaPrimitiveType,   // redirectPrinters
            Boolean::class.javaPrimitiveType,   // redirectDrives
            String::class.java,                 // audioMode
            Boolean::class.javaPrimitiveType,   // adminSession
            Int::class.javaPrimitiveType        // autoReconnectMaxRetries
        )
        setMethod.invoke(
            null,
            freerdpSession,
            params.host,
            params.port,
            params.username ?: "",
            params.password ?: "",
            params.domain ?: "",
            params.resolutionWidth,
            params.resolutionHeight,
            params.colorDepth,
            params.redirectClipboard,
            params.redirectPrinters,
            params.redirectDrives,
            params.audioMode,
            params.adminSession,
            5 // auto-reconnect max retries
        )
    }

    private fun addFloatingToolbar(container: FrameLayout) {
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xCC333333.toInt())
            setPadding(16, 8, 16, 8)
        }

        // Disconnect button
        val disconnectBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setOnClickListener { showDisconnectConfirmation() }
            contentDescription = getString(R.string.rdp_disconnect)
        }
        toolbar.addView(disconnectBtn)

        // Keyboard toggle button
        val keyboardBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_edit)
            setOnClickListener { toggleSoftKeyboard() }
            contentDescription = "Keyboard"
        }
        toolbar.addView(keyboardBtn)

        val layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
            topMargin = 16
        }

        container.addView(toolbar, layoutParams)
    }

    private fun showDisconnectConfirmation() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.rdp_embedded_disconnect_confirm))
            .setMessage(getString(R.string.rdp_embedded_disconnect_message))
            .setPositiveButton(getString(R.string.rdp_disconnect)) { _, _ -> finish() }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun disconnectFreeRdp() {
        if (freerdpSession != 0L) {
            try {
                val libClass = Class.forName("com.freerdp.freerdpcore.services.LibFreeRDP")
                val disconnectMethod = libClass.getMethod("disconnect", Long::class.javaPrimitiveType)
                disconnectMethod.invoke(null, freerdpSession)
                val freeMethod = libClass.getMethod("freeSession", Long::class.javaPrimitiveType)
                freeMethod.invoke(null, freerdpSession)
            } catch (e: Exception) {
                Timber.w(e, "Error disconnecting FreeRDP session")
            }
            freerdpSession = 0L
        }
    }

    private fun startRdpService() {
        val serviceIntent = Intent(this, RdpSessionService::class.java).apply {
            putExtra(RdpSessionService.EXTRA_ROUTE_NAME, params?.routeName ?: "")
            putExtra(RdpSessionService.EXTRA_CONNECTED_SINCE, sessionStartMs)
        }
        startForegroundService(serviceIntent)
    }

    private fun stopRdpService() {
        stopService(Intent(this, RdpSessionService::class.java))
    }

    private fun toggleSoftKeyboard() {
        val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        imm.toggleSoftInput(
            android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT,
            0
        )
    }
}
