package com.gatecontrol.android.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.R
import com.gatecontrol.android.chat.ChatRepository
import com.gatecontrol.android.chat.ChatShareHandover
import com.gatecontrol.android.chat.ChatTimeline
import com.gatecontrol.android.ui.adaptive.GcTwoPane
import com.gatecontrol.android.ui.theme.GateControlTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Badge of the tab "Chats": unread messages in conversations that are not muted. */
@HiltViewModel
class ChatBadgeViewModel @Inject constructor(repository: ChatRepository) : ViewModel() {
    val badge: StateFlow<Int> = repository.conversations.map { ChatTimeline.badge(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
}

/** Wide windows: the chat list beside the open conversation. */
@Composable
fun ChatsTwoPane(
    initial: Long?,
    share: Boolean,
    onOpenInbox: () -> Unit,
    onNewChat: () -> Unit,
    onOpenInfo: (Long) -> Unit,
    onOpenImage: (String) -> Unit,
) {
    var selected by rememberSaveable(initial) { mutableStateOf(initial) }
    // The conversation picked for what was shared from another app.
    var shareFor by rememberSaveable { mutableStateOf<Long?>(null) }
    GcTwoPane(
        listWidth = 400.dp,
        list = {
            ChatListScreen(
                onOpen = { id ->
                    if (share || ChatShareHandover.pending.value != null) shareFor = id
                    selected = id
                },
                onOpenInbox = onOpenInbox,
                onNewChat = onNewChat,
                selectedId = selected,
            )
        },
        detail = {
            val id = selected
            if (id == null) {
                ChatPanePlaceholder()
            } else {
                key(id) {
                    ConversationScreen(
                        conversationId = id,
                        onBack = { selected = null },
                        onOpenInfo = { onOpenInfo(id) },
                        onOpenImage = onOpenImage,
                        share = shareFor == id,
                    )
                }
            }
        },
    )
}

@Composable
private fun ChatPanePlaceholder() {
    val extra = GateControlTheme.extraColors
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.AutoMirrored.Outlined.Chat, contentDescription = null, tint = extra.faint, modifier = Modifier.size(40.dp))
        Text(
            stringResource(R.string.chat_pick),
            style = MaterialTheme.typography.bodyLarge,
            color = extra.muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}
