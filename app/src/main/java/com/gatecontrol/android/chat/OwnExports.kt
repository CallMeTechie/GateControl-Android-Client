package com.gatecontrol.android.chat

import java.io.File
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Files this app has just exported to the share sheet itself (the log, a logbook export) — the only URIs of its own
 * provider its share targets (chats, GateDrop, Drive) take. Every export lies in a folder with a random name below
 * cacheDir/export, and its URI is granted in this process for [TTL_MS]: another app cannot name such a file, and a
 * URI of this app that was not just exported here stays refused (ChatShareRules).
 */
object OwnExports {
    const val TTL_MS = 15 * 60_000L
    private const val KEEP_MS = 24 * 3600_000L

    private val random = SecureRandom()
    private val granted = ConcurrentHashMap<String, Long>()

    /** A fresh folder cacheDir/export/<random> for one export; folders of exports older than a day are removed. */
    fun newDir(cacheDir: File, now: Long = System.currentTimeMillis()): File {
        val root = File(cacheDir, "export").apply { mkdirs() }
        root.listFiles()?.filter { now - it.lastModified() > KEEP_MS }?.forEach { it.deleteRecursively() }
        val name = ByteArray(12).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        return File(root, name).apply { mkdirs() }
    }

    /** [uri] (as text) may be shared into this app until [TTL_MS] from [now]. */
    fun grant(uri: String, now: Long = System.currentTimeMillis()) {
        granted.entries.removeIf { it.value <= now }
        granted[uri] = now + TTL_MS
    }

    /** Whether [uri] was exported here and is still granted. */
    fun allows(uri: String?, now: Long = System.currentTimeMillis()): Boolean {
        val until = uri?.let { granted[it] } ?: return false
        return until > now
    }
}
