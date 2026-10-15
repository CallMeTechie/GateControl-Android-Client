package com.gatecontrol.android.profile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.core.graphics.scale
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A picture ready for `PUT /client/me/avatar`. */
class EncodedAvatar(val bytes: ByteArray, val mime: String)

/** Square crop in source pixels. */
data class CropRect(val left: Int, val top: Int, val side: Int)

/**
 * The simple crop of the profile screen: the picture covers a square frame
 * (side [frame] px), the person zooms ([zoom] ≥ 1) and drags ([offsetX],
 * [offsetY] px from the centred position). Pure, unit-tested.
 */
object AvatarCrop {
    const val MAX_ZOOM = 5f

    /** Scale that makes the shorter side of the picture fill the frame. */
    fun baseScale(width: Int, height: Int, frame: Float): Float = frame / min(width, height).coerceAtLeast(1)

    /** How far the picture may move each way without uncovering the frame. */
    fun maxOffset(width: Int, height: Int, frame: Float, zoom: Float): Pair<Float, Float> {
        val s = baseScale(width, height, frame) * zoom
        return max(0f, (width * s - frame) / 2f) to max(0f, (height * s - frame) / 2f)
    }

    fun clampZoom(zoom: Float): Float = zoom.coerceIn(1f, MAX_ZOOM)

    fun clampOffset(width: Int, height: Int, frame: Float, zoom: Float, x: Float, y: Float): Pair<Float, Float> {
        val (mx, my) = maxOffset(width, height, frame, zoom)
        return x.coerceIn(-mx, mx) to y.coerceIn(-my, my)
    }

    /** The square of the source picture visible in the frame. */
    fun sourceRect(width: Int, height: Int, frame: Float, zoom: Float, offsetX: Float, offsetY: Float): CropRect {
        val z = clampZoom(zoom)
        val (ox, oy) = clampOffset(width, height, frame, z, offsetX, offsetY)
        val s = baseScale(width, height, frame) * z
        val side = (frame / s).roundToInt().coerceIn(1, min(width, height))
        val cx = width / 2f - ox / s
        val cy = height / 2f - oy / s
        val left = (cx - side / 2f).roundToInt().coerceIn(0, width - side)
        val top = (cy - side / 2f).roundToInt().coerceIn(0, height - side)
        return CropRect(left, top, side)
    }
}

/**
 * Downscale and encode for the server: 256×256, WebP lossy (JPEG as the
 * fallback), quality lowered until it fits [MAX_BYTES].
 */
object AvatarEncoder {
    const val SIZE = 256
    /** The server takes ≤ 96 KB. */
    const val MAX_BYTES = 96 * 1000
    val QUALITIES = listOf(85, 75, 65, 55, 45, 35, 25)

    /** First encoding (from the highest quality down) that fits [maxBytes], else null. Pure. */
    fun encodeWithin(maxBytes: Int, qualities: List<Int> = QUALITIES, encode: (quality: Int) -> ByteArray?): ByteArray? {
        for (q in qualities) {
            val bytes = encode(q) ?: continue
            if (bytes.isNotEmpty() && bytes.size <= maxBytes) return bytes
        }
        return null
    }

    /** Crops [source] to [rect], scales to [SIZE] and encodes it; null when that fails. */
    fun encode(source: Bitmap, rect: CropRect): EncodedAvatar? {
        val cropped = Bitmap.createBitmap(source, rect.left, rect.top, rect.side, rect.side)
        val scaled = cropped.scale(SIZE, SIZE)
        if (cropped !== source && cropped !== scaled) cropped.recycle()
        try {
            // minSdk 31: lossy WebP is always there (API 30+); JPEG only if it fails.
            encodeWithin(MAX_BYTES) { q -> compress(scaled, Bitmap.CompressFormat.WEBP_LOSSY, q) }
                ?.let { return EncodedAvatar(it, "image/webp") }
            encodeWithin(MAX_BYTES) { q -> compress(scaled, Bitmap.CompressFormat.JPEG, q) }
                ?.let { return EncodedAvatar(it, "image/jpeg") }
            return null
        } finally {
            if (scaled !== source) scaled.recycle()
        }
    }

    private fun compress(bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int): ByteArray? {
        val out = ByteArrayOutputStream(32 * 1024)
        return if (bitmap.compress(format, quality, out)) out.toByteArray() else null
    }

    /**
     * Decodes a picked photo (EXIF rotation applied by ImageDecoder), at
     * most [maxSide] px on its longer side, as a software bitmap.
     */
    fun decode(context: Context, uri: Uri, maxSide: Int = 2048): Bitmap? = runCatching {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            val longer = max(w, h)
            if (longer > maxSide) {
                val f = maxSide.toFloat() / longer
                decoder.setTargetSize((w * f).roundToInt().coerceAtLeast(1), (h * f).roundToInt().coerceAtLeast(1))
            }
        }
    }.getOrNull()
}
