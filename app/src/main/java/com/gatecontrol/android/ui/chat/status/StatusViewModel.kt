package com.gatecontrol.android.ui.chat.status

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.chat.ChatExtrasRepository
import com.gatecontrol.android.chat.ChatFeedItem
import com.gatecontrol.android.chat.ChatPerson
import com.gatecontrol.android.chat.ChatProfile
import com.gatecontrol.android.chat.ChatRepository
import com.gatecontrol.android.chat.ChatResult
import com.gatecontrol.android.chat.ChatStatus
import com.gatecontrol.android.chat.ChatStatusFeed
import com.gatecontrol.android.chat.ChatStatusLogic
import com.gatecontrol.android.chat.ChatStory
import com.gatecontrol.android.chat.ChatStoryViewer
import com.gatecontrol.android.chat.status
import com.gatecontrol.android.chat.stories
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject

data class StatusUi(
    val profile: ChatProfile? = null,
    val feed: ChatStatusFeed? = null,
    /** The server offers a status line / stories. */
    val statusOn: Boolean = false,
    val storiesOn: Boolean = false,
    val own: ChatStatus? = null,
    val busy: Boolean = false,
)

/** One-shot results of the status screens. */
sealed interface StatusNotice {
    data class Error(val code: String?) : StatusNotice
    data class Done(val res: Int) : StatusNotice
}

/**
 * Status and stories (Messenger 1.4): the own status ("Dein Status"),
 * the feed for the strip in the chat list, the story viewer (seen,
 * reactions, replies, own viewers) and the composer.
 */
@HiltViewModel
class StatusViewModel @Inject constructor(
    private val repository: ChatRepository,
    private val extras: ChatExtrasRepository,
) : ViewModel() {

    private val busy = MutableStateFlow(false)
    private val notices = Channel<StatusNotice>(Channel.BUFFERED)
    val notice: Flow<StatusNotice> = notices.receiveAsFlow()

    val ui: StateFlow<StatusUi> = combine(repository.profile, extras.feed, busy) { p, f, b ->
        StatusUi(
            profile = p,
            feed = f,
            statusOn = p?.features?.status == true,
            storiesOn = p?.features?.stories == true,
            own = f?.me?.status ?: p?.status,
            busy = b,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatusUi())

    private val _people = MutableStateFlow<List<ChatPerson>>(emptyList())
    val people: StateFlow<List<ChatPerson>> = _people

    init {
        viewModelScope.launch {
            if (repository.profile.value == null) repository.refreshProfile()
            extras.refreshFeedIfStale(30_000L)
        }
    }

    fun refresh() = extras.refreshFeedSoon(0L)

    fun loadPeople() {
        if (_people.value.isNotEmpty()) return
        viewModelScope.launch { (repository.people() as? ChatResult.Ok)?.value?.let { list -> _people.value = list.filter { !it.gone } } }
    }

    private fun run(done: Int? = null, onOk: () -> Unit = {}, block: suspend () -> ChatResult<*>) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                val r = block()
                if (r is ChatResult.Ok) {
                    done?.let { notices.trySend(StatusNotice.Done(it)) }
                    onOk()
                } else {
                    notices.trySend(StatusNotice.Error((r as? ChatResult.Failed)?.code ?: "offline"))
                }
            } finally {
                busy.value = false
            }
        }
    }

    /**
     * Saves "Dein Status": [untilChoice] null keeps the current end. An
     * empty text without a preset clears the status (the server takes no
     * empty status).
     */
    fun save(text: String, preset: String, untilChoice: String?, visibility: String, userIds: List<Long>, etaMs: Long?, dest: String?, onDone: () -> Unit) {
        val t = text.trim().take(ChatStatus.TEXT_MAX).takeIf { it.isNotEmpty() }
        if (t == null && preset == ChatStatus.PRESET_NONE) {
            clear(onDone)
            return
        }
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val endOfDay = today.atTime(LocalTime.of(23, 59)).atZone(zone).toInstant().toEpochMilli()
        val work = today.atTime(LocalTime.of(17, 0)).atZone(zone).toInstant().toEpochMilli()
        val until = if (untilChoice == null) ui.value.own?.until
        else ChatStatusLogic.untilMs(untilChoice, now, endOfDay, work)?.let { Instant.ofEpochMilli(it).toString() }
        val status = ChatStatus(
            text = t,
            preset = preset,
            until = until?.takeIf { (ChatStatusLogic.active(ChatStatus(null, preset, it, visibility), now)) },
            visibility = visibility,
            userIds = userIds,
            eta = etaMs?.takeIf { preset == ChatStatus.PRESET_AWAY && it > now }?.let { Instant.ofEpochMilli(it).toString() },
            destLabel = dest?.trim()?.takeIf { preset == ChatStatus.PRESET_AWAY && it.isNotEmpty() },
        )
        run(com.gatecontrol.android.R.string.chat_status_saved, onDone) { extras.setStatus(status) }
    }

    fun clear(onDone: () -> Unit) = run(onOk = onDone) { extras.clearStatus() }

    fun postText(text: String, bg: String, font: String, visibility: String, userIds: List<Long>, onDone: () -> Unit) =
        run(com.gatecontrol.android.R.string.chat_story_sent, onDone) { extras.postTextStory(text, bg, font, visibility, userIds, null) }

    fun postMedia(uri: Uri, video: Boolean, caption: String?, visibility: String, userIds: List<Long>, onDone: () -> Unit) =
        run(com.gatecontrol.android.R.string.chat_story_sent, onDone) {
            if (video) extras.postVideoStory(uri, caption, visibility, userIds, null) else extras.postImageStory(uri, caption, visibility, userIds, null)
        }

    fun deleteStory(story: ChatStory, onDone: () -> Unit) = run(onOk = onDone) { extras.deleteStory(story.id) }

    fun markSeen(item: ChatFeedItem, story: ChatStory) = extras.markSeen(item.user.userId, story)

    fun react(story: ChatStory, emoji: String) = run(com.gatecontrol.android.R.string.chat_story_reacted) { extras.react(story, emoji) }

    fun reply(owner: ChatPerson, story: ChatStory, text: String, onDone: () -> Unit) =
        run(com.gatecontrol.android.R.string.chat_story_reply_sent, onDone) { extras.replyToStory(owner, story, text) }

    suspend fun viewers(story: ChatStory): List<ChatStoryViewer>? = (extras.viewers(story.id) as? ChatResult.Ok)?.value

    /** The default audience of a new status / story (privacy `statusDefault`). */
    fun defaultVisibility(): String = ui.value.profile?.prefs14?.privacy?.statusDefault ?: ChatStatus.VIS_ALL
}
