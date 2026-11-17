package com.gatecontrol.android.ui.chat

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WhereToVote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.R
import com.gatecontrol.android.chat.ChatBridgeChat
import com.gatecontrol.android.chat.ChatConversation
import com.gatecontrol.android.chat.ChatExtrasRepository
import com.gatecontrol.android.chat.ChatPeople
import com.gatecontrol.android.chat.ChatPersonWay
import com.gatecontrol.android.chat.ChatPlaceGeofences
import com.gatecontrol.android.chat.ChatPlaceMessage
import com.gatecontrol.android.chat.ChatPlaceMessages
import com.gatecontrol.android.chat.ChatPlaceMessagesRepository
import com.gatecontrol.android.chat.ChatPmDraft
import com.gatecontrol.android.chat.ChatPmPlace
import com.gatecontrol.android.chat.ChatPmWindow
import com.gatecontrol.android.chat.ChatRepository
import com.gatecontrol.android.chat.ChatResult
import com.gatecontrol.android.familie.locate.FamiliePermissions
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcFilterChip
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcListRow
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcSectionLabel
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.components.GcSwitchRow
import com.gatecontrol.android.ui.components.GcTextField
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.familie.LatLon
import com.gatecontrol.android.ui.familie.OsmMap
import com.gatecontrol.android.ui.notizen.NTimePicker
import com.gatecontrol.android.ui.theme.GateControlTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

/*
 * Messenger 1.27: Ortsnachrichten — the overview (Einstellungen › Chats › Ortsnachrichten) and the form („Beim Ankommen
 * senden“ from the plus menu, the recipient set). In the app's design: cards, chips, switch rows, the OSM map of the live
 * arrival.
 */

/** A one-shot notice of the Ortsnachrichten: a string resource (with a number) or an error code. */
sealed interface PlaceNotice {
    data class Done(val res: Int) : PlaceNotice
    /** [message]: the server's own sentence (`error`), shown where the app has no text of its own for [code]. */
    data class Failed(val code: String?, val message: String? = null) : PlaceNotice
}

@HiltViewModel
class ChatPlaceMessagesViewModel @Inject constructor(
    private val repo: ChatPlaceMessagesRepository,
    private val geofences: ChatPlaceGeofences,
    private val chat: ChatRepository,
    private val extras: ChatExtrasRepository,
) : ViewModel() {
    val list = repo.list
    val places = extras.places
    val profile = chat.profile
    val conversations: StateFlow<List<ChatConversation>> = chat.conversations.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val deviceId: String get() = repo.deviceId
    val deviceName: String get() = repo.deviceName

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val _dropped = MutableStateFlow<Map<Long, Long>>(emptyMap())
    /** Ortsnachrichten whose message was dropped (no network within 30 minutes). */
    val dropped: StateFlow<Map<Long, Long>> = _dropped

    private val _readiness = MutableStateFlow(geofences.readiness())
    val readiness: StateFlow<FamiliePermissions.GeofenceReadiness> = _readiness

    private val noticeCh = Channel<PlaceNotice>(Channel.BUFFERED)
    val notices = noticeCh.receiveAsFlow()

    /** The list, the Familie places, the permission (again after a return from the Android settings). */
    fun load() {
        _readiness.value = geofences.readiness()
        _dropped.value = repo.dropped()
        geofences.onSystemChange()
        viewModelScope.launch {
            repo.refresh()
            _dropped.value = repo.dropped()
        }
        viewModelScope.launch { if (chat.profile.value?.familie?.places != false) extras.refreshPlaces() }
    }

    private fun run(block: suspend () -> ChatResult<*>, done: Int?, onOk: () -> Unit = {}) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                when (val r = block()) {
                    is ChatResult.Ok -> {
                        onOk()
                        done?.let { noticeCh.trySend(PlaceNotice.Done(it)) }
                    }
                    is ChatResult.Failed -> noticeCh.trySend(PlaceNotice.Failed(r.code, r.message))
                    else -> noticeCh.trySend(PlaceNotice.Failed("offline"))
                }
            } finally {
                _busy.value = false
            }
        }
    }

    fun save(editingId: Long?, d: ChatPmDraft, onDone: () -> Unit) =
        run({ if (editingId == null) repo.create(d) else repo.update(editingId, d) }, R.string.chat_pm_saved, onDone)

    fun delete(id: Long, onDone: () -> Unit = {}) = run({ repo.delete(id) }, R.string.chat_pm_deleted, onDone)

    fun setEnabled(id: Long, on: Boolean) = run({ repo.setEnabled(id, on) }, null)

    /** „Auf dieses Handy holen“: every Ortsnachricht of another device. */
    fun moveHere() = run({ repo.moveHere(list.value?.items.orEmpty().filter { it.deviceId != repo.deviceId }.map { it.id }) }, null)
}

// ─── texts ──────────────────────────────────────────────────────────

private val DAY_RES = intArrayOf(
    R.string.chat_pm_day_1, R.string.chat_pm_day_2, R.string.chat_pm_day_3, R.string.chat_pm_day_4,
    R.string.chat_pm_day_5, R.string.chat_pm_day_6, R.string.chat_pm_day_7,
)

/** „Mo–Fr, 16:00–20:00 Uhr“. */
internal fun windowText(ctx: Context, w: ChatPmWindow): String {
    val run = ChatPlaceMessages.dayRun(w.days)
    val days = if (run != null) {
        ctx.getString(R.string.chat_pm_day_range, ctx.getString(DAY_RES[run.first - 1]), ctx.getString(DAY_RES[run.second - 1]))
    } else {
        w.days.joinToString(", ") { ctx.getString(DAY_RES[it - 1]) }
    }
    return ctx.getString(R.string.chat_pm_window, days, w.from, w.to)
}

/** The label of a way of an Ortsnachricht: „Automatisch“, „WhatsApp“. */
@Composable
private fun wayText(way: String): String = if (way == ChatPeople.REPLY_AUTO) stringResource(R.string.chat_pm_way_auto) else ChatPeople.labelOf(way)

private fun openAppSettings(ctx: Context) {
    runCatching {
        ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/**
 * Why saving (or switching) an Ortsnachricht failed — never only „Das hat nicht geklappt.“: the app's own text for
 * the codes it knows, else the server's sentence ([message], in the app's language), else the code itself.
 */
internal fun placeErrorText(ctx: Context, code: String?, message: String?, activeMax: Int = 10): String {
    val why = message ?: code ?: "?"
    return when (code) {
        "limit" -> ctx.getString(R.string.chat_pm_err_limit, activeMax)
        "readonly" -> ctx.getString(R.string.chat_pm_err_readonly)
        "bad_request" -> ctx.getString(R.string.chat_pm_err_way)
        "invalid" -> ctx.getString(R.string.chat_pm_err_invalid, why)
        "not_found" -> ctx.getString(R.string.chat_pm_err_not_found)
        // the answer came (2xx) but was not an Ortsnachricht the app could read
        com.gatecontrol.android.chat.ChatApi.CODE_INVALID -> ctx.getString(R.string.chat_pm_err_answer)
        else -> ChatText.actionErrorRes(code)?.let { ctx.getString(it) } ?: ctx.getString(R.string.chat_pm_err_other, why)
    }
}

@Composable
private fun PlaceNotices(vm: ChatPlaceMessagesViewModel) {
    val ctx = LocalContext.current
    val res = androidx.compose.ui.platform.LocalResources.current
    val limits = vm.list.collectAsStateWithLifecycle().value?.limits
    LaunchedEffect(vm) {
        vm.notices.collect { n ->
            val text = when (n) {
                is PlaceNotice.Done -> res.getString(n.res)
                is PlaceNotice.Failed -> placeErrorText(ctx, n.code, n.message, limits?.activeMax ?: 10)
            }
            Toast.makeText(ctx, text, Toast.LENGTH_LONG).show()
        }
    }
}

// ─── the overview ───────────────────────────────────────────────────

/** Einstellungen › Chats › Ortsnachrichten: on and off, tap to change, the device, „Immer erlauben“. */
@Composable
fun ChatPlaceMessagesScreen(onBack: () -> Unit, vm: ChatPlaceMessagesViewModel = hiltViewModel()) {
    val ctx = LocalContext.current
    val extra = GateControlTheme.extraColors
    val list by vm.list.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val readiness by vm.readiness.collectAsStateWithLifecycle()
    val dropped by vm.dropped.collectAsStateWithLifecycle()
    val conversations by vm.conversations.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<ChatPlaceMessage?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ChatPlaceMessage?>(null) }
    LifecycleResumeEffect(Unit) {
        vm.load()
        onPauseOrDispose { }
    }
    PlaceNotices(vm)
    val items = list?.items.orEmpty()
    val limits = list?.limits
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            GcSubpageBar(title = stringResource(R.string.chat_pm_title), onBack = onBack, backDescription = stringResource(R.string.chat_back))
            LazyColumn(
                Modifier.weight(1f).testTag("place-messages"),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "device") { DeviceNote(readiness, vm.deviceName) { openAppSettings(ctx) } }
                val others = items.any { it.deviceId != vm.deviceId && it.active }
                if (others) {
                    item(key = "others") {
                        GcBanner(tone = GcTone.Info, icon = Icons.Outlined.Info) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.chat_pm_other_note), style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = vm::moveHere, enabled = !busy) { Text(stringResource(R.string.chat_pm_move_here)) }
                            }
                        }
                    }
                }
                if (items.isEmpty() && list != null) {
                    item(key = "empty") { Text(stringResource(R.string.chat_pm_empty), style = MaterialTheme.typography.bodyMedium, color = extra.muted, modifier = Modifier.padding(8.dp)) }
                }
                items(items, key = { it.id }) { p ->
                    PlaceMessageCard(
                        p = p,
                        here = p.deviceId == vm.deviceId,
                        dropped = dropped[p.id],
                        busy = busy,
                        onToggle = { on -> vm.setEnabled(p.id, on) },
                        onOpen = { editing = p },
                        onDelete = { deleting = p },
                    )
                }
                item(key = "limit") {
                    Text(
                        stringResource(R.string.chat_pm_limit_note, limits?.activeMax ?: 10),
                        style = MaterialTheme.typography.bodySmall,
                        color = extra.muted,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { creating = true },
            icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
            text = { Text(stringResource(R.string.chat_pm_add)) },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).testTag("place-message-add"),
        )
    }
    if (creating || editing != null) {
        PlaceMessageEditor(
            vm = vm,
            editing = editing,
            fixed = null,
            conversations = conversations,
            onDismiss = { creating = false; editing = null },
        )
    }
    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            text = { Text(stringResource(R.string.chat_pm_delete_q)) },
            confirmButton = { TextButton(onClick = { deleting = null; vm.delete(p.id) }) { Text(stringResource(R.string.chat_pm_delete)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.chat_cancel)) } },
        )
    }
}

/** „Standort: Immer erlauben. Ortsnachrichten laufen auf diesem Handy (…)“ — or the yellow hint without „Immer“. */
@Composable
private fun DeviceNote(readiness: FamiliePermissions.GeofenceReadiness, device: String, onSettings: () -> Unit) {
    if (readiness == FamiliePermissions.GeofenceReadiness.READY) {
        GcBanner(tone = GcTone.Ok, icon = Icons.Outlined.WhereToVote) {
            Text(stringResource(R.string.chat_pm_ready, device), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        }
        return
    }
    GcBanner(tone = GcTone.Warn, icon = Icons.Outlined.Info, modifier = Modifier.testTag("place-messages-permission")) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(
                    when (readiness) {
                        FamiliePermissions.GeofenceReadiness.UNAVAILABLE -> R.string.chat_pm_unavailable
                        FamiliePermissions.GeofenceReadiness.NEED_LOCATION -> R.string.chat_pm_need_location
                        else -> R.string.chat_pm_need_background
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            if (readiness != FamiliePermissions.GeofenceReadiness.UNAVAILABLE) {
                TextButton(onClick = onSettings) { Text(stringResource(R.string.chat_pm_open_settings)) }
            }
        }
    }
}

@Composable
private fun PlaceMessageCard(
    p: ChatPlaceMessage,
    here: Boolean,
    dropped: Long?,
    busy: Boolean,
    onToggle: (Boolean) -> Unit,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    val ctx = LocalContext.current
    val extra = GateControlTheme.extraColors
    GcCard(contentPadding = PaddingValues(0.dp), verticalArrangement = Arrangement.spacedBy(0.dp), modifier = Modifier.testTag("place-message-${p.id}")) {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onOpen).padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(extra.accentBg), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.WhereToVote, contentDescription = null, tint = extra.accentText, modifier = Modifier.size(22.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(R.string.chat_pm_head, stringResource(if (p.trigger == ChatPlaceMessages.EXIT) R.string.chat_pm_exit_short else R.string.chat_pm_enter_short), p.place.name),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(p.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                p.target?.title?.let { Text(stringResource(R.string.chat_pm_to, it), style = MaterialTheme.typography.bodySmall, color = extra.muted, maxLines = 1) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    val shown = if (p.way == ChatPeople.REPLY_AUTO) p.target?.reply?.way else p.way
                    if (shown != null && p.way != ChatPeople.REPLY_AUTO) ChatNetLogo(shown, 14.dp)
                    val parts = buildList {
                        add(wayText(p.way))
                        add(stringResource(if (p.repeat == ChatPlaceMessages.ALWAYS) R.string.chat_pm_always else R.string.chat_pm_once))
                        p.window?.let { add(windowText(ctx, it)) }
                        when {
                            p.doneAt != null -> add(stringResource(R.string.chat_pm_sent_at, ChatText.listTime(ctx, p.lastFiredAt ?: p.doneAt) + " " + ChatText.clock(ctx, p.lastFiredAt ?: p.doneAt)))
                            dropped != null && (ChatPlaceMessages.ms(p.lastFiredAt) ?: 0L) < dropped -> add(stringResource(R.string.chat_pm_dropped))
                            else -> add(stringResource(if (p.ask) R.string.chat_pm_asks else R.string.chat_pm_sends))
                        }
                        if (!here) add(stringResource(R.string.chat_pm_other_device))
                    }
                    Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = extra.muted)
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Switch(checked = p.active, onCheckedChange = onToggle, enabled = !busy)
                GcIconButton(Icons.Outlined.DeleteOutline, stringResource(R.string.chat_pm_delete), onClick = onDelete, tint = extra.muted)
            }
        }
    }
}

// ─── the form ───────────────────────────────────────────────────────

/** A chat an Ortsnachricht may go to: one you may write in (no channel, no chat only the admins write in). */
internal fun placeTargetOk(c: ChatConversation): Boolean =
    !c.readonly && c.can.send && !c.isNotice && ChatBridgeChat.readOnly(c) == null

/**
 * The form of an Ortsnachricht: Beim Ankommen / Beim Verlassen, the place (a Familie place or one on the map with its
 * radius), the text, to whom over which way, once or every time, the time window, „Vor dem Senden fragen“.
 * [fixed]: opened from a chat — the recipient is set.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlaceMessageEditor(
    vm: ChatPlaceMessagesViewModel,
    editing: ChatPlaceMessage?,
    fixed: ChatConversation?,
    conversations: List<ChatConversation>,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val extra = GateControlTheme.extraColors
    val placesState by vm.places.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.load() }
    PlaceNotices(vm)
    val familie = placesState.orEmpty().mapNotNull { ChatPlaceMessages.fromFamilie(it) }
    var d by remember(editing?.id) {
        mutableStateOf(editing?.let { ChatPlaceMessages.draftOf(it) } ?: ChatPmDraft(conversationId = fixed?.id))
    }
    var mapMode by remember(editing?.id) { mutableStateOf(editing != null && editing.place.familieId == null) }
    var mapName by remember(editing?.id) { mutableStateOf(editing?.place?.takeIf { it.familieId == null }?.name.orEmpty()) }
    var picking by remember { mutableStateOf(false) }
    var timeFor by remember { mutableStateOf<Boolean?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val target = fixed ?: conversations.firstOrNull { it.id == d.conversationId }
    val person = target?.person?.takeIf { it.conversationId == target.id }
    // without Familie places only the map
    val showMap = mapMode || familie.isEmpty()
    val place = d.place?.let { if (showMap) it.copy(name = mapName.trim().take(ChatPlaceMessages.NAME_MAX), familieId = null) else it }
    val ready = place != null && place.name.isNotBlank() && d.text.isNotBlank() && d.conversationId != null && ChatPlaceMessages.windowOk(d.window)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // a full-screen dialog draws edge to edge (decorFitsSystemWindows = false) and is outside the app's Scaffold:
        // keep the bar below the status bar, the form above the navigation bar and the keyboard
        Column(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                .statusBarsPadding().navigationBarsPadding().imePadding().testTag("place-message-editor"),
        ) {
            GcSubpageBar(title = stringResource(R.string.chat_pm_add), onBack = onDismiss, backDescription = stringResource(R.string.chat_pm_close)) {
                TextButton(onClick = { vm.save(editing?.id, d.copy(place = place), onDismiss) }, enabled = ready && !busy) { Text(stringResource(R.string.chat_pm_save)) }
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GcFilterChip(stringResource(R.string.chat_pm_enter), d.trigger == ChatPlaceMessages.ENTER, { d = d.copy(trigger = ChatPlaceMessages.ENTER) })
                    GcFilterChip(stringResource(R.string.chat_pm_exit), d.trigger == ChatPlaceMessages.EXIT, { d = d.copy(trigger = ChatPlaceMessages.EXIT) })
                }

                GcSectionLabel(stringResource(R.string.chat_pm_place))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    familie.forEach { f ->
                        GcFilterChip(f.name, !showMap && d.place?.familieId == f.familieId, {
                            mapMode = false
                            d = d.copy(place = f)
                        })
                    }
                    GcFilterChip(stringResource(R.string.chat_pm_other_place), showMap, {
                        mapMode = true
                        if (d.place?.familieId != null) d = d.copy(place = null)
                    })
                }
                if (showMap) {
                    Text(stringResource(R.string.chat_pm_map_hint), style = MaterialTheme.typography.bodySmall, color = extra.muted)
                    val p = d.place
                    OsmMap(
                        point = p?.let { LatLon(it.lat, it.lon) },
                        contentDescription = stringResource(R.string.chat_pm_map),
                        modifier = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(16.dp)),
                        zoom = if (p != null) 15.0 else 6.0,
                        radiusM = p?.radiusM?.toDouble(),
                        onTap = { pt -> d = d.copy(place = ChatPmPlace(null, mapName, pt.lat, pt.lon, d.place?.radiusM ?: ChatPlaceMessages.RADIUS_DEFAULT_M)) },
                    )
                    GcTextField(mapName, { mapName = it.take(ChatPlaceMessages.NAME_MAX) }, stringResource(R.string.chat_pm_place_name))
                } else {
                    Text(stringResource(R.string.chat_pm_familie_place), style = MaterialTheme.typography.bodySmall, color = extra.muted)
                }
                d.place?.let { p ->
                    Text(stringResource(R.string.chat_pm_radius, p.radiusM), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                    Slider(
                        value = p.radiusM.toFloat(),
                        onValueChange = { v -> d = d.copy(place = p.copy(radiusM = ChatPlaceMessages.clampRadius((v / 50f).roundToInt() * 50))) },
                        valueRange = ChatPlaceMessages.RADIUS_MIN_M.toFloat()..ChatPlaceMessages.RADIUS_MAX_M.toFloat(),
                        steps = (ChatPlaceMessages.RADIUS_MAX_M - ChatPlaceMessages.RADIUS_MIN_M) / 50 - 1,
                    )
                }

                GcTextField(
                    value = d.text,
                    onValueChange = { d = d.copy(text = ChatPlaceMessages.clipText(it)) },
                    label = stringResource(R.string.chat_pm_text),
                    placeholder = stringResource(R.string.chat_pm_text_hint),
                    singleLine = false,
                )

                GcSectionLabel(stringResource(R.string.chat_pm_recipient))
                GcCard(contentPadding = PaddingValues(0.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    GcListRow(
                        title = target?.let { ChatText.title(ctx, it) } ?: stringResource(R.string.chat_pm_pick_chat),
                        description = target?.let { t ->
                            val now = person?.reply?.label ?: editing?.target?.reply?.label
                            if (d.way == ChatPeople.REPLY_AUTO) {
                                stringResource(R.string.chat_pm_via, if (now != null) stringResource(R.string.chat_pm_way_auto_now, now) else stringResource(R.string.chat_pm_way_auto))
                            } else {
                                stringResource(R.string.chat_pm_via, ChatPeople.labelOf(d.way))
                            }.takeIf { t.id == d.conversationId }
                        },
                        onClick = if (fixed == null && editing == null) ({ picking = true }) else null,
                        leading = target?.let { t -> { if (t.person != null) PersonAvatar(t, 40.dp) else ConversationAvatar(t, 40.dp) } },
                        trailing = person?.let { p -> { PersonWayLogos(p, 16.dp) } },
                    )
                    // the ways of a person: Automatisch · GateControl · WhatsApp
                    if (person != null && person.kinds.size > 1) {
                        PlaceWayPicker(
                            ways = person.kinds,
                            selected = d.way,
                            onPick = { d = d.copy(way = it) },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }

                GcSectionLabel(stringResource(R.string.chat_pm_how_often))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GcFilterChip(stringResource(R.string.chat_pm_once_cap), d.repeat == ChatPlaceMessages.ONCE, { d = d.copy(repeat = ChatPlaceMessages.ONCE) })
                    GcFilterChip(stringResource(R.string.chat_pm_always_cap), d.repeat == ChatPlaceMessages.ALWAYS, { d = d.copy(repeat = ChatPlaceMessages.ALWAYS) })
                }

                GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    GcSwitchRow(
                        stringResource(R.string.chat_pm_window_title),
                        d.window?.let { windowText(ctx, it) } ?: stringResource(R.string.chat_pm_window_any),
                        d.window != null,
                        { on -> d = d.copy(window = if (on) ChatPmWindow(listOf(1, 2, 3, 4, 5), "16:00", "20:00") else null) },
                    )
                    d.window?.let { w ->
                        FlowRow(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            (1..7).forEach { day ->
                                GcFilterChip(stringResource(DAY_RES[day - 1]), day in w.days, {
                                    val days = if (day in w.days) w.days - day else (w.days + day).sorted()
                                    if (days.isNotEmpty()) d = d.copy(window = w.copy(days = days))
                                })
                            }
                        }
                        Row(Modifier.padding(horizontal = 8.dp)) {
                            TextButton(onClick = { timeFor = true }) { Text(stringResource(R.string.chat_pm_window_from, w.from)) }
                            TextButton(onClick = { timeFor = false }) { Text(stringResource(R.string.chat_pm_window_to, w.to)) }
                        }
                        if (w.from == w.to) {
                            Text(
                                stringResource(R.string.chat_pm_window_same),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 16.dp).testTag("place-window-same"),
                            )
                        }
                    }
                    GcSwitchRow(stringResource(R.string.chat_pm_ask_title), stringResource(R.string.chat_pm_ask_desc), d.ask, { on -> d = d.copy(ask = on) })
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.Info, contentDescription = null, tint = extra.muted, modifier = Modifier.padding(top = 2.dp).size(16.dp))
                    Text(stringResource(R.string.chat_pm_info), style = MaterialTheme.typography.bodySmall, color = extra.muted)
                }
                GcPrimaryButton(
                    text = stringResource(R.string.chat_pm_save),
                    onClick = { vm.save(editing?.id, d.copy(place = place), onDismiss) },
                    enabled = ready && !busy,
                    loading = busy,
                )
                if (editing != null) {
                    TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.chat_pm_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(Modifier.heightIn(min = 24.dp))
            }
        }
    }
    if (picking) {
        val candidates = conversations.filter(::placeTargetOk).sortedByDescending { it.activityAt }
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text(stringResource(R.string.chat_pm_pick_chat)) },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(candidates, key = { it.id }) { c ->
                        GcListRow(
                            title = ChatText.title(ctx, c),
                            onClick = {
                                picking = false
                                d = d.copy(conversationId = c.id, way = ChatPeople.REPLY_AUTO)
                            },
                            leading = { if (c.person != null) PersonAvatar(c, 36.dp) else ConversationAvatar(c, 36.dp) },
                            trailing = c.person?.let { p -> { PersonWayLogos(p, 14.dp) } } ?: c.bridge?.let { b -> { ChatNetLogo(b.network, 14.dp) } },
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.chat_cancel)) } },
        )
    }
    timeFor?.let { from ->
        val w = d.window
        if (w != null) {
            NTimePicker(
                initial = if (from) w.from else w.to,
                onPick = { t ->
                    timeFor = null
                    if (t != null) d = d.copy(window = if (from) w.copy(from = t) else w.copy(to = t))
                },
                onDismiss = { timeFor = null },
            )
        } else {
            timeFor = null
        }
    }
    if (confirmDelete && editing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            text = { Text(stringResource(R.string.chat_pm_delete_q)) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete(editing.id, onDismiss) }) { Text(stringResource(R.string.chat_pm_delete)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.chat_cancel)) } },
        )
    }
}

/**
 * „An“ of a person with several ways: Automatisch · GateControl · WhatsApp … as icon buttons of the same width (no text,
 * so nothing breaks in a narrow row): a wand for Automatisch, the shield of GateControl, the logo of each network. Each
 * says its name to TalkBack and in a tooltip (long press); the chosen one is filled as the app's chips are.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaceWayPicker(ways: List<ChatPersonWay>, selected: String, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    val extra = GateControlTheme.extraColors
    val options = listOf(ChatPeople.REPLY_AUTO to stringResource(R.string.chat_pm_way_auto)) + ways.map { it.way to it.label }
    Row(modifier.fillMaxWidth().selectableGroup().testTag("place-way"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (way, label) ->
            val on = way == selected
            // the weight on a Box of its own: TooltipBox does not pass a RowScope weight on to its anchor
            Box(Modifier.weight(1f)) {
                TooltipBox(
                    positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                    tooltip = { PlainTooltip { Text(label) } },
                    state = rememberTooltipState(),
                ) {
                    Box(
                        Modifier.fillMaxWidth().height(44.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (on) extra.accentBg else Color.Transparent)
                            .border(1.dp, if (on) Color.Transparent else extra.border2, RoundedCornerShape(10.dp))
                            .selectable(selected = on, role = Role.RadioButton, onClick = { onPick(way) })
                            .semantics { contentDescription = label }
                            .testTag("place-way-$way"),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (way == ChatPeople.REPLY_AUTO) {
                            Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = if (on) extra.accentText else extra.muted, modifier = Modifier.size(22.dp))
                        } else {
                            ChatNetLogo(way, 24.dp)
                        }
                    }
                }
            }
        }
    }
}
