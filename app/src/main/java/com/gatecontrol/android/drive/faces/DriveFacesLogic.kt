package com.gatecontrol.android.drive.faces

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The pure arithmetic of the face features (Drive 1.16, model `sface-2021dec`; see the scratch file drive-face-model.md):
 * YuNet's output decoded as OpenCV's `FaceDetectorYN` does, the 5 points aligned to SFace's 112×112 template as
 * `FaceRecognizerSF::alignCrop` does (similarity transform, bilinear), the feature normalised and sent as base64 of
 * float32 little endian. No Android here: pictures are ARGB ints, so it runs in unit tests.
 */
object DriveFacesLogic {

    /** The model id Drive knows these vectors by. */
    const val MODEL = "sface-2021dec"
    const val FEATURES = 128
    const val ALIGNED = 112
    /** YuNet's input (the 2023mar model has a fixed 640×640 input). */
    const val DETECT_SIZE = 640
    const val SCORE_MIN = 0.8f
    const val NMS_IOU = 0.3f
    /** A face smaller than this (px of the picture the points are in) gives no usable feature. */
    const val MIN_FACE_PX = 32f
    const val MAX_FACES = 50

    /** SFace's five points in 112×112 (eyes, nose, mouth corners) and their mean. */
    private val TEMPLATE = doubleArrayOf(38.2946, 51.6963, 73.5318, 51.5014, 56.0252, 71.7366, 41.5493, 92.3655, 70.7299, 92.2041)
    private const val TEMPLATE_MX = 56.0262
    private const val TEMPLATE_MY = 71.9008

    /** A face YuNet found: box (x, y, w, h), the 5 points (x, y each), score — in pixels of its input. */
    data class Face(val x: Float, val y: Float, val w: Float, val h: Float, val points: FloatArray, val score: Float) {
        fun scaled(f: Float) = Face(x * f, y * f, w * f, h * f, FloatArray(points.size) { points[it] * f }, score)

        override fun equals(other: Any?): Boolean =
            other is Face && x == other.x && y == other.y && w == other.w && h == other.h && score == other.score && points.contentEquals(other.points)

        override fun hashCode(): Int = ((x.hashCode() * 31 + y.hashCode()) * 31 + w.hashCode()) * 31 + points.contentHashCode()
    }

    // ─── detection (YuNet) ──────────────────────────────────────────

    /** The factor a picture of [w]×[h] is scaled by for YuNet (long side 640, never larger). */
    fun detectScale(w: Int, h: Int): Float = min(1f, DETECT_SIZE.toFloat() / max(w, h).coerceAtLeast(1))

    /**
     * YuNet's input: the (scaled) picture top left in a 640×640 field, the rest 0; NCHW float, **BGR**, 0…255.
     * [pixels] ARGB of [w]×[h] (at most 640 each).
     */
    fun detectInput(pixels: IntArray, w: Int, h: Int): FloatArray {
        val n = DETECT_SIZE * DETECT_SIZE
        val out = FloatArray(3 * n)
        for (y in 0 until min(h, DETECT_SIZE)) {
            for (x in 0 until min(w, DETECT_SIZE)) {
                val p = pixels[y * w + x]
                val i = y * DETECT_SIZE + x
                out[i] = (p and 0xFF).toFloat()
                out[n + i] = (p shr 8 and 0xFF).toFloat()
                out[2 * n + i] = (p shr 16 and 0xFF).toFloat()
            }
        }
        return out
    }

    /**
     * The faces of YuNet's outputs `cls_s`, `obj_s`, `bbox_s`, `kps_s` (s = 8, 16, 32) for a [size]×[size] input, as
     * OpenCV decodes them, then non-maximum suppression; best first, at most [MAX_FACES].
     */
    fun decode(out: (String) -> FloatArray?, size: Int = DETECT_SIZE, threshold: Float = SCORE_MIN, iou: Float = NMS_IOU): List<Face> {
        val found = ArrayList<Face>()
        for (s in intArrayOf(8, 16, 32)) {
            val cls = out("cls_$s") ?: continue
            val obj = out("obj_$s") ?: continue
            val bbox = out("bbox_$s") ?: continue
            val kps = out("kps_$s") ?: continue
            val cols = size / s
            val rows = size / s
            val count = min(rows * cols, min(cls.size, obj.size))
            for (idx in 0 until count) {
                val score = sqrt(cls[idx].coerceIn(0f, 1f) * obj[idx].coerceIn(0f, 1f))
                if (score < threshold) continue
                if (bbox.size < idx * 4 + 4 || kps.size < idx * 10 + 10) continue
                val r = idx / cols
                val c = idx % cols
                val cx = (c + bbox[idx * 4]) * s
                val cy = (r + bbox[idx * 4 + 1]) * s
                val w = exp(bbox[idx * 4 + 2]) * s
                val h = exp(bbox[idx * 4 + 3]) * s
                val pts = FloatArray(10) { k -> (kps[idx * 10 + k] + if (k % 2 == 0) c else r) * s }
                found += Face(cx - w / 2f, cy - h / 2f, w, h, pts, score)
            }
        }
        return nms(found, iou).take(MAX_FACES)
    }

    /** OpenCV's `NMSBoxes` on boxes cut to whole pixels: best first, a box overlapping a kept one by more than [iou] goes. */
    fun nms(faces: List<Face>, iou: Float): List<Face> {
        val sorted = faces.sortedByDescending { it.score }
        val kept = ArrayList<Face>()
        for (f in sorted) {
            if (kept.none { overlap(it, f) > iou }) kept += f
        }
        return kept
    }

    private fun overlap(a: Face, b: Face): Float {
        val ax = a.x.toInt(); val ay = a.y.toInt(); val aw = a.w.toInt(); val ah = a.h.toInt()
        val bx = b.x.toInt(); val by = b.y.toInt(); val bw = b.w.toInt(); val bh = b.h.toInt()
        val iw = min(ax + aw, bx + bw) - max(ax, bx)
        val ih = min(ay + ah, by + bh) - max(ay, by)
        if (iw <= 0 || ih <= 0) return 0f
        val inter = iw.toFloat() * ih
        val union = aw.toFloat() * ah + bw.toFloat() * bh - inter
        return if (union <= 0f) 0f else inter / union
    }

    /** Whether a face (pixels of the picture its points are in) is big enough and lies mostly in the picture. */
    fun usable(f: Face, w: Int, h: Int): Boolean {
        if (f.w < MIN_FACE_PX || f.h < MIN_FACE_PX) return false
        val cx = f.x + f.w / 2f
        val cy = f.y + f.h / 2f
        return cx in 0f..w.toFloat() && cy in 0f..h.toFloat()
    }

    /** The box `[x, y, w, h]` and the points as 0…1 of a [w]×[h] picture (what Drive takes). */
    fun normalized(f: Face, w: Int, h: Int): Pair<List<Float>, List<Float>> {
        val fw = w.toFloat()
        val fh = h.toFloat()
        val x0 = f.x.coerceIn(0f, fw)
        val y0 = f.y.coerceIn(0f, fh)
        val x1 = (f.x + f.w).coerceIn(0f, fw)
        val y1 = (f.y + f.h).coerceIn(0f, fh)
        val box = listOf(x0 / fw, y0 / fh, (x1 - x0) / fw, (y1 - y0) / fh)
        val pts = f.points.mapIndexed { i, v -> (v / if (i % 2 == 0) fw else fh).coerceIn(0f, 1f) }
        return box to pts
    }

    // ─── alignment ──────────────────────────────────────────────────

    /**
     * The similarity transform (rotation, uniform scale, shift; no mirroring) taking the 5 [points] (x, y each) onto
     * SFace's template, least squares — the same as OpenCV's Umeyama: `[a, -b, tx, b, a, ty]` (row-major 2×3).
     */
    fun similarity(points: FloatArray): DoubleArray {
        require(points.size == 10) { "5 points" }
        var mx = 0.0
        var my = 0.0
        for (i in 0 until 5) {
            mx += points[2 * i]
            my += points[2 * i + 1]
        }
        mx /= 5
        my /= 5
        var variance = 0.0
        var sa = 0.0
        var sb = 0.0
        for (i in 0 until 5) {
            val sx = points[2 * i] - mx
            val sy = points[2 * i + 1] - my
            val dx = TEMPLATE[2 * i] - TEMPLATE_MX
            val dy = TEMPLATE[2 * i + 1] - TEMPLATE_MY
            variance += sx * sx + sy * sy
            sa += sx * dx + sy * dy
            sb += sx * dy - sy * dx
        }
        if (variance <= 1e-12) return doubleArrayOf(1.0, 0.0, TEMPLATE_MX - mx, 0.0, 1.0, TEMPLATE_MY - my)
        val a = sa / variance
        val b = sb / variance
        return doubleArrayOf(a, -b, TEMPLATE_MX - (a * mx - b * my), b, a, TEMPLATE_MY - (b * mx + a * my))
    }

    /** The inverse of a 2×3 affine matrix (row-major). */
    fun invert(m: DoubleArray): DoubleArray {
        val det = m[0] * m[4] - m[1] * m[3]
        require(det != 0.0) { "singular" }
        val ia = m[4] / det
        val ib = -m[1] / det
        val id = -m[3] / det
        val ie = m[0] / det
        return doubleArrayOf(ia, ib, -(ia * m[2] + ib * m[5]), id, ie, -(id * m[2] + ie * m[5]))
    }

    /**
     * The 112×112 face of [pixels] (ARGB, [w]×[h]) under [m] (picture → 112×112), as `warpAffine(INTER_LINEAR,
     * BORDER_CONSTANT 0)`: each target pixel samples the picture at `m⁻¹·(x, y)` bilinearly (pixel centres at whole
     * numbers, outside is 0), rounded to 0…255. Out: RGB bytes (0…255 as Int), row by row.
     */
    fun warp(pixels: IntArray, w: Int, h: Int, m: DoubleArray, size: Int = ALIGNED): IntArray {
        val inv = invert(m)
        val out = IntArray(size * size * 3)
        fun px(xx: Int, yy: Int): Int = if (xx < 0 || yy < 0 || xx >= w || yy >= h) 0 else pixels[yy * w + xx]
        for (y in 0 until size) {
            for (x in 0 until size) {
                val sx = inv[0] * x + inv[1] * y + inv[2]
                val sy = inv[3] * x + inv[4] * y + inv[5]
                val x0 = floor(sx).toInt()
                val y0 = floor(sy).toInt()
                val fx = sx - x0
                val fy = sy - y0
                val p00 = px(x0, y0)
                val p01 = px(x0 + 1, y0)
                val p10 = px(x0, y0 + 1)
                val p11 = px(x0 + 1, y0 + 1)
                val w00 = (1 - fx) * (1 - fy)
                val w01 = fx * (1 - fy)
                val w10 = (1 - fx) * fy
                val w11 = fx * fy
                val o = (y * size + x) * 3
                for (ch in 0 until 3) {
                    val shift = 16 - 8 * ch // R, G, B
                    val v = (p00 shr shift and 0xFF) * w00 + (p01 shr shift and 0xFF) * w01 + (p10 shr shift and 0xFF) * w10 + (p11 shr shift and 0xFF) * w11
                    out[o + ch] = floor(v + 0.5).toInt().coerceIn(0, 255)
                }
            }
        }
        return out
    }

    /** SFace's input of an aligned face (RGB bytes): NCHW float, **RGB**, 0…255 (the model normalises itself). */
    fun featureInput(rgb: IntArray, size: Int = ALIGNED): FloatArray {
        val n = size * size
        val out = FloatArray(3 * n)
        for (i in 0 until n) {
            out[i] = rgb[i * 3].toFloat()
            out[n + i] = rgb[i * 3 + 1].toFloat()
            out[2 * n + i] = rgb[i * 3 + 2].toFloat()
        }
        return out
    }

    // ─── the feature ────────────────────────────────────────────────

    /** [v] with length 1 (a zero vector stays as it is). */
    fun l2(v: FloatArray): FloatArray {
        var sum = 0.0
        for (x in v) sum += x.toDouble() * x
        val n = sqrt(sum)
        if (n <= 0.0) return v.copyOf()
        return FloatArray(v.size) { (v[it] / n).toFloat() }
    }

    fun cosine(a: FloatArray, b: FloatArray): Double {
        require(a.size == b.size)
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i].toDouble() * b[i]
            na += a[i].toDouble() * a[i]
            nb += b[i].toDouble() * b[i]
        }
        return if (na == 0.0 || nb == 0.0) 0.0 else dot / sqrt(na * nb)
    }

    /** base64 (standard alphabet, padded, one line) of [v] as float32 little endian — Drive's `embedding`. */
    fun encode(v: FloatArray): String {
        val buf = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        v.forEach { buf.putFloat(it) }
        return Base64.getEncoder().encodeToString(buf.array())
    }

    /** The other way round; null when it is no whole number of floats. */
    fun decodeVector(s: String): FloatArray? {
        val bytes = runCatching { Base64.getDecoder().decode(s) }.getOrNull() ?: return null
        if (bytes.isEmpty() || bytes.size % 4 != 0) return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / 4) { buf.getFloat() }
    }
}
