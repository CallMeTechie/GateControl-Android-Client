package com.gatecontrol.android.rdp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Custom view that owns the Bitmap backbuffer for a FreeRDP session.
 *
 * FreeRDP native code writes pixel data directly into [surface] via JNI.
 * Our only job on the Java side is to [invalidateSurfaceRegion] when
 * `OnGraphicsUpdate` fires.
 *
 * Touch events are translated into FreeRDP cursor events via [onCursorEvent].
 * The view auto-scales the backbuffer to fit its measured size.
 */
class RdpCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    companion object {
        // FreeRDP cursor flag bits (from freerdp/input.h)
        const val PTR_FLAGS_MOVE = 0x0800
        const val PTR_FLAGS_DOWN = 0x8000
        const val PTR_FLAGS_BUTTON1 = 0x1000  // Left
        const val PTR_FLAGS_BUTTON2 = 0x2000  // Right
        const val PTR_FLAGS_BUTTON3 = 0x4000  // Middle

        fun cursorFlagsFor(action: Int): Int = when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN ->
                PTR_FLAGS_DOWN or PTR_FLAGS_BUTTON1
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP ->
                PTR_FLAGS_BUTTON1
            MotionEvent.ACTION_MOVE ->
                PTR_FLAGS_MOVE
            else -> 0
        }

        fun canvasToRemote(
            touchX: Float,
            touchY: Float,
            viewWidth: Int,
            viewHeight: Int,
            surfaceWidth: Int,
            surfaceHeight: Int,
        ): Pair<Int, Int> {
            if (viewWidth <= 0 || viewHeight <= 0) return 0 to 0
            val rx = (touchX / viewWidth.toFloat() * surfaceWidth).toInt()
                .coerceIn(0, surfaceWidth - 1)
            val ry = (touchY / viewHeight.toFloat() * surfaceHeight).toInt()
                .coerceIn(0, surfaceHeight - 1)
            return rx to ry
        }
    }

    /** Set by the hosting activity when `OnGraphicsResize` fires. */
    var surface: Bitmap? = null
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    /** Called by the hosting activity to forward touch input to FreeRDP. */
    var onCursorEvent: ((x: Int, y: Int, flags: Int) -> Unit)? = null

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { isAntiAlias = true }
    private val srcRect = Rect()
    private val dstRect = Rect()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = surface ?: return
        srcRect.set(0, 0, bmp.width, bmp.height)
        dstRect.set(0, 0, width, height)
        canvas.drawBitmap(bmp, srcRect, dstRect, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val bmp = surface
        val dispatch = onCursorEvent
        if (bmp == null || dispatch == null) return false
        val (rx, ry) = canvasToRemote(
            touchX = event.x,
            touchY = event.y,
            viewWidth = width,
            viewHeight = height,
            surfaceWidth = bmp.width,
            surfaceHeight = bmp.height,
        )
        val flags = cursorFlagsFor(event.actionMasked)
        if (flags != 0) {
            dispatch(rx, ry, flags)
        }
        return true
    }

    /** Call from `OnGraphicsUpdate` to invalidate only the dirty rect. */
    fun invalidateSurfaceRegion(x: Int, y: Int, w: Int, h: Int) {
        val bmp = surface ?: return
        if (width <= 0 || height <= 0) return
        val sx = x * width / bmp.width
        val sy = y * height / bmp.height
        val sw = (w + 1) * width / bmp.width
        val sh = (h + 1) * height / bmp.height
        postInvalidateOnAnimation(sx, sy, sx + sw, sy + sh)
    }
}
