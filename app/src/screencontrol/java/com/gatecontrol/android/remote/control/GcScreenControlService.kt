package com.gatecontrol.android.remote.control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.TextView
import com.gatecontrol.android.R
import com.gatecontrol.android.remote.ControlInput
import com.gatecontrol.android.remote.GesturePlan
import com.gatecontrol.android.remote.ScreenControl
import com.gatecontrol.android.remote.ScreenInput
import timber.log.Timber

/**
 * „GateControl-Fernsteuerung“ (Fernzugriff, stage 6) — the accessibility service of the **GitHub build only**
 * (src/screencontrol; the Google Play build has neither this class nor its declaration). It does nothing on its own:
 * it only registers with [ScreenControl] while Android has it bound, and [com.gatecontrol.android.remote.ScreenShareService]
 * hands it the helper's input of a session the person allowed with „Ansehen und steuern“ (mode control in force).
 *
 * Gestures go through [dispatchGesture], Back / Home / Overview are global actions, text goes into the focused input
 * field ([AccessibilityNodeInfo.ACTION_SET_TEXT] at its cursor — the only content it ever reads, and only on this phone).
 * Password fields are refused. While controlled it shows a red frame and a badge with „Steuern beenden“ over every app.
 * It never handles an accessibility event and never logs an input, a text or a coordinate.
 */
class GcScreenControlService : AccessibilityService(), ScreenControl.Executor {

    private var frame: View? = null
    private var badge: View? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        ScreenControl.attach(this)
        Timber.d("Fernzugriff: remote control service connected")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        hideOverlay()
        ScreenControl.detach(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        hideOverlay()
        ScreenControl.detach(this)
        super.onDestroy()
    }

    // nothing is read from events — the service only acts when a session asks it to
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun displaySize(): Pair<Int, Int> {
        val b = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        return b.width() to b.height()
    }

    override fun gesture(plan: GesturePlan, done: (Boolean) -> Unit) {
        val first = plan.points.firstOrNull() ?: return done(false)
        val path = Path().apply {
            moveTo(first.first, first.second)
            // a single point (tap, long press): a line of no length is what Android expects
            if (plan.points.size == 1) lineTo(first.first, first.second)
            for (p in plan.points.drop(1)) lineTo(p.first, p.second)
        }
        val stroke = runCatching { GestureDescription.StrokeDescription(path, 0L, plan.durationMs.coerceIn(1L, GestureDescription.getMaxGestureDuration())) }.getOrNull()
            ?: return done(false)
        val ok = runCatching {
            dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) = done(true)
                override fun onCancelled(gestureDescription: GestureDescription?) = done(false)
            }, null)
        }.getOrDefault(false)
        if (!ok) done(false)
    }

    override fun global(key: String): Boolean = when (key) {
        "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
        "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
        "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        else -> false
    }

    override fun edit(input: ControlInput): String? {
        val node = runCatching { rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull() ?: return "no_field"
        if (!node.isEditable) return "no_field"
        if (node.isPassword) return "secure"
        if (input is ControlInput.Key && input.key == "enter") {
            return if (node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) null else "blocked"
        }
        // a hint shown in an empty field is no text
        val current = if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
        val (text, cursor) = when (input) {
            is ControlInput.Text -> ScreenInput.insert(current, node.textSelectionStart, node.textSelectionEnd, input.text)
            is ControlInput.Key -> if (input.key == "backspace") ScreenInput.backspace(current, node.textSelectionStart, node.textSelectionEnd) else return "blocked"
            else -> return "blocked"
        }
        val set = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        })
        if (!set) return "blocked"
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
        })
        return null
    }

    // ─── The overlay while the phone is controlled ───

    private fun dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun showOverlay(viewerName: String, onStop: () -> Unit) {
        val wm = getSystemService(WindowManager::class.java)
        val text = getString(R.string.screen_control_overlay, viewerName)
        (badge as? TextView)?.let {
            it.text = text
            it.setOnClickListener { onStop() }
            return
        }
        runCatching {
            // a red frame around the whole screen — never touchable, never focusable
            val f = FrameLayout(this).apply {
                background = GradientDrawable().apply { setStroke(dp(4), LIVE_RED); setColor(Color.TRANSPARENT) }
            }
            wm.addView(f, WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ))
            frame = f
            // the badge at the top: who controls, a tap takes control away („Steuern beenden“)
            val b = TextView(this).apply {
                this.text = text
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(dp(14), dp(8), dp(14), dp(8))
                background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(LIVE_RED) }
                contentDescription = text
                setOnClickListener { onStop() }
            }
            wm.addView(b, WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = dp(36)
            })
            badge = b
        }.onFailure { Timber.d("Fernzugriff: overlay not shown (%s)", it.javaClass.simpleName) }
    }

    override fun hideOverlay() {
        val wm = getSystemService(WindowManager::class.java)
        for (v in listOfNotNull(frame, badge)) runCatching { wm.removeView(v) }
        frame = null
        badge = null
    }

    private companion object {
        /** The red of a running sharing (design: „rote Leiste“), as in the app. */
        val LIVE_RED = 0xFFC2410C.toInt()
    }
}
