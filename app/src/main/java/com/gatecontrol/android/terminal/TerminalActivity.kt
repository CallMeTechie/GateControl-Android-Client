package com.gatecontrol.android.terminal

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.ui.terminal.TerminalScreen
import com.gatecontrol.android.ui.terminal.TerminalScreenActions
import com.gatecontrol.android.ui.theme.GateControlTheme
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import javax.inject.Inject

/**
 * The SSH terminal of „Fernzugriff“ (stage 3): Termux's [TerminalView] in Compose ([TerminalScreen] via AndroidView),
 * the bar with the keys a phone keyboard lacks, pinch zoom, selection with copy and paste. The session itself lives in
 * [TermSessions] — one at a time; a rotation or a trip to another app keeps it. Unlike the RDP window the terminal may
 * rotate; screenshots are allowed unless „Bildschirmfotos im Terminal verbieten“ is on (FLAG_SECURE then).
 */
@AndroidEntryPoint
class TerminalActivity : ComponentActivity() {

    @Inject lateinit var sessions: TermSessions
    @Inject lateinit var prefs: TermPrefs
    @Inject lateinit var settingsRepository: SettingsRepository

    private var view: TerminalView? = null
    private val ctrl = mutableStateOf(Latch.OFF)
    private val alt = mutableStateOf(Latch.OFF)
    /** Text waiting for „N Zeilen einfügen?“. */
    private val pendingPaste = mutableStateOf<String?>(null)
    /** The last font change by pinch or menu (for the short hint with columns × rows). */
    private val fontHint = mutableStateOf(0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (sessions.ui.value == null) {
            finish()
            return
        }
        setContent {
            val theme by settingsRepository.getTheme().collectAsStateWithLifecycle(initialValue = "system")
            val sysLang = rememberSaveable { Locale.getDefault().language }
            val locale by settingsRepository.getLocale().collectAsStateWithLifecycle(initialValue = if (sysLang == "de") "de" else "en")
            LaunchedEffect(locale) {
                val config = Configuration(resources.configuration).apply { setLocale(Locale.forLanguageTag(locale)) }
                @Suppress("DEPRECATION")
                resources.updateConfiguration(config, resources.displayMetrics)
            }
            val settings by prefs.state.collectAsStateWithLifecycle()
            LaunchedEffect(settings.blockScreenshots) {
                if (settings.blockScreenshots) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
            val ui by sessions.ui.collectAsStateWithLifecycle()
            LaunchedEffect(ui == null) { if (ui == null) finish() }
            val dark = when (theme) {
                "dark" -> true
                "light" -> false
                else -> isSystemInDarkTheme()
            }
            GateControlTheme(darkTheme = dark) {
                val current = ui ?: return@GateControlTheme
                var confirmLeave by rememberSaveable { mutableStateOf(false) }
                BackHandler {
                    if (current.phase is TermPhase.Ready || current.phase is TermPhase.Connecting || current.phase is TermPhase.Reconnecting) confirmLeave = true
                    else sessions.disconnect()
                }
                TerminalScreen(
                    ui = current,
                    settings = settings,
                    ctrl = ctrl.value,
                    alt = alt.value,
                    pendingPaste = pendingPaste.value,
                    fontHint = fontHint.value,
                    confirmLeave = confirmLeave,
                    actions = TerminalScreenActions(
                        createView = ::createView,
                        releaseView = ::releaseView,
                        onKey = ::pressKey,
                        onPaste = ::pasteFromClipboard,
                        onPasteConfirm = { text, never ->
                            pendingPaste.value = null
                            if (never) prefs.setAskPaste(false)
                            sessions.paste(text)
                        },
                        onPasteCancel = { pendingPaste.value = null },
                        onCopyAll = ::copyAll,
                        onFont = ::setFont,
                        onExtraKeys = prefs::setExtraKeys,
                        onTrust = sessions::answerHostKey,
                        onReconnect = sessions::reconnectNow,
                        onLeave = { confirmLeave = true },
                        onLeaveCancel = { confirmLeave = false },
                        onDisconnect = {
                            confirmLeave = false
                            sessions.disconnect()
                        },
                    ),
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        sessions.onForeground()
    }

    override fun onDestroy() {
        releaseView()
        super.onDestroy()
    }

    // ─── The view ───

    private val sessionClient = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {
            view?.onScreenUpdated()
        }

        override fun onTitleChanged(changedSession: TerminalSession) = Unit
        override fun onSessionFinished(finishedSession: TerminalSession) = Unit

        /** The person's own copy from the selection. */
        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
            if (!text.isNullOrEmpty()) copy(text)
        }

        override fun onPasteTextFromClipboard(session: TerminalSession?) = pasteFromClipboard()
        override fun onBell(session: TerminalSession) = Unit
        override fun onColorsChanged(session: TerminalSession) {
            view?.invalidate()
        }

        override fun onTerminalCursorStateChange(state: Boolean) = Unit
        override fun getTerminalCursorStyle(): Int? = null

        // the emulator's notes can carry pieces of the terminal's content: never logged
        override fun logError(tag: String?, message: String?) = Unit
        override fun logWarn(tag: String?, message: String?) = Unit
        override fun logInfo(tag: String?, message: String?) = Unit
        override fun logDebug(tag: String?, message: String?) = Unit
        override fun logVerbose(tag: String?, message: String?) = Unit
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = Unit
        override fun logStackTrace(tag: String?, e: Exception?) = Unit
    }

    private val viewClient = object : TerminalViewClient {
        /** Pinch: one step of the font per clear change of the spread (8…24). */
        override fun onScale(scale: Float): Float {
            if (scale < 0.9f || scale > 1.1f) {
                setFont(prefs.state.value.fontSp + if (scale > 1f) 1 else -1)
                return 1.0f
            }
            return scale
        }

        override fun onSingleTapUp(e: MotionEvent?) {
            val v = view ?: return
            v.requestFocus()
            getSystemService(InputMethodManager::class.java)?.showSoftInput(v, 0)
        }

        override fun shouldBackButtonBeMappedToEscape(): Boolean = false
        /** Visible-password input: keyboards make no suggestions and learn nothing typed in the terminal. */
        override fun shouldEnforceCharBasedInput(): Boolean = true
        override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
        override fun isTerminalViewSelected(): Boolean = true
        override fun copyModeChanged(copyMode: Boolean) = Unit
        override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean = false
        override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false
        override fun onLongPress(event: MotionEvent?): Boolean = false

        override fun readControlKey(): Boolean {
            val on = ctrl.value != Latch.OFF
            ctrl.value = ctrl.value.used()
            return on
        }

        override fun readAltKey(): Boolean {
            val on = alt.value != Latch.OFF
            alt.value = alt.value.used()
            return on
        }

        override fun readShiftKey(): Boolean = false
        override fun readFnKey(): Boolean = false
        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean = false
        override fun onEmulatorSet() = Unit
        override fun logError(tag: String?, message: String?) = Unit
        override fun logWarn(tag: String?, message: String?) = Unit
        override fun logInfo(tag: String?, message: String?) = Unit
        override fun logDebug(tag: String?, message: String?) = Unit
        override fun logVerbose(tag: String?, message: String?) = Unit
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = Unit
        override fun logStackTrace(tag: String?, e: Exception?) = Unit
    }

    private fun createView(context: Context): TerminalView {
        val v = TerminalView(context, null)
        v.setTerminalViewClient(viewClient)
        v.setTextSize(fontPx(prefs.state.value.fontSp))
        ResourcesCompat.getFont(context, com.gatecontrol.android.core.designsystem.R.font.jetbrains_mono_regular)?.let { v.setTypeface(it) }
        v.isFocusable = true
        v.isFocusableInTouchMode = true
        sessions.terminal(sessionClient)?.let { v.attachSession(it) }
        v.requestFocus()
        view = v
        return v
    }

    private fun releaseView() {
        view = null
        sessions.detach(sessionClient)
    }

    private fun fontPx(sp: Int): Int = (sp * resources.displayMetrics.scaledDensity).toInt().coerceAtLeast(6)

    private fun setFont(sp: Int) {
        val next = sp.coerceIn(TermPrefs.FONT_MIN, TermPrefs.FONT_MAX)
        if (next == prefs.state.value.fontSp) return
        prefs.setFont(next)
        view?.setTextSize(fontPx(next))
        fontHint.value = System.currentTimeMillis()
    }

    // ─── The bar ───

    private fun pressKey(key: TermKey) {
        val v = view ?: return
        when {
            key.modifier == TermKey.CTRL -> ctrl.value = ctrl.value.tapped()
            key.modifier == TermKey.ALT -> alt.value = alt.value.tapped()
            key.code != 0 -> {
                var mod = 0
                if (ctrl.value != Latch.OFF) mod = mod or KeyHandler.KEYMOD_CTRL
                if (alt.value != Latch.OFF) mod = mod or KeyHandler.KEYMOD_ALT
                ctrl.value = ctrl.value.used()
                alt.value = alt.value.used()
                if (v.mEmulator != null) v.handleKeyCode(key.code, mod)
            }
            key.char != 0 -> if (v.mEmulator != null) v.inputCodePoint(key.char, false, false)
        }
    }

    // ─── Copy and paste ───

    private fun copy(text: String) {
        val cm = getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText("Terminal", text)
        // what a terminal shows is often a configuration or a key: no preview of it on the screen (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        }
        cm.setPrimaryClip(clip)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            android.widget.Toast.makeText(this, R.string.term_copied, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyAll() {
        val text = view?.mEmulator?.screen?.transcriptText?.trimEnd() ?: return
        if (text.isNotEmpty()) copy(text)
    }

    /** From the clipboard; more than one line asks first (decision 6) unless „Nicht mehr fragen“. */
    private fun pasteFromClipboard() {
        val cm = getSystemService(ClipboardManager::class.java) ?: return
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString() ?: return
        if (text.isEmpty()) return
        if (prefs.state.value.askPaste && TermProtocol.pasteLines(text) > 1) pendingPaste.value = text
        else sessions.paste(text)
    }

    companion object {
        fun open(context: Context) {
            context.startActivity(Intent(context, TerminalActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
    }
}
