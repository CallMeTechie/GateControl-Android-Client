package com.gatecontrol.android.drive.work

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import com.gatecontrol.android.drive.DNode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where a photo in Drive was taken, read on the phone: its original in the gallery (found by name and size), the
 * location in its EXIF — only with "Zugriff auf Fotostandorte" (`ACCESS_MEDIA_LOCATION`, asked with the photo backup),
 * else Android strips it. Nothing leaves the phone.
 */
@Singleton
class DriveWorkGeo @Inject constructor(@ApplicationContext private val context: Context) {

    /** What a look found: [known] the phone could look (else ask again later), [at] where (null: not on the phone, no GPS). */
    data class Look(val known: Boolean, val at: GeoPoint?)

    fun canLook(): Boolean {
        fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
        val read = when {
            Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> true
            Build.VERSION.SDK_INT >= 33 -> granted(Manifest.permission.READ_MEDIA_IMAGES)
            else -> granted(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        return read && granted(Manifest.permission.ACCESS_MEDIA_LOCATION)
    }

    /** Where [node] was taken, if its original is on this phone. */
    suspend fun of(node: DNode): Look = withContext(Dispatchers.IO) {
        if (node.isDir || node.encrypted || node.size <= 0 || node.mime?.startsWith("image/") != true) return@withContext Look(true, null)
        if (!canLook()) return@withContext Look(false, null)
        try {
            val id = context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.SIZE} = ?",
                arrayOf(node.name, node.size.toString()),
                null,
            )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null } ?: return@withContext Look(true, null)
            val uri = MediaStore.setRequireOriginal(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id))
            val at = context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd -> DriveWorkPlaces.of(ExifInterface(pfd.fileDescriptor).latLong) }
            Look(true, at)
        } catch (e: Exception) {
            Timber.d("Drive work: no location (%s)", e.javaClass.simpleName)
            Look(false, null)
        }
    }
}
