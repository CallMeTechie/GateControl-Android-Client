package com.gatecontrol.android.profile

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AvatarImageTest {

    @Test
    fun `centred crop of a landscape photo is the middle square`() {
        // 4000×3000, frame 300 px: base scale 0.1, no zoom, no offset.
        assertEquals(CropRect(500, 0, 3000), AvatarCrop.sourceRect(4000, 3000, 300f, 1f, 0f, 0f))
        assertEquals(CropRect(0, 500, 3000), AvatarCrop.sourceRect(3000, 4000, 300f, 1f, 0f, 0f))
    }

    @Test
    fun `zoom and drag pick a smaller square, never outside the photo`() {
        // Zoom 2: a 1500 px square; dragged right by 50 frame px = 250 source px to the left.
        assertEquals(CropRect(1000, 750, 1500), AvatarCrop.sourceRect(4000, 3000, 300f, 2f, 50f, 0f))
        // Dragged far beyond the edge: clamped to the left edge.
        val r = AvatarCrop.sourceRect(4000, 3000, 300f, 2f, 10_000f, 10_000f)
        assertEquals(CropRect(0, 0, 1500), r)
        // Zoom is limited to 1…5.
        assertEquals(CropRect(500, 0, 3000), AvatarCrop.sourceRect(4000, 3000, 300f, 0.2f, 0f, 0f))
        assertEquals(600, AvatarCrop.sourceRect(4000, 3000, 300f, 50f, 0f, 0f).side)
    }

    @Test
    fun `offsets are limited so the frame stays covered`() {
        val (mx, my) = AvatarCrop.maxOffset(4000, 3000, 300f, 1f)
        assertEquals(50f, mx, 0.01f)
        assertEquals(0f, my, 0.01f)
        val (x, y) = AvatarCrop.clampOffset(4000, 3000, 300f, 1f, 500f, -40f)
        assertEquals(50f, x, 0.001f)
        assertEquals(0f, y, 0.001f)
    }

    @Test
    fun `encoding lowers the quality until the picture fits`() {
        val tried = mutableListOf<Int>()
        // Fake encoder: 2 KB per quality point.
        val bytes = AvatarEncoder.encodeWithin(AvatarEncoder.MAX_BYTES) { q -> tried += q; ByteArray(q * 2_000) }
        assertEquals(listOf(85, 75, 65, 55, 45), tried)
        assertEquals(90_000, bytes!!.size)
        assertTrue(bytes.size <= AvatarEncoder.MAX_BYTES)
    }

    @Test
    fun `nothing fits or the encoder fails - no picture`() {
        assertNull(AvatarEncoder.encodeWithin(1_000) { ByteArray(5_000) })
        assertNull(AvatarEncoder.encodeWithin(1_000) { null })
        assertEquals(500, AvatarEncoder.encodeWithin(1_000) { q -> if (q == 85) null else ByteArray(500) }!!.size)
        assertEquals(96_000, AvatarEncoder.MAX_BYTES)
        assertEquals(256, AvatarEncoder.SIZE)
    }

    @Test
    fun `avatars are decoded no larger than needed`() {
        assertEquals(1, AvatarLoader.sampleSize(256, 120))
        assertEquals(2, AvatarLoader.sampleSize(512, 120))
        assertEquals(1, AvatarLoader.sampleSize(512, 300))
        assertEquals(32, AvatarLoader.sampleSize(4096, 40))
        assertEquals(32, AvatarLoader.fileName("https://gc.example.com/avatars/x.webp").length)
    }
}
