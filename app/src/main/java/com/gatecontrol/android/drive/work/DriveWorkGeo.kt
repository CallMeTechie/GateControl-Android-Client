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
    suspend fun of(node: DNode): Look = when (val f = find(node)) {
        Found.Unknown -> Look(false, null)
        Found.NotHere, Found.NoPosition -> Look(true, null)
        is Found.At -> Look(true, f.at)
    }

    /** What a look at the phone found for a photo in Drive (Drive 1.16 "Orte nachtragen" tells these apart). */
    sealed class Found {
        /** The phone could not look (no permission, an error): ask again later. */
        data object Unknown : Found()
        /** The original is not on this phone (or no picture): another device may know. */
        data object NotHere : Found()
        /** The original is here and has no position. */
        data object NoPosition : Found()
        data class At(val at: GeoPoint) : Found()
    }

    /** The original of [node] in the gallery (same name and size); null when it is not on this phone. Throws on errors. */
    fun original(node: DNode): android.net.Uri? {
        if (node.isDir || node.encrypted || node.size <= 0 || node.mime?.startsWith("image/") != true) return null
        return context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.SIZE} = ?",
            arrayOf(node.name, node.size.toString()),
            null,
        )?.use { c -> if (c.moveToFirst()) ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)) else null }
    }

    suspend fun find(node: DNode): Found = withContext(Dispatchers.IO) {
        if (node.isDir || node.encrypted || node.size <= 0 || node.mime?.startsWith("image/") != true) return@withContext Found.NotHere
        if (!canLook()) return@withContext Found.Unknown
        try {
            val plain = original(node) ?: return@withContext Found.NotHere
            val uri = MediaStore.setRequireOriginal(plain)
            val at = context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd -> DriveWorkPlaces.of(ExifInterface(pfd.fileDescriptor).latLong) }
            if (at == null) Found.NoPosition else Found.At(at)
        } catch (e: Exception) {
            Timber.d("Drive work: no location (%s)", e.javaClass.simpleName)
            Found.Unknown
        }
    }
}
