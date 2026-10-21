package com.gatecontrol.android.gatedrop

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.IOException
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Received files go to Downloads/GateControl through MediaStore (no
 * storage permission needed). A file is written as a pending entry and only
 * published once it arrived complete and checked; a broken one is deleted.
 */
@Singleton
class GateDropSaver @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        val RELATIVE_DIR: String = Environment.DIRECTORY_DOWNLOADS + "/GateControl"
    }

    /** A pending download entry: [open] (re)opens it for writing from the start. */
    inner class Pending(val uri: Uri) {
        fun open(): OutputStream = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("not writable")

        fun publish(): Uri? = runCatching {
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            uri
        }.getOrNull()

        fun discard() {
            runCatching { context.contentResolver.delete(uri, null, null) }
        }
    }

    fun create(name: String, mime: String): Pending? = try {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, GateDropJson.safeName(name))
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, RELATIVE_DIR)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)?.let { Pending(it) }
    } catch (e: Exception) {
        Timber.w("GateDrop: download entry not created (%s)", e.javaClass.simpleName)
        null
    }

    /** The file still exists (not deleted by the person meanwhile). */
    fun exists(uri: Uri): Boolean = runCatching {
        context.contentResolver.query(uri, arrayOf(MediaStore.Downloads._ID), null, null, null)?.use { it.moveToFirst() } == true
    }.getOrDefault(false)
}
