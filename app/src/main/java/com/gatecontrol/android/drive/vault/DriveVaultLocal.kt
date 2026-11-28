package com.gatecontrol.android.drive.vault

import android.content.Context
import com.gatecontrol.android.drive.DNode
import com.gatecontrol.android.drive.DVaultLocalItem
import com.gatecontrol.android.drive.DriveJson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One entry of a folder that lies only on this phone ("Nur auf diesem Gerät", Drive 1.32): a folder or a file below the
 * placeholder [DriveVaultLocalFolder.nodeId]. Its [name] is a nameCipher with the folder's own key LK (`kv = 0`), a
 * file's content lies next to the index, encrypted with LK in the vault's format — never a plain name, never plaintext.
 */
data class DriveVaultLocalItem(
    /** The server's node id for what came from the server; a negative random one for what was made here. */
    val id: Long,
    /** The folder it lies in: the placeholder's node id at the top. */
    val parentId: Long,
    val isDir: Boolean,
    /** nameCipher with LK (key version 0). */
    val name: String,
    /** Bytes of plaintext. */
    val size: Long = 0,
    /** When it was changed last (ms). */
    val mtime: Long = 0,
    /** Came from the server: its revision and the SHA-256 of its ciphertext there (a move that goes on reuses it). */
    val srcRev: Int? = null,
    val srcSha256: String? = null,
    /** On the way back: its node id on the server now (a return that goes on skips it). */
    val up: Long? = null,
)

/**
 * Something deleted in a folder kept only on this phone: the local trash (30 days, [DriveVaultLocalLogic.TRASH_DAYS]).
 * [items] are the deleted entry ([id], the first) and everything that was below it, as they were — names and content
 * stay encrypted with LK, the files stay where they were; only the index says they are in the trash.
 */
data class DriveVaultLocalTrashEntry(
    /** The deleted entry's id (also the id of the first of [items]). */
    val id: Long,
    /** Where it was ([DriveVaultLocalFolder.nodeId]: the top) — a restore goes back there, if that folder is still there. */
    val parentId: Long,
    /** When it was deleted (ms): gone for good [DriveVaultLocalLogic.TRASH_MS] later. */
    val deletedAt: Long,
    val items: List<DriveVaultLocalItem>,
) {
    val top: DriveVaultLocalItem? get() = items.firstOrNull { it.id == id }
    val files: Int get() = items.count { !it.isDir }
    val bytes: Long get() = items.filter { !it.isDir }.sumOf { it.size }
}

/**
 * A folder of a vault that lies only on this phone, as the index file keeps it: whose it is ([server], [vaultId], the
 * vault device [deviceId]), where its placeholder is ([nodeId], [parentId], its encrypted name [nameCipher] — the
 * vault's key, as the server keeps it) and what is in it ([items]).
 */
data class DriveVaultLocalFolder(
    val server: String,
    val vaultId: String,
    val nodeId: Long,
    val parentId: Long?,
    val areaId: String,
    val nameCipher: String?,
    val deviceId: String,
    val state: State,
    /** Since when only here (ms; while moving: when it began). */
    val since: Long,
    /** Until when "Rückgängig" works (ms; null: no longer). */
    val purgeAt: Long? = null,
    /** The last export ("Sicherung exportieren", ms); null: never. */
    val lastExport: Long? = null,
    /** The last change of the content here (ms). */
    val changedAt: Long = since,
    val items: List<DriveVaultLocalItem> = emptyList(),
    /** The device was removed from the vault: the folder is locked (export and delete only). */
    val revoked: Boolean = false,
    /** Asked once after the move: the reminder "Sicherung exportieren" (ms of the last reminder). */
    val remindedAt: Long? = null,
    /** The local trash: what was deleted here, for 30 days (not part of [items], an export or the way back). */
    val trash: List<DriveVaultLocalTrashEntry> = emptyList(),
) {
    enum class State { MOVING, LOCAL, RETURNING }

    val files: List<DriveVaultLocalItem> get() = items.filter { !it.isDir }

    /** Bytes of plaintext of every file. */
    val bytes: Long get() = items.filter { !it.isDir }.sumOf { it.size }

    /** Bytes of plaintext in the trash (on the phone as well until it is emptied). */
    val trashBytes: Long get() = trash.sumOf { it.bytes }

    /** Everything this folder holds on the phone: its files and its trash. */
    val allBytes: Long get() = bytes + trashBytes

    /** Every id taken here (entries and the trash): a new entry never takes one of them. */
    val ids: Set<Long> get() = HashSet<Long>().also { out -> items.forEach { out += it.id }; trash.forEach { t -> t.items.forEach { out += it.id } } }
}

/** The rules of the folders kept only on this phone (pure, unit-tested). */
object DriveVaultLocalLogic {
    private const val MB = 1_048_576L

    /** A move needs this much free room: the content × 1.1 and 500 MB (concept 1.4). */
    fun roomNeeded(bytes: Long): Long = (bytes.coerceAtLeast(0) * 11) / 10 + 500 * MB

    fun enoughRoom(bytes: Long, free: Long): Boolean = free >= roomNeeded(bytes)

    /** At most 3 commits when the server answers `changed` (concept 1.4, 5). */
    const val COMMIT_TRIES = 3

    /** The lease is renewed this often while a move or a return runs (vertrag-apps 4: every 5 minutes). */
    const val LEASE_EVERY_MS = 5 * 60_000L

    private val random = SecureRandom()

    /** A new id for an entry made on this phone: negative, so it never meets a server's node id. */
    fun newId(taken: Set<Long>): Long {
        while (true) {
            val v = -(random.nextLong() and 0x3fffffffffffffffL) - 1
            if (v !in taken) return v
        }
    }

    /** The entries right inside [parentId] (the placeholder's id: the top). */
    fun children(folder: DriveVaultLocalFolder, parentId: Long): List<DriveVaultLocalItem> = folder.items.filter { it.parentId == parentId }

    /** [id] and everything below it. */
    fun subtree(folder: DriveVaultLocalFolder, id: Long): Set<Long> {
        val out = linkedSetOf(id)
        var grew = true
        while (grew) {
            grew = false
            folder.items.forEach { if (it.parentId in out && out.add(it.id)) grew = true }
        }
        return out
    }

    /** The entries of [folder] whose parents are there (a broken index never shows orphans). */
    fun reachable(folder: DriveVaultLocalFolder): List<DriveVaultLocalItem> {
        val ids = subtree(folder, folder.nodeId)
        return folder.items.filter { it.id in ids }
    }

    /** The folders above [id] inside the local folder, top first (for the crumbs). */
    fun path(folder: DriveVaultLocalFolder, id: Long): List<DriveVaultLocalItem> {
        val by = folder.items.associateBy { it.id }
        val out = ArrayList<DriveVaultLocalItem>()
        var at = by[id]
        val seen = HashSet<Long>()
        while (at != null && seen.add(at.id)) {
            out += at
            at = by[at.parentId]
        }
        return out.asReversed()
    }

    // ── a move ──────────────────────────────────────────────────────

    /**
     * What a move (that goes on) still has to do with the server's [manifest]: [fetch] the files not here in that very
     * revision, [keep] what is here already (the same revision and SHA-256), [drop] what is here but no longer there.
     */
    data class Plan(val fetch: List<DVaultLocalItem>, val dirs: List<DVaultLocalItem>, val keep: List<DriveVaultLocalItem>, val drop: List<DriveVaultLocalItem>)

    fun plan(manifest: List<DVaultLocalItem>, have: List<DriveVaultLocalItem>): Plan {
        val by = have.associateBy { it.id }
        val fetch = ArrayList<DVaultLocalItem>()
        val dirs = ArrayList<DVaultLocalItem>()
        val keep = ArrayList<DriveVaultLocalItem>()
        for (m in manifest) {
            val h = by[m.id]
            if (m.isDir) {
                dirs += m
                if (h != null && h.isDir) keep += h
                continue
            }
            if (h != null && !h.isDir && h.srcRev == m.rev && h.srcSha256 == m.sha256) keep += h else fetch += m
        }
        val ids = manifest.mapTo(HashSet()) { it.id }
        val drop = have.filter { it.id !in ids }
        return Plan(fetch, dirs, keep, drop)
    }

    /** The commit's manifest: every node of the move as it was fetched — folders `{ id }`, files `{ id, rev, sha256 }`. */
    fun commitManifest(items: List<DriveVaultLocalItem>): List<DVaultLocalItem> = items.map { i ->
        DVaultLocalItem(id = i.id, parentId = i.parentId, isDir = i.isDir, nameCipher = null, keyVersion = null, rev = i.srcRev ?: 0, sha256 = i.srcSha256)
    }

    /** Is everything of [manifest] here, checked? */
    fun complete(manifest: List<DVaultLocalItem>, have: List<DriveVaultLocalItem>): Boolean = plan(manifest, have).let { it.fetch.isEmpty() && it.dirs.all { d -> have.any { h -> h.id == d.id } } }

    /** The manifest after a `409 changed`: the changed and new nodes take the place of the old, the removed go. */
    fun merge(manifest: List<DVaultLocalItem>, changed: List<DVaultLocalItem>, added: List<DVaultLocalItem>, removed: List<Long>): List<DVaultLocalItem> {
        val gone = removed.toSet()
        val by = LinkedHashMap<Long, DVaultLocalItem>()
        manifest.forEach { if (it.id !in gone) by[it.id] = it }
        (changed + added).forEach { if (it.id !in gone) by[it.id] = it }
        return by.values.toList()
    }

    // ── undo, back, reminders ───────────────────────────────────────

    /**
     * "Rückgängig" (within the 24 hours) only while nothing changed here since the move: the server brings back the
     * content as it was — what was added here since would be lost (vertrag-apps 6.2).
     */
    fun canUndo(folder: DriveVaultLocalFolder, now: Long): Boolean =
        folder.state == DriveVaultLocalFolder.State.LOCAL && !folder.revoked && (folder.purgeAt ?: 0) > now && folder.changedAt <= folder.since

    /** A month (concept 1.7, question 5): the reminder of a new export. */
    const val REMIND_EVERY_MS = 30L * 24 * 60 * 60_000L

    /** The export is missing or older than a month, or something changed since: remind (at most once a month). */
    fun needsBackup(folder: DriveVaultLocalFolder, now: Long): Boolean {
        if (folder.state != DriveVaultLocalFolder.State.LOCAL) return false
        val last = folder.lastExport ?: return true
        return now - last >= REMIND_EVERY_MS || folder.changedAt > last
    }

    fun remindNow(folder: DriveVaultLocalFolder, now: Long): Boolean =
        needsBackup(folder, now) && now - (folder.remindedAt ?: folder.since) >= REMIND_EVERY_MS

    // ── the local trash ─────────────────────────────────────────────

    /** Deleted entries stay this long in the trash of a folder kept here (like Drive's trash). */
    const val TRASH_DAYS = 30

    const val TRASH_MS = TRASH_DAYS * 24L * 60 * 60_000L

    /** When [e] goes for good (ms). */
    fun purgeAt(e: DriveVaultLocalTrashEntry): Long = e.deletedAt + TRASH_MS

    /** The trash, the newest first. */
    fun trashSorted(folder: DriveVaultLocalFolder): List<DriveVaultLocalTrashEntry> = folder.trash.sortedByDescending { it.deletedAt }

    /**
     * [itemId] and everything below it go from [folder]'s entries into its trash, deleted [now]. Nothing changes when
     * it is not an entry (an entry of the trash, the top).
     */
    fun toTrash(folder: DriveVaultLocalFolder, itemId: Long, now: Long): DriveVaultLocalFolder {
        val top = folder.items.firstOrNull { it.id == itemId } ?: return folder
        val gone = subtree(folder, itemId)
        val moved = listOf(top) + folder.items.filter { it.id in gone && it.id != itemId }
        val entry = DriveVaultLocalTrashEntry(id = itemId, parentId = top.parentId, deletedAt = now, items = moved)
        return folder.copy(items = folder.items.filter { it.id !in gone }, trash = folder.trash + entry, changedAt = now)
    }

    /**
     * Where a restore of [e] goes: the folder it was in, if it is still there (not deleted, not in the trash itself),
     * else the top of the folder kept here.
     */
    fun restoreParent(folder: DriveVaultLocalFolder, e: DriveVaultLocalTrashEntry): Long =
        if (e.parentId == folder.nodeId || folder.items.any { it.id == e.parentId && it.isDir }) e.parentId else folder.nodeId

    /**
     * The trash entry [entryId] back into [folder]: under [restoreParent], its top entry with the name cipher
     * [topName] (a free name when the old one is taken there — "Name (2)", as Drive does). Null: no such entry.
     */
    fun restore(folder: DriveVaultLocalFolder, entryId: Long, topName: String?, now: Long): DriveVaultLocalFolder? {
        val e = folder.trash.firstOrNull { it.id == entryId } ?: return null
        val parent = restoreParent(folder, e)
        val back = e.items.map { if (it.id == e.id) it.copy(parentId = parent, name = topName ?: it.name) else it }
        return folder.copy(items = folder.items + back, trash = folder.trash.filter { it.id != entryId }, changedAt = now)
    }

    /** The entries of the trash whose 30 days are over at [now]. */
    fun expired(folder: DriveVaultLocalFolder, now: Long): List<DriveVaultLocalTrashEntry> = folder.trash.filter { purgeAt(it) <= now }

    // ── an entry as a node of the vault page ────────────────────────

    /** An entry as the vault's lists show a node: its opaque name, kv 0 (the name and content open with LK). */
    fun asNode(folder: DriveVaultLocalFolder, i: DriveVaultLocalItem): DNode = DNode(
        id = i.id,
        areaId = folder.areaId,
        parentId = i.parentId,
        name = opaque(i.id),
        isDir = i.isDir,
        size = i.size,
        mime = null,
        mediaId = null,
        rev = 1,
        mtime = if (i.mtime > 0) java.time.Instant.ofEpochMilli(i.mtime).toString() else null,
        updatedAt = null,
        updatedBy = null,
        vaultId = folder.vaultId,
        nameCipher = i.name,
        keyVersion = DriveVaultCrypto.LOCAL_KEY_VERSION,
    )

    /** A stable opaque name of an entry (never the real one). */
    fun opaque(id: Long): String = "%032x".format(id and Long.MAX_VALUE)

    // ── the index file ──────────────────────────────────────────────

    fun toJson(f: DriveVaultLocalFolder): String = JsonObject().apply {
        addProperty("v", 1)
        addProperty("server", f.server)
        addProperty("vaultId", f.vaultId)
        addProperty("nodeId", f.nodeId)
        f.parentId?.let { addProperty("parentId", it) }
        addProperty("areaId", f.areaId)
        f.nameCipher?.let { addProperty("nameCipher", it) }
        addProperty("deviceId", f.deviceId)
        addProperty("state", f.state.name.lowercase())
        addProperty("since", f.since)
        f.purgeAt?.let { addProperty("purgeAt", it) }
        f.lastExport?.let { addProperty("lastExport", it) }
        addProperty("changedAt", f.changedAt)
        if (f.revoked) addProperty("revoked", true)
        f.remindedAt?.let { addProperty("remindedAt", it) }
        add("items", itemsJson(f.items))
        if (f.trash.isNotEmpty()) add("trash", JsonArray().apply {
            f.trash.forEach { t ->
                add(JsonObject().apply {
                    addProperty("id", t.id)
                    addProperty("parentId", t.parentId)
                    addProperty("deletedAt", t.deletedAt)
                    add("items", itemsJson(t.items))
                })
            }
        })
    }.toString()

    private fun itemsJson(items: List<DriveVaultLocalItem>): JsonArray = JsonArray().apply {
        items.forEach { i ->
            add(JsonObject().apply {
                addProperty("id", i.id)
                addProperty("parentId", i.parentId)
                if (i.isDir) addProperty("dir", true)
                addProperty("name", i.name)
                if (!i.isDir) addProperty("size", i.size)
                addProperty("mtime", i.mtime)
                i.srcRev?.let { addProperty("srcRev", it) }
                i.srcSha256?.let { addProperty("srcSha256", it) }
                i.up?.let { addProperty("up", it) }
            })
        }
    }

    private fun itemsFrom(a: JsonArray?): List<DriveVaultLocalItem> = a?.mapNotNull { e ->
        val x = e as? JsonObject ?: return@mapNotNull null
        DriveVaultLocalItem(
            id = x.get("id")?.asLong ?: return@mapNotNull null,
            parentId = x.get("parentId")?.asLong ?: return@mapNotNull null,
            isDir = x.get("dir")?.asBoolean == true,
            name = x.get("name")?.asString ?: return@mapNotNull null,
            size = x.get("size")?.asLong ?: 0,
            mtime = x.get("mtime")?.asLong ?: 0,
            srcRev = x.get("srcRev")?.asInt,
            srcSha256 = x.get("srcSha256")?.asString,
            up = x.get("up")?.asLong,
        )
    }.orEmpty()

    /** The index → the folder; null when it cannot be read (it is kept on disk: nothing of a folder only here is thrown away). */
    fun fromJson(text: String): DriveVaultLocalFolder? = runCatching {
        val o = JsonParser.parseString(text).asJsonObject
        if (o.get("v")?.asInt != 1) return null
        val items = itemsFrom(o.getAsJsonArray("items"))
        val trash = o.getAsJsonArray("trash")?.mapNotNull { e ->
            val x = e as? JsonObject ?: return@mapNotNull null
            val id = x.get("id")?.asLong ?: return@mapNotNull null
            DriveVaultLocalTrashEntry(
                id = id,
                parentId = x.get("parentId")?.asLong ?: return@mapNotNull null,
                deletedAt = x.get("deletedAt")?.asLong ?: return@mapNotNull null,
                items = itemsFrom(x.getAsJsonArray("items")).takeIf { l -> l.any { it.id == id } } ?: return@mapNotNull null,
            )
        }.orEmpty()
        DriveVaultLocalFolder(
            server = o.get("server")?.asString ?: return null,
            vaultId = DriveJson.vaultId(o.get("vaultId")?.asString) ?: return null,
            nodeId = o.get("nodeId")?.asLong ?: return null,
            parentId = o.get("parentId")?.asLong,
            areaId = o.get("areaId")?.asString ?: return null,
            nameCipher = o.get("nameCipher")?.asString,
            deviceId = o.get("deviceId")?.asString ?: return null,
            state = when (o.get("state")?.asString) {
                "moving" -> DriveVaultLocalFolder.State.MOVING
                "returning" -> DriveVaultLocalFolder.State.RETURNING
                else -> DriveVaultLocalFolder.State.LOCAL
            },
            since = o.get("since")?.asLong ?: 0,
            purgeAt = o.get("purgeAt")?.asLong,
            lastExport = o.get("lastExport")?.asLong,
            changedAt = o.get("changedAt")?.asLong ?: 0,
            items = items,
            revoked = o.get("revoked")?.asBoolean == true,
            remindedAt = o.get("remindedAt")?.asLong,
            trash = trash,
        )
    }.getOrNull()

    /** The key file: LK sealed to this phone's device key and to the vault's recovery key (export). */
    data class KeyFile(val device: String, val recovery: String?)

    fun keyJson(k: KeyFile): String = JsonObject().apply {
        addProperty("v", 1)
        addProperty("device", k.device)
        k.recovery?.let { addProperty("recovery", it) }
    }.toString()

    fun keyFromJson(text: String): KeyFile? = runCatching {
        val o = JsonParser.parseString(text).asJsonObject
        if (o.get("v")?.asInt != 1) return null
        KeyFile(o.get("device")?.asString ?: return null, o.get("recovery")?.asString)
    }.getOrNull()
}

/**
 * The folders kept only on this phone, on disk: the index and the files (LK ciphertext) in the app's own files
 * (`filesDir/drive-vault-local/<vault>/<node>/`, not reachable by other apps, no Android backup), the folder key LK
 * sealed to this phone's device key and to the vault's recovery key in the no-backup files
 * (`noBackupFilesDir/drive-vault-local/<vault>/<node>.key`). LK itself is only in memory while its vault is open
 * ([unlock] after the device key was read, [lockKeys] with the vaults). Nothing here is deleted on its own: only the
 * person's own action (back on the server, delete, sign out after the question) removes a folder.
 */
@Singleton
class DriveVaultLocalStore internal constructor(private val dataRoot: File, private val keyRoot: File) {

    @Inject constructor(@ApplicationContext context: Context) : this(File(context.filesDir, DIR), File(context.noBackupFilesDir, DIR))

    private val _folders = MutableStateFlow<Map<Long, DriveVaultLocalFolder>>(emptyMap())

    /** Every folder kept only on this phone, by the placeholder's node id. */
    val folders: StateFlow<Map<Long, DriveVaultLocalFolder>> = _folders.asStateFlow()

    /** node id of the folder → LK, while its vault is open. Never written anywhere in the clear. */
    private val keys = ConcurrentHashMap<Long, ByteArray>()

    @Volatile private var loaded = false

    /** Reads the indexes once (the first use). */
    @Synchronized
    fun load(): Map<Long, DriveVaultLocalFolder> {
        if (loaded) return _folders.value
        val out = HashMap<Long, DriveVaultLocalFolder>()
        dataRoot.listFiles()?.forEach { v ->
            v.listFiles()?.forEach { n ->
                val f = File(n, INDEX)
                if (f.isFile) DriveVaultLocalLogic.fromJson(runCatching { f.readText() }.getOrDefault(""))?.let { out[it.nodeId] = it }
                    ?: Timber.w("Drive vault local: an index is not readable (kept)")
            }
        }
        _folders.value = out
        loaded = true
        return out
    }

    fun get(nodeId: Long): DriveVaultLocalFolder? = load()[nodeId]

    fun ofVault(vaultId: String): List<DriveVaultLocalFolder> = load().values.filter { it.vaultId == vaultId }

    /** The folder the entry [itemId] lies in (a local entry's id is unique over all folders here). */
    fun folderOfItem(itemId: Long): DriveVaultLocalFolder? = load().values.firstOrNull { f -> f.items.any { it.id == itemId } || f.trash.any { t -> t.items.any { it.id == itemId } } }

    private fun dirOf(vaultId: String, nodeId: Long): File? = DriveJson.vaultId(vaultId)?.let { File(File(dataRoot, it), nodeId.toString()) }

    private fun keyFileOf(vaultId: String, nodeId: Long): File? = DriveJson.vaultId(vaultId)?.let { File(File(keyRoot, it), "$nodeId.key") }

    /** The ciphertext (LK) of entry [itemId] of [folder]. */
    fun fileOf(folder: DriveVaultLocalFolder, itemId: Long): File? = dirOf(folder.vaultId, folder.nodeId)?.let { File(File(it, FILES), "$itemId.gcdv") }

    /** A file for what is being written now (it becomes an entry's file with [place]). */
    fun tempFile(folder: DriveVaultLocalFolder): File {
        val dir = File(dirOf(folder.vaultId, folder.nodeId) ?: error("vault id"), TMP).apply { mkdirs() }
        return File.createTempFile("write", ".part", dir)
    }

    /** [tmp] becomes the file of entry [itemId] (replacing an older one). */
    fun place(folder: DriveVaultLocalFolder, itemId: Long, tmp: File) {
        val target = fileOf(folder, itemId) ?: error("vault id")
        target.parentFile?.mkdirs()
        if (!tmp.renameTo(target)) {
            target.delete()
            check(tmp.renameTo(target)) { "not placed" }
        }
    }

    /** Free room where the folders lie. */
    fun usableSpace(): Long = dataRoot.apply { mkdirs() }.usableSpace

    /** Writes [folder]'s index (atomically) and shows it. */
    @Synchronized
    fun put(folder: DriveVaultLocalFolder) {
        load()
        val dir = dirOf(folder.vaultId, folder.nodeId) ?: return
        dir.mkdirs()
        val f = File(dir, INDEX)
        val tmp = File(dir, "$INDEX.tmp")
        tmp.writeText(DriveVaultLocalLogic.toJson(folder))
        if (!tmp.renameTo(f)) {
            f.delete()
            check(tmp.renameTo(f)) { "index not written" }
        }
        _folders.value = _folders.value + (folder.nodeId to folder)
    }

    /** [folder] changed by [change] (read, changed and written under the store's lock). */
    @Synchronized
    fun update(nodeId: Long, change: (DriveVaultLocalFolder) -> DriveVaultLocalFolder): DriveVaultLocalFolder? {
        val now = get(nodeId) ?: return null
        val next = change(now)
        put(next)
        return next
    }

    /**
     * This device is (again) a device of [vaultId] or no longer: the folders of the vault lock while it is not (export
     * and delete only — nothing goes on its own, concept 1.7).
     */
    @Synchronized
    fun setRevoked(vaultId: String, revoked: Boolean) {
        ofVault(vaultId).filter { it.revoked != revoked }.forEach { f -> runCatching { put(f.copy(revoked = revoked)) } }
    }

    /**
     * The trash entries of [nodeId] whose 30 days are over ([now]) go for good: their files and their index entries.
     * Needs no key (nothing is decrypted). Returns how many went.
     */
    @Synchronized
    fun purgeExpired(nodeId: Long, now: Long): Int {
        val f = get(nodeId) ?: return 0
        val due = DriveVaultLocalLogic.expired(f, now)
        if (due.isEmpty()) return 0
        dropTrash(nodeId, due.mapTo(HashSet()) { it.id })
        return due.size
    }

    /**
     * The trash entries [entryIds] of [nodeId] go for good: first out of the index, then their files (only theirs —
     * never what is being written right now). Returns the folder as it is then.
     */
    @Synchronized
    fun dropTrash(nodeId: Long, entryIds: Set<Long>): DriveVaultLocalFolder? {
        val f = get(nodeId) ?: return null
        val gone = f.trash.filter { it.id in entryIds }
        if (gone.isEmpty()) return f
        val next = update(nodeId) { x -> x.copy(trash = x.trash.filter { it.id !in entryIds }) } ?: return null
        gone.flatMap { it.items }.filter { !it.isDir }.forEach { i -> runCatching { fileOf(next, i.id)?.delete() } }
        return next
    }

    /** [purgeExpired] for every folder kept here (opening a vault, the daily look). */
    fun purgeExpiredAll(now: Long): Int = load().keys.sumOf { runCatching { purgeExpired(it, now) }.getOrDefault(0) }

    /** Removes the folder from this phone: its files, its index and its key (only the person's own choice comes here). */
    @Synchronized
    fun remove(nodeId: Long) {
        val f = get(nodeId) ?: return
        keys.remove(nodeId)?.fill(0)
        runCatching { dirOf(f.vaultId, f.nodeId)?.deleteRecursively() }
        runCatching { keyFileOf(f.vaultId, f.nodeId)?.delete() }
        _folders.value = _folders.value - nodeId
    }

    /** The entries' files that are no entry any more (an interrupted delete or write) and the files being written go. */
    fun tidy(folder: DriveVaultLocalFolder) {
        val dir = dirOf(folder.vaultId, folder.nodeId) ?: return
        runCatching { File(dir, TMP).deleteRecursively() }
        // the files of the trash stay as well (until it is emptied or their 30 days are over)
        val ids = (folder.items + folder.trash.flatMap { it.items }).filter { !it.isDir }.mapTo(HashSet()) { "${it.id}.gcdv" }
        File(dir, FILES).listFiles()?.forEach { if (it.name !in ids) it.delete() }
    }

    // ── the folder key LK ───────────────────────────────────────────

    /**
     * A new LK for [nodeId] of [vaultId]: sealed to this phone's device key ([devicePublic]) and to the vault's recovery
     * key ([recoveryPub], base64url; null when the vault has none — then no export is possible) and kept in memory.
     */
    fun newKey(vaultId: String, nodeId: Long, devicePublic: ByteArray, recoveryPub: String?): ByteArray {
        val lk = DriveVaultCrypto.randomBytes(DriveVaultCrypto.KEY_BYTES)
        writeKey(vaultId, nodeId, lk, devicePublic, recoveryPub?.let { pub -> DriveVaultCrypto.sealLocalKey(lk, DriveVaultCrypto.unb64u(pub), DriveVaultCrypto.localKeyInfo(vaultId, nodeId)) })
        return lk
    }

    /** Keeps [lk] for [nodeId] (sealed to the device key; [recoverySealed] as it came, e.g. from an export). */
    fun writeKey(vaultId: String, nodeId: Long, lk: ByteArray, devicePublic: ByteArray, recoverySealed: String?) {
        val file = keyFileOf(vaultId, nodeId) ?: error("vault id")
        val k = DriveVaultLocalLogic.KeyFile(
            device = DriveVaultCrypto.sealLocalKey(lk, devicePublic, DriveVaultCrypto.localDeviceInfo(vaultId, nodeId)),
            recovery = recoverySealed,
        )
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(DriveVaultLocalLogic.keyJson(k))
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "key not written" }
        }
        keys.put(nodeId, lk.copyOf())?.fill(0)
    }

    /** LK sealed to the vault's recovery key (for the export); null when there is none. */
    fun recoverySealed(folder: DriveVaultLocalFolder): String? =
        keyFileOf(folder.vaultId, folder.nodeId)?.takeIf { it.isFile }?.let { DriveVaultLocalLogic.keyFromJson(it.readText()) }?.recovery

    /** The vault [vaultId] is open: the LK of its folders here, with the device's key pair [device] (read just now). */
    fun unlock(vaultId: String, device: DriveVaultCrypto.KeyPair) {
        for (f in ofVault(vaultId)) {
            if (keys.containsKey(f.nodeId)) continue
            val k = keyFileOf(vaultId, f.nodeId)?.takeIf { it.isFile }?.let { DriveVaultLocalLogic.keyFromJson(runCatching { it.readText() }.getOrDefault("")) } ?: continue
            runCatching { DriveVaultCrypto.openLocalKey(k.device, device, DriveVaultCrypto.localDeviceInfo(vaultId, f.nodeId)) }
                .onSuccess { keys[f.nodeId] = it }
                .onFailure { Timber.w("Drive vault local: a folder key is not readable with this device key") }
        }
    }

    /** LK of the folder [nodeId] (null: its vault is locked, or the key is not readable). */
    fun key(nodeId: Long): ByteArray? = keys[nodeId]

    /** LK of the folder the entry [itemId] lies in. */
    fun keyOfItem(itemId: Long): ByteArray? = folderOfItem(itemId)?.let { keys[it.nodeId] }

    /** The vaults lock: every LK in memory is overwritten and forgotten. */
    fun lockKeys() {
        val gone = keys.values.toList().also { keys.clear() }
        gone.forEach { it.fill(0) }
    }

    companion object {
        const val DIR = "drive-vault-local"
        private const val INDEX = "index.json"
        private const val FILES = "f"
        private const val TMP = "tmp"
    }
}
