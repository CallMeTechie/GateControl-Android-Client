package com.gatecontrol.android.drive

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.gatecontrol.android.drive.work.DriveWorkPlaces
import com.gatecontrol.android.drive.work.GeoPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * When a photo or video was taken (Drive 1.7, feature `takenAt`): EXIF `DateTimeOriginal` with `OffsetTimeOriginal`
 * (else `DateTime`) of a picture, the recording date of a video's container. Only the file's own data — nothing is
 * guessed; without it the server takes the file's time. Never throws.
 */
@Singleton
class DriveTakenAt @Inject constructor(@ApplicationContext private val context: Context) {

    /** Of a file in the app's cache (a picked file copied, a scan, a share). */
    fun of(file: File, mime: String?, name: String): String? =
        read(mime, name, image = { fromExif(ExifInterface(file)) }) { it.setDataSource(file.path) }

    /** Of a content URI (a gallery photo, a file of a backed up folder). */
    fun of(uri: Uri, mime: String?, name: String): String? = read(
        mime,
        name,
        image = { context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd -> fromExif(ExifInterface(pfd.fileDescriptor)) } },
    ) { it.setDataSource(context, uri) }

    /** When and where a photo was taken: [takenAt] as [of], [at] its EXIF location (null: none, or stripped). */
    data class Shot(val takenAt: String?, val at: GeoPoint?)

    /**
     * [of] with the location of a picture, from one read of its EXIF (the backup's: [uri] the original, with
     * `ACCESS_MEDIA_LOCATION`; else Android strips it). A video's location is not read.
     */
    fun shot(uri: Uri, mime: String?, name: String): Shot {
        var at: GeoPoint? = null
        val takenAt = read(
            mime,
            name,
            image = {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    val e = ExifInterface(pfd.fileDescriptor)
                    at = runCatching { DriveWorkPlaces.of(e.latLong) }.getOrNull()
                    fromExif(e)
                }
            },
        ) { it.setDataSource(context, uri) }
        return Shot(takenAt, at)
    }

    private fun read(mime: String?, name: String, image: () -> String?, video: (MediaMetadataRetriever) -> Unit): String? =
        try {
            when (DriveLogic.kind(mime, name)) {
                DriveLogic.Kind.IMAGE -> image()
                DriveLogic.Kind.VIDEO -> {
                    val r = MediaMetadataRetriever()
                    try {
                        video(r)
                        DriveLogic.videoTakenAt(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE))
                    } finally {
                        runCatching { r.release() }
                    }
                }
                else -> null
            }
        } catch (e: Exception) {
            Timber.d("Drive: no date taken (%s)", e.javaClass.simpleName)
            null
        }

    private fun fromExif(e: ExifInterface): String? =
        DriveLogic.exifTakenAt(e.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL), e.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL))
            ?: DriveLogic.exifTakenAt(e.getAttribute(ExifInterface.TAG_DATETIME), e.getAttribute(ExifInterface.TAG_OFFSET_TIME))
}
