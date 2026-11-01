package com.gatecontrol.android.drive.faces

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Drive 1.16 faces (`sface-2021dec`): the alignment as OpenCV's `FaceRecognizerSF::alignCrop`, YuNet's decoding as
 * `FaceDetectorYN`, the feature's normalisation and base64. Expected values come from OpenCV 5.0 (Python), see the
 * scratch file drive-face-model.md.
 */
class DriveFacesLogicTest {

    private fun close(expected: DoubleArray, actual: DoubleArray, eps: Double) {
        assertEquals(expected.size, actual.size)
        expected.indices.forEach { assertEquals(expected[it], actual[it], eps, "index $it") }
    }

    // ─── alignment ──────────────────────────────────────────────────

    @Test
    fun `similarity of the test picture's points is OpenCV's matrix`() {
        // Alvaro_Uribe_0028.jpg of OpenCV Zoo's face_recognition benchmark data
        val m = DriveFacesLogic.similarity(floatArrayOf(65f, 68f, 89f, 67f, 81f, 80f, 68f, 93f, 89f, 92f))
        close(doubleArrayOf(1.525415679, -0.0724858342, -57.7675225022, 0.0724858342, 1.525415679, -55.8153437199), m, 1e-4)
    }

    @Test
    fun `similarity of turned points rotates back and keeps mirrored points unmirrored`() {
        val rot = DriveFacesLogic.similarity(floatArrayOf(300f, 200f, 340f, 240f, 300f, 260f, 250f, 270f, 280f, 300f))
        close(doubleArrayOf(0.3828282783, 0.345776347, -144.3525059567, -0.345776347, 0.3828282783, 76.3206633458), rot, 1e-4)
        val mirror = DriveFacesLogic.similarity(floatArrayOf(89f, 67f, 65f, 68f, 81f, 80f, 89f, 92f, 68f, 93f))
        close(doubleArrayOf(0.25154076395, -0.023279770329, 38.167785732, 0.023279770329, 0.25154076395, 49.95240489), mirror, 1e-4)
        // a similarity: a = e, b = -d (no shear, no mirroring)
        assertEquals(mirror[0], mirror[4], 1e-12)
        assertEquals(mirror[1], -mirror[3], 1e-12)
    }

    @Test
    fun `the template's own points map onto themselves`() {
        val t = floatArrayOf(38.2946f, 51.6963f, 73.5318f, 51.5014f, 56.0252f, 71.7366f, 41.5493f, 92.3655f, 70.7299f, 92.2041f)
        close(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0), DriveFacesLogic.similarity(t), 1e-3)
    }

    @Test
    fun `the inverse undoes the matrix`() {
        val m = doubleArrayOf(1.5, -0.2, -50.0, 0.2, 1.5, -40.0)
        val inv = DriveFacesLogic.invert(m)
        val x = 30.0
        val y = 70.0
        val tx = m[0] * x + m[1] * y + m[2]
        val ty = m[3] * x + m[4] * y + m[5]
        assertEquals(x, inv[0] * tx + inv[1] * ty + inv[2], 1e-9)
        assertEquals(y, inv[3] * tx + inv[4] * ty + inv[5], 1e-9)
    }

    /** OpenCV's warpAffine of a pattern picture (BGR b = x·y, g = x² + y, r = 7x + 3y, each mod 256). */
    private fun pattern(w: Int, h: Int): IntArray = IntArray(w * h) { i ->
        val x = i % w
        val y = i / w
        val r = (x * 7 + y * 3) % 256
        val g = (x * x + y) % 256
        val b = (x * y) % 256
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    @Test
    fun `warp samples like warpAffine INTER_LINEAR`() {
        val m = doubleArrayOf(1.525415679, -0.0724858342, -57.7675225022, 0.0724858342, 1.525415679, -55.8153437199)
        val out = DriveFacesLogic.warp(pattern(150, 150), 150, 150, m)
        assertEquals(112 * 112 * 3, out.size)
        // OpenCV 5.0: RGB at (x, y); OpenCV rounds in fixed point, so ±2 on this hard-edged pattern
        val expected = mapOf(
            (0 to 0) to intArrayOf(125, 61, 92),
            (56 to 56) to intArrayOf(242, 21, 56),
            (10 to 100) to intArrayOf(132, 170, 84),
            (111 to 111) to intArrayOf(97, 149, 101),
            (80 to 30) to intArrayOf(37, 212, 202),
        )
        for ((at, rgb) in expected) {
            val o = (at.second * 112 + at.first) * 3
            for (c in 0 until 3) assertTrue(abs(out[o + c] - rgb[c]) <= 2, "pixel $at channel $c: ${out[o + c]} vs ${rgb[c]}")
        }
        // the whole picture within 0.5 % of OpenCV's sum
        assertEquals(4_806_068.0, out.sum().toDouble(), 4_806_068.0 * 0.005)
    }

    @Test
    fun `outside the picture is black`() {
        val m = doubleArrayOf(1.0, 0.0, 200.0, 0.0, 1.0, 200.0) // the picture lies far right and below
        val out = DriveFacesLogic.warp(IntArray(4) { -1 }, 2, 2, m)
        assertTrue(out.all { it == 0 })
    }

    @Test
    fun `feature input is planar RGB and detect input planar BGR`() {
        val rgb = IntArray(112 * 112 * 3) { i -> i % 3 * 100 }
        val f = DriveFacesLogic.featureInput(rgb)
        val n = 112 * 112
        assertEquals(0f, f[0])
        assertEquals(100f, f[n])
        assertEquals(200f, f[2 * n])
        val px = intArrayOf((0xFF shl 24) or (10 shl 16) or (20 shl 8) or 30)
        val d = DriveFacesLogic.detectInput(px, 1, 1)
        val m = 640 * 640
        assertEquals(30f, d[0]) // B
        assertEquals(20f, d[m]) // G
        assertEquals(10f, d[2 * m]) // R
        assertEquals(0f, d[1])
    }

    // ─── the feature ────────────────────────────────────────────────

    @Test
    fun `l2 gives length one and leaves zero alone`() {
        val v = DriveFacesLogic.l2(floatArrayOf(3f, 4f))
        assertEquals(0.6f, v[0], 1e-6f)
        assertEquals(0.8f, v[1], 1e-6f)
        assertArrayEquals(floatArrayOf(0f, 0f), DriveFacesLogic.l2(floatArrayOf(0f, 0f)))
        val big = DriveFacesLogic.l2(FloatArray(128) { it - 64f })
        assertEquals(1.0, sqrt(big.sumOf { it.toDouble() * it }), 1e-6)
    }

    @Test
    fun `embedding is base64 of float32 little endian`() {
        assertEquals("AAAAPwAAgL4AAIA/AAAAAA==", DriveFacesLogic.encode(floatArrayOf(0.5f, -0.25f, 1f, 0f)))
        val v = FloatArray(128) { (it - 60) / 100f }
        val s = DriveFacesLogic.encode(v)
        assertEquals(684, s.length) // 512 bytes
        assertFalse(s.contains('\n'))
        assertArrayEquals(v, DriveFacesLogic.decodeVector(s))
        assertNull(DriveFacesLogic.decodeVector("AAA="))
    }

    @Test
    fun `the reference features of the same person are near, of others far`() {
        // drive-face-model.md: Alvaro_Uribe_0028 and _0029 (OpenCV, fp32)
        val a = DriveFacesLogic.decodeVector(REF_0028)!!
        val b = DriveFacesLogic.decodeVector(REF_0029)!!
        assertEquals(128, a.size)
        assertEquals(1.0, sqrt(a.sumOf { it.toDouble() * it }), 1e-5)
        assertEquals(0.5714, DriveFacesLogic.cosine(a, b), 1e-3)
        assertEquals(-0.051028f, a[0], 1e-5f)
        assertEquals(0.221019f, a[5], 1e-5f)
    }

    // ─── detection ──────────────────────────────────────────────────

    /** YuNet's outputs with one face at grid cell (row 2, col 3) of stride 8. */
    private fun outputs(score: Float, cell: Int = 2 * 80 + 3): Map<String, FloatArray> {
        val out = HashMap<String, FloatArray>()
        for (s in intArrayOf(8, 16, 32)) {
            val n = (640 / s) * (640 / s)
            out["cls_$s"] = FloatArray(n)
            out["obj_$s"] = FloatArray(n)
            out["bbox_$s"] = FloatArray(n * 4)
            out["kps_$s"] = FloatArray(n * 10)
        }
        out["cls_8"]!![cell] = score
        out["obj_8"]!![cell] = 1f
        out["bbox_8"]!!.let { it[cell * 4] = 0.5f; it[cell * 4 + 1] = 0.5f; it[cell * 4 + 2] = ln(4f); it[cell * 4 + 3] = ln(5f) }
        out["kps_8"]!!.let { k -> for (i in 0 until 10) k[cell * 10 + i] = 0.25f * (i % 2 + 1) }
        return out
    }

    @Test
    fun `decode reads boxes and points as OpenCV does`() {
        val faces = DriveFacesLogic.decode({ outputs(0.81f)[it] })
        assertEquals(1, faces.size)
        val f = faces[0]
        // cx = (3 + 0.5)·8 = 28, cy = (2 + 0.5)·8 = 20, w = 4·8 = 32, h = 5·8 = 40
        assertEquals(12f, f.x, 1e-4f)
        assertEquals(0f, f.y, 1e-4f)
        assertEquals(32f, f.w, 1e-4f)
        assertEquals(40f, f.h, 1e-4f)
        assertEquals((3 + 0.25f) * 8, f.points[0], 1e-4f)
        assertEquals((2 + 0.5f) * 8, f.points[1], 1e-4f)
        assertEquals(sqrt(0.81f), f.score, 1e-5f)
        // below the threshold (sqrt(0.6) < 0.8): none
        assertTrue(DriveFacesLogic.decode({ outputs(0.6f)[it] }).isEmpty())
    }

    @Test
    fun `nms keeps the best of overlapping boxes`() {
        fun face(x: Float, s: Float) = DriveFacesLogic.Face(x, 0f, 100f, 100f, FloatArray(10), s)
        val kept = DriveFacesLogic.nms(listOf(face(0f, 0.9f), face(10f, 0.95f), face(300f, 0.85f)), 0.3f)
        assertEquals(listOf(0.95f, 0.85f), kept.map { it.score })
    }

    @Test
    fun `scale, size and normalised box`() {
        assertEquals(0.3125f, DriveFacesLogic.detectScale(2048, 1536), 1e-6f)
        assertEquals(1f, DriveFacesLogic.detectScale(150, 150))
        val f = DriveFacesLogic.Face(-10f, 20f, 60f, 40f, FloatArray(10) { 50f }, 0.9f)
        assertTrue(DriveFacesLogic.usable(f, 200, 100))
        assertFalse(DriveFacesLogic.usable(f.copy(w = 20f), 200, 100))
        val (box, pts) = DriveFacesLogic.normalized(f, 200, 100)
        assertEquals(listOf(0f, 0.2f, 0.25f, 0.4f), box.map { (it * 1000).toInt() / 1000f })
        assertEquals(0.25f, pts[0], 1e-6f)
        assertEquals(0.5f, pts[1], 1e-6f)
    }

    companion object {
        const val REF_0028 = "wwJRvQ6VmLxBMoA9BZuSOnfnHr3tUmI+41NPvkaFmr0Xzck7CIZ5vTSErDxo3Js9gYrKuyWZb70dJBo9DPA0PQ6iFby6pl89aPv2veVmgr3ES9s9L1BtPK1M4btcgQQ99wStvN8HkbzLHWq9GCevPDZAFb3zcUC9H6cBvmNdfz1pET48q5adPVDXjL3zM2O8cPINvt8yb700W3y9PpPtPT3dnrwloCe+cliUPY6xpTxswbA9O30EPhePLDx4crQ9pTzxPWKGzLszgfM7BEr/PedOoD1kBi8+oUWwvR/Ipr22/Sa9L2pivZs3Sz4iAce8DdD4vJm1Ij5tl8m91IcQvV/pWLqLST4+vsyFvD19hr6QPtC8otUdvcKqkby1nQG+8xoTPTShd76XWa29hgH0vNJrPT1jxgC+96IhvjcmSz2bgmW9EL5lvGAn17wWHmC9KMVcu2QBkDsCP2Y9rb7dPVRG9zt6LsU9MY6YvTdowzzj2Dq9WfSqPeKq1LpqQoq9dcyLvSEa1712bwS+/jCTu1fISD1Xd9U9UWpKvvJIXr5w2Uc8m2oSPnwHAj3KE4O9ftaEPddNND3JTW49dbvWvcXhPLw8K0w9XljLPbXb8buZyTQ9pwaZPYLoez108189uozIvGrRRz1TAwA+NeoPvcWnIr41vga8yKNRPWDfFj0="
        const val REF_0029 = "GpbbPQ8Rejzb2RI93AcwvqWf7byA/Nk9zsYgvi1Sm70HzPI8zisCvkoDUz0cKY493RmLPdfOoDn9hqu8NUGDvCB1Gj2KIQE9qe5zvazSSL7VGTk+alsgveQT+LwWx9g8NpAAPfaXrL39EWk79IdSPQUyz71/KZU9xuAFvpMmBL7WkaY8qE2YvKbJ2L2gxpK9G1r2vC1oJL1g7LC9vYjVvGOcMj1JVE++arCTPVFPZjxO+Zc9sMZ6PfS13j0p7vQ5pTGwPUNdUL2lraW8/A5evSNMwzwiblI+6H9rulyawjwmjpo8H0+0u+ydND4qJuy9oDpHva04tT1HkTK+YnyrvUIRsb3udOS7sxG6vV6+g758XHu7s2j8vfiEb71koKy8p2vZPVBdHL4pEnc9JQxavT+Jfj1Onem9UAdfvUqNKz5m0y++V3X9vFAsML4wsV89Tz/Dveoodb3a6Qw+NGWcOZUOnz2s9gO9xSmgPCGR7D2UJOO8avwlPX59CL70eju9bUQNvdIaxLyuaMu9kRHION5mOb2nq6A9fTaLvaQuBb79Y8E80XwIvLX5F73qXxC9Wr6SPZXJM7xNkhc+bTFsPRWTFz1zpUC8X1OpvTIKkbtbc5S7WqxSPYz9Nz4gOtk9awo2vbDyuD0fFYM9fagzvc5T37xUS0O93YMQvuWOOb0="
    }
}
