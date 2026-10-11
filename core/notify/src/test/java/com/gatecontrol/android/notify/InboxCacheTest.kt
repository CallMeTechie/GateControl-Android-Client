package com.gatecontrol.android.notify

import com.gatecontrol.android.notify.model.PushState
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class InboxCacheTest {

    @TempDir lateinit var dir: File

    @Test
    fun `keeps the newest 100 messages and survives a restart`() = runBlocking {
        val file = File(dir, "push/inbox.json")
        val cache = InboxCache(file)
        for (i in 1L..120L) cache.upsert(message(id = i, seq = i))
        assertEquals(100, cache.items.value.size)
        assertEquals(120L, cache.items.value.first().seq)
        assertEquals(21L, cache.items.value.last().seq)

        val reloaded = InboxCache(file)
        reloaded.load()
        assertEquals(cache.items.value, reloaded.items.value)
    }

    @Test
    fun `an older copy never overwrites a newer one`() = runBlocking {
        val cache = InboxCache(File(dir, "i.json"))
        cache.upsert(message(id = 1, seq = 9).copy(title = "new"))
        cache.upsert(message(id = 1, seq = 5).copy(title = "old"))
        assertEquals("new", cache.get(1)!!.title)
    }

    @Test
    fun `read state is kept for a replay of the same seq, a new seq is unread again`() = runBlocking {
        val cache = InboxCache(File(dir, "i.json"))
        cache.upsert(message(id = 1, seq = 5))
        cache.setState(listOf(1L), PushState.READ)
        cache.upsert(message(id = 1, seq = 5))
        assertFalse(cache.get(1)!!.isUnread)
        cache.upsert(message(id = 1, seq = 6))
        assertTrue(cache.get(1)!!.isUnread)
    }

    @Test
    fun `dismissed is not turned back into read`() = runBlocking {
        val cache = InboxCache(File(dir, "i.json"))
        cache.upsert(message(id = 1, seq = 5))
        cache.setState(listOf(1L), PushState.DISMISSED)
        assertTrue(cache.setState(listOf(1L), PushState.READ).isEmpty())
        assertEquals(PushState.DISMISSED, cache.get(1)!!.state)
    }

    @Test
    fun `a broken file starts empty`() = runBlocking {
        val file = File(dir, "i.json").apply { writeText("{not json") }
        val cache = InboxCache(file)
        cache.load()
        assertTrue(cache.items.value.isEmpty())
        cache.upsert(message(id = 1))
        assertEquals(1, InboxCache(file).also { it.load() }.items.value.size)
    }

    @Test
    fun `server inbox replaces the cache but keeps local delivery details`() = runBlocking {
        val cache = InboxCache(File(dir, "i.json"))
        cache.upsert(message(id = 1, seq = 5).copy(receivedAt = 1234L, via = "direct"))
        cache.upsert(message(id = 2, seq = 6))
        cache.replaceAll(listOf(message(id = 1, seq = 5, state = PushState.READ)))
        val only = cache.items.value.single()
        assertEquals(1234L, only.receivedAt)
        assertEquals("direct", only.via)
        assertFalse(only.isUnread)
    }
}
