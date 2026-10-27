package com.gatecontrol.android.drive

import android.content.Context
import android.net.Uri
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.documentscanner.GmsDocumentScanner
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * "Scannen" in the Drive tab: Google's document scanner (Play services: edges, straightening, several pages, a picture
 * from the gallery) hands back JPEG pages and a PDF; the text of each page is recognised on the phone (nothing leaves
 * it for that) and goes up with the file, so the server finds the scan by its words (Drive 1.3+).
 */
@Singleton
class DriveScanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val transfers: DriveTransfers,
) {
    /** What the scanner brought: the pages (JPEG) and the same pages as one PDF (null when it made none). */
    data class Pages(val pages: List<Uri>, val pdf: Uri?)

    fun client(): GmsDocumentScanner = GmsDocumentScanning.getClient(
        GmsDocumentScannerOptions.Builder()
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .setGalleryImportAllowed(true)
            .setPageLimit(PAGE_LIMIT)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG, GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
            .build(),
    )

    /** The recognised text of each page; null for a page without text (or when recognising failed). */
    suspend fun recognize(pages: List<Uri>): List<String?> = withContext(Dispatchers.IO) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            pages.map { uri ->
                try {
                    recognizer.process(InputImage.fromFilePath(context, uri)).await().text.trim().ifEmpty { null }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.d("Drive scan: no text recognised (%s)", e.javaClass.simpleName)
                    null
                }
            }
        } finally {
            recognizer.close()
        }
    }

    /**
     * Queues the scan for [folderId] as [base].pdf (all text) or one picture per page (each with its text). A taken
     * name gets a free one. False when the scanner's files could not be read.
     */
    suspend fun save(scan: Pages, texts: List<String?>, base: String, pdf: Boolean, folderId: Long): Boolean = withContext(Dispatchers.IO) {
        val asPdf = pdf && scan.pdf != null
        val names = DriveLogic.scanFiles(base, asPdf, scan.pages.size)
        val parts = if (asPdf) listOf(Triple(scan.pdf!!, names[0], DriveLogic.scanText(texts))) else scan.pages.mapIndexed { i, uri -> Triple(uri, names[i], texts.getOrNull(i)) }
        val copies = parts.map { (uri, name, text) -> Triple(copy(uri), name, text) }
        if (copies.any { it.first == null }) {
            copies.forEach { it.first?.delete() }
            return@withContext false
        }
        for ((file, name, text) in copies) {
            file ?: continue
            transfers.uploadWritten(file, name, if (asPdf) "application/pdf" else "image/jpeg", folderId, DriveLogic.ON_CONFLICT_RENAME, text)
        }
        true
    }

    /** The scanner's file in the cache, handed over to the upload (which deletes it); null when it cannot be read. */
    private fun copy(uri: Uri): File? {
        val dir = File(context.cacheDir, "drive-scan").apply { mkdirs() }
        val target = File.createTempFile("scan", ".part", dir)
        return try {
            val input = context.contentResolver.openInputStream(uri) ?: run { target.delete(); return null }
            input.use { inp -> target.outputStream().use { inp.copyTo(it, 256 * 1024) } }
            target
        } catch (e: Exception) {
            Timber.d("Drive scan: cannot read a page (%s)", e.javaClass.simpleName)
            target.delete()
            null
        }
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { c ->
        addOnSuccessListener { c.resume(it) }
        addOnFailureListener { c.resumeWithException(it) }
        addOnCanceledListener { c.cancel() }
    }

    companion object {
        const val PAGE_LIMIT = 30
    }
}
