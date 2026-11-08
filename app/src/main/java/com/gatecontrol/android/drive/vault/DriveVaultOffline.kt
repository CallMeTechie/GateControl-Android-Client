package com.gatecontrol.android.drive.vault

import android.content.Context
import com.gatecontrol.android.drive.DFolder
import com.gatecontrol.android.drive.DNode
import com.gatecontrol.android.drive.DVault
import com.gatecontrol.android.drive.DVaultWrap
import com.gatecontrol.android.drive.DriveJson
import com.gatecontrol.android.drive.DriveOfflineCopy
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What this phone keeps of a vault so it opens without a connection ("Tresor-Dateien offline"): the vault as the server
 * describes it ([vault]: its id, folder, encrypted name, key version, my devices), this device's **wrapped** keys of
 * every key version ([keys]: sealed to the device key, which itself only the Android Keystore opens) and the folder
 * listings seen ([folders]: opaque names, encrypted names, sizes). Nothing in it is readable without the device key —
 * no vault key (TK), no name, no content. [server] and [deviceId] say whom it belongs to.
 */
data class DriveVaultOfflineEntry(
    val server: String,
    val deviceId: String,
    val vault: DVault,
    val keys: List<DVaultWrap>,
    val folders: Map<Long, DFolder> = emptyMap(),
    val at: Long = 0L,
)

/** Pure rules of the vault files kept offline (unit-tested). */
object DriveVaultOfflineLogic {
    /** At most this many folder listings are kept per vault (the newest). */
    const val MAX_FOLDERS = 300

    /** Where a node of a vault stands with "Offline verfügbar". */
    enum class Mark {
        /** Not marked, nothing above it either. */
        NONE,

        /** Marked itself. */
        SELF,

        /** A folder above it (inside the vault) is marked. */
        FOLDER,

        /** "Ganzen Tresor offline halten". */
        VAULT,
    }

    /**
     * The mark of [nodeId] with its folders [ancestors] (top first, e.g. a folder's crumbs and the folder), given the
     * vault's folder [vaultRoot] and the marked node ids [marks]. Only marks inside the vault count: a vault's content
     * is kept by a mark of the vault, of a folder of it or of the file itself, never by a mark of a folder outside.
     */
    fun markOf(nodeId: Long, ancestors: List<Long>, vaultRoot: Long, marks: Set<Long>): Mark {
        if (nodeId == vaultRoot) return if (vaultRoot in marks) Mark.VAULT else Mark.NONE
        if (nodeId in marks) return Mark.SELF
        val start = ancestors.indexOf(vaultRoot)
        if (start < 0) return Mark.NONE
        if (vaultRoot in marks) return Mark.VAULT
        return if (ancestors.subList(start + 1, ancestors.size).any { it in marks }) Mark.FOLDER else Mark.NONE
    }

    /** The folders of a vault page from the vault's folder down to [folder] itself (its crumbs and its own id). */
    fun ancestors(folder: DFolder): List<Long> = folder.crumbs.map { it.id } + folder.node.id

    /**
     * May a node found while looking into the marked folder [mark] be kept? A vault's content only below a mark of the
     * same vault, plain files only below plain marks (an old Drive may still list a vault inside a plain folder).
     */
    fun include(mark: DNode, child: DNode): Boolean =
        if (mark.encrypted) child.encrypted && child.vaultId == mark.vaultId else !child.encrypted

    /** How a vault row stands on this phone. */
    enum class RowState {
        /** Not offline. */
        NONE,

        /** On this phone (also an older version while the new one loads): opens without a connection. */
        OFFLINE,

        /** Loading now, or queued. */
        LOADING,

        /** Waiting (WLAN, space). */
        WAITING,

        /** Could not be loaded. */
        FAILED,
    }

    fun rowState(copy: DriveOfflineCopy?): RowState = when {
        copy == null -> RowState.NONE
        copy.hasFile -> RowState.OFFLINE
        copy.state == DriveOfflineCopy.State.FAILED -> RowState.FAILED
        copy.reason != null -> RowState.WAITING
        else -> RowState.LOADING
    }

    /** The copies of [vaultId] (also those still to come). */
    fun ofVault(copies: Collection<DriveOfflineCopy>, vaultId: String): List<DriveOfflineCopy> = copies.filter { it.vaultId == vaultId }

    /** The copies of [vaultId] that lie below the folder [folderId] (the vault's folder [vaultRoot]: all of them). */
    fun below(copies: Collection<DriveOfflineCopy>, vaultId: String, vaultRoot: Long, folderId: Long): List<DriveOfflineCopy> =
        ofVault(copies, vaultId).filter { folderId == vaultRoot || folderId in it.parents }

    /** "7 von 19 Dateien offline · 48 MB": [stored] on this phone of [total] kept for the marks, [bytes] of ciphertext. */
    data class Summary(val stored: Int, val total: Int, val bytes: Long)

    fun summary(copies: Collection<DriveOfflineCopy>): Summary = Summary(
        stored = copies.count { it.hasFile },
        total = copies.size,
        bytes = copies.filter { it.hasFile }.sumOf { it.size.coerceAtLeast(0) },
    )

    /**
     * Without a connection, a page of the vault: [ready] the files on this phone below the folder (they open, decrypted
     * in memory as always), [netOnly] the other files of the folder as last seen (shown greyed out, "Nur mit Netz").
     */
    data class OfflinePage(val ready: List<DriveOfflineCopy>, val netOnly: List<DNode>)

    fun offlinePage(vaultId: String, vaultRoot: Long, folderId: Long, seen: List<DNode>?, copies: Collection<DriveOfflineCopy>): OfflinePage {
        val ready = below(copies, vaultId, vaultRoot, folderId).filter { it.hasFile }
        val here = ready.mapTo(HashSet()) { it.id }
        val netOnly = seen.orEmpty().filter { !it.isDir && it.vaultId == vaultId && it.id !in here }
        return OfflinePage(ready, netOnly)
    }

    /** The answer of `GET vaults/<id>/keys` for this device: anything but `approved` ends what it keeps of the vault. */
    fun revoked(state: String?): Boolean = state != "approved"

    /** The vaults whose offline data stays: those with copies (also ones still to come). */
    fun keep(copies: Collection<DriveOfflineCopy>): Set<String> = copies.mapNotNullTo(HashSet()) { it.vaultId }

    /** The copies without those of [vaultId] → (kept, gone). */
    fun withoutVault(have: Map<Long, DriveOfflineCopy>, vaultId: String): Pair<Map<Long, DriveOfflineCopy>, List<DriveOfflineCopy>> {
        val (gone, keep) = have.values.partition { it.vaultId == vaultId }
        return keep.associateBy { it.id } to gone
    }

    /** The entry may unlock on this phone now: the same server, this device, at least one wrapped key. */
    fun usable(e: DriveVaultOfflineEntry?, server: String, deviceId: String?): Boolean =
        e != null && server.isNotEmpty() && e.server == server && deviceId != null && e.deviceId == deviceId && e.keys.isNotEmpty()

    /** [e] with the listing [folder] (the newest [MAX_FOLDERS] stay). */
    fun withFolder(e: DriveVaultOfflineEntry, folder: DFolder): DriveVaultOfflineEntry {
        val items = folder.items.filter { it.vaultId == e.vault.id }
        val m = LinkedHashMap(e.folders)
        m.remove(folder.node.id)
        m[folder.node.id] = folder.copy(items = items, total = items.size, next = null)
        while (m.size > MAX_FOLDERS) m.remove(m.keys.first())
        return e.copy(folders = m)
    }

    /** The encrypted name of the folder [id] as last seen (its own listing, or a listing it is in). */
    fun folderCipher(e: DriveVaultOfflineEntry?, id: Long): String? {
        if (e == null) return null
        if (id == e.vault.nodeId) return e.vault.nameCipher
        e.folders[id]?.node?.nameCipher?.let { return it }
        return e.folders.values.firstNotNullOfOrNull { f -> f.items.firstOrNull { it.id == id }?.nameCipher }
    }

    // ─── the file on disk (the server's own format, read back with DriveJson) ─────

    fun nodeJson(n: DNode): JsonObject = JsonObject().apply {
        addProperty("id", n.id)
        addProperty("areaId", n.areaId)
        n.parentId?.let { addProperty("parentId", it) }
        addProperty("name", n.name)
        addProperty("isDir", n.isDir)
        addProperty("size", n.size)
        n.mime?.let { addProperty("mime", it) }
        addProperty("rev", n.rev)
        n.mtime?.let { addProperty("mtime", it) }
        n.updatedAt?.let { addProperty("updatedAt", it) }
        n.sha256?.let { addProperty("sha256", it) }
        addProperty("encrypted", true)
        n.vaultId?.let { addProperty("vaultId", it) }
        n.nameCipher?.let { addProperty("nameCipher", it) }
        n.keyVersion?.let { addProperty("keyVersion", it) }
    }

    fun vaultJson(v: DVault): JsonObject = JsonObject().apply {
        addProperty("id", v.id)
        addProperty("nodeId", v.nodeId)
        v.areaId?.let { addProperty("areaId", it) }
        v.parentId?.let { addProperty("parentId", it) }
        v.nameCipher?.let { addProperty("nameCipher", it) }
        addProperty("keyVersion", v.keyVersion)
        v.recoveryPub?.let { addProperty("recoveryPub", it) }
        addProperty("needsRotation", v.needsRotation)
        add("myDevices", JsonArray().apply {
            v.myDevices.forEach { d ->
                add(JsonObject().apply {
                    addProperty("deviceId", d.deviceId)
                    addProperty("state", d.state)
                    d.name?.let { addProperty("name", it) }
                })
            }
        })
        // the requests of other devices need the server anyway
        addProperty("pending", 0)
    }

    fun toJson(e: DriveVaultOfflineEntry): String = JsonObject().apply {
        addProperty("v", 1)
        addProperty("server", e.server)
        addProperty("deviceId", e.deviceId)
        addProperty("at", e.at)
        add("vault", vaultJson(e.vault))
        add("keys", JsonArray().apply {
            e.keys.forEach { w -> add(JsonObject().apply { addProperty("keyVersion", w.keyVersion); addProperty("wrap", w.wrap) }) }
        })
        add("folders", JsonArray().apply {
            e.folders.values.forEach { f ->
                add(JsonObject().apply {
                    add("node", nodeJson(f.node).apply {
                        add("crumbs", JsonArray().apply { f.crumbs.forEach { c -> add(JsonObject().apply { addProperty("id", c.id); addProperty("name", c.name) }) } })
                        addProperty("role", f.role)
                    })
                    add("items", JsonArray().apply { f.items.forEach { add(nodeJson(it)) } })
                    addProperty("role", f.role)
                })
            }
        })
    }.toString()

    /** The file → the entry; null when it cannot be read or is not of [vaultId] (then it goes). */
    fun fromJson(text: String, vaultId: String): DriveVaultOfflineEntry? = runCatching {
        val o = JsonParser.parseString(text).asJsonObject
        if (o.get("v")?.asInt != 1) return null
        val vault = DriveJson.vault(o.getAsJsonObject("vault")) ?: return null
        if (vault.id != vaultId) return null
        val keys = DriveJson.vaultKeys(JsonObject().apply {
            addProperty("deviceId", o.get("deviceId")?.asString)
            addProperty("state", "approved")
            add("keys", o.getAsJsonArray("keys") ?: JsonArray())
        }) ?: return null
        val folders = LinkedHashMap<Long, DFolder>()
        o.getAsJsonArray("folders")?.forEach { e ->
            val f = (e as? JsonObject)?.let { DriveJson.folder(JsonObject().apply { add("node", it.get("node")) }, it) } ?: return@forEach
            if (f.node.vaultId == vaultId) folders[f.node.id] = f.copy(items = f.items.filter { it.vaultId == vaultId })
        }
        DriveVaultOfflineEntry(
            server = o.get("server")?.asString ?: return null,
            deviceId = keys.deviceId,
            vault = vault,
            keys = keys.keys,
            folders = folders,
            at = o.get("at")?.asLong ?: 0L,
        )
    }.getOrNull()
}

/**
 * The [DriveVaultOfflineEntry] of each vault with files kept offline, one file a vault in the no-backup files
 * (`noBackupFilesDir/drive-vault/offline/<vault id>.json`, next to the device key it needs). Written whenever this
 * phone talks to the vault while it has offline files of it (the background run fetches the wrapped keys of every key
 * version, the vault's pages store the listings they show); deleted with the last offline file of the vault, when the
 * server says this device is no longer approved, and with the login.
 */
@Singleton
class DriveVaultOfflineCache internal constructor(private val dir: File) {

    @Inject constructor(@ApplicationContext context: Context) : this(File(File(context.noBackupFilesDir, "drive-vault"), "offline"))

    private val entries = HashMap<String, DriveVaultOfflineEntry?>()

    private fun fileOf(vaultId: String): File? = DriveJson.vaultId(vaultId)?.let { File(dir, "$it.json") }

    @Synchronized
    fun get(vaultId: String): DriveVaultOfflineEntry? {
        if (entries.containsKey(vaultId)) return entries[vaultId]
        val f = fileOf(vaultId) ?: return null
        val e = if (f.isFile) runCatching { DriveVaultOfflineLogic.fromJson(f.readText(), vaultId) }.getOrNull().also { if (it == null) f.delete() } else null
        entries[vaultId] = e
        return e
    }

    /** The vault and this device's wrapped keys (an answer of `GET keys` with state `approved`); the listings stay. */
    @Synchronized
    fun putVault(server: String, deviceId: String, vault: DVault, keys: List<DVaultWrap>, now: Long = System.currentTimeMillis()) {
        if (keys.isEmpty()) return
        val old = get(vault.id)?.takeIf { it.server == server && it.deviceId == deviceId }
        write(DriveVaultOfflineEntry(server, deviceId, vault, keys.sortedBy { it.keyVersion }, old?.folders.orEmpty(), now))
    }

    /** A listing of the vault as shown (only while there is an entry: a vault with offline files on this device). */
    @Synchronized
    fun putFolder(vaultId: String, folder: DFolder) {
        val e = get(vaultId) ?: return
        if (folder.node.vaultId != vaultId) return
        write(DriveVaultOfflineLogic.withFolder(e, folder))
    }

    @Synchronized
    fun forget(vaultId: String) {
        entries[vaultId] = null
        fileOf(vaultId)?.delete()
    }

    /** Only these vaults keep their entry. */
    @Synchronized
    fun keep(vaultIds: Set<String>) {
        vaultIds().filter { it !in vaultIds }.forEach(::forget)
    }

    @Synchronized
    fun forgetAll() {
        entries.clear()
        runCatching { dir.deleteRecursively() }
    }

    /** The vaults with an entry on this phone. */
    @Synchronized
    fun vaultIds(): Set<String> =
        dir.listFiles()?.mapNotNullTo(HashSet()) { f -> f.name.removeSuffix(".json").takeIf { f.name.endsWith(".json") && DriveJson.vaultId(it) != null } }.orEmpty()

    private fun write(e: DriveVaultOfflineEntry) {
        val f = fileOf(e.vault.id) ?: return
        entries[e.vault.id] = e
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, f.name + ".tmp")
            tmp.writeText(DriveVaultOfflineLogic.toJson(e))
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        }.onFailure { Timber.w("Drive vault: offline entry not written (%s)", it.javaClass.simpleName) }
    }
}
