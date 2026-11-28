package com.gatecontrol.android.drive.vault

import android.content.Context
import android.net.Uri
import com.gatecontrol.android.drive.DResult
import com.gatecontrol.android.drive.DVault
import com.gatecontrol.android.drive.DriveApi
import com.gatecontrol.android.drive.DriveJson
import com.gatecontrol.android.drive.DriveLogic
import com.gatecontrol.android.drive.notOk
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The export of a folder kept only on this phone ("Sicherung exportieren", `.gctresor`): its content exactly as it lies
 * here — encrypted with the folder's key LK — and LK sealed to the vault's **recovery key** (info
 * `gcdrive-v1:lk:<vaultId>:<nodeId>`). Without the 56 characters of the recovery key nothing in it is readable; there
 * is no new password. Pure JVM (unit-tested):
 *
 *   "GCTRESOR" ‖ 0x01 ‖ u32 n ‖ header (n bytes, JSON) ‖ HMAC-SHA256(macKey, everything before) (32) ‖ the files
 *   header   { v, vaultId, nodeId, lk (sealed), name (the folder's name in LK), exportedAt,
 *              items: [{ id, parentId, dir, name, size, mtime, len, sha256 }] } — folders first, the files in this order
 *   macKey   HKDF-SHA256(LK, salt empty, "gcdrive-v1:export:<vaultId>")
 *
 * The header (and through each file's SHA-256 every file) is checked with LK once the recovery key opened it.
 */
object DriveVaultBackupFormat {
    private val MAGIC = "GCTRESOR".toByteArray(Charsets.US_ASCII)
    private const val VERSION: Byte = 1
    private const val MAX_HEADER = 16 * 1_048_576

    data class Entry(val id: Long, val parentId: Long, val isDir: Boolean, val name: String, val size: Long, val mtime: Long, val len: Long, val sha256: String?)

    data class Header(val vaultId: String, val nodeId: Long, val sealed: String, val name: String, val exportedAt: Long, val items: List<Entry>)

    private fun macKey(lk: ByteArray, vaultId: String) = DriveVaultCrypto.hkdf(lk, ByteArray(0), "gcdrive-v1:export:$vaultId")

    private fun mac(lk: ByteArray, vaultId: String, data: ByteArray): ByteArray {
        val k = macKey(lk, vaultId)
        try {
            return Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(k, "HmacSHA256")) }.doFinal(data)
        } finally {
            k.fill(0)
        }
    }

    private fun headerJson(h: Header): ByteArray = JsonObject().apply {
        addProperty("v", 1)
        addProperty("vaultId", h.vaultId)
        addProperty("nodeId", h.nodeId)
        addProperty("lk", h.sealed)
        addProperty("name", h.name)
        addProperty("exportedAt", h.exportedAt)
        add("items", JsonArray().apply {
            h.items.forEach { e ->
                add(JsonObject().apply {
                    addProperty("id", e.id)
                    addProperty("parentId", e.parentId)
                    if (e.isDir) addProperty("dir", true)
                    addProperty("name", e.name)
                    if (!e.isDir) {
                        addProperty("size", e.size)
                        addProperty("len", e.len)
                        e.sha256?.let { addProperty("sha256", it) }
                    }
                    addProperty("mtime", e.mtime)
                })
            }
        })
    }.toString().toByteArray(Charsets.UTF_8)

    /** Writes the export: [h] (its files' [Entry.len] and [Entry.sha256] filled in) and each file's bytes from [open]. */
    fun write(out: OutputStream, h: Header, lk: ByteArray, open: (Entry) -> InputStream) {
        val json = headerJson(h)
        val start = MAGIC + byteArrayOf(VERSION) + ByteBuffer.allocate(4).putInt(json.size).array() + json
        out.write(start)
        out.write(mac(lk, h.vaultId, start))
        val buf = ByteArray(256 * 1024)
        for (e in h.items) {
            if (e.isDir) continue
            var left = e.len
            open(e).use { inp ->
                while (left > 0) {
                    val n = inp.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) throw EOFException("file shorter than announced")
                    out.write(buf, 0, n)
                    left -= n
                }
            }
        }
        out.flush()
    }

    /** What was read before the recovery key: the header and its bytes (for the check), the stream at the first file. */
    class Opened(val header: Header, private val signed: ByteArray, private val tag: ByteArray, val input: InputStream) {
        /** The header is the one written with [lk] (and so every file's SHA-256 in it). */
        fun check(lk: ByteArray): Boolean = MessageDigest.isEqual(mac(lk, header.vaultId, signed), tag)
    }

    /** Reads the head of an export; `invalid` when it is none (or a later version). */
    fun open(input: InputStream): Opened {
        val d = DataInputStream(input)
        val magic = ByteArray(MAGIC.size)
        try {
            d.readFully(magic)
            if (!magic.contentEquals(MAGIC)) throw DriveVaultCrypto.DriveVaultException("invalid", "not an export")
            val v = d.readByte()
            if (v != VERSION) throw DriveVaultCrypto.DriveVaultException("invalid", "version")
            val n = d.readInt()
            if (n <= 0 || n > MAX_HEADER) throw DriveVaultCrypto.DriveVaultException("invalid", "header")
            val json = ByteArray(n).also { d.readFully(it) }
            val tag = ByteArray(32).also { d.readFully(it) }
            val signed = MAGIC + byteArrayOf(v) + ByteBuffer.allocate(4).putInt(n).array() + json
            return Opened(parse(String(json, Charsets.UTF_8)), signed, tag, input)
        } catch (e: EOFException) {
            throw DriveVaultCrypto.DriveVaultException("invalid", "too short")
        }
    }

    private fun parse(text: String): Header {
        val o = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: throw DriveVaultCrypto.DriveVaultException("invalid", "header")
        if (o.get("v")?.asInt != 1) throw DriveVaultCrypto.DriveVaultException("invalid", "version")
        return runCatching {
            Header(
                vaultId = DriveJson.vaultId(o.get("vaultId").asString)!!,
                nodeId = o.get("nodeId").asLong,
                sealed = o.get("lk").asString,
                name = o.get("name").asString,
                exportedAt = o.get("exportedAt")?.asLong ?: 0,
                items = o.getAsJsonArray("items").map { e ->
                    val x = e.asJsonObject
                    val dir = x.get("dir")?.asBoolean == true
                    Entry(
                        id = x.get("id").asLong,
                        parentId = x.get("parentId").asLong,
                        isDir = dir,
                        name = x.get("name").asString,
                        size = x.get("size")?.asLong ?: 0,
                        mtime = x.get("mtime")?.asLong ?: 0,
                        len = if (dir) 0 else x.get("len").asLong.also { require(it >= 0) },
                        sha256 = x.get("sha256")?.asString,
                    )
                },
            )
        }.getOrElse { throw DriveVaultCrypto.DriveVaultException("invalid", "header") }
    }

    /** The entries in the order of an export: folders top down, then the files. */
    fun order(folder: DriveVaultLocalFolder): List<DriveVaultLocalItem> {
        val all = DriveVaultLocalLogic.reachable(folder)
        val dirs = all.filter { it.isDir }.sortedBy { DriveVaultLocalLogic.path(folder, it.id).size }
        return dirs + all.filter { !it.isDir }
    }

    fun sha256(file: File): String = file.inputStream().buffered(256 * 1024).use(DriveVaultLocalCrypt::sha256)
}

/**
 * "Sicherung exportieren" and "Sicherung importieren" on the phone: the export to a place the person picks (Android's
 * storage access: SD card, USB stick, Downloads, a folder of another cloud app) and the way back of such a file into a
 * vault — opened with the recovery key, every file checked, then uploaded with the vault's current key as a new folder
 * (the placeholder of the lost device is removed on its own sheet, "Platzhalter entfernen").
 */
@Singleton
class DriveVaultLocalBackup internal constructor(
    private val api: DriveApi,
    private val store: DriveVaultLocalStore,
    private val keys: (String, Int) -> ByteArray?,
    private val currentVersion: (String) -> Int?,
    private val streams: Streams,
    private val tmpDir: () -> File,
    private val now: () -> Long = System::currentTimeMillis,
) {
    @Inject constructor(@ApplicationContext context: Context, api: DriveApi, store: DriveVaultLocalStore, session: DriveVaultSession) : this(
        api = api,
        store = store,
        keys = { v, k -> session.vaultKey(v, k) },
        currentVersion = { session.keyVersion(it) },
        streams = object : Streams {
            override fun output(uri: Uri): OutputStream? = context.contentResolver.openOutputStream(uri, "w")
            override fun input(uri: Uri): InputStream? = context.contentResolver.openInputStream(uri)
            override fun delete(uri: Uri) { runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) } }
        },
        tmpDir = { File(context.cacheDir, "drive-vault-backup") },
    )

    /** Where the export goes and where an import comes from (the person's pick). */
    interface Streams {
        fun output(uri: Uri): OutputStream?
        fun input(uri: Uri): InputStream?
        fun delete(uri: Uri)
    }

    /** The suggested file name: the folder's name and `.gctresor`. */
    fun fileName(name: String): String = (DriveLogic.safeName(name) ?: "Tresor") + ".gctresor"

    /**
     * Writes the export of the folder [nodeId] (its decrypted [name]) to [uri]. Needs LK in memory (the vault open, or
     * unlocked with the device key) and a recovery key of the vault (else `no_recovery`). The folder notes the time.
     */
    suspend fun export(nodeId: Long, name: String, uri: Uri): DResult<Unit> = withContext(Dispatchers.IO) {
        val f = store.get(nodeId) ?: return@withContext DResult.Failed("not_found", 0)
        val lk = store.key(nodeId) ?: return@withContext DResult.Failed("locked", 0)
        val sealed = store.recoverySealed(f) ?: return@withContext DResult.Failed("no_recovery", 0)
        val entries = DriveVaultBackupFormat.order(f).map { i ->
            val file = if (i.isDir) null else store.fileOf(f, i.id)?.takeIf { it.isFile } ?: return@withContext DResult.Failed("failed", 0)
            DriveVaultBackupFormat.Entry(i.id, i.parentId, i.isDir, i.name, i.size, i.mtime, file?.length() ?: 0, file?.let(DriveVaultBackupFormat::sha256))
        }
        val h = DriveVaultBackupFormat.Header(
            vaultId = f.vaultId, nodeId = f.nodeId, sealed = sealed,
            name = DriveVaultCrypto.encryptName(lk, f.vaultId, DriveVaultCrypto.LOCAL_KEY_VERSION, name),
            exportedAt = now(), items = entries,
        )
        try {
            val out = streams.output(uri) ?: return@withContext DResult.Failed("write", 0)
            out.buffered(256 * 1024).use { o -> DriveVaultBackupFormat.write(o, h, lk) { e -> store.fileOf(f, e.id)!!.inputStream() } }
        } catch (e: CancellationException) {
            streams.delete(uri)
            throw e
        } catch (e: Exception) {
            streams.delete(uri)
            return@withContext DResult.Failed("write", 0)
        }
        store.update(nodeId) { it.copy(lastExport = now(), remindedAt = now()) }
        DResult.Ok(Unit)
    }

    /** What an import brought: the new folder on the server and how many files are in it. */
    data class Imported(val folderId: Long, val name: String, val files: Int)

    /**
     * An export [uri] back into [vault]: opened with the written-down [recoveryKey] (`recovery_key` when it does not
     * open it — perhaps a newer one was made since), checked (`backup_damaged`), then uploaded as a new folder [name]
     * in [parentId] with the vault's current key — every file checked on the way. [taken]: names in [parentId].
     */
    suspend fun import(vault: DVault, parentId: Long, uri: Uri, recoveryKey: String, taken: List<String>, onProgress: (String, Float) -> Unit = { _, _ -> }): DResult<Imported> = withContext(Dispatchers.IO) {
        val key = try {
            DriveVaultCrypto.parseRecoveryKey(recoveryKey)
        } catch (e: DriveVaultCrypto.DriveVaultException) {
            return@withContext DResult.Failed("recovery_key", 0)
        }
        val pair = DriveVaultCrypto.recoveryKeyPair(key)
        key.fill(0)
        var lk: ByteArray? = null
        try {
            val input = streams.input(uri) ?: return@withContext DResult.Failed("read", 0)
            input.buffered(256 * 1024).use { inp ->
                val opened = try {
                    DriveVaultBackupFormat.open(inp)
                } catch (e: DriveVaultCrypto.DriveVaultException) {
                    return@withContext DResult.Failed("backup_invalid", 0)
                }
                val h = opened.header
                if (h.vaultId != vault.id) return@withContext DResult.Failed("backup_other_vault", 0)
                val k = try {
                    DriveVaultCrypto.openLocalKey(h.sealed, pair, DriveVaultCrypto.localKeyInfo(h.vaultId, h.nodeId))
                } catch (e: DriveVaultCrypto.DriveVaultException) {
                    return@withContext DResult.Failed("recovery_key", 0)
                }
                lk = k
                if (!opened.check(k)) return@withContext DResult.Failed("backup_damaged", 0)
                val lkKeys: (Int) -> ByteArray? = { v -> if (v == DriveVaultCrypto.LOCAL_KEY_VERSION) k else null }
                val v = currentVersion(vault.id) ?: return@withContext DResult.Failed("locked", 0)
                val tk = keys(vault.id, v) ?: return@withContext DResult.Failed("locked", 0)
                val folderName = runCatching { DriveVaultCrypto.decryptName(lkKeys, vault.id, h.name) }.getOrNull() ?: return@withContext DResult.Failed("backup_damaged", 0)
                val top = DriveLogic.freeName(folderName, taken)
                val made = api.createVaultFolder(parentId, DriveVaultCrypto.encryptName(tk, vault.id, v, top), v)
                if (made !is DResult.Ok) return@withContext made.notOk()
                val ids = HashMap<Long, Long>().apply { put(h.nodeId, made.value.id) }
                val files = h.items.count { !it.isDir }
                var done = 0
                for (e in h.items) {
                    val parent = ids[e.parentId] ?: return@withContext DResult.Failed("backup_damaged", 0)
                    val plainName = runCatching { DriveVaultCrypto.decryptName(lkKeys, vault.id, e.name) }.getOrNull() ?: return@withContext DResult.Failed("backup_damaged", 0)
                    if (e.isDir) {
                        val d = api.createVaultFolder(parent, DriveVaultCrypto.encryptName(tk, vault.id, v, plainName), v)
                        if (d !is DResult.Ok) return@withContext d.notOk()
                        ids[e.id] = d.value.id
                        continue
                    }
                    onProgress(plainName, if (files > 0) done.toFloat() / files else 0f)
                    val r = uploadOne(vault.id, inp, e, plainName, parent, lkKeys, tk, v)
                    if (r !is DResult.Ok) return@withContext r.notOk()
                    done++
                }
                DResult.Ok(Imported(made.value.id, top, files))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            DResult.Failed("read", 0)
        } finally {
            pair.wipe()
            lk?.fill(0)
            runCatching { tmpDir().listFiles()?.forEach { it.delete() } }
        }
    }

    /** One file of the export: its bytes into the cache (checked), LK → the vault key, uploaded, checked once more. */
    private suspend fun uploadOne(
        vaultId: String,
        inp: InputStream,
        e: DriveVaultBackupFormat.Entry,
        name: String,
        parent: Long,
        lkKeys: (Int) -> ByteArray?,
        tk: ByteArray,
        v: Int,
    ): DResult<Unit> {
        val dir = tmpDir().apply { mkdirs() }
        val raw = File.createTempFile("backup", ".gcdv", dir)
        val up = File.createTempFile("backup", ".gcdv", dir)
        try {
            raw.outputStream().buffered(256 * 1024).use { o ->
                var left = e.len
                val buf = ByteArray(256 * 1024)
                while (left > 0) {
                    val n = inp.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) return DResult.Failed("backup_damaged", 0)
                    o.write(buf, 0, n)
                    left -= n
                }
            }
            if (e.sha256 != null && DriveVaultBackupFormat.sha256(raw) != e.sha256) return DResult.Failed("backup_damaged", 0)
            try {
                raw.inputStream().buffered(256 * 1024).use { i -> up.outputStream().buffered(256 * 1024).use { o -> DriveVaultLocalCrypt.transcode(lkKeys, vaultId, i, tk, v, o) } }
            } catch (x: DriveVaultCrypto.DriveVaultException) {
                return DResult.Failed("backup_damaged", 0)
            }
            val sha = DriveVaultBackupFormat.sha256(up)
            val media = api.upload(up, "application/octet-stream")
            if (media !is DResult.Ok) return media.notOk()
            val c = api.commitVault(media.value, parent, DriveVaultCrypto.encryptName(tk, vaultId, v, name), v, e.mtime.takeIf { it > 0 })
            if (c !is DResult.Ok) return c.notOk()
            if (!DriveVaultMove.verified(c.value.first, up.length(), sha)) return DResult.Failed(DriveVaultSession.CODE_VERIFY, 0)
            return DResult.Ok(Unit)
        } finally {
            raw.delete()
            up.delete()
        }
    }
}
