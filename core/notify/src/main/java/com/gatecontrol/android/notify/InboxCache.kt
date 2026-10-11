package com.gatecontrol.android.notify

import com.gatecontrol.android.notify.model.PushMessage
import com.gatecontrol.android.notify.model.PushState
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * The last [MAX_ITEMS] messages as a small JSON file, for the inbox while
 * offline. No database: the server is the source of truth, a refresh
 * replaces this list ([replaceAll]). Newest first (by seq).
 *
 * One entry per notification id: a bundled notification arrives again
 * under a new seq and replaces its older copy.
 */
class InboxCache(private val file: File) {

    companion object {
        const val MAX_ITEMS = 100
        private val gson = Gson()
        private val listType = object : TypeToken<List<PushMessage>>() {}.type
    }

    private val mutex = Mutex()
    private val _items = MutableStateFlow<List<PushMessage>>(emptyList())
    val items: StateFlow<List<PushMessage>> = _items.asStateFlow()
    @Volatile private var loaded = false

    suspend fun load() {
        mutex.withLock { ensureLoaded() }
    }

    fun get(id: Long): PushMessage? = _items.value.firstOrNull { it.id == id }

    /** Adds or replaces (same notification id) a message. Returns the message before, if any. */
    suspend fun upsert(message: PushMessage): PushMessage? = mutate { list ->
        val prev = list.firstOrNull { it.id == message.id }
        // An older copy (lower seq) never overwrites a newer one.
        if (prev != null && prev.seq > message.seq) return@mutate list to prev
        val keepState = if (prev != null && !prev.isUnread && message.isUnread && prev.seq == message.seq) prev.state else message.state
        (listOf(message.copy(state = keepState)) + list.filter { it.id != message.id }) to prev
    }

    /** Marks notifications (by id) as [state]; returns the ones that changed. */
    suspend fun setState(ids: Collection<Long>, state: String): List<PushMessage> = mutate { list ->
        val changed = mutableListOf<PushMessage>()
        val next = list.map { m ->
            if (m.id in ids && m.state != state && !(state == PushState.READ && m.state == PushState.DISMISSED)) {
                m.copy(state = state).also { changed += it }
            } else {
                m
            }
        }
        next to changed
    }

    /** Removes notifications (revoked); returns the removed ones. */
    suspend fun remove(ids: Collection<Long>): List<PushMessage> = mutate { list ->
        val (gone, keep) = list.partition { it.id in ids }
        keep to gone
    }

    /**
     * Server inbox replaces the cache. Local arrival time and connection
     * are kept for messages already known.
     */
    suspend fun replaceAll(serverItems: List<PushMessage>): List<PushMessage> = mutate { list ->
        val known = list.associateBy { it.id }
        val next = serverItems.distinctBy { it.id }.map { s ->
            val k = known[s.id]
            if (k != null) s.copy(receivedAt = k.receivedAt, via = k.via) else s
        }
        next to list
    }

    suspend fun clear() {
        mutate { emptyList<PushMessage>() to Unit }
    }

    private suspend fun <R> mutate(block: (List<PushMessage>) -> Pair<List<PushMessage>, R>): R = mutex.withLock {
        ensureLoaded()
        val (next, result) = block(_items.value)
        val sorted = next.sortedByDescending { it.seq }.take(MAX_ITEMS)
        if (sorted != _items.value) {
            _items.value = sorted
            write(sorted)
        }
        result
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val read = withContext(Dispatchers.IO) {
            try {
                if (file.exists()) gson.fromJson<List<PushMessage>>(file.readText(), listType).orEmpty() else emptyList()
            } catch (e: Exception) {
                Timber.w("Inbox cache unreadable (%s) — starting empty", e.javaClass.simpleName)
                emptyList()
            }
        }
        // Gson bypasses Kotlin null-safety: drop entries from an incompatible older format.
        @Suppress("SENSELESS_COMPARISON")
        _items.value = read.filter { it != null && it.title != null && it.topic != null && it.actions != null }
            .sortedByDescending { it.seq }.take(MAX_ITEMS)
    }

    private suspend fun write(list: List<PushMessage>) {
        withContext(Dispatchers.IO) {
            try {
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(gson.toJson(list))
                if (!tmp.renameTo(file)) {
                    file.writeText(gson.toJson(list))
                    tmp.delete()
                }
            } catch (e: Exception) {
                Timber.w("Inbox cache not written (%s)", e.javaClass.simpleName)
            }
        }
    }
}
