package com.gatecontrol.android.ui.chat

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.ui.draw.paint
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.LocusIdCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.chat.ChatJump
import com.gatecontrol.android.chat.ChatMessage
import com.gatecontrol.android.chat.actions
import com.gatecontrol.android.chat.arrival
import com.gatecontrol.android.chat.events
import com.gatecontrol.android.chat.msgTools
import com.gatecontrol.android.chat.polls
import com.gatecontrol.android.chat.ChatMime
import com.gatecontrol.android.chat.ChatResult
import com.gatecontrol.android.chat.ChatPending
import com.gatecontrol.android.chat.ChatShortcutLogic
import com.gatecontrol.android.chat.PrepareError
import com.gatecontrol.android.chat.TimelineItem
import com.gatecontrol.android.chat.VoiceWave
import com.gatecontrol.android.location.DeviceLocator
import com.gatecontrol.android.location.LocateResult
import com.gatecontrol.android.ui.adaptive.LocalInDetailPane
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.theme.GateControlTheme
import com.gatecontrol.android.util.findComponentActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale

private val QUICK_REACTIONS = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")

/**
 * One conversation (mockup "Einzelchat" / "Gruppe"): bubbles with day
 * separators, ticks, replies, reactions, pictures, voice messages, files,
 * locations; the composer with attachments and hold-to-record; long press
 * for the message actions; search in the top bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(
    conversationId: Long,
    onBack: () -> Unit,
    onOpenInfo: () -> Unit,
    onOpenImage: (mediaId: String) -> Unit,
    share: Boolean = false,
    /** Settings with the update check (GitHub build); the Play build opens the store. */
    onOpenUpdates: () -> Unit = {},
    /** The own profile ("Orte für Regeln teilen" for a live arrival); null: only the hint. */
    onOpenProfile: (() -> Unit)? = null,
    viewModel: ConversationViewModel = hiltViewModel(key = "conversation-$conversationId"),
    homeViewModel: ChatHomeViewModel = hiltViewModel(key = "chat-home-$conversationId"),
) {
    LaunchedEffect(conversationId, share) { viewModel.bind(conversationId, share) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val player by viewModel.player.state.collectAsState()
    val context = LocalContext.current
    val resources = LocalResources.current
    val extra = GateControlTheme.extraColors
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val listState = rememberLazyListState()
    var draft by rememberSaveable(conversationId) { mutableStateOf("") }
    var actionsFor by remember { mutableStateOf<ChatMessage?>(null) }
    var pendingFor by remember { mutableStateOf<ChatPending?>(null) }
    var showAttach by remember { mutableStateOf(false) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var searchText by rememberSaveable { mutableStateOf("") }
    var highlight by remember { mutableStateOf<Long?>(null) }
    var confirmDeleteAll by remember { mutableStateOf<ChatMessage?>(null) }
    // Messenger 1.4.
    val homeActions by viewModel.homeActions.collectAsStateWithLifecycle()
    val places by viewModel.places.collectAsStateWithLifecycle()
    val deviceConfig by viewModel.deviceConfig.collectAsStateWithLifecycle()
    val richBusy by viewModel.richBusy.collectAsStateWithLifecycle()
    var showPoll by remember { mutableStateOf(false) }
    var showEvent by remember { mutableStateOf(false) }
    var arrivalSheet by remember { mutableStateOf(false) }
    var arrivalDest by remember { mutableStateOf<com.gatecontrol.android.chat.ChatEventPlace?>(null) }
    var actionMode by remember { mutableStateOf<String?>(null) }
    var votersFor by remember { mutableStateOf<ChatMessage?>(null) }
    var addOptionFor by remember { mutableStateOf<ChatMessage?>(null) }
    var confirmClosePoll by remember { mutableStateOf<ChatMessage?>(null) }
    var confirmCancelEvent by remember { mutableStateOf<ChatMessage?>(null) }
    var confirmRun by remember { mutableStateOf<Pair<ChatMessage, com.gatecontrol.android.chat.ChatCardButton?>?>(null) }
    var remindFor by remember { mutableStateOf<ChatMessage?>(null) }
    var pinnedIndex by remember { mutableStateOf(0) }
    var dismissedEvent by rememberSaveable { mutableStateOf<Long?>(null) }
    val features14 = state.profile?.features
    // Home sheets of the attach menu (door request, share a device, task, shopping list).
    val familieOffer by homeViewModel.offer.collectAsStateWithLifecycle()
    val homeBusy by homeViewModel.busy.collectAsStateWithLifecycle()
    var requestSheet by remember { mutableStateOf<com.gatecontrol.android.chat.ChatHomeAction?>(null) }
    var requestOpen by remember { mutableStateOf(false) }
    var shareSheet by remember { mutableStateOf<com.gatecontrol.android.chat.ChatHomeAction?>(null) }
    var shareOpen by remember { mutableStateOf(false) }
    var taskSheet by remember { mutableStateOf(false) }
    var shoppingSheet by remember { mutableStateOf(false) }
    var attachHint by remember { mutableStateOf<String?>(null) }
    var arrivalPlacesHint by remember { mutableStateOf(false) }

    LifecycleResumeEffect(conversationId) {
        viewModel.setVisible(true)
        // The system links this screen to the chat's shortcut and notification (LocusId).
        val activity = runCatching { context.findComponentActivity() }.getOrNull()
        runCatching { activity?.setLocusContext(LocusIdCompat(ChatShortcutLogic.id(conversationId)).toLocusId(), null) }
        onPauseOrDispose {
            viewModel.setVisible(false)
            runCatching { activity?.setLocusContext(null, null) }
        }
    }

    // Left, removed or deleted meanwhile: the conversation closes (wide windows: the empty pane).
    LaunchedEffect(state.gone) {
        if (state.gone) {
            if (!viewModel.leftHere()) Toast.makeText(context, R.string.chat_gone, Toast.LENGTH_LONG).show()
            onBack()
        }
    }

    // What was shared from another app: its text goes into the composer.
    LaunchedEffect(state.shared) {
        state.shared?.text?.let { if (draft.isBlank()) draft = it }
    }
    LaunchedEffect(state.editing) {
        state.editing?.let { draft = it.text.orEmpty() }
    }

    LaunchedEffect(Unit) {
        viewModel.notice.collect { n ->
            val text = when (n) {
                is ConversationNotice.Error -> ChatText.actionError(context, n.code)
                is ConversationNotice.PrepareFailed -> when (val e = n.error) {
                    is PrepareError.TooLarge -> resources.getString(R.string.chat_err_file_too_large, ChatText.bytes(e.maxBytes))
                    PrepareError.TypeNotAllowed -> resources.getString(R.string.chat_err_type)
                    PrepareError.Unreadable -> resources.getString(R.string.chat_err_unreadable)
                }
                is ConversationNotice.Location -> resources.getString(
                    when (n.result) {
                        LocateResult.NoPermission -> R.string.chat_loc_no_permission
                        LocateResult.LocationOff -> R.string.chat_loc_off
                        else -> R.string.chat_loc_no_fix
                    },
                )
                ConversationNotice.RecordingFailed -> resources.getString(R.string.chat_rec_failed)
                ConversationNotice.Left -> resources.getString(R.string.chat_left)
                is ConversationNotice.Done -> if (n.arg != null) resources.getString(n.res, n.arg) else resources.getString(n.res)
                is ConversationNotice.ArrivalStarted -> {
                    // The position goes out from a foreground service while the arrival is live.
                    if (DeviceLocator.hasPermission(context)) com.gatecontrol.android.chat.ChatArrivalService.start(context, conversationId, n.messageId, n.label)
                    null
                }
            }
            if (text != null) Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        }
    }

    // Reverse layout: index 0 is the newest row.
    val rows = remember(state.items) { state.items.asReversed() }
    val atBottom by remember { derivedStateOf { listState.firstVisibleItemIndex <= 1 } }
    val nearTop by remember { derivedStateOf { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= rows.size - 4 } == true } }
    LaunchedEffect(nearTop, state.hasOlder, rows.size) {
        if (nearTop && state.hasOlder && state.loaded) viewModel.loadOlder()
    }
    LaunchedEffect(atBottom, rows.firstOrNull()?.key) {
        if (atBottom) viewModel.onNewestVisible()
    }
    // A new own message (or new message while at the bottom) scrolls into view.
    val audio = remember { context.getSystemService(android.media.AudioManager::class.java) }
    var lastSoundKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(rows.firstOrNull()?.key) {
        if (atBottom || rows.firstOrNull() is TimelineItem.Pending) listState.animateScrollToItem(0)
        // Messenger 1.4 settings: a short sound for messages sent and received in the open chat.
        val first = rows.firstOrNull()
        val key = first?.key
        if (lastSoundKey != null && key != lastSoundKey && viewModel.soundsOn() && state.loaded) {
            val incoming = first is TimelineItem.Message && !first.message.mine && !first.message.isSystem
            if (incoming || first is TimelineItem.Pending) runCatching { audio?.playSoundEffect(android.media.AudioManager.FX_KEY_CLICK, 0.6f) }
        }
        lastSoundKey = key
    }
    LaunchedEffect(features14?.actions, features14?.events, features14?.arrival) { viewModel.loadExtras() }
    LaunchedEffect(Unit) {
        homeViewModel.notice.collect { n ->
            val text = when (n) {
                is ChatHomeNotice.Done -> if (n.arg != null) resources.getString(n.res, n.arg) else resources.getString(n.res)
                is ChatHomeNotice.ChatError -> ChatText.actionError(context, n.code)
                is ChatHomeNotice.FamilieError -> com.gatecontrol.android.ui.familie.familieErrorMessage(context, n.code)
            }
            Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        }
    }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.sendImages(uris, draft.takeIf { it.isNotBlank() })
            draft = ""
        }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) viewModel.sendFiles(uris.take(10))
    }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) viewModel.sendLocation()
        else Toast.makeText(context, R.string.chat_loc_no_permission, Toast.LENGTH_LONG).show()
    }
    // Camera: the system camera app writes into a FileProvider file of the app's cache.
    var cameraPath by rememberSaveable { mutableStateOf<String?>(null) }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val path = cameraPath
        cameraPath = null
        if (ok && path != null) {
            viewModel.sendCameraPhoto(path, draft.takeIf { it.isNotBlank() })
            draft = ""
        } else {
            path?.let { File(it).delete() }
        }
    }
    val launchCamera: () -> Unit = {
        val target = viewModel.newCameraTarget()
        if (target == null) {
            Toast.makeText(context, R.string.chat_camera_failed, Toast.LENGTH_LONG).show()
        } else {
            cameraPath = target.first.path
            try {
                takePicture.launch(target.second)
            } catch (e: ActivityNotFoundException) {
                cameraPath = null
                target.first.delete()
                Toast.makeText(context, R.string.chat_camera_no_app, Toast.LENGTH_LONG).show()
            }
        }
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera() else Toast.makeText(context, R.string.chat_camera_no_permission, Toast.LENGTH_LONG).show()
    }
    // Live arrival: the location permission first (the same system dialog as for a one-off location).
    val arrivalPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) arrivalSheet = true
        else Toast.makeText(context, R.string.chat_arrival_permission, Toast.LENGTH_LONG).show()
    }
    val openArrival: (com.gatecontrol.android.chat.ChatEventPlace?) -> Unit = { dest ->
        arrivalDest = dest
        if (DeviceLocator.hasPermission(context)) arrivalSheet = true
        else arrivalPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        Toast.makeText(context, if (granted) R.string.chat_rec_hold else R.string.chat_rec_no_permission, Toast.LENGTH_LONG).show()
    }

    val c = state.conversation
    val myId = state.myId
    val currentRows by rememberUpdatedState(rows)
    val gapLoading by viewModel.gapLoading.collectAsStateWithLifecycle()
    // To a message (search result, quote): scrolled to when loaded, else loaded with its
    // neighbours first (`around=`, Messenger 1.1.0).
    val jumpTo: (Long) -> Unit = remember(viewModel) {
        { id ->
            fun indexOf() = currentRows.indexOfFirst { it is TimelineItem.Message && it.message.id == id }
            scope.launch {
                var idx = indexOf()
                if (idx < 0) {
                    when (val r = viewModel.jumpTo(id)) {
                        is ChatResult.Ok -> when (r.value) {
                            ChatJump.Found -> idx = withTimeoutOrNull(3_000) { snapshotFlow { indexOf() }.first { it >= 0 } } ?: -1
                            ChatJump.Missing -> Toast.makeText(context, R.string.chat_jump_missing, Toast.LENGTH_SHORT).show()
                            ChatJump.Unsupported -> Toast.makeText(context, R.string.chat_search_not_loaded, Toast.LENGTH_SHORT).show()
                        }
                        is ChatResult.Failed -> Toast.makeText(context, ChatText.actionError(context, r.code), Toast.LENGTH_LONG).show()
                        else -> Toast.makeText(context, ChatText.actionError(context, "offline"), Toast.LENGTH_LONG).show()
                    }
                }
                if (idx >= 0) {
                    listState.scrollToItem(idx)
                    highlight = id
                }
            }
        }
    }
    val bubbleActions = remember(viewModel, state.profile?.features?.arrival) {
        BubbleActions(
            onLongPress = { actionsFor = it },
            onOpenImage = { m -> m.media?.id?.let(onOpenImage) },
            onOpenFile = { m ->
                scope.launch {
                    val f = viewModel.file(m)
                    if (f == null) Toast.makeText(context, R.string.chat_err_download, Toast.LENGTH_LONG).show()
                    else openFile(context, f, m.meta?.fileName ?: "datei", m.media?.mime, share = false)
                }
            },
            onOpenLocation = { m -> m.location?.let { openLocation(context, it.lat, it.lon, it.label) } },
            onTogglePlay = viewModel::togglePlay,
            onSeek = { m, f -> viewModel.player.seek("m:${m.id}", f, m.meta?.durationMs ?: 0L) },
            onReact = viewModel::react,
            onQuote = jumpTo,
            rich = RichActions(
                onVote = { m, ids -> viewModel.vote(m, ids) },
                onAddOption = { addOptionFor = it },
                onShowVoters = { votersFor = it },
                onClosePoll = { confirmClosePoll = it },
                onNudge = { viewModel.nudge(it) },
                onRsvp = { m, a, g -> viewModel.rsvp(m, a, g) },
                onChecklist = { m, item, done -> viewModel.checklist(m, item, done) },
                onOpenPlace = { lat, lon, label -> openLocation(context, lat, lon, label) },
                onCancelEvent = { confirmCancelEvent = it },
                onShareArrival = { m -> openArrival(m.event?.place) },
                onStopArrival = { m ->
                    com.gatecontrol.android.chat.ChatArrivalService.stop(context, m.id)
                    viewModel.stopArrival(m)
                },
                onActionRun = { m -> if (m.action?.needsConfirm == true) confirmRun = m to null else viewModel.actionRun(m) },
                onActionApprove = { m -> viewModel.actionApprove(m) },
                onActionDeny = { m -> viewModel.actionDeny(m) },
                onCardButton = { m, b ->
                    val route = b.route
                    when {
                        // "Live-Bild": the plugin's own screen (the app's route navigation, e.g. the door screen).
                        route != null -> com.gatecontrol.android.push.PushNavigation.request(com.gatecontrol.android.push.PushNavTarget.forRoute(route, null))
                        b.needsConfirm -> confirmRun = m to b
                        else -> viewModel.cardButton(m, b)
                    }
                },
                onUpdateApp = { if (com.gatecontrol.android.update.PlayUpdater.enabled) openPlayStore(context) else onOpenUpdates() },
                loadCardImage = { m -> viewModel.cardImage(m) },
                features = RichFeatures(arrival = state.profile?.features?.arrival == true),
            ),
        )
    }
    // Messenger 1.4: what a tapped push names (poll, event, reminder …) and the departure hint.
    LaunchedEffect(conversationId) {
        com.gatecontrol.android.chat.ChatDeepLink.pending.collect { t ->
            if (t?.conversationId != conversationId) return@collect
            val target = com.gatecontrol.android.chat.ChatDeepLink.take(conversationId) ?: return@collect
            target.messageId?.let { jumpTo(it) }
            if (target.open == com.gatecontrol.android.chat.ChatNotifyLogic.OPEN_ARRIVAL && state.profile?.features?.arrival != false) {
                val ev = target.messageId?.let { id -> state.items.firstNotNullOfOrNull { (it as? TimelineItem.Message)?.message?.takeIf { m -> m.id == id } } }?.event
                openArrival(ev?.place)
            }
        }
    }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            // The keyboard: above what the scaffold already leaves for the navigation bar.
            .windowInsetsPadding(WindowInsets.ime.exclude(WindowInsets.navigationBars)),
    ) {
        // ─── top bar ───
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!LocalInDetailPane.current) {
                GcIconButton(GcIcons.Back, stringResource(R.string.chat_back), onClick = onBack, tint = MaterialTheme.colorScheme.onBackground)
            } else {
                Spacer(Modifier.width(12.dp))
            }
            if (searchOpen) {
                OutlinedTextField(
                    value = searchText,
                    onValueChange = { searchText = it.take(100) },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.chat_search_in_chat)) },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { viewModel.search(searchText) }),
                    shape = RoundedCornerShape(14.dp),
                )
                GcIconButton(GcIcons.Search, stringResource(R.string.chat_search), onClick = { viewModel.search(searchText) })
                GcIconButton(GcIcons.Close, stringResource(R.string.chat_close), onClick = {
                    searchOpen = false
                    searchText = ""
                    viewModel.closeSearch()
                })
            } else {
                Row(
                    Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onOpenInfo).padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (c != null) ConversationAvatar(c, 40.dp)
                    Column(Modifier.weight(1f)) {
                        Text(
                            c?.let { ChatText.title(context, it) } ?: "",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val typing = ChatText.typing(context, c, state.typing, myId)
                        val sub = typing ?: c?.takeIf { it.isGroup }?.let { conv ->
                            conv.members.filter { it.userId != myId }.joinToString(", ") { ChatText.name(context, it) } +
                                ", " + resources.getString(R.string.chat_you_lower)
                        } ?: c?.takeIf { !it.isGroup }?.let { ChatText.presence(context, it.peer) }
                        if (sub != null) {
                            Text(
                                sub,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (typing != null) MaterialTheme.colorScheme.primary else extra.muted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                if (state.profile?.features?.search != false) {
                    GcIconButton(GcIcons.Search, stringResource(R.string.chat_search_in_chat), onClick = { searchOpen = true })
                }
                GcIconButton(Icons.Outlined.Info, stringResource(R.string.chat_info), onClick = onOpenInfo)
            }
        }
        if (c?.managedBy != null) {
            Text(
                stringResource(R.string.chat_managed_hint),
                style = MaterialTheme.typography.bodySmall,
                color = extra.muted,
                modifier = Modifier.fillMaxWidth().background(extra.panel2).padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }

        // ─── Messenger 1.4: pinned messages and the next event ───
        if (c != null && state.profile?.features?.msgTools == true) {
            val pinned = com.gatecontrol.android.chat.ChatRichLogic.pinnedMessages(c.pinned, state.items.mapNotNull { (it as? TimelineItem.Message)?.message })
            if (pinned.isNotEmpty()) {
                val idx = pinnedIndex.coerceIn(0, pinned.size - 1)
                val shown = pinned[idx]
                PinnedBanner(
                    text = ChatText.content(context, shown, c, myId),
                    position = if (pinned.size > 1) stringResource(R.string.chat_pinned_count, idx + 1, pinned.size) else null,
                    onClick = {
                        jumpTo(shown.id)
                        pinnedIndex = (idx + 1) % pinned.size
                    },
                )
            }
        }
        if (c != null && state.profile?.features?.events == true && com.gatecontrol.android.chat.ChatRichLogic.showNextEvent(c.nextEvent, dismissedEvent, System.currentTimeMillis())) {
            val next = c.nextEvent!!
            NextEventBanner(
                text = stringResource(R.string.chat_event_next, next.title, shortDayTime(context, next.startsAt), next.yesCount),
                onOpen = { jumpTo(next.messageId) },
                onDismiss = { dismissedEvent = next.messageId },
            )
        }

        // ─── messages / search results ───
        val unmetered = remember(deviceConfig.autoWifi, deviceConfig.autoMobile) { viewModel.unmetered() }
        val look = remember(deviceConfig, extra.isDark, unmetered) {
            ChatLookState(
                autoPhotos = com.gatecontrol.android.chat.ChatAutoDownload.photos(deviceConfig, unmetered),
                autoAll = com.gatecontrol.android.chat.ChatAutoDownload.everything(deviceConfig, unmetered),
                fontScale = com.gatecontrol.android.chat.ChatLook.fontScale(deviceConfig.fontSize),
                ownBubble = deviceConfig.bubbleColor.takeIf { it != com.gatecontrol.android.chat.ChatLook.BUBBLE_DEFAULT }
                    ?.let { com.gatecontrol.android.chat.ChatLook.BUBBLE_COLORS[it] }?.let { (dark, light) -> androidx.compose.ui.graphics.Color(if (extra.isDark) dark else light) },
            )
        }
        androidx.compose.runtime.CompositionLocalProvider(LocalChatLook provides look) {
        Box(Modifier.weight(1f).fillMaxWidth().then(chatWallpaper(deviceConfig.wallpaper, viewModel.wallpaperFile, extra.isDark))) {
            val results = state.searchResults
            if (results != null) {
                SearchResults(results, state, onPick = { m ->
                    searchOpen = false
                    viewModel.closeSearch()
                    jumpTo(m.id)
                })
            } else {
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    items(rows, key = { it.key }, contentType = { it::class }) { item ->
                        when (item) {
                            is TimelineItem.Day -> DaySeparator(ChatText.day(context, item.date))
                            is TimelineItem.Message -> {
                                // Automatic download "Alle Medien": pictures, videos and voice load ahead.
                                val mid = item.message.media?.id
                                if (look.autoAll && mid != null && (item.message.kind != ChatMessage.KIND_FILE || item.message.isVideo)) {
                                    LaunchedEffect(mid) { viewModel.prefetch(mid) }
                                }
                                MessageBubble(
                                m = item.message,
                                status = item.status,
                                showSender = item.showSender,
                                conversation = c,
                                myId = myId,
                                player = player,
                                actions = bubbleActions,
                                highlighted = highlight == item.message.id,
                            )
                            }
                            is TimelineItem.Gap -> GapRow(
                                loading = gapLoading == item.gap,
                                onLoad = { viewModel.fillGap(item.gap) },
                            )
                            is TimelineItem.Pending -> PendingBubble(
                                p = item.pending,
                                conversation = c,
                                myId = myId,
                                player = player,
                                onLongPress = { pendingFor = it },
                                onTogglePlay = { p -> p.localPath?.let { viewModel.togglePlayLocal(p.clientId, it) } },
                            )
                        }
                    }
                    if (state.loadingOlder) {
                        item(key = "older") {
                            Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            }
                        }
                    }
                    if (rows.isEmpty()) {
                        item(key = "empty") {
                            Text(
                                stringResource(if (state.loaded) R.string.chat_no_messages_yet else R.string.chat_loading),
                                style = MaterialTheme.typography.bodyMedium,
                                color = extra.muted,
                                modifier = Modifier.fillMaxWidth().padding(32.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                        }
                    }
                }
                if (!atBottom) {
                    SmallFloatingActionButton(
                        onClick = { scope.launch { listState.animateScrollToItem(0) } },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                        containerColor = MaterialTheme.colorScheme.surface,
                    ) {
                        Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = stringResource(R.string.chat_to_newest))
                    }
                }
            }
        }
        }

        // ─── composer ───
        if (c != null && (c.readonly || !c.can.send)) {
            Text(
                stringResource(if (c.readonly) R.string.chat_readonly else R.string.chat_cannot_send),
                style = MaterialTheme.typography.bodyMedium,
                color = extra.muted,
                modifier = Modifier.fillMaxWidth().background(extra.panel2).padding(16.dp),
            )
        } else {
            state.shared?.let { sh ->
                SharedBar(
                    count = sh.files.size,
                    onSend = viewModel::sendShared,
                    onDrop = viewModel::dropShared,
                )
            }
            state.replyTo?.let { r ->
                ComposerBar(
                    icon = Icons.AutoMirrored.Outlined.Reply,
                    title = stringResource(R.string.chat_reply_to, ChatText.nameOf(context, c, r.senderId, myId)),
                    text = ChatText.content(context, r, c, myId),
                    onClose = viewModel::cancelReply,
                )
            }
            state.editing?.let { e ->
                ComposerBar(
                    icon = Icons.Outlined.Edit,
                    title = stringResource(R.string.chat_editing),
                    text = ChatText.content(context, e, c, myId),
                    onClose = {
                        viewModel.cancelEdit()
                        draft = ""
                    },
                )
            }
            Composer(
                draft = draft,
                onDraft = {
                    draft = it
                    if (state.editing == null) viewModel.onTyping(it)
                },
                editing = state.editing != null,
                busy = state.busy,
                enterSends = deviceConfig.enterSends,
                voice = state.profile?.features?.voice != false,
                recorder = viewModel.recorder,
                onSend = { if (viewModel.send(draft)) draft = "" },
                onAttach = { showAttach = true },
                onRecordStart = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        micPermission.launch(Manifest.permission.RECORD_AUDIO)
                        false
                    } else {
                        viewModel.startRecording()
                    }
                },
                onRecordFinish = viewModel::finishRecording,
                onRecordCancel = viewModel::cancelRecording,
                onRecordTooShort = { Toast.makeText(context, R.string.chat_rec_hold, Toast.LENGTH_SHORT).show() },
            )
        }
    }

    if (showAttach) {
        val features = state.profile?.features
        val members = c?.members.orEmpty()
        val others = members.filter { it.userId != myId && !it.gone }
        val actionsOn = features?.actions == true
        LaunchedEffect(Unit) { homeViewModel.prepare() }
        AttachSheet14(
            options = AttachOptions(
                camera = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) },
                files = features?.files != false,
                location = features?.location != false,
                polls = features?.polls == true,
                events = features?.events == true,
                arrival = features?.arrival == true,
                actions = actionsOn,
                maxBytes = state.profile?.limits?.maxBytes,
                task = familieOffer.tasks,
                shopping = familieOffer.shopping,
                actionsHint = if (actionsOn && others.isEmpty()) stringResource(R.string.chat_attach_hint_others) else null,
                quickShare = homeActions?.let { com.gatecontrol.android.chat.ChatRichLogic.visibleActions(it, deviceConfig.hiddenActions) }.orEmpty().take(8),
            ),
            onGallery = {
                showAttach = false
                // Videos are files on the server: offered only where files are.
                val type = if (features?.files != false) ActivityResultContracts.PickVisualMedia.ImageAndVideo else ActivityResultContracts.PickVisualMedia.ImageOnly
                photoPicker.launch(PickVisualMediaRequest(type))
            },
            onCamera = {
                showAttach = false
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchCamera()
                else cameraPermission.launch(Manifest.permission.CAMERA)
            },
            onFile = {
                showAttach = false
                filePicker.launch(arrayOf("*/*"))
            },
            onLocation = {
                showAttach = false
                if (DeviceLocator.hasPermission(context)) viewModel.sendLocation()
                else locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            },
            onPoll = { showAttach = false; showPoll = true },
            onEvent = { showAttach = false; showEvent = true },
            onArrival = {
                showAttach = false
                if (familieOffer.placesShared == false) arrivalPlacesHint = true else openArrival(null)
            },
            onAction = { mode ->
                showAttach = false
                when (mode) {
                    com.gatecontrol.android.chat.ChatActionCard.MODE_REQUEST -> {
                        homeViewModel.loadRequestable(conversationId)
                        requestSheet = null
                        requestOpen = true
                    }
                    com.gatecontrol.android.chat.ChatActionCard.MODE_GRANT -> {
                        shareSheet = null
                        shareOpen = true
                    }
                    else -> actionMode = mode
                }
            },
            onTask = { showAttach = false; taskSheet = true },
            onShopping = { showAttach = false; shoppingSheet = true },
            onQuickShare = { a ->
                showAttach = false
                if (a.needsConfirm) {
                    homeViewModel.loadRequestable(conversationId)
                    requestSheet = a
                    requestOpen = true
                } else {
                    shareSheet = a
                    shareOpen = true
                }
            },
            onHint = { h -> attachHint = h },
            onDismiss = { showAttach = false },
        )
    }
    attachHint?.let { h ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { attachHint = null },
            text = { Text(h) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { attachHint = null }) { Text(stringResource(R.string.chat_ok)) } },
        )
    }
    if (arrivalPlacesHint) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { arrivalPlacesHint = false },
            title = { Text(stringResource(R.string.chat_attach_arrival)) },
            text = { Text(stringResource(R.string.chat_arrival_places_hint)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    arrivalPlacesHint = false
                    if (onOpenProfile != null) onOpenProfile() else openArrival(null)
                }) { Text(stringResource(if (onOpenProfile != null) R.string.chat_attach_open_profile else R.string.chat_arrival_anyway)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { arrivalPlacesHint = false; openArrival(null) }) {
                    Text(stringResource(R.string.chat_arrival_anyway))
                }
            },
        )
    }
    val homeInfo = state.profile?.home ?: com.gatecontrol.android.chat.ChatHomeInfo()
    val homeOthers = c?.members.orEmpty().filter { it.userId != myId && !it.gone }
    if (requestOpen) {
        val requestable by homeViewModel.requestable.collectAsStateWithLifecycle()
        val fallback by homeViewModel.requestableFallback.collectAsStateWithLifecycle()
        val tokens = state.profile?.features?.tokens.orEmpty()
        DoorRequestSheet(
            requestable = requestable,
            fallback = fallback,
            others = homeOthers,
            chooseApprovers = "action_requests" in tokens,
            parentsOffered = homeInfo.familyRoles || homeInfo.approversDefault == com.gatecontrol.android.chat.ChatApprovers.PARENTS,
            defaultApprovers = homeInfo.approversDefault,
            ttlMin = homeInfo.requestTtlMin,
            canRequest = homeInfo.canRequest,
            initial = requestSheet,
            busy = homeBusy,
            onApprovers = { c -> homeViewModel.loadRequestable(conversationId, c) },
            onSend = { a, approvers, note -> homeViewModel.sendRequest(conversationId, a, approvers, note) { requestOpen = false } },
            onDismiss = { requestOpen = false },
        )
    }
    if (shareOpen) {
        ShareDeviceSheet(
            actions = homeActions?.let { com.gatecontrol.android.chat.ChatRichLogic.visibleActions(it, deviceConfig.hiddenActions) },
            others = homeOthers,
            maxDays = homeInfo.grantMaxDays,
            offAtEndDefault = homeInfo.offAtEndDefault,
            initial = shareSheet,
            busy = homeBusy,
            onShare = { a, to, until, off -> homeViewModel.sendGrant(conversationId, a, to, until, off) { shareOpen = false } },
            onRequestInstead = { a ->
                shareOpen = false
                homeViewModel.loadRequestable(conversationId)
                requestSheet = a
                requestOpen = true
            },
            onDismiss = { shareOpen = false },
            grantOff = "grant_off" in state.profile?.features?.tokens.orEmpty(),
        )
    }
    if (taskSheet) {
        TaskSheet(
            others = homeOthers,
            me = c?.members?.firstOrNull { it.userId == myId },
            busy = homeBusy,
            onSend = { title, assignee, due, repeat -> homeViewModel.sendTask(conversationId, title, assignee, due, repeat) { taskSheet = false } },
            onDismiss = { taskSheet = false },
        )
    }
    if (shoppingSheet) {
        val lists by homeViewModel.shoppingLists.collectAsStateWithLifecycle()
        val direct = c?.isGroup == false
        ShoppingShareSheet(
            lists = lists,
            others = homeOthers,
            direct = direct,
            busy = homeBusy,
            onGoing = { l -> homeViewModel.shoppingGoing(conversationId, l) { shoppingSheet = false } },
            onAsk = { l, uid ->
                homeViewModel.shoppingAssign(conversationId, l, uid, if (direct) com.gatecontrol.android.ui.familie.ShoppingNotify.DIRECT else com.gatecontrol.android.ui.familie.ShoppingNotify.FAMILY) { shoppingSheet = false }
            },
            onDismiss = { shoppingSheet = false },
        )
    }

    // ─── Messenger 1.4 editors and sheets ───
    if (showPoll) {
        PollCreateDialog(
            busy = richBusy,
            onSend = { q, opts, multi, anon, add, closes -> viewModel.sendPoll(q, opts, multi, anon, add, closes) { showPoll = false } },
            onDismiss = { showPoll = false },
        )
    }
    if (showEvent) {
        EventCreateDialog(
            places = places.takeIf { state.profile?.familie?.places != false },
            defaultReminderMin = state.profile?.prefs14?.notify?.eventReminderMin ?: 60,
            arrivalOn = state.profile?.features?.arrival == true,
            busy = richBusy,
            onSend = { d -> viewModel.sendEvent(d) { showEvent = false } },
            onDismiss = { showEvent = false },
        )
    }
    if (arrivalSheet) {
        ArrivalSheet(
            places = places.takeIf { state.profile?.familie?.places != false } ?: emptyList(),
            actions = if (state.profile?.features?.actions == true) homeActions.orEmpty() else emptyList(),
            initial = arrivalDest,
            busy = richBusy,
            onStart = { dest, onArrive -> viewModel.sendArrival(dest, onArrive) { arrivalSheet = false } },
            onDismiss = { arrivalSheet = false },
            placesShared = familieOffer.placesShared,
            onOpenProfile = onOpenProfile?.let { open -> { arrivalSheet = false; open() } },
        )
    }
    actionMode?.let { mode ->
        // Actions with a confirmation (a door) cannot be granted.
        val visible = homeActions?.let { com.gatecontrol.android.chat.ChatRichLogic.visibleActions(it, deviceConfig.hiddenActions) }
            ?.filter { mode != com.gatecontrol.android.chat.ChatActionCard.MODE_GRANT || !it.needsConfirm }
        ActionPickerSheet(
            mode = mode,
            actions = visible,
            members = c?.members.orEmpty().filter { it.userId != myId && !it.gone },
            busy = richBusy,
            onPick = { a, to, until -> viewModel.sendAction(mode, a, to, until) { actionMode = null } },
            onDismiss = { actionMode = null },
        )
    }
    votersFor?.let { m -> m.poll?.let { VotersSheet(it, c, myId) { votersFor = null } } ?: run { votersFor = null } }
    addOptionFor?.let { m ->
        TextLineDialog(
            title = stringResource(R.string.chat_poll_add_option),
            hint = stringResource(R.string.chat_poll_add_hint),
            max = com.gatecontrol.android.chat.ChatPoll.OPTION_MAX,
            onDone = { t -> addOptionFor = null; viewModel.addOption(m, t) },
            onDismiss = { addOptionFor = null },
        )
    }
    confirmClosePoll?.let { m ->
        ConfirmDialog(stringResource(R.string.chat_poll_close_title), stringResource(R.string.chat_poll_close_text), stringResource(R.string.chat_poll_close), {
            confirmClosePoll = null
            viewModel.closePoll(m)
        }) { confirmClosePoll = null }
    }
    confirmCancelEvent?.let { m ->
        ConfirmDialog(stringResource(R.string.chat_event_cancel), stringResource(R.string.chat_event_cancel_text), stringResource(R.string.chat_event_cancel), {
            confirmCancelEvent = null
            viewModel.cancelEvent(m)
        }) { confirmCancelEvent = null }
    }
    confirmRun?.let { (m, button) ->
        val label = button?.label ?: m.action?.label ?: ""
        ConfirmDialog(stringResource(R.string.chat_action_confirm_title, label), null, stringResource(R.string.chat_action_run), {
            confirmRun = null
            if (button != null) viewModel.cardButton(m, button) else viewModel.actionRun(m)
        }) { confirmRun = null }
    }
    remindFor?.let { m ->
        RemindSheet(onPick = { at -> remindFor = null; viewModel.remind(m, at) }, onDismiss = { remindFor = null })
    }

    actionsFor?.let { m ->
        val tools = state.profile?.features?.msgTools == true && !m.deleted && !m.isSystem
        val familie = state.profile?.familie
        // "Auf Einkaufsliste" / "Termin anlegen": what the server finds in the text (loaded with the menu).
        val suggestion by androidx.compose.runtime.produceState<com.gatecontrol.android.chat.ChatSuggestion?>(null, m.id, tools) {
            value = if (tools && !m.text.isNullOrBlank()) viewModel.suggest(m) else null
        }
        val pinnedNow = c?.pinned?.contains(m.id) == true
        MessageActionsSheet(
            m = m,
            tools = tools,
            canPin = tools && c?.can?.pin != false && c?.readonly != true,
            pinned = pinnedNow,
            suggestion = suggestion?.let { s -> s.copy(items = s.items.takeIf { familie?.lists != false }.orEmpty(), dates = s.dates.takeIf { familie?.calendar != false }.orEmpty()) },
            onPin = {
                actionsFor = null
                viewModel.pin(m, !pinnedNow)
            },
            onRemind = {
                actionsFor = null
                remindFor = m
            },
            onToList = { items ->
                actionsFor = null
                viewModel.toList(m, items)
            },
            onToCalendar = { d ->
                actionsFor = null
                viewModel.toCalendar(m, d)
            },
            reactions = state.profile?.features?.reactions != false && !m.deleted,
            myReaction = m.reactions.firstOrNull { r -> myId != null && myId in r.userIds }?.emoji,
            canEdit = viewModel.canEdit(m),
            canDeleteForAll = viewModel.canDeleteForAll(m),
            canReply = c?.can?.send != false && c?.readonly != true && !m.deleted,
            onReact = { e ->
                actionsFor = null
                viewModel.react(m, e)
            },
            onReply = {
                actionsFor = null
                viewModel.startReply(m)
            },
            onCopy = {
                actionsFor = null
                clipboard.setText(AnnotatedString(m.text ?: ChatText.content(context, m, c, myId)))
            },
            onEdit = {
                actionsFor = null
                viewModel.startEdit(m)
            },
            onShareFile = if (m.media != null) {
                {
                    actionsFor = null
                    scope.launch {
                        val f = viewModel.file(m)
                        if (f == null) Toast.makeText(context, R.string.chat_err_download, Toast.LENGTH_LONG).show()
                        else openFile(context, f, m.meta?.fileName ?: defaultName(m), m.media.mime, share = true)
                    }
                }
            } else null,
            onDeleteForMe = {
                actionsFor = null
                viewModel.delete(m, forAll = false)
            },
            onDeleteForAll = {
                actionsFor = null
                confirmDeleteAll = m
            },
            onDismiss = { actionsFor = null },
        )
    }

    pendingFor?.let { p ->
        ModalBottomSheet(onDismissRequest = { pendingFor = null }, containerColor = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(bottom = 24.dp)) {
                if (p.failed) {
                    Text(
                        ChatText.sendError(context, p.error),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                SheetRow(Icons.Outlined.Refresh, stringResource(R.string.chat_retry)) {
                    pendingFor = null
                    viewModel.retry(p.clientId)
                }
                SheetRow(Icons.Outlined.Delete, stringResource(R.string.chat_discard)) {
                    pendingFor = null
                    viewModel.discard(p.clientId)
                }
            }
        }
    }

    confirmDeleteAll?.let { m ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDeleteAll = null },
            title = { Text(stringResource(R.string.chat_delete_all_title)) },
            text = { Text(stringResource(R.string.chat_delete_all_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteAll = null
                    viewModel.delete(m, forAll = true)
                }) { Text(stringResource(R.string.chat_delete_all)) }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteAll = null }) { Text(stringResource(R.string.chat_cancel)) } },
        )
    }
}

internal fun defaultName(m: ChatMessage): String = when (m.kind) {
    ChatMessage.KIND_IMAGE -> "bild-${m.id}.webp"
    ChatMessage.KIND_VOICE -> "sprachnachricht-${m.id}.m4a"
    else -> "datei-${m.id}"
}

@Composable
private fun SearchResults(results: List<ChatMessage>, state: ConversationUiState, onPick: (ChatMessage) -> Unit) {
    val context = LocalContext.current
    val extra = GateControlTheme.extraColors
    LazyColumn(Modifier.fillMaxSize()) {
        if (results.isEmpty()) {
            item {
                Text(
                    stringResource(if (state.searching) R.string.chat_loading else R.string.chat_search_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = extra.muted,
                    modifier = Modifier.padding(24.dp),
                )
            }
        }
        items(results, key = { it.id }) { m ->
            Column(Modifier.fillMaxWidth().clickable { onPick(m) }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Row {
                    Text(
                        ChatText.nameOf(context, state.conversation, m.senderId, state.myId),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Text(ChatText.listTime(context, m.createdAt), style = MaterialTheme.typography.bodySmall, color = extra.muted)
                }
                Text(ChatText.content(context, m, state.conversation, state.myId), style = MaterialTheme.typography.bodyMedium, color = extra.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Messages not loaded yet between two rows (after a jump far back): loaded once scrolled to. */
@Composable
private fun GapRow(loading: Boolean, onLoad: () -> Unit) {
    val extra = GateControlTheme.extraColors
    LaunchedEffect(Unit) { onLoad() }
    Box(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        } else {
            Text(
                stringResource(R.string.chat_gap_load),
                style = MaterialTheme.typography.labelLarge,
                color = extra.accentText,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(extra.panel2)
                    .clickable(role = Role.Button, onClick = onLoad).padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun ComposerBar(icon: ImageVector, title: String, text: String, onClose: () -> Unit) {
    val extra = GateControlTheme.extraColors
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(start = 16.dp, end = 4.dp, top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = extra.accentText, maxLines = 1)
            Text(text, style = MaterialTheme.typography.bodySmall, color = extra.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        GcIconButton(GcIcons.Close, stringResource(R.string.chat_close), onClick = onClose, size = 40.dp, iconSize = 18.dp)
    }
}

@Composable
private fun SharedBar(count: Int, onSend: () -> Unit, onDrop: () -> Unit) {
    val extra = GateControlTheme.extraColors
    Row(
        Modifier.fillMaxWidth().background(extra.blueBg).padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Share, contentDescription = null, tint = extra.blue, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            if (count > 0) androidx.compose.ui.res.pluralStringResource(R.plurals.chat_shared_files, count, count) else stringResource(R.string.chat_shared_text),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (count > 0) TextButton(onClick = onSend) { Text(stringResource(R.string.chat_send)) }
        TextButton(onClick = onDrop) { Text(stringResource(R.string.chat_cancel)) }
    }
}

/** Text field, attachments, send — or the microphone (hold to record, slide left to cancel). */
@Composable
private fun Composer(
    draft: String,
    onDraft: (String) -> Unit,
    editing: Boolean,
    busy: Boolean,
    enterSends: Boolean,
    voice: Boolean,
    recorder: com.gatecontrol.android.chat.VoiceRecorder,
    onSend: () -> Unit,
    onAttach: () -> Unit,
    onRecordStart: () -> Boolean,
    onRecordFinish: () -> Unit,
    onRecordCancel: () -> Unit,
    onRecordTooShort: () -> Unit,
) {
    val extra = GateControlTheme.extraColors
    val scheme = MaterialTheme.colorScheme
    var recording by remember { mutableStateOf(false) }
    var dragX by remember { mutableFloatStateOf(0f) }
    val elapsed by recorder.elapsedMs.collectAsState()
    val cancelPx = with(LocalDensity.current) { 110.dp.toPx() }
    Row(
        Modifier.fillMaxWidth().background(scheme.surface).padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (recording) {
            Row(
                Modifier.weight(1f).heightIn(min = 48.dp).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(scheme.error))
                Text(VoiceWave.clock(elapsed), style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
                Text(
                    stringResource(R.string.chat_rec_slide_cancel),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (-dragX > cancelPx * 0.6f) scheme.error else extra.muted,
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            GcIconButton(Icons.Outlined.AddCircleOutline, stringResource(R.string.chat_attach), onClick = onAttach, enabled = !editing && !busy, tint = extra.muted)
            OutlinedTextField(
                value = draft,
                onValueChange = { onDraft(it.take(com.gatecontrol.android.chat.ChatJson.TEXT_MAX)) },
                placeholder = { Text(stringResource(R.string.chat_message_hint)) },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).then(
                    // Messenger 1.4 settings: Enter sends (a hardware keyboard's Enter; Shift+Enter a new line).
                    if (enterSends) {
                        Modifier.onPreviewKeyEvent { e ->
                            if (e.key == androidx.compose.ui.input.key.Key.Enter && !e.isShiftPressed) {
                                if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && draft.isNotBlank()) onSend()
                                true
                            } else {
                                false
                            }
                        }
                    } else {
                        Modifier
                    },
                ),
                maxLines = 6,
                shape = RoundedCornerShape(24.dp),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = if (enterSends) androidx.compose.ui.text.input.ImeAction.Send else androidx.compose.ui.text.input.ImeAction.Default,
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { if (draft.isNotBlank()) onSend() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = scheme.background,
                    unfocusedContainerColor = scheme.background,
                    focusedBorderColor = extra.border2,
                    unfocusedBorderColor = extra.border,
                ),
            )
        }
        val showSend = draft.isNotBlank() || editing || !voice
        if (busy && !recording) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
        } else if (showSend) {
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(scheme.primary).clickable(role = Role.Button, enabled = draft.isNotBlank() || editing, onClick = onSend),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (editing) GcIcons.Check else Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(if (editing) R.string.chat_save else R.string.chat_send),
                    tint = scheme.onPrimary,
                )
            }
        } else {
            val desc = stringResource(R.string.chat_rec_hold)
            Box(
                Modifier
                    .size(if (recording) 64.dp else 48.dp)
                    .clip(CircleShape)
                    .background(if (recording) scheme.error else scheme.primary)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            val startedAt = System.currentTimeMillis()
                            dragX = 0f
                            recording = onRecordStart()
                            if (!recording) return@awaitEachGesture
                            var cancelled = false
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                dragX = change.position.x - down.position.x
                                if (-dragX > cancelPx) {
                                    cancelled = true
                                    break
                                }
                                if (!change.pressed) break
                            }
                            recording = false
                            when {
                                cancelled -> onRecordCancel()
                                System.currentTimeMillis() - startedAt < com.gatecontrol.android.chat.VoiceRecorder.MIN_MS -> {
                                    onRecordCancel()
                                    onRecordTooShort()
                                }
                                else -> onRecordFinish()
                            }
                            dragX = 0f
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Mic, contentDescription = desc, tint = scheme.onPrimary)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageActionsSheet(
    m: ChatMessage,
    tools: Boolean,
    canPin: Boolean,
    pinned: Boolean,
    suggestion: com.gatecontrol.android.chat.ChatSuggestion?,
    onPin: () -> Unit,
    onRemind: () -> Unit,
    onToList: (List<String>) -> Unit,
    onToCalendar: (com.gatecontrol.android.chat.ChatSuggestedDate) -> Unit,
    reactions: Boolean,
    myReaction: String?,
    canEdit: Boolean,
    canDeleteForAll: Boolean,
    canReply: Boolean,
    onReact: (String) -> Unit,
    onReply: () -> Unit,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onShareFile: (() -> Unit)?,
    onDeleteForMe: () -> Unit,
    onDeleteForAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    val extra = GateControlTheme.extraColors
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(bottom = 24.dp)) {
            if (reactions && !m.isSystem) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    QUICK_REACTIONS.forEach { e ->
                        Box(
                            Modifier.size(48.dp).clip(CircleShape)
                                .background(if (e == myReaction) extra.accentBg else extra.panel2)
                                .border(1.dp, extra.border, CircleShape)
                                .clickable(role = Role.Button) { onReact(e) },
                            contentAlignment = Alignment.Center,
                        ) { Text(e, fontSize = 22.sp) }
                    }
                }
            }
            if (canReply && !m.isSystem) SheetRow(Icons.AutoMirrored.Outlined.Reply, stringResource(R.string.chat_reply), onClick = onReply)
            if (!m.deleted && (m.text != null || m.isSystem)) SheetRow(Icons.Outlined.ContentCopy, stringResource(R.string.chat_copy), onClick = onCopy)
            if (canPin) SheetRow(Icons.Outlined.PushPin, stringResource(if (pinned) R.string.chat_unpin else R.string.chat_pin), onClick = onPin)
            // Messenger 1.4: what the server found in the text, straight into Familie.
            suggestion?.items?.takeIf { it.isNotEmpty() }?.let { items ->
                SheetRow(
                    Icons.Outlined.ShoppingCart, stringResource(R.string.chat_suggest_list), highlight = true,
                    trailing = pluralStringResource(R.plurals.chat_suggest_items, items.size, items.size),
                ) { onToList(items) }
            }
            suggestion?.dates?.firstOrNull()?.let { d ->
                SheetRow(Icons.Outlined.Event, stringResource(R.string.chat_suggest_calendar), highlight = true, trailing = d.label?.uppercase()) { onToCalendar(d) }
            }
            if (tools) SheetRow(Icons.Outlined.Alarm, stringResource(R.string.chat_remind_me), onClick = onRemind)
            if (canEdit) SheetRow(Icons.Outlined.Edit, stringResource(R.string.chat_edit), onClick = onEdit)
            onShareFile?.let { SheetRow(Icons.Outlined.Share, stringResource(R.string.chat_share_file), onClick = it) }
            SheetRow(Icons.Outlined.Delete, stringResource(R.string.chat_delete_me), onClick = onDeleteForMe)
            if (canDeleteForAll) SheetRow(Icons.Outlined.Delete, stringResource(R.string.chat_delete_all), danger = true, onClick = onDeleteForAll)
            if (suggestion != null && !suggestion.isEmpty) {
                Text(
                    stringResource(R.string.chat_suggest_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = extra.muted,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun SheetRow(icon: ImageVector, label: String, danger: Boolean = false, highlight: Boolean = false, trailing: String? = null, onClick: () -> Unit) {
    val extra = GateControlTheme.extraColors
    val color = when {
        danger -> MaterialTheme.colorScheme.error
        highlight -> extra.accentText
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, contentDescription = null, tint = if (danger || highlight) color else extra.muted)
        Text(label, style = MaterialTheme.typography.bodyLarge, color = color, modifier = Modifier.weight(1f))
        trailing?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = com.gatecontrol.android.ui.theme.MonoFontFamily, fontWeight = FontWeight.Bold),
                color = extra.accentText,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(extra.accentBg).padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
internal fun ConfirmDialog(title: String, text: String?, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = text?.let { { Text(it) } },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_cancel)) } },
    )
}

/** Pinned messages (Messenger 1.4): tapping shows the message and steps to the next pin. */
@Composable
private fun PinnedBanner(text: String, position: String?, onClick: () -> Unit) {
    val extra = GateControlTheme.extraColors
    Row(
        Modifier.fillMaxWidth().background(extra.panel2).clickable(role = Role.Button, onClickLabel = stringResource(R.string.chat_jump_message), onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.width(3.dp).heightIn(min = 32.dp).background(MaterialTheme.colorScheme.primary))
        Icon(Icons.Outlined.PushPin, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.chat_pinned_title) + (position?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = extra.accentText,
            )
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The next event of the chat (Messenger 1.4 `nextEvent`), pinned above the messages. */
@Composable
private fun NextEventBanner(text: String, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val extra = GateControlTheme.extraColors
    Row(
        Modifier.fillMaxWidth().background(extra.blueBg).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Outlined.Event, contentDescription = null, tint = extra.blue, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        TextButton(onClick = onOpen) { Text(stringResource(R.string.chat_event_view)) }
        GcIconButton(GcIcons.Close, stringResource(R.string.chat_close), onClick = onDismiss, size = 40.dp, iconSize = 18.dp)
    }
}

/** Background of the messages: none, a colour or the own photo (this device's setting). */
@Composable
private fun chatWallpaper(key: String, file: File, dark: Boolean): Modifier {
    if (key == com.gatecontrol.android.chat.ChatLook.WALLPAPER_NONE) return Modifier
    if (key == com.gatecontrol.android.chat.ChatLook.WALLPAPER_PHOTO) {
        val px = with(LocalDensity.current) { 1080.dp.roundToPx() }
        val bmp = rememberLocalBitmap(file.path.takeIf { file.isFile }, px) ?: return Modifier
        val painter = remember(bmp) { androidx.compose.ui.graphics.painter.BitmapPainter(bmp) }
        val scrim = if (dark) androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f) else androidx.compose.ui.graphics.Color.White.copy(alpha = 0.35f)
        return Modifier.paint(painter, contentScale = androidx.compose.ui.layout.ContentScale.Crop).background(scrim)
    }
    val (d, l) = com.gatecontrol.android.chat.ChatLook.WALLPAPERS[key] ?: return Modifier
    return Modifier.background(androidx.compose.ui.graphics.Color(if (dark) d else l))
}

/** Opens (or shares) a downloaded chat file under its own name through the FileProvider. */
internal fun openFile(context: Context, file: File, name: String, mime: String?, share: Boolean) {
    try {
        val dir = File(context.cacheDir, "chat-open").apply { mkdirs() }
        dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 24 * 3600_000L }?.forEach { it.delete() }
        val safe = ChatMime.safeName(name, "bin")
        val target = File(dir, safe)
        file.copyTo(target, overwrite = true)
        val uri: Uri = FileProvider.getUriForFile(context, context.packageName + ".provider", target)
        val type = mime ?: "application/octet-stream"
        val intent = if (share) {
            Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uri)
        } else {
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, type)
        }.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData = ClipData.newRawUri(safe, uri)
        context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, R.string.chat_err_no_app, Toast.LENGTH_LONG).show()
    } catch (e: Exception) {
        Toast.makeText(context, R.string.chat_err_download, Toast.LENGTH_LONG).show()
    }
}

/** A shared location in the person's maps app (`geo:` link). */
internal fun openLocation(context: Context, lat: Double, lon: Double, label: String?) {
    val q = String.format(Locale.ROOT, "%.6f,%.6f", lat, lon) + (label?.let { "(" + Uri.encode(it) + ")" } ?: "")
    val uri = ("geo:" + String.format(Locale.ROOT, "%.6f,%.6f", lat, lon) + "?q=" + q).toUri()
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        val web = String.format(Locale.ROOT, "https://www.openstreetmap.org/?mlat=%.6f&mlon=%.6f#map=17/%.6f/%.6f", lat, lon, lat, lon).toUri()
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, web).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
