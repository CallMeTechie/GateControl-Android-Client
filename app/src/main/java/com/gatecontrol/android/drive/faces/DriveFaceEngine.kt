package com.gatecontrol.android.drive.faces

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.core.graphics.scale
import com.gatecontrol.android.drive.DFaceUpload
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.Closeable
import java.nio.FloatBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Faces in a picture, on the phone (Drive 1.16, `photoPeople`): YuNet finds them with their 5 points, SFace describes
 * each as 128 numbers (model `sface-2021dec`, ONNX Runtime on the CPU; the models ship in the app's assets, see
 * assets/faces/NOTICE.txt). Only the numbers and the boxes go to the person's own server, never a picture.
 * [open] loads both models for one run (about 40 MB): close it when the run is done.
 */
@Singleton
class DriveFaceEngine @Inject constructor(@ApplicationContext private val context: Context) {

    /** Both models, loaded. Not thread safe: one picture at a time. */
    inner class Models internal constructor(private val env: OrtEnvironment, private val detector: OrtSession, private val recognizer: OrtSession) : Closeable {

        /** The faces of [bitmap] (upright), at most [DriveFacesLogic.MAX_FACES]; box and points 0…1 of it. */
        fun faces(bitmap: Bitmap): List<DFaceUpload> {
            val w = bitmap.width
            val h = bitmap.height
            if (w < 16 || h < 16) return emptyList()
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            val scale = DriveFacesLogic.detectScale(w, h)
            val sw = (w * scale).roundToInt().coerceIn(1, DriveFacesLogic.DETECT_SIZE)
            val sh = (h * scale).roundToInt().coerceIn(1, DriveFacesLogic.DETECT_SIZE)
            val small = if (sw == w && sh == h) pixels else {
                val b = bitmap.scale(sw, sh)
                IntArray(sw * sh).also { b.getPixels(it, 0, sw, 0, 0, sw, sh); if (b !== bitmap) b.recycle() }
            }
            val input = DriveFacesLogic.detectInput(small, sw, sh)
            val n = DriveFacesLogic.DETECT_SIZE.toLong()
            val found = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, n, n)).use { t ->
                detector.run(mapOf(detector.inputNames.first() to t)).use { r ->
                    val outputs = HashMap<String, FloatArray>()
                    for (name in OUTPUTS) {
                        val v = r.get(name).orElse(null) as? OnnxTensor ?: continue
                        val fb = v.floatBuffer
                        outputs[name] = FloatArray(fb.remaining()).also { fb.get(it) }
                    }
                    DriveFacesLogic.decode({ outputs[it] })
                }
            }
            // back to the picture's pixels (sx and sy differ a little by rounding)
            val fx = w.toFloat() / sw
            val fy = h.toFloat() / sh
            return found.asSequence()
                .map { f -> DriveFacesLogic.Face(f.x * fx, f.y * fy, f.w * fx, f.h * fy, FloatArray(10) { i -> f.points[i] * if (i % 2 == 0) fx else fy }, f.score) }
                .filter { DriveFacesLogic.usable(it, w, h) }
                .take(DriveFacesLogic.MAX_FACES)
                .mapNotNull { f ->
                    val vector = runCatching { feature(pixels, w, h, f.points) }.onFailure { Timber.d("Drive faces: no feature (%s)", it.javaClass.simpleName) }.getOrNull()
                        ?: return@mapNotNull null
                    val (box, points) = DriveFacesLogic.normalized(f, w, h)
                    DFaceUpload(box, points, f.score.coerceIn(0f, 1f), DriveFacesLogic.encode(vector))
                }
                .toList()
        }

        /** SFace's 128 numbers of the face at [points] in [pixels], normalised. */
        fun feature(pixels: IntArray, w: Int, h: Int, points: FloatArray): FloatArray {
            val aligned = DriveFacesLogic.warp(pixels, w, h, DriveFacesLogic.similarity(points))
            val input = DriveFacesLogic.featureInput(aligned)
            val s = DriveFacesLogic.ALIGNED.toLong()
            return OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, s, s)).use { t ->
                recognizer.run(mapOf(INPUT_FEATURE to t)).use { r ->
                    val fb = (r.get(0) as OnnxTensor).floatBuffer
                    val v = FloatArray(fb.remaining()).also { fb.get(it) }
                    require(v.size == DriveFacesLogic.FEATURES) { "feature size ${v.size}" }
                    DriveFacesLogic.l2(v)
                }
            }
        }

        override fun close() {
            runCatching { detector.close() }
            runCatching { recognizer.close() }
        }
    }

    /** Both models for a run; null when they cannot be loaded (missing, too little memory). */
    fun open(): Models? = try {
        val env = OrtEnvironment.getEnvironment()
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(THREADS)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        val detector = env.createSession(asset(DETECTOR), options)
        val recognizer = try {
            env.createSession(asset(RECOGNIZER), options)
        } catch (e: Exception) {
            detector.close()
            throw e
        }
        Models(env, detector, recognizer)
    } catch (e: Exception) {
        Timber.w("Drive faces: models not loaded (%s)", e.javaClass.simpleName)
        null
    } catch (e: OutOfMemoryError) {
        Timber.w("Drive faces: too little memory for the models")
        null
    } catch (e: UnsatisfiedLinkError) {
        Timber.w("Drive faces: no runtime on this phone")
        null
    }

    private fun asset(name: String): ByteArray = context.assets.open("faces/$name").use { it.readBytes() }

    /** A photo on the phone, upright, at most [MAX_SIDE] px on its long side; null when it cannot be read. */
    fun decode(uri: Uri): Bitmap? = try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
            val long = max(info.size.width, info.size.height)
            if (long > MAX_SIDE) {
                val f = MAX_SIDE.toFloat() / long
                decoder.setTargetSize((info.size.width * f).roundToInt().coerceAtLeast(1), (info.size.height * f).roundToInt().coerceAtLeast(1))
            }
        }.let { b -> if (b.config == Bitmap.Config.ARGB_8888) b else b.copy(Bitmap.Config.ARGB_8888, false).also { b.recycle() } }
    } catch (e: Exception) {
        Timber.d("Drive faces: picture not read (%s)", e.javaClass.simpleName)
        null
    } catch (e: OutOfMemoryError) {
        Timber.w("Drive faces: picture too large")
        null
    }

    companion object {
        const val DETECTOR = "face_detection_yunet_2023mar.onnx"
        const val RECOGNIZER = "face_recognition_sface_2021dec.onnx"
        private const val INPUT_FEATURE = "data"
        private const val THREADS = 2
        /** Originals are read at most this large (long side). */
        const val MAX_SIDE = 2048
        private val OUTPUTS = listOf(8, 16, 32).flatMap { s -> listOf("cls_$s", "obj_$s", "bbox_$s", "kps_$s") }
    }
}
