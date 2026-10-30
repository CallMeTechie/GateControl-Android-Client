package com.gatecontrol.android.mail

import android.content.Context
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.familie.PluginDirectory
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Mail tab's offline copy (contract D "Offline"): the last lists (the inbox at least 30 days back), the mails
 * opened, `me` and the folders — the server's answers as they came (read with [MailJson], never by reflection). Kept
 * per login (server + token) in the app's private, not backed-up files; another login, the plugin gone from the
 * server or the token revoked delete it ([wipe]), and so do the files opened from mails.
 */
@Singleton
class MailCache @Inject constructor(
    @ApplicationContext private val context: Context,
    private val setupRepository: SetupRepository,
) {
    private val mutex = Mutex()

    private fun root(): File = File(context.noBackupFilesDir, DIR)

    private fun ownerDir(): File? {
        val key = PluginDirectory.ownerKey(setupRepository.getServerUrl(), setupRepository.getApiToken()).takeIf { it.isNotEmpty() } ?: return null
        return File(root(), key)
    }

    private fun name(key: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        return d.take(12).joinToString("") { "%02x".format(it) }
    }

    private suspend fun <T> io(block: (File) -> T?): T? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val dir = ownerDir() ?: return@withLock null
            // only this login's copy stays
            root().listFiles()?.filter { it.name != dir.name }?.forEach { runCatching { it.deleteRecursively() } }
            runCatching { block(dir) }.getOrNull()
        }
    }

    private fun read(file: File): JsonObject? = file.takeIf { it.isFile }?.let { MailJson.parse(it.readText()) }

    private fun write(file: File, o: JsonObject) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(o.toString())
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    // ─── me, folders ────────────────────────────────────────────────

    suspend fun putMe(raw: JsonObject) { io { write(File(it, "me.json"), raw) } }

    suspend fun me(): MMe? = io { dir -> read(File(dir, "me.json"))?.let(MailJson::me) }

    suspend fun putFolders(raw: JsonObject) { io { write(File(it, "folders.json"), raw) } }

    suspend fun folders(): Map<Long, List<MFolder>>? = io { dir -> read(File(dir, "folders.json"))?.let(MailJson::folders) }

    // ─── account pictures ───────────────────────────────────────────

    /** An account's picture as it came ([MAccount.avatar]); the media id changes with the picture. */
    suspend fun putAvatar(mediaId: String, bytes: ByteArray) {
        io { dir ->
            val avatars = File(dir, "avatars").apply { mkdirs() }
            File(avatars, name(mediaId)).writeBytes(bytes)
            // only the newest few stay (a picture changed: the old one is of no use)
            avatars.listFiles()?.sortedByDescending { it.lastModified() }?.drop(MAX_AVATARS)?.forEach { it.delete() }
        }
    }

    suspend fun avatar(mediaId: String): ByteArray? = io { dir -> File(dir, "avatars/${name(mediaId)}").takeIf { it.isFile }?.readBytes() }

    // ─── lists ──────────────────────────────────────────────────────

    /**
     * A list's page as it came. The first page ([first]) starts the copy afresh, later ones are added; what lies
     * beyond the kept window goes ([MailCacheLogic.prune]).
     */
    suspend fun putList(key: String, raw: JsonObject, first: Boolean, now: Long = System.currentTimeMillis()) {
        io { dir ->
            val file = File(dir, "lists/${name(key)}.json")
            val items = raw.getAsJsonArray("items")?.toList()?.filter { it.isJsonObject }?.map { it.asJsonObject }.orEmpty()
            val old = if (first) emptyList() else read(file)?.getAsJsonArray("items")?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }.orEmpty()
            val kept = MailCacheLogic.prune(MailCacheLogic.mergeRaw(old, items), now)
            val out = JsonObject().apply {
                add("items", JsonArray().apply { kept.forEach { add(it) } })
                raw.get("counts")?.takeIf { it.isJsonObject }?.let { add("counts", it) }
                    ?: read(file)?.get("counts")?.let { add("counts", it) }
                addProperty("savedAt", now)
            }
            write(file, out)
        }
    }

    /** The copy of a list (no `next`: offline, the list ends where the copy ends). */
    suspend fun list(key: String): MPage? = io { dir ->
        read(File(dir, "lists/${name(key)}.json"))?.let(MailJson::page)?.copy(next = null)
    }

    // ─── mails ──────────────────────────────────────────────────────

    suspend fun putMessage(raw: JsonObject, now: Long = System.currentTimeMillis()) {
        val id = raw.get("id")?.takeIf { it.isJsonPrimitive }?.asString?.toLongOrNull()?.takeIf { it > 0 } ?: return
        io { dir ->
            write(File(dir, "msg/$id.json"), raw)
            MailCacheLogic.overflow(File(dir, "msg").listFiles()?.map { it.name to it.lastModified() }.orEmpty(), now)
                .forEach { File(dir, "msg/$it").delete() }
        }
    }

    suspend fun message(id: Long): MMessage? = io { dir -> read(File(dir, "msg/$id.json"))?.let(MailJson::message) }

    /** A mail's copy is gone (deleted, or moved and to be fetched again). */
    suspend fun forgetMessages(ids: Collection<Long>) {
        io { dir -> ids.forEach { File(dir, "msg/$it.json").delete() } }
    }

    /** An account was removed: its lists and mails go with the next load; the summary copies of all lists now. */
    suspend fun forgetAccount(accountId: Long) {
        io { dir ->
            File(dir, "lists").listFiles()?.forEach { f ->
                val o = read(f) ?: return@forEach
                val items = o.getAsJsonArray("items") ?: return@forEach
                val left = JsonArray()
                items.forEach { e ->
                    val acc = e.takeIf { it.isJsonObject }?.asJsonObject?.get("accountId")?.takeIf { it.isJsonPrimitive }?.asString?.toLongOrNull()
                    if (acc != accountId) left.add(e)
                }
                o.add("items", left)
                write(f, o)
            }
            File(dir, "msg").listFiles()?.forEach { f ->
                val acc = read(f)?.get("accountId")?.takeIf { it.isJsonPrimitive }?.asString?.toLongOrNull()
                if (acc == accountId) f.delete()
            }
        }
    }

    /** Everything of the Mail tab on this device: the copies of every login and the files opened from mails. */
    suspend fun wipe() = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching { root().deleteRecursively() }
            runCatching { File(context.cacheDir, OPEN_DIR).deleteRecursively() }
            runCatching { File(context.cacheDir, UPLOAD_DIR).deleteRecursively() }
        }
    }

    /** Whether there is anything to wipe (no work at every start when the plugin was never there). */
    fun hasData(): Boolean = root().exists() || File(context.cacheDir, OPEN_DIR).exists()

    companion object {
        private const val DIR = "mail"
        private const val MAX_AVATARS = 20
        /** Attachments opened or shared (FileProvider `mail_open`). */
        const val OPEN_DIR = "mail-open"
        /** Device files on their way to the server (private). */
        const val UPLOAD_DIR = "mail-up"
    }
}

/** What the offline copy keeps (pure, unit-tested). */
object MailCacheLogic {
    /** At least this many of a list, however old. */
    const val MIN_ITEMS = 100
    /** At most this many of a list. */
    const val MAX_ITEMS = 3000
    /** At most this many opened mails. */
    const val MAX_MESSAGES = 500

    private fun idOf(o: JsonObject): Long? = o.get("id")?.takeIf { it.isJsonPrimitive }?.asString?.toLongOrNull()

    private fun dateOf(o: JsonObject): Long = o.get("date")?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asLong }.getOrNull() } ?: 0L

    /** Pages one after the other: a newer copy of a mail replaces the older one in its place. */
    fun mergeRaw(old: List<JsonObject>, more: List<JsonObject>): List<JsonObject> {
        val byId = more.mapNotNull { o -> idOf(o)?.let { it to o } }.toMap()
        val oldIds = old.mapNotNull(::idOf).toHashSet()
        return old.map { o -> idOf(o)?.let { byId[it] } ?: o } + more.filter { o -> idOf(o)?.let { it !in oldIds } ?: false }
    }

    /** The newest [MIN_ITEMS] and everything of the last 30 days, at most [MAX_ITEMS]; entries without id go. */
    fun prune(items: List<JsonObject>, now: Long): List<JsonObject> {
        val cutoff = now - MailLogic.OFFLINE_KEEP_MS
        return items.filter { idOf(it) != null }
            .filterIndexed { i, o -> i < MIN_ITEMS || dateOf(o) >= cutoff }
            .take(MAX_ITEMS)
    }

    /** Copies of opened mails to delete: older than 30 days, and the oldest above [MAX_MESSAGES]. */
    fun overflow(files: List<Pair<String, Long>>, now: Long): List<String> {
        val cutoff = now - MailLogic.OFFLINE_KEEP_MS
        val (old, fresh) = files.partition { it.second < cutoff }
        val extra = fresh.sortedByDescending { it.second }.drop(MAX_MESSAGES)
        return (old + extra).map { it.first }
    }
}
