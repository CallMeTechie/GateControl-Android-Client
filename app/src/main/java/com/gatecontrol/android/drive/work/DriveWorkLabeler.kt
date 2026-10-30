package com.gatecontrol.android.drive.work

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Size
import com.gatecontrol.android.drive.DNode
import com.gatecontrol.android.drive.DriveNodeResolver
import com.gatecontrol.android.drive.DrivePreviews
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabel
import com.google.mlkit.vision.label.ImageLabeler
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * What is in a photo, found on the phone: ML Kit's image labeling (through Google Play services, the model on the
 * phone; no picture goes to a service for it) on a small picture — the phone's own thumbnail of a photo still on it, or
 * the preview Drive made of a photo already backed up (fetched from the person's own server). The labels (index →
 * confidence) are the AI's features ([DriveWorkFeatures]).
 */
@Singleton
class DriveWorkLabeler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val previews: DrivePreviews,
    private val resolver: DriveNodeResolver,
) {
    private val lock = Mutex()
    private var labeler: ImageLabeler? = null

    private fun client(): ImageLabeler = labeler ?: ImageLabeling.getClient(
        ImageLabelerOptions.Builder().setConfidenceThreshold(DriveWorkFeatures.MIN_CONFIDENCE).build(),
    ).also { labeler = it }

    /** The labels of a photo on the phone; null when it cannot be read or labeled (Play services missing, …). */
    suspend fun ofUri(uri: Uri): Map<Int, Float>? = labelsOfUri(uri)?.associate { it.index to it.confidence }

    /** The labels of a photo in Drive (its preview); null without one. */
    suspend fun ofNode(node: DNode): Map<Int, Float>? = labelsOfNode(node)?.associate { it.index to it.confidence }

    /** What is in a photo on the phone, in words (ML Kit's English labels, Drive 1.14 `photoSearch`), with how sure. */
    suspend fun namesOfUri(uri: Uri): List<Pair<String, Float>>? = labelsOfUri(uri)?.map { it.text to it.confidence }

    /** What is in a photo in Drive (its preview), in words. */
    suspend fun namesOfNode(node: DNode): List<Pair<String, Float>>? = labelsOfNode(node)?.map { it.text to it.confidence }

    /** The last photo labeled: the backup asks twice (where it goes, what is in it). */
    @Volatile private var last: Pair<Uri, List<ImageLabel>>? = null

    private suspend fun labelsOfUri(uri: Uri): List<ImageLabel>? = withContext(Dispatchers.IO) {
        last?.takeIf { it.first == uri }?.let { return@withContext it.second }
        val bmp = try {
            context.contentResolver.loadThumbnail(uri, Size(THUMB, THUMB), null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d("Drive work: no thumbnail (%s)", e.javaClass.simpleName)
            return@withContext null
        }
        label(bmp)?.also { last = uri to it }
    }

    private suspend fun labelsOfNode(node: DNode): List<ImageLabel>? {
        if (node.isDir || node.encrypted) return null
        val full = if (node.mediaId != null) node else resolver.resolve(node.id) ?: return null
        if (!full.preview) return null
        val bmp = previews.load(full) ?: return null
        return label(bmp)
    }

    private suspend fun label(bmp: Bitmap): List<ImageLabel>? = lock.withLock {
        try {
            client().process(InputImage.fromBitmap(bmp, 0)).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d("Drive work: labeling failed (%s)", e.javaClass.simpleName)
            null
        }
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { c ->
        addOnSuccessListener { c.resume(it) }
        addOnFailureListener { c.resumeWithException(it) }
        addOnCanceledListener { c.cancel() }
    }

    private companion object {
        /** The labeler needs no more; small pictures are quick. */
        const val THUMB = 384
    }
}
