// FILE: core/data/src/main/java/com/gatecontrol/android/data/NetworkGroupRepository.kt
//
// 业务逻辑层。负责：
//   1. CRUD 分组 & CIDR
//   2. 将激活的分组合并 → 写回 SettingsRepository.SPLIT_TUNNEL_NETWORKS (JSON)
//      ← TunnelConnector 读这个 key，所以下游零改动
//   3. 导出分组为 SQLite 文件 / 从 SQLite 文件导入分组
//   4. 启动时从旧 DataStore JSON 一次性迁移

package com.gatecontrol.android.data

import android.content.Context
import com.gatecontrol.android.data.db.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NetworkGroupRepository @Inject constructor(
    private val dao: NetworkGroupDao,
    private val settingsRepository: SettingsRepository,
    private val context: Context,
) {

    // ────────────────────────────────────────────
    // Observe
    // ────────────────────────────────────────────

    fun observeAllGroupsWithCidrs(): Flow<List<NetworkGroupWithCidrs>> =
        dao.observeAllGroupsWithCidrs()

    fun observeCidrsForGroup(groupId: Long): Flow<List<NetworkCidrEntity>> =
        dao.observeCidrsForGroup(groupId)

    /** Emits a merged list of all enabled CIDRs across all enabled groups. */
    fun observeEnabledCidrs(): Flow<List<NetworkCidrEntity>> =
        dao.observeEnabledCidrs()

    // ────────────────────────────────────────────
    // Group CRUD
    // ────────────────────────────────────────────

    suspend fun createGroup(name: String): Long {
        val id = dao.insertGroup(NetworkGroupEntity(name = name))
        syncToDataStore()
        return id
    }

    suspend fun renameGroup(groupId: Long, newName: String) {
        val groups = dao.getAllGroups()
        val target = groups.firstOrNull { it.id == groupId } ?: return
        dao.updateGroup(target.copy(name = newName))
        // name doesn't affect routing — no syncToDataStore needed
    }

    suspend fun setGroupEnabled(groupId: Long, enabled: Boolean) {
        dao.setGroupEnabled(groupId, enabled)
        syncToDataStore()
    }

    suspend fun deleteGroup(groupId: Long) {
        val groups = dao.getAllGroups()
        val target = groups.firstOrNull { it.id == groupId } ?: return
        dao.deleteGroup(target)      // CASCADE deletes child CIDRs
        syncToDataStore()
    }

    // ────────────────────────────────────────────
    // CIDR CRUD
    // ────────────────────────────────────────────

    /**
     * Add a single CIDR to a group. Returns true on success, false if duplicate
     * or invalid format.
     */
    suspend fun addCidr(groupId: Long, cidr: String, label: String = ""): Boolean {
        val normalized = cidr.trim()
        if (!isValidCidr(normalized)) return false
        // Check for duplicate within this group
        val existing = dao.getCidrsForGroup(groupId).map { it.cidr }
        if (normalized in existing) return false
        dao.insertCidr(NetworkCidrEntity(groupId = groupId, cidr = normalized, label = label))
        syncToDataStore()
        return true
    }

    /**
     * Bulk add — one CIDR per line. Returns (added, skipped) counts.
     */
    suspend fun addCidrsBulk(groupId: Long, rawText: String): Pair<Int, Int> {
        val existing = dao.getCidrsForGroup(groupId).map { it.cidr }.toMutableSet()
        var added = 0
        var skipped = 0
        val toInsert = mutableListOf<NetworkCidrEntity>()
        rawText.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { line ->
                if (isValidCidr(line) && line !in existing) {
                    toInsert.add(NetworkCidrEntity(groupId = groupId, cidr = line))
                    existing.add(line)
                    added++
                } else {
                    skipped++
                }
            }
        if (toInsert.isNotEmpty()) {
            dao.insertCidrs(toInsert)
            syncToDataStore()
        }
        return added to skipped
    }

    suspend fun deleteCidr(cidr: NetworkCidrEntity) {
        dao.deleteCidr(cidr)
        syncToDataStore()
    }

    // ────────────────────────────────────────────
    // Export / Import SQLite
    // ────────────────────────────────────────────

    /**
     * Exports a single group (its metadata + all CIDRs) as a self-contained
     * SQLite DB file in the app cache dir.
     *
     * Schema of the exported file:
     *   CREATE TABLE meta   (key TEXT PRIMARY KEY, value TEXT);
     *   CREATE TABLE cidrs  (cidr TEXT PRIMARY KEY, label TEXT);
     *
     * Returns the exported File, or null on failure.
     */
    suspend fun exportGroup(groupId: Long): File? = withContext(Dispatchers.IO) {
        val gwc = dao.getGroupWithCidrs(groupId) ?: return@withContext null
        val outFile = File(context.cacheDir, "gc_network_${gwc.group.name.sanitizeFilename()}_${System.currentTimeMillis()}.sqlite3")
        try {
            android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(outFile, null).use { db ->
                db.execSQL("CREATE TABLE IF NOT EXISTS meta  (key TEXT PRIMARY KEY, value TEXT)")
                db.execSQL("CREATE TABLE IF NOT EXISTS cidrs (cidr TEXT PRIMARY KEY, label TEXT)")

                db.execSQL("INSERT OR REPLACE INTO meta VALUES ('name', ?)", arrayOf(gwc.group.name))
                db.execSQL("INSERT OR REPLACE INTO meta VALUES ('enabled', ?)", arrayOf(if (gwc.group.enabled) "1" else "0"))
                db.execSQL("INSERT OR REPLACE INTO meta VALUES ('exported_at', ?)", arrayOf(System.currentTimeMillis().toString()))
                db.execSQL("INSERT OR REPLACE INTO meta VALUES ('version', ?)", arrayOf("1"))

                gwc.cidrs.forEach { entry ->
                    db.execSQL("INSERT OR IGNORE INTO cidrs VALUES (?, ?)", arrayOf(entry.cidr, entry.label))
                }
            }
            outFile
        } catch (e: Exception) {
            Timber.e(e, "Export group failed")
            outFile.delete()
            null
        }
    }

    /**
     * Imports a group from a previously exported SQLite file.
     * Creates a new group (never overwrites existing data).
     * Returns the new group id, or -1 on failure.
     */
    suspend fun importGroup(sourceFile: File): Long = withContext(Dispatchers.IO) {
        try {
            var groupName = sourceFile.nameWithoutExtension
            val cidrs = mutableListOf<Pair<String, String>>()  // cidr, label

            android.database.sqlite.SQLiteDatabase.openDatabase(
                sourceFile.absolutePath, null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY
            ).use { db ->
                db.rawQuery("SELECT value FROM meta WHERE key = 'name'", null)?.use { c ->
                    if (c.moveToFirst()) groupName = c.getString(0)
                }
                db.rawQuery("SELECT cidr, label FROM cidrs", null)?.use { c ->
                    while (c.moveToNext()) {
                        cidrs.add(c.getString(0) to c.getString(1))
                    }
                }
            }

            val newId = dao.insertGroup(NetworkGroupEntity(name = groupName))
            val entities = cidrs
                .filter { isValidCidr(it.first) }
                .map { NetworkCidrEntity(groupId = newId, cidr = it.first, label = it.second) }
            dao.insertCidrs(entities)
            syncToDataStore()
            newId
        } catch (e: Exception) {
            Timber.e(e, "Import group failed")
            -1L
        }
    }

    // ────────────────────────────────────────────
    // One-time migration from DataStore JSON
    // ────────────────────────────────────────────

    /**
     * Call once at app startup (e.g. from SettingsViewModel.init).
     * If the new DB is empty but the old DataStore JSON has data,
     * migrates it into a default group called "Migrated".
     */
    suspend fun migrateFromDataStoreIfNeeded() {
        val existingGroups = dao.getAllGroups()
        if (existingGroups.isNotEmpty()) return   // already migrated

        val json = settingsRepository.getSplitTunnelNetworks().first()
        if (json.isBlank() || json == "[]") return

        try {
            val arr = JSONArray(json)
            if (arr.length() == 0) return

            val groupId = dao.insertGroup(
                NetworkGroupEntity(name = "Migrated", enabled = true)
            )
            val cidrs = (0 until arr.length()).mapNotNull {
                val obj = arr.getJSONObject(it)
                val cidr = obj.optString("cidr").trim()
                val label = obj.optString("label", "")
                if (isValidCidr(cidr)) NetworkCidrEntity(groupId = groupId, cidr = cidr, label = label) else null
            }
            dao.insertCidrs(cidrs)
            Timber.i("Migrated %d CIDRs from DataStore JSON into Room group %d", cidrs.size, groupId)
            // Don't clear the DataStore key — it stays as the ground truth for TunnelConnector
        } catch (e: Exception) {
            Timber.w(e, "Migration from DataStore failed")
        }
    }

    // ────────────────────────────────────────────
    // Internal helpers
    // ────────────────────────────────────────────

    /**
     * Reads all enabled CIDRs from Room and writes them back to
     * SettingsRepository.SPLIT_TUNNEL_NETWORKS so TunnelConnector
     * continues to work without modification.
     */
    private suspend fun syncToDataStore() {
        try {
            val cidrs = dao.getEnabledCidrs()
            val arr = JSONArray()
            cidrs.forEach { cidr ->
                arr.put(JSONObject().put("cidr", cidr).put("label", ""))
            }
            settingsRepository.setSplitTunnelNetworks(arr.toString())
        } catch (e: Exception) {
            Timber.e(e, "syncToDataStore failed")
        }
    }

    private fun isValidCidr(cidr: String): Boolean {
        val parts = cidr.split("/")
        if (parts.size != 2) return false
        val prefix = parts[1].toIntOrNull() ?: return false
        if (prefix < 0 || prefix > 32) return false
        val octets = parts[0].split(".")
        if (octets.size != 4) return false
        return octets.all { it.toIntOrNull()?.let { v -> v in 0..255 } == true }
    }

    private fun String.sanitizeFilename(): String =
        replace(Regex("[^a-zA-Z0-9_\\-]"), "_").take(40)
}
