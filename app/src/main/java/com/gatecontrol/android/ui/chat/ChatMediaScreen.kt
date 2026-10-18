package com.gatecontrol.android.ui.chat

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.R
import com.gatecontrol.android.chat.ChatConversation
import com.gatecontrol.android.chat.ChatMediaLogic
import com.gatecontrol.android.chat.ChatMediaLogic.Tab
import com.gatecontrol.android.chat.ChatMediaStore
import com.gatecontrol.android.chat.ChatMessage
import com.gatecontrol.android.chat.ChatProfile
import com.gatecontrol.android.chat.ChatRepository
import com.gatecontrol.android.chat.ChatResult
import com.gatecontrol.android.chat.VoicePlayer
import com.gatecontrol.android.chat.VoiceWave
import com.gatecontrol.android.ui.components.GcFilterChip
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.theme.GateControlTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import javax.inject.Inject

/** One tab of the media overview. */
data class MediaTabState(
    val items: List<ChatMessage> = emptyList(),
    /** `before=` of the next page (the oldest id the server sent). */
    val cursor: Long? = null,
    val hasMore: Boolean = true,
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val failed: Boolean = false,
)

/** Counts of the overview (`counts` of the server). */
data class MediaCounts(val images: Int? = null, val files: Int? = null, val voice: Int? = null)

/**
 * The media overview of a conversation (`GET conversations/:id/media`):
 * pictures and videos as a grid, files and voice messages as lists,
 * paged backwards (newest first).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChatMediaViewModel @Inject constructor(
    private val repository: ChatRepository,
    private val media: ChatMediaStore,
) : ViewModel() {
    private val id = MutableStateFlow<Long?>(null)
    val conversation: StateFlow<ChatConversation?> = id.filterNotNull().flatMapLatest { repository.conversation(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val profile: StateFlow<ChatProfile?> = repository.profile

    private val _tab = MutableStateFlow(Tab.Media)
    val tab: StateFlow<Tab> = _tab.asStateFlow()
    private val _tabs = MutableStateFlow(Tab.entries.associateWith { MediaTabState() })
    val tabs: StateFlow<Map<Tab, MediaTabState>> = _tabs.asStateFlow()
    private val _counts = MutableStateFlow(MediaCounts())
    val counts: StateFlow<MediaCounts> = _counts.asStateFlow()

    private val errors = Channel<String?>(Channel.BUFFERED)
    val error = errors.receiveAsFlow()

    val player = VoicePlayer()

    fun bind(conversationId: Long) {
        if (id.value == conversationId) return
        id.value = conversationId
        _tabs.value = Tab.entries.associateWith { MediaTabState() }
        loadMore(_tab.value)
    }

    fun select(tab: Tab) {
        _tab.value = tab
        if (_tabs.value[tab]?.loaded != true) loadMore(tab)
    }

    /**
     * The next page of [tab]. Pictures and videos page through `all`
     * (videos are files): pages without one of them are skipped, a few at a time.
     */
    fun loadMore(tab: Tab) {
        val cid = id.value ?: return
        val cur = _tabs.value[tab] ?: return
        if (cur.loading || (cur.loaded && !cur.hasMore)) return
        setTab(tab) { it.copy(loading = true, failed = false) }
        viewModelScope.launch {
            var state = _tabs.value[tab] ?: MediaTabState()
            var pages = 0
            while (true) {
                val r = repository.media(cid, tab.kind, state.cursor, if (tab == Tab.Media) PAGE_ALL else PAGE)
                if (r !is ChatResult.Ok) {
                    setTab(tab) { it.copy(loading = false, failed = true, loaded = true) }
                    errors.trySend(if (r is ChatResult.Failed) r.code else "offline")
                    return@launch
                }
                val page = r.value
                _counts.update { c -> MediaCounts(page.images ?: c.images, page.files ?: c.files, page.voice ?: c.voice) }
                val before = state.items.size
                state = state.copy(
                    items = ChatMediaLogic.merge(tab, state.items, page.items),
                    cursor = ChatMediaLogic.cursor(state.cursor, page.items),
                    hasMore = page.hasMore && page.items.isNotEmpty(),
                )
                pages++
                if (state.items.size > before || !state.hasMore || pages >= MAX_PAGES_PER_LOAD) break
            }
            val done = state
            setTab(tab) { done.copy(loading = false, loaded = true, failed = false) }
        }
    }

    private fun setTab(tab: Tab, f: (MediaTabState) -> MediaTabState) {
        _tabs.update { m -> m + (tab to f(m[tab] ?: MediaTabState())) }
    }

    /** The file of [m], downloaded (open, share, play). */
    suspend fun file(m: ChatMessage): File? = m.media?.id?.let { media.file(it) }

    fun togglePlay(m: ChatMessage) {
        viewModelScope.launch {
            val f = file(m)
            if (f == null) errors.trySend("offline") else player.toggle("m:${m.id}", f)
        }
    }

    override fun onCleared() {
        player.stop()
    }

    companion object {
        private const val PAGE = 30
        private const val PAGE_ALL = 60
        private const val MAX_PAGES_PER_LOAD = 5
    }
}

@Composable
fun ChatMediaScreen(
    conversationId: Long,
    onBack: () -> Unit,
    onOpenImage: (String) -> Unit,
    viewModel: ChatMediaViewModel = hiltViewModel(key = "chat-media-$conversationId"),
) {
    LaunchedEffect(conversationId) { viewModel.bind(conversationId) }
    val c by viewModel.conversation.collectAsStateWithLifecycle()
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val tab by viewModel.tab.collectAsStateWithLifecycle()
    val tabs by viewModel.tabs.collectAsStateWithLifecycle()
    val counts by viewModel.counts.collectAsStateWithLifecycle()
    val player by viewModel.player.state.collectAsState()
    val context = LocalContext.current
    val resources = LocalResources.current
    val extra = GateControlTheme.extraColors
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        viewModel.error.collect { Toast.makeText(context, ChatText.actionError(context, it), Toast.LENGTH_LONG).show() }
    }

    val open: (ChatMessage, Boolean) -> Unit = { m, share ->
        scope.launch {
            val f = viewModel.file(m)
            if (f == null) Toast.makeText(context, R.string.chat_err_download, Toast.LENGTH_LONG).show()
            else openFile(context, f, m.meta?.fileName ?: defaultName(m), m.media?.mime, share = share)
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        GcSubpageBar(title = stringResource(R.string.chat_media_title), onBack = onBack, backDescription = stringResource(R.string.chat_back))
        val summary = listOfNotNull(
            counts.images?.let { resources.getQuantityString(R.plurals.chat_media_count_images, it, it) },
            counts.files?.let { resources.getQuantityString(R.plurals.chat_media_count_files, it, it) },
            counts.voice?.let { resources.getQuantityString(R.plurals.chat_media_count_voice, it, it) },
        ).joinToString(" · ")
        if (summary.isNotEmpty()) {
            Text(summary, style = MaterialTheme.typography.bodySmall, color = extra.muted, modifier = Modifier.padding(horizontal = 16.dp))
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GcFilterChip(stringResource(R.string.chat_media_tab_media), tab == Tab.Media, { viewModel.select(Tab.Media) })
            GcFilterChip(stringResource(R.string.chat_media_tab_files), tab == Tab.Files, { viewModel.select(Tab.Files) })
            if (profile?.features?.voice != false || (counts.voice ?: 0) > 0) {
                GcFilterChip(stringResource(R.string.chat_media_tab_voice), tab == Tab.Voice, { viewModel.select(Tab.Voice) })
            }
        }
        val state = tabs[tab] ?: MediaTabState()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                Tab.Media -> MediaGrid(state, onMore = { viewModel.loadMore(Tab.Media) }, onOpen = { m ->
                    if (m.kind == ChatMessage.KIND_IMAGE) m.media?.id?.let(onOpenImage) else open(m, false)
                })
                Tab.Files -> MediaList(state, onMore = { viewModel.loadMore(Tab.Files) }) { m ->
                    FileRow(m, c, profile?.user?.userId, onOpen = { open(m, false) }, onShare = { open(m, true) })
                }
                Tab.Voice -> MediaList(state, onMore = { viewModel.loadMore(Tab.Voice) }) { m ->
                    VoiceRow(
                        m, c, profile?.user?.userId,
                        state = player.takeIf { it.key == "m:${m.id}" },
                        onToggle = { viewModel.togglePlay(m) },
                        onShare = { open(m, true) },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyOrLoading(state: MediaTabState, modifier: Modifier = Modifier) {
    val extra = GateControlTheme.extraColors
    Box(modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        when {
            state.loading -> CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
            state.failed -> Text(stringResource(R.string.chat_media_failed), color = extra.muted, textAlign = TextAlign.Center)
            state.loaded && state.items.isEmpty() -> Text(stringResource(R.string.chat_media_empty), color = extra.muted, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun MediaGrid(state: MediaTabState, onMore: () -> Unit, onOpen: (ChatMessage) -> Unit) {
    val gridState = rememberLazyGridState()
    val nearEnd by remember { derivedStateOf { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= gridState.layoutInfo.totalItemsCount - 6 } ?: true } }
    LaunchedEffect(nearEnd, state.items.size, state.hasMore) {
        if (nearEnd && state.hasMore && state.loaded && !state.loading) onMore()
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(110.dp),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(state.items, key = { it.id }) { m -> MediaTile(m, onClick = { onOpen(m) }) }
        if (state.items.isEmpty() || state.loading || state.failed) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "status") { EmptyOrLoading(state) }
        }
    }
}

@Composable
private fun MediaTile(m: ChatMessage, onClick: () -> Unit) {
    val extra = GateControlTheme.extraColors
    val video = ChatMediaLogic.isVideo(m)
    val px = with(LocalDensity.current) { 140.dp.roundToPx() }
    val bmp = if (video) null else rememberChatBitmap(m.media?.thumbId ?: m.media?.id, px)
    Box(
        Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(6.dp)).background(if (video) Color(0xFF1B1F24) else extra.panel2)
            .clickable(role = Role.Button, onClickLabel = stringResource(if (video) R.string.chat_media_open else R.string.chat_kind_image), onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        when {
            bmp != null -> Image(bmp, contentDescription = stringResource(R.string.chat_kind_image), contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            video -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(6.dp)) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.85f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.chat_media_video), tint = Color.Black)
                }
                Text(
                    m.meta?.fileName ?: stringResource(R.string.chat_media_video),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            else -> Icon(Icons.Outlined.Image, contentDescription = stringResource(R.string.chat_kind_image), tint = extra.faint, modifier = Modifier.size(28.dp))
        }
    }
}

@Composable
private fun MediaList(state: MediaTabState, onMore: () -> Unit, row: @Composable (ChatMessage) -> Unit) {
    val listState = rememberLazyListState()
    val nearEnd by remember { derivedStateOf { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= listState.layoutInfo.totalItemsCount - 4 } ?: true } }
    LaunchedEffect(nearEnd, state.items.size, state.hasMore) {
        if (nearEnd && state.hasMore && state.loaded && !state.loading) onMore()
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        items(state.items, key = { it.id }) { m -> row(m) }
        if (state.items.isEmpty() || state.loading || state.failed) {
            item(key = "status") { EmptyOrLoading(state) }
        }
    }
}

@Composable
private fun FileRow(m: ChatMessage, c: ChatConversation?, myId: Long?, onOpen: () -> Unit, onShare: () -> Unit) {
    val ctx = LocalContext.current
    val extra = GateControlTheme.extraColors
    val name = m.meta?.fileName ?: "datei-${m.id}"
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(role = Role.Button, onClick = onOpen).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(extra.panel2), contentAlignment = Alignment.Center) {
            val ext = name.substringAfterLast('.', "").uppercase(Locale.ROOT).take(4)
            if (ext.isNotEmpty()) Text(ext, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.ExtraBold), color = extra.accentText)
            else Icon(Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = null, tint = extra.accentText)
        }
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(
                    ChatText.nameOf(ctx, c, m.senderId, myId),
                    ChatText.listTime(ctx, m.createdAt),
                    ChatText.bytes(m.media?.bytes).takeIf { it.isNotEmpty() },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = extra.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        GcIconButton(Icons.Outlined.Share, stringResource(R.string.chat_share_file), onClick = onShare)
    }
}

@Composable
private fun VoiceRow(m: ChatMessage, c: ChatConversation?, myId: Long?, state: VoicePlayer.State?, onToggle: () -> Unit, onShare: () -> Unit) {
    val ctx = LocalContext.current
    val extra = GateControlTheme.extraColors
    val scheme = MaterialTheme.colorScheme
    val total = state?.durationMs?.takeIf { it > 0 } ?: m.meta?.durationMs ?: 0L
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(scheme.primary).clickable(role = Role.Button, onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (state?.playing == true) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = stringResource(if (state?.playing == true) R.string.chat_pause else R.string.chat_play),
                tint = scheme.onPrimary,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(ChatText.nameOf(ctx, c, m.senderId, myId), style = MaterialTheme.typography.titleSmall, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(
                    ChatText.listTime(ctx, m.createdAt) + " " + ChatText.clock(ctx, m.createdAt),
                    if (state != null && (state.playing || state.positionMs > 0)) VoiceWave.clock(state.positionMs) + " / " + VoiceWave.clock(total) else VoiceWave.clock(total),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = extra.muted,
            )
        }
        GcIconButton(Icons.Outlined.Share, stringResource(R.string.chat_share_file), onClick = onShare)
    }
}
