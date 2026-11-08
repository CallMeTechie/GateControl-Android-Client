package com.gatecontrol.android.drive.device

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * "Auf dem Gerät": the files of the shared storage (internal, SD card, USB — everything below `/storage`) as content
 * URIs for another app (open, share) and for Drive's upload queue. Not exported; a URI is granted per intent only.
 */
class DeviceFileProvider : FileProvider() {
    companion object {
        fun authority(context: Context) = context.packageName + ".devicefiles"

        /** The URI of [file]; null for one outside `/storage`. */
        fun uriOf(context: Context, file: File): Uri? = runCatching { getUriForFile(context, authority(context), file) }.getOrNull()

        /**
         * The file behind a URI of this provider (`content://…devicefiles/storage/<path>` is `/storage/<path>`, see
         * res/xml/device_file_paths.xml); null for any other URI or a path that leaves `/storage`.
         */
        fun fileOf(context: Context, uri: Uri): File? {
            if (uri.scheme != "content" || uri.authority != authority(context)) return null
            val f = File(uri.path ?: return null)
            val canonical = runCatching { f.canonicalPath }.getOrNull() ?: return null
            return f.takeIf { canonical.startsWith("/storage/") }
        }
    }
}
