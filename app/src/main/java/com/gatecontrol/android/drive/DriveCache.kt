package com.gatecontrol.android.drive

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Zwischenspeicher" of the Drive settings (Drive 1.11): the preview pictures and the files opened from Drive on this
 * phone. Never the vault's keys, its decrypted copies (they go when the vault locks) or uploads still waiting.
 */
@Singleton
class DriveCache @Inject constructor(
    @ApplicationContext private val context: Context,
    private val previews: DrivePreviews,
) {
    private fun dirs(): List<File> = CLEARABLE.map { File(context.cacheDir, it) }

    /** Bytes in the cache. */
    suspend fun size(): Long = withContext(Dispatchers.IO) { dirs().sumOf(::bytes) }

    /** Empties the cache. */
    suspend fun clear() = withContext(Dispatchers.IO) {
        previews.clearMemory()
        dirs().forEach { d -> d.listFiles()?.forEach { runCatching { it.deleteRecursively() } } }
    }

    private fun bytes(f: File): Long = when {
        f.isFile -> f.length()
        f.isDirectory -> f.listFiles()?.sumOf(::bytes) ?: 0L
        else -> 0L
    }

    internal companion object {
        /** Preview pictures ([DrivePreviews]) and opened files ([DriveTransfers.fetch]); uploads (`drive-up`) stay. */
        val CLEARABLE = listOf("drive-previews", "drive-open")
    }
}
