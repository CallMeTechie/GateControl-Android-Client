package com.gatecontrol.android.chat.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * The device's copy of the chats (messenger plugin): conversations and
 * messages as the server sent them (raw JSON, parsed by ChatJson on read —
 * no reflection, so R8 cannot break it), the outbox of messages not sent
 * yet and a few sync marks. Belongs to one login: cleared when the server
 * or token changes (kv `owner`). Not in backups (the app allows none).
 */
@Database(
    entities = [ConversationEntity::class, MessageEntity::class, OutboxEntity::class, KvEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun dao(): ChatDao

    companion object {
        const val NAME = "chats.db"

        fun build(context: Context): ChatDatabase =
            Room.databaseBuilder(context, ChatDatabase::class.java, NAME)
                // A cache: a schema change simply loads everything again.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: Long,
    /** The server's conversation object. */
    val json: String,
    /** Sort key: newest activity (ISO time). */
    val activityAt: String,
)

@Entity(tableName = "messages", indices = [Index("conversationId"), Index("clientId")])
data class MessageEntity(
    @PrimaryKey val id: Long,
    val conversationId: Long,
    val clientId: String?,
    /** The server's message object (tombstones included). */
    val json: String,
)

/**
 * A message not stored on the server yet. [clientId] is its idempotency
 * key (a retry never sends it twice). Files are copies in the app's private
 * storage ([filePath], [thumbPath]); [mediaId] / [thumbMediaId] once
 * uploaded, so a retry does not upload them again.
 */
@Entity(tableName = "outbox", indices = [Index("conversationId")])
data class OutboxEntity(
    @PrimaryKey val clientId: String,
    val conversationId: Long,
    val createdAtMs: Long,
    val kind: String,
    val text: String?,
    val replyToId: Long?,
    val filePath: String?,
    val mime: String?,
    val thumbPath: String?,
    /** `meta` of the send body (JSON) or null. */
    val metaJson: String?,
    /** `location` of the send body (JSON) or null. */
    val locationJson: String?,
    val mediaId: String?,
    val thumbMediaId: String?,
    /** queued | failed (given up: the person retries or deletes it). */
    val state: String,
    val attempts: Int,
    val error: String?,
    val nextAttemptMs: Long,
    /** `notification` for an answer from a notification, else null. */
    val source: String?,
) {
    companion object {
        const val STATE_QUEUED = "queued"
        const val STATE_FAILED = "failed"
    }
}

@Entity(tableName = "kv")
data class KvEntity(@PrimaryKey val key: String, val value: String)

@Dao
abstract class ChatDao {
    // ─── conversations ──────────────────────────────────────────────

    @Query("SELECT * FROM conversations ORDER BY activityAt DESC")
    abstract fun observeConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations ORDER BY activityAt DESC")
    abstract suspend fun conversations(): List<ConversationEntity>

    @Query("SELECT * FROM conversations WHERE id = :id")
    abstract suspend fun conversation(id: Long): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE id = :id")
    abstract fun observeConversation(id: Long): Flow<ConversationEntity?>

    @Upsert
    abstract suspend fun upsertConversations(items: List<ConversationEntity>)

    @Query("DELETE FROM conversations WHERE id IN (:ids)")
    abstract suspend fun deleteConversations(ids: List<Long>)

    // ─── messages ───────────────────────────────────────────────────

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY id")
    abstract fun observeMessages(conversationId: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE id = :id")
    abstract suspend fun message(id: Long): MessageEntity?

    @Query("SELECT MAX(id) FROM messages WHERE conversationId = :conversationId")
    abstract suspend fun maxMessageId(conversationId: Long): Long?

    @Query("SELECT MIN(id) FROM messages WHERE conversationId = :conversationId")
    abstract suspend fun minMessageId(conversationId: Long): Long?

    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId")
    abstract suspend fun messageCount(conversationId: Long): Int

    @Query("SELECT id FROM messages WHERE conversationId = :conversationId AND id >= :from AND id <= :to")
    abstract suspend fun messageIdsBetween(conversationId: Long, from: Long, to: Long): List<Long>

    @Upsert
    abstract suspend fun upsertMessages(items: List<MessageEntity>)

    @Query("DELETE FROM messages WHERE id IN (:ids)")
    abstract suspend fun deleteMessages(ids: List<Long>)

    @Query("DELETE FROM messages WHERE conversationId IN (:conversationIds)")
    abstract suspend fun deleteMessagesOf(conversationIds: List<Long>)

    // ─── outbox ─────────────────────────────────────────────────────

    @Query("SELECT * FROM outbox WHERE conversationId = :conversationId ORDER BY createdAtMs")
    abstract fun observeOutbox(conversationId: Long): Flow<List<OutboxEntity>>

    @Query("SELECT * FROM outbox ORDER BY createdAtMs")
    abstract fun observeAllOutbox(): Flow<List<OutboxEntity>>

    @Query("SELECT * FROM outbox ORDER BY createdAtMs")
    abstract suspend fun outbox(): List<OutboxEntity>

    @Query("SELECT * FROM outbox WHERE clientId = :clientId")
    abstract suspend fun outboxItem(clientId: String): OutboxEntity?

    @Upsert
    abstract suspend fun upsertOutbox(item: OutboxEntity)

    @Query("DELETE FROM outbox WHERE clientId = :clientId")
    abstract suspend fun deleteOutbox(clientId: String)

    @Query("DELETE FROM outbox WHERE conversationId IN (:conversationIds)")
    abstract suspend fun deleteOutboxOf(conversationIds: List<Long>)

    // ─── kv ─────────────────────────────────────────────────────────

    @Query("SELECT value FROM kv WHERE `key` = :key")
    abstract suspend fun kv(key: String): String?

    @Upsert
    abstract suspend fun putKv(item: KvEntity)

    @Query("DELETE FROM kv WHERE `key` = :key")
    abstract suspend fun deleteKv(key: String)

    @Query("DELETE FROM kv WHERE `key` LIKE :prefix || '%'")
    abstract suspend fun deleteKvPrefix(prefix: String)

    // ─── everything ─────────────────────────────────────────────────

    @Query("DELETE FROM conversations")
    abstract suspend fun clearConversations()

    @Query("DELETE FROM messages")
    abstract suspend fun clearMessages()

    @Query("DELETE FROM outbox")
    abstract suspend fun clearOutbox()

    @Query("DELETE FROM kv")
    abstract suspend fun clearKv()

    @Transaction
    open suspend fun clearAll() {
        clearConversations()
        clearMessages()
        clearOutbox()
        clearKv()
    }

    /** A conversation the caller left or was removed from: gone with its messages. */
    @Transaction
    open suspend fun dropConversations(ids: List<Long>) {
        if (ids.isEmpty()) return
        deleteConversations(ids)
        deleteMessagesOf(ids)
        deleteOutboxOf(ids)
    }
}
