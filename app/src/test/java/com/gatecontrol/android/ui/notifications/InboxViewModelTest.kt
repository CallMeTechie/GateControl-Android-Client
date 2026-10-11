package com.gatecontrol.android.ui.notifications

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.gatecontrol.android.notify.InboxCache
import com.gatecontrol.android.notify.PushPrefsStore
import com.gatecontrol.android.notify.PushRepository
import com.gatecontrol.android.notify.model.PushMessage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class InboxViewModelTest {

    @TempDir lateinit var dir: File

    private lateinit var inbox: InboxCache
    private lateinit var store: PushPrefsStore
    private val repository: PushRepository = mockk(relaxed = true)

    private fun msg(id: Long, topic: String, unread: Boolean = true, createdAt: String = "2026-10-10T21:42:03Z") = PushMessage(
        seq = id, id = id, eventId = "e", topic = topic, priority = "normal", title = "T$id", body = "B",
        createdAt = createdAt, expiresAt = null, collapseKey = null, silent = false, route = null,
        actions = emptyList(), state = if (unread) "delivered" else "read",
    )

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        inbox = InboxCache(File(dir, "inbox.json"))
        store = PushPrefsStore(
            PreferenceDataStoreFactory.create(
                scope = storeScope,
                produceFile = { File(dir, "s.preferences_pb") },
            ),
        )
        every { repository.inbox } returns inbox
        runBlocking {
            inbox.upsert(msg(1, "devices", unread = false))
            inbox.upsert(msg(2, "security"))
            inbox.upsert(msg(3, "plugin:skoda:charging"))
            inbox.upsert(msg(4, "devices"))
        }
    }

    private val viewModels = mutableListOf<androidx.lifecycle.ViewModel>()
    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @AfterEach
    fun tearDown() {
        // Nothing may resume on Dispatchers.Main after resetMain (it would fail a later test).
        runBlocking { viewModels.forEach { it.viewModelScope.coroutineContext[Job]!!.cancelAndJoin() } }
        storeScope.cancel()
        Dispatchers.resetMain()
    }

    private fun <T : androidx.lifecycle.ViewModel> track(vm: T): T = vm.also { viewModels += it }

    private fun <T> await(block: suspend () -> T): T = runBlocking { withTimeout(5_000) { block() } }

    @Test
    fun `lists newest first with unread count and the topics present`() {
        val vm = track(InboxViewModel(repository, store))
        val s = await { vm.uiState.first { it.items.size == 4 } }
        assertEquals(listOf(4L, 3L, 2L, 1L), s.items.map { it.id })
        assertEquals(3, s.unread)
        assertEquals(listOf("devices", "plugin:skoda:charging", "security"), s.topics)
        assertEquals(3, await { vm.unreadCount.first { it == 3 } })
        assertFalse(s.enabled)
    }

    @Test
    fun `filter by topic and back to all`() {
        val vm = track(InboxViewModel(repository, store))
        vm.setFilter("devices")
        val s = await { vm.uiState.first { it.filter == "devices" } }
        assertEquals(listOf(4L, 1L), s.items.map { it.id })
        vm.setFilter(null)
        assertNull(await { vm.uiState.first { it.items.size == 4 } }.filter)
    }

    @Test
    fun `a failed refresh shows the saved list as offline`() {
        coEvery { repository.refreshInbox() } returns false
        val vm = track(InboxViewModel(repository, store))
        vm.refresh()
        assertTrue(await { vm.uiState.first { it.offline } }.offline)
        coEvery { repository.refreshInbox() } returns true
        vm.refresh()
        await { vm.uiState.first { !it.offline && !it.refreshing } }
    }

    @Test
    fun `mark all read goes to the repository`() {
        val vm = track(InboxViewModel(repository, store))
        vm.markAllRead()
        coVerify(timeout = 2_000) { repository.markAllRead() }
    }

    @Test
    fun `messages are grouped by day, newest day first`() {
        val zone = ZoneOffset.UTC
        val today = LocalDate.of(2026, 10, 10)
        val groups = groupByDay(
            listOf(
                msg(3, "devices", createdAt = "2026-10-10T08:00:00Z"),
                msg(2, "devices", createdAt = "2026-10-09T23:05:00Z"),
                msg(1, "devices", createdAt = "2026-10-01T10:00:00Z"),
            ),
            today, zone,
        )
        assertEquals(listOf(today, today.minusDays(1), LocalDate.of(2026, 10, 1)), groups.map { it.first })
        assertEquals("23:05", timeOf(groups[1].second.single(), zone))
        assertEquals("2 h 14 min", shortDuration((2 * 3600 + 14 * 60) * 1000L))
    }
}
