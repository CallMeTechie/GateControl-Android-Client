package com.gatecontrol.android.rdp

import android.view.MotionEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RdpCanvasViewTest {

    @Test
    fun `pointer flags for ACTION_DOWN are PTR_DOWN + PTR_LBUTTON`() {
        val flags = RdpCanvasView.cursorFlagsFor(MotionEvent.ACTION_DOWN)
        assertEquals(
            RdpCanvasView.PTR_FLAGS_DOWN or RdpCanvasView.PTR_FLAGS_BUTTON1,
            flags
        )
    }

    @Test
    fun `pointer flags for ACTION_UP are PTR_LBUTTON only`() {
        val flags = RdpCanvasView.cursorFlagsFor(MotionEvent.ACTION_UP)
        assertEquals(RdpCanvasView.PTR_FLAGS_BUTTON1, flags)
    }

    @Test
    fun `pointer flags for ACTION_MOVE are PTR_FLAGS_MOVE`() {
        val flags = RdpCanvasView.cursorFlagsFor(MotionEvent.ACTION_MOVE)
        assertEquals(RdpCanvasView.PTR_FLAGS_MOVE, flags)
    }

    @Test
    fun `canvasToRemote scales touch coordinates by surface ratio`() {
        // Surface is 1920x1080 backbuffer shown on a 960x540 view.
        val remote = RdpCanvasView.canvasToRemote(
            touchX = 480f, touchY = 270f,
            viewWidth = 960, viewHeight = 540,
            surfaceWidth = 1920, surfaceHeight = 1080
        )
        assertEquals(960, remote.first)
        assertEquals(540, remote.second)
    }
}
