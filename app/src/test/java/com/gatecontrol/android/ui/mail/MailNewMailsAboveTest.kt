package com.gatecontrol.android.ui.mail

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * New mails while the list is shown (report: „wenn neue Mails ankommen, muss ich immer zum Kopf scrollen“): the
 * list keeps its first visible mail in view by its key, so mails put in front of it ended up above the view. At the
 * top it now follows them; further down it stays and the pill counts them; another box starts at the top.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp")
class MailNewMailsAboveTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val ids = mutableStateOf((100L downTo 1L).toList())
    private val view = mutableStateOf<Any>("inbox" to "relevant")
    private val loading = mutableStateOf(false)
    private lateinit var state: LazyListState
    private var pending: Set<Long> = emptySet()

    private fun show() = rule.setContent {
        state = rememberLazyListState()
        val list = ids.value
        pending = rememberNewMailsAbove(state, list, view.value, loading.value)
        LazyColumn(Modifier.fillMaxSize(), state = state) {
            items(list, key = { it }) { Box(Modifier.fillMaxWidth().height(72.dp)) { Text("m$it") } }
        }
    }

    private fun firstKey(): Any? = rule.runOnIdle { state.layoutInfo.visibleItemsInfo.first().key }

    private fun scrollTo(index: Int) {
        rule.runOnIdle { runBlocking { state.scrollToItem(index) } }
        rule.waitForIdle()
    }

    private fun prepend(vararg new: Long) {
        rule.runOnIdle { ids.value = new.toList() + ids.value }
        rule.waitForIdle()
    }

    @Test
    fun `at the top the new mails are seen`() {
        show()
        prepend(102L, 101L)
        assertEquals(102L, firstKey())
        rule.runOnIdle {
            assertEquals(0, state.firstVisibleItemIndex)
            assertEquals(emptySet<Long>(), pending)
        }
    }

    @Test
    fun `further down the list stays and the pill counts, at the top it goes`() {
        show()
        scrollTo(10)
        assertEquals(90L, firstKey())
        prepend(102L, 101L)
        assertEquals(90L, firstKey())
        rule.runOnIdle { assertEquals(setOf(102L, 101L), pending) }
        prepend(103L)
        rule.runOnIdle { assertEquals(setOf(103L, 102L, 101L), pending) }
        scrollTo(0)
        assertEquals(103L, firstKey())
        rule.runOnIdle { assertEquals(emptySet<Long>(), pending) }
    }

    @Test
    fun `the next page and the first load make no pill`() {
        loading.value = true
        show()
        scrollTo(10)
        // the server's list after the device's copy: new mails in front, still no pill
        rule.runOnIdle {
            loading.value = false
            ids.value = listOf(102L, 101L) + ids.value
        }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(emptySet<Long>(), pending) }
        rule.runOnIdle { ids.value = ids.value + (0L downTo -20L).toList() }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(emptySet<Long>(), pending) }
    }

    @Test
    fun `another box starts at the top`() {
        show()
        scrollTo(10)
        // the other box has the same mail (all inboxes → the account): it is not kept in view
        rule.runOnIdle {
            view.value = "account" to "relevant"
            ids.value = (200L downTo 150L).toList() + (95L downTo 80L).toList()
        }
        rule.waitForIdle()
        assertEquals(200L, firstKey())
        rule.runOnIdle { assertEquals(emptySet<Long>(), pending) }
    }
}
