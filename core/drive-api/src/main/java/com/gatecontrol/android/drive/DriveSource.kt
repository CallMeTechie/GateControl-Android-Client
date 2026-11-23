package com.gatecontrol.android.drive

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.channels.Channels

/** Bytes to upload: their [length] and a stream from any offset (a resumable upload asks for each part). */
class DriveSource(val length: Long, val open: (offset: Long) -> InputStream) {
    companion object {
        fun of(file: File) = DriveSource(file.length()) { offset ->
            val raf = RandomAccessFile(file, "r")
            raf.seek(offset)
            Channels.newInputStream(raf.channel)
        }

        /** A content URI that can be opened as a file (media store), positioned without reading what is before. */
        fun of(context: Context, uri: Uri, length: Long) = DriveSource(length) { offset ->
            val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: throw FileNotFoundException(uri.toString())
            ParcelFileDescriptor.AutoCloseInputStream(pfd).also { it.channel.position(offset) }
        }
    }
}
