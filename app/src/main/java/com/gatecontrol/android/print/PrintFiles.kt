package com.gatecontrol.android.print

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.graphics.createBitmap
import timber.log.Timber
import java.io.File

/** Shared files into the cache for printing (while the share activity still holds the read permission). */
object PrintFiles {

    /** Longest side of a picture turned into a JPEG here (plenty for A4 at 300 dpi). */
    private const val MAX_SIDE = 4096

    fun dir(context: Context): File = File(context.cacheDir, "print-share").apply { mkdirs() }

    /** The type of a shared [uri]: the provider's, else the share's, else from the name's extension. */
    fun mimeOf(context: Context, uri: Uri, shared: String?, name: String?): String? {
        val provider = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        val fromName = name?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.isNotEmpty() }
            ?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
        return listOfNotNull(provider, shared?.takeIf { !it.contains('*') }, fromName).firstOrNull { PrintLogic.upload(it) != null }
            ?: provider ?: shared?.takeIf { !it.contains('*') } ?: fromName
    }

    /** [uri] as a file to upload ([upload] as it is, or as a JPEG); null when it cannot be read or is too large. */
    fun copy(context: Context, uri: Uri, upload: PrintLogic.Upload): File? {
        val target = File.createTempFile("share", if (upload == PrintLogic.Upload.AS_JPEG) ".jpg" else ".part", dir(context))
        return try {
            when (upload) {
                PrintLogic.Upload.AS_IS -> {
                    val input = context.contentResolver.openInputStream(uri) ?: run { target.delete(); return null }
                    var total = 0L
                    input.use { inp ->
                        target.outputStream().use { out ->
                            val buf = ByteArray(256 * 1024)
                            while (true) {
                                val n = inp.read(buf)
                                if (n < 0) break
                                total += n
                                if (total > PrintLogic.MAX_BYTES) break
                                out.write(buf, 0, n)
                            }
                        }
                    }
                    if (total > PrintLogic.MAX_BYTES) {
                        target.delete()
                        null
                    } else {
                        target
                    }
                }
                PrintLogic.Upload.AS_JPEG -> {
                    val source = ImageDecoder.createSource(context.contentResolver, uri)
                    val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        val w = info.size.width
                        val h = info.size.height
                        val longest = maxOf(w, h)
                        if (longest > MAX_SIDE) decoder.setTargetSize(w * MAX_SIDE / longest, h * MAX_SIDE / longest)
                    }
                    // transparency on white paper, not black
                    val flat = createBitmap(bitmap.width, bitmap.height)
                    Canvas(flat).apply {
                        drawColor(Color.WHITE)
                        drawBitmap(bitmap, 0f, 0f, null)
                    }
                    bitmap.recycle()
                    target.outputStream().use { flat.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                    flat.recycle()
                    target
                }
            }
        } catch (e: Exception) {
            Timber.d("Print: shared file not read (%s)", e.javaClass.simpleName)
            target.delete()
            null
        } catch (e: OutOfMemoryError) {
            Timber.d("Print: shared picture too big")
            target.delete()
            null
        }
    }
}
