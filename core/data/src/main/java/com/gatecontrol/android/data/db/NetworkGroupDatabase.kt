// FILE: core/data/src/main/java/com/gatecontrol/android/data/db/NetworkGroupDatabase.kt
//
// 新增 Room 数据库，负责存储"网络分组"及其下的 CIDR 条目。
// 现有 DataStore (SPLIT_TUNNEL_NETWORKS JSON) 继续保留，
// 迁移完成后由 NetworkGroupRepository 将激活的分组合并成 CIDR 列表
// 写回 SPLIT_TUNNEL_NETWORKS，TunnelConnector 侧无需任何改动。

package com.gatecontrol.android.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

// ──────────────────────────────────────────────
// Entities
// ──────────────────────────────────────────────

/**
 * 一个"网络分组"相当于原来用户手动维护的那一大堆 CIDR。
 * 现在每个分组独立管理，可以单独启用/禁用、导入/导出 SQLite 文件。
 *
 * @param id         主键，自增
 * @param name       用户给分组起的名字，如 "办公室内网"
 * @param enabled    是否参与 Split Tunnel 路由计算
 * @param sortOrder  显示顺序
 * @param createdAt  创建时间戳（ms）
 */
@Entity(tableName = "network_groups")
data class NetworkGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val enabled: Boolean = true,
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * 属于某个分组的单条 CIDR。
 *
 * @param id       主键，自增
 * @param groupId  外键 → network_groups.id
 * @param cidr     如 "172.16.0.0/12"
 * @param label    可选备注，如 "打印机网段"
 */
@Entity(
    tableName = "network_cidrs",
    foreignKeys = [
        ForeignKey(
            entity = NetworkGroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE,   // 删分组 → CIDR 级联删除
        )
    ],
    indices = [Index("groupId")],
)
data class NetworkCidrEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val groupId: Long,
    val cidr: String,
    val label: String = "",
)

// ──────────────────────────────────────────────
// Relation (Group + its CIDRs together)
// ──────────────────────────────────────────────

data class NetworkGroupWithCidrs(
    @Embedded val group: NetworkGroupEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "groupId",
    )
    val cidrs: List<NetworkCidrEntity>,
)

// ──────────────────────────────────────────────
// DAOs
// ──────────────────────────────────────────────

@Dao
interface NetworkGroupDao {

    // ── Groups ──

    @Query("SELECT * FROM network_groups ORDER BY sortOrder ASC, createdAt ASC")
    fun observeAllGroups(): Flow<List<NetworkGroupEntity>>

    @Query("SELECT * FROM network_groups ORDER BY sortOrder ASC, createdAt ASC")
    suspend fun getAllGroups(): List<NetworkGroupEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGroup(group: NetworkGroupEntity): Long

    @Update
    suspend fun updateGroup(group: NetworkGroupEntity)

    @Delete
    suspend fun deleteGroup(group: NetworkGroupEntity)

    @Query("UPDATE network_groups SET enabled = :enabled WHERE id = :id")
    suspend fun setGroupEnabled(id: Long, enabled: Boolean)

    // ── CIDRs ──

    @Query("SELECT * FROM network_cidrs WHERE groupId = :groupId ORDER BY id ASC")
    fun observeCidrsForGroup(groupId: Long): Flow<List<NetworkCidrEntity>>

    @Query("SELECT * FROM network_cidrs WHERE groupId = :groupId ORDER BY id ASC")
    suspend fun getCidrsForGroup(groupId: Long): List<NetworkCidrEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)   // 忽略重复 CIDR（按主键；业务层再做去重）
    suspend fun insertCidr(cidr: NetworkCidrEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCidrs(cidrs: List<NetworkCidrEntity>)

    @Delete
    suspend fun deleteCidr(cidr: NetworkCidrEntity)

    @Query("DELETE FROM network_cidrs WHERE groupId = :groupId")
    suspend fun deleteAllCidrsInGroup(groupId: Long)

    // ── Grouped relation ──

    @Transaction
    @Query("SELECT * FROM network_groups ORDER BY sortOrder ASC, createdAt ASC")
    fun observeAllGroupsWithCidrs(): Flow<List<NetworkGroupWithCidrs>>

    @Transaction
    @Query("SELECT * FROM network_groups WHERE id = :groupId")
    suspend fun getGroupWithCidrs(groupId: Long): NetworkGroupWithCidrs?

    // ── Enabled CIDRs (for VPN route calculation) ──

    @Query("""
        SELECT c.cidr FROM network_cidrs c
        INNER JOIN network_groups g ON g.id = c.groupId
        WHERE g.enabled = 1
    """)
    suspend fun getEnabledCidrs(): List<String>

    @Query("""
        SELECT c.cidr, c.label FROM network_cidrs c
        INNER JOIN network_groups g ON g.id = c.groupId
        WHERE g.enabled = 1
    """)
    fun observeEnabledCidrs(): Flow<List<NetworkCidrEntity>>
}

// ──────────────────────────────────────────────
// Database
// ──────────────────────────────────────────────

@Database(
    entities = [NetworkGroupEntity::class, NetworkCidrEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class NetworkGroupDatabase : RoomDatabase() {
    abstract fun networkGroupDao(): NetworkGroupDao
}
