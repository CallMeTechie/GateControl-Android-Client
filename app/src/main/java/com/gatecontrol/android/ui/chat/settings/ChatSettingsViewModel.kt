package com.gatecontrol.android.ui.chat.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.chat.ChatConversation
import com.gatecontrol.android.chat.ChatDeviceConfig
import com.gatecontrol.android.chat.ChatDeviceSettings
import com.gatecontrol.android.chat.ChatExtrasRepository
import com.gatecontrol.android.chat.ChatHomeAction
import com.gatecontrol.android.chat.ChatList
import com.gatecontrol.android.chat.ChatMediaStore
import com.gatecontrol.android.chat.ChatNotifier
import com.gatecontrol.android.chat.ChatPerson
import com.gatecontrol.android.chat.ChatPrefs14
import com.gatecontrol.android.chat.ChatProfile
import com.gatecontrol.android.chat.ChatRepository
import com.gatecontrol.android.chat.ChatResult
import com.gatecontrol.android.chat.ChatShortcuts
import com.gatecontrol.android.chat.ChatStorageUsage
import com.gatecontrol.android.chat.ChatToolsLogic
import com.gatecontrol.android.chat.actions
import com.gatecontrol.android.chat.msgTools
import com.gatecontrol.android.chat.status
import com.gatecontrol.android.data.SetupRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import javax.inject.Inject

/** One-shot results of the chat settings. */
sealed interface SettingsNotice {
    data class Failed(val code: String?) : SettingsNotice
    data class Done(val res: Int) : SettingsNotice
    /** An exported chat to share. */
    data class Export(val file: File, val truncated: Boolean) : SettingsNotice
}

/**
 * Chat-Einstellungen (Messenger 1.4): server preferences (`PUT prefs`:
 * read receipts, typing, preview, privacy, notify, chats), this device's
 * settings (DataStore), blocks, lists, home actions in the attach sheet,
 * storage, devices and export.
 */
@HiltViewModel
class ChatSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: ChatRepository,
    private val extras: ChatExtrasRepository,
    private val device: ChatDeviceSettings,
    private val shortcuts: ChatShortcuts,
    private val media: ChatMediaStore,
    private val notifier: ChatNotifier,
    private val setupRepository: SetupRepository,
) : ViewModel() {

    val profile: StateFlow<ChatProfile?> = repository.profile
    val config: StateFlow<ChatDeviceConfig> = device.config
    val shortcutsOn: StateFlow<Boolean> = shortcuts.enabled
    val bubblesOn: StateFlow<Boolean> = shortcuts.bubbles
    val blocks: StateFlow<List<ChatPerson>?> = extras.blocks
    val lists: StateFlow<List<ChatList>> = extras.lists
    val actions: StateFlow<List<ChatHomeAction>?> = extras.actions
    val conversations: StateFlow<List<ChatConversation>> = repository.conversations
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _storage = MutableStateFlow<ChatStorageUsage?>(null)
    val storage: StateFlow<ChatStorageUsage?> = _storage

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val notices = Channel<SettingsNotice>(Channel.BUFFERED)
    val notice: Flow<SettingsNotice> = notices.receiveAsFlow()

    val serverHost: String get() = runCatching { Uri.parse(setupRepository.getServerUrl()).host }.getOrNull() ?: "—"

    val wallpaperFile: File get() = device.wallpaperFile

    init {
        viewModelScope.launch {
            if (repository.profile.value == null) repository.refreshProfile()
            val f = repository.profile.value?.features
            if (f?.msgTools == true) {
                extras.refreshLists()
                extras.refreshBlocks()
            }
            if (f?.actions == true) extras.refreshActions()
            if (f?.status == true) extras.refreshFeedIfStale()
        }
        viewModelScope.launch { runCatching { notifier.ensureChannels() } }
    }

    private fun report(r: ChatResult<*>, done: Int? = null) {
        if (r is ChatResult.Ok) done?.let { notices.trySend(SettingsNotice.Done(it)) }
        else notices.trySend(SettingsNotice.Failed((r as? ChatResult.Failed)?.code ?: "offline"))
    }

    /** Read receipts, "schreibt …", content in notifications (all devices). */
    fun setPrefs(readReceipts: Boolean? = null, typing: Boolean? = null, preview: Boolean? = null) {
        viewModelScope.launch { report(repository.putPrefs(readReceipts, typing, preview)) }
    }

    /** Privacy, notify and chat preferences (Messenger 1.4, all devices). */
    fun setPrefs14(change: (ChatPrefs14) -> ChatPrefs14) {
        val current = repository.profile.value?.prefs14 ?: ChatPrefs14()
        viewModelScope.launch { report(extras.putPrefs(change(current))) }
    }

    fun setDevice(change: (ChatDeviceConfig) -> ChatDeviceConfig) {
        viewModelScope.launch { device.update(change) }
    }

    fun setShortcuts(on: Boolean) = viewModelScope.launch { shortcuts.setEnabled(on) }

    fun setBubbles(on: Boolean) = shortcuts.setBubbles(on)

    fun setWallpaperPhoto(uri: Uri) = viewModelScope.launch {
        if (!device.setWallpaperPhoto(uri)) notices.trySend(SettingsNotice.Failed("unreadable"))
    }

    fun block(p: ChatPerson) = viewModelScope.launch { report(extras.block(p)) }

    fun unblock(userId: Long) = viewModelScope.launch { report(extras.unblock(userId)) }

    fun saveLists(lists: List<ChatList>) = viewModelScope.launch { report(extras.saveLists(lists)) }

    fun newList(name: String): ChatList = ChatList(ChatToolsLogic.newListId(lists.value, System.currentTimeMillis()), name.trim().take(ChatList.NAME_MAX), emptyList())

    fun refreshStorage() = viewModelScope.launch { _storage.value = media.usage() }

    fun clearCache() = viewModelScope.launch {
        media.clearCache()
        _storage.value = media.usage()
        notices.trySend(SettingsNotice.Done(com.gatecontrol.android.R.string.chat_storage_cleared))
    }

    fun refreshProfile() = viewModelScope.launch { repository.refreshProfile() }

    /** Exports chat [c] as text and hands the file over for sharing. */
    fun export(c: ChatConversation, title: String) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                when (val r = extras.export(c.id)) {
                    is ChatResult.Ok -> {
                        val name = ChatToolsLogic.exportName(title, LocalDate.now())
                        val file = extras.exportFile(File(context.cacheDir, "chat-export"), name, r.value.text)
                        if (file != null) notices.trySend(SettingsNotice.Export(file, r.value.truncated))
                        else notices.trySend(SettingsNotice.Failed("unreadable"))
                    }
                    else -> report(r)
                }
            } finally {
                _busy.value = false
            }
        }
    }

    /** Home actions hidden from the attach sheet on this device. */
    fun setActionShown(id: String, shown: Boolean) = setDevice { c -> c.copy(hiddenActions = if (shown) c.hiddenActions - id else c.hiddenActions + id) }

    /** Name of the sound / vibration of a chat channel (Android settings). */
    fun channelInfo(group: Boolean): Pair<String?, Boolean?> = runCatching {
        val nm = context.getSystemService(android.app.NotificationManager::class.java)
        val ch = nm.getNotificationChannel(if (group) ChatNotifier.CHANNEL_GROUPS else ChatNotifier.CHANNEL_ID) ?: return@runCatching null to null
        val sound = ch.sound?.let { uri -> runCatching { android.media.RingtoneManager.getRingtone(context, uri)?.getTitle(context) }.getOrNull() }
        (if (ch.sound == null) "" else sound) to ch.shouldVibrate()
    }.getOrDefault(null to null)

    /** Profile status for the header (own status). */
    fun ownStatus() = extras.ownStatus()
}
