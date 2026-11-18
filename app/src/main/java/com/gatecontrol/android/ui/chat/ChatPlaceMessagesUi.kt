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
import com.gatecontrol.android.chat.ChatPmDiag
import com.gatecontrol.android.chat.ChatPmDraft
import com.gatecontrol.android.chat.ChatPmEvent
import com.gatecontrol.android.chat.ChatPmHealth
import com.gatecontrol.android.chat.ChatPlaceWatch
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
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
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
    @ApplicationContext private val appContext: Context,
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

    private val _diag = MutableStateFlow(ChatPmDiag(readiness = _readiness.value))
    /** The phone's state for the status lines: the permission, the registered places, the last events. */
    val diag: StateFlow<ChatPmDiag> = _diag

    private val _locating = MutableStateFlow(false)
    val locating: StateFlow<Boolean> = _locating

    init {
        viewModelScope.launch { combine(repo.diagTick, repo.list) { _, _ -> }.collect { refreshDiag() } }
    }

    private suspend fun refreshDiag() {
        _diag.value = withContext(Dispatchers.IO) { runCatching { geofences.diag() }.getOrNull() } ?: return
    }

    /** „Aktuellen Standort übernehmen“: the phone's position now, or null (no permission, no fix). */
    fun locateHere(onFix: (Double, Double) -> Unit) {
        if (_locating.value) return
        _locating.value = true
        viewModelScope.launch {
            try {
                val r = com.gatecontrol.android.location.DeviceLocator.locate(appContext)
                val loc = (r as? com.gatecontrol.android.location.LocateResult.Fix)?.location
                if (loc != null) onFix(loc.latitude, loc.longitude) else noticeCh.trySend(PlaceNotice.Done(R.string.chat_pm_no_fix))
            } finally {
                _locating.value = false
            }
        }
    }

    /** The list, the Familie places, the permission (again after a return from the Android settings). */
    fun load() {
        _readiness.value = geofences.readiness()
        viewModelScope.launch { refreshDiag() }
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
                        // the notice first: [onOk] may close the form whose notices show it
                        done?.let { noticeCh.trySend(PlaceNotice.Done(it)) }
                        onOk()
                    }
                    is ChatResult.Failed -> noticeCh.trySend(PlaceNotice.Failed(r.code, r.message))
                    else -> noticeCh.trySend(PlaceNotice.Failed("offline"))
                }
            } finally {
                _busy.value = false
            }
        }
    }

    /** [quiet]: no toast „Ortsnachricht gespeichert.“ — the caller says it itself (the chat: with „Anzeigen“). */
    fun save(editingId: Long?, d: ChatPmDraft, quiet: Boolean = false, onDone: () -> Unit) =
        run({ if (editingId == null) repo.create(d) else repo.update(editingId, d) }, if (quiet) null else R.string.chat_pm_saved, onDone)

    /** Only the list (the chat info's section). */
    fun refresh() {
        viewModelScope.launch { refreshDiag() }
        viewModelScope.launch { repo.refresh() }
    }

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

/** The status of an Ortsnachricht in one line: „Wird überwacht“, „Nicht aktiv: Standort „Immer“ fehlt“ … */
internal fun placeHealthText(ctx: Context, h: ChatPmHealth): String = ctx.getString(
    when (h) {
        ChatPmHealth.WATCHING -> R.string.chat_pm_st_watching
        ChatPmHealth.OFF -> R.string.chat_pm_st_off
        ChatPmHealth.OTHER_DEVICE -> R.string.chat_pm_st_other_device
        ChatPmHealth.NO_POINT -> R.string.chat_pm_st_no_point
        ChatPmHealth.NO_PLAY_SERVICES -> R.string.chat_pm_st_no_play
        ChatPmHealth.NEED_LOCATION -> R.string.chat_pm_st_need_location
        ChatPmHealth.NEED_BACKGROUND -> R.string.chat_pm_st_need_background
        ChatPmHealth.LOCATION_OFF -> R.string.chat_pm_st_location_off
        ChatPmHealth.NOTIFICATIONS_OFF -> R.string.chat_pm_st_notifications_off
        ChatPmHealth.NOT_REGISTERED -> R.string.chat_pm_st_not_registered
    },
)

private fun skipText(ctx: Context, why: String?): String = ctx.getString(
    when (why) {
        ChatPlaceWatch.WHY_WINDOW -> R.string.chat_pm_why_window
        ChatPlaceWatch.WHY_TOO_SOON -> R.string.chat_pm_why_too_soon
        ChatPlaceWatch.WHY_INACTIVE -> R.string.chat_pm_why_inactive
        ChatPlaceWatch.WHY_OTHER_DEVICE -> R.string.chat_pm_why_other_device
        ChatPlaceWatch.WHY_ASK_EXPIRED -> R.string.chat_pm_why_ask_expired
        else -> R.string.chat_pm_why_unknown
    },
)

/** „Zuletzt erkannt: Ankunft 07:42, außerhalb der Zeiten“, „Zuletzt ausgelöst: 18.11. 07:42“ … — or null (nothing yet). */
internal fun placeLastText(ctx: Context, p: ChatPlaceMessage, log: List<ChatPmEvent>): String? {
    val e = ChatPlaceWatch.lastEvent(p, log) ?: return null
    val iso = java.time.Instant.ofEpochMilli(e.atMs).toString()
    val time = ChatText.listTime(ctx, iso).let { day -> val c = ChatText.clock(ctx, iso); if (day == c) c else "$day $c" }
    val what = ctx.getString(if (p.trigger == ChatPlaceMessages.EXIT) R.string.chat_pm_departure else R.string.chat_pm_arrival)
    return when (e.kind) {
        ChatPlaceWatch.SENT -> ctx.getString(R.string.chat_pm_last_sent, time)
        ChatPlaceWatch.ASKED -> ctx.getString(R.string.chat_pm_last_asked, time)
        ChatPlaceWatch.DETECTED -> ctx.getString(R.string.chat_pm_last_detected, what, time)
        ChatPlaceWatch.SKIPPED -> ctx.getString(R.string.chat_pm_last_skipped, what, time, skipText(ctx, e.detail))
        ChatPlaceWatch.FAILED -> ctx.getString(R.string.chat_pm_last_failed, time, e.detail ?: "?")
        ChatPlaceWatch.DROPPED -> ctx.getString(R.string.chat_pm_last_dropped, time)
        ChatPlaceWatch.BLOCKED -> ctx.getString(R.string.chat_pm_last_blocked, what, time)
        ChatPlaceWatch.UNAVAILABLE -> ctx.getString(R.string.chat_pm_last_unavailable, time)
        ChatPlaceWatch.CAUGHT_UP -> ctx.getString(R.string.chat_pm_last_caught_up, time)
        ChatPlaceWatch.DECLINED -> ctx.getString(R.string.chat_pm_last_declined, time)
        else -> null
    }
}

/** The status and the last event of [p], one line each („Wird überwacht“ / „Zuletzt …“). */
internal fun placeStatusLines(ctx: Context, p: ChatPlaceMessage, deviceId: String, diag: ChatPmDiag): List<String> =
    listOfNotNull(placeHealthText(ctx, ChatPlaceWatch.health(p, deviceId, diag)), placeLastText(ctx, p, diag.log[p.id].orEmpty()))

/** The status of an Ortsnachricht: a dot in the tone (watched: the accent, else the warning), the lines next to it. */
@Composable
internal fun PlaceStatus(p: ChatPlaceMessage, deviceId: String, diag: ChatPmDiag, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val extra = GateControlTheme.extraColors
    val h = ChatPlaceWatch.health(p, deviceId, diag)
    val color = when (h) {
        ChatPmHealth.WATCHING -> extra.accentText
        ChatPmHealth.OFF, ChatPmHealth.OTHER_DEVICE -> extra.muted
        else -> extra.warn
    }
    Column(modifier.testTag("place-status-${p.id}"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(color))
            Text(placeHealthText(ctx, h), style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = color)
        }
        placeLastText(ctx, p, diag.log[p.id].orEmpty())?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = extra.muted, modifier = Modifier.padding(start = 14.dp))
        }
    }
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
    val diag by vm.diag.collectAsStateWithLifecycle()
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
                        status = { PlaceStatus(p, vm.deviceId, diag, Modifier.padding(top = 4.dp)) },
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
    status: @Composable () -> Unit = {},
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
                status()
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
 * [fixed]: opened from a chat — the recipient is set. [onSaved]: after „Speichern“ the caller says so itself (the chat:
 * a snackbar with „Anzeigen“), else the toast „Ortsnachricht gespeichert.“. One being changed has a bin at the top.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlaceMessageEditor(
    vm: ChatPlaceMessagesViewModel,
    editing: ChatPlaceMessage?,
    fixed: ChatConversation?,
    conversations: List<ChatConversation>,
    onDismiss: () -> Unit,
    onSaved: (() -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val extra = GateControlTheme.extraColors
    val placesState by vm.places.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val diag by vm.diag.collectAsStateWithLifecycle()
    val readiness by vm.readiness.collectAsStateWithLifecycle()
    val locating by vm.locating.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.load() }
    PlaceNotices(vm)
    val familie = placesState.orEmpty().mapNotNull { ChatPlaceMessages.fromFamilie(it) }
    // Familie places without a position (an address only): shown, and picked on the map — never dropped silently
    val familieNoPoint = ChatPlaceMessages.familieWithoutPoint(placesState.orEmpty())
    var d by remember(editing?.id) {
        mutableStateOf(editing?.let { ChatPlaceMessages.draftOf(it) } ?: ChatPmDraft(conversationId = fixed?.id))
    }
    // one saved without a point (0/0) is fixed on the map
    val onMap = editing != null && (editing.place.familieId == null || !ChatPlaceMessages.hasPoint(editing.place))
    var mapMode by remember(editing?.id) { mutableStateOf(onMap) }
    var mapName by remember(editing?.id) { mutableStateOf(if (onMap) editing.place.name else "") }
    var noPointName by remember(editing?.id) { mutableStateOf(editing?.place?.takeIf { !ChatPlaceMessages.hasPoint(it) }?.name) }
    var picking by remember { mutableStateOf(false) }
    var timeFor by remember { mutableStateOf<Boolean?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val target = fixed ?: conversations.firstOrNull { it.id == d.conversationId }
    val person = target?.person?.takeIf { it.conversationId == target.id }
    // without Familie places only the map
    val showMap = mapMode || familie.isEmpty()
    val place = d.place?.let { if (showMap) it.copy(name = mapName.trim().take(ChatPlaceMessages.NAME_MAX), familieId = null) else it }
    val ready = place != null && ChatPlaceMessages.hasPoint(place) && place.name.isNotBlank() && d.text.isNotBlank() && d.conversationId != null && ChatPlaceMessages.windowOk(d.window)
    val save = { vm.save(editing?.id, d.copy(place = place), quiet = onSaved != null) { onSaved?.invoke(); onDismiss() } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // a full-screen dialog draws edge to edge (decorFitsSystemWindows = false) and is outside the app's Scaffold:
        // keep the bar below the status bar, the form above the navigation bar and the keyboard
        Column(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                .statusBarsPadding().navigationBarsPadding().imePadding().testTag("place-message-editor"),
        ) {
            GcSubpageBar(title = stringResource(R.string.chat_pm_add), onBack = onDismiss, backDescription = stringResource(R.string.chat_pm_close)) {
                if (editing != null) {
                    GcIconButton(
                        Icons.Outlined.DeleteOutline,
                        stringResource(R.string.chat_pm_delete),
                        onClick = { confirmDelete = true },
                        modifier = Modifier.testTag("place-message-editor-delete"),
                        tint = MaterialTheme.colorScheme.error,
                        enabled = !busy,
                    )
                }
                TextButton(onClick = save, enabled = ready && !busy) { Text(stringResource(R.string.chat_pm_save)) }
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // is it armed? the status of the one being changed; for a new one what is missing on this phone
                if (editing != null) {
                    val h = ChatPlaceWatch.health(editing, vm.deviceId, diag)
                    GcBanner(
                        tone = if (h == ChatPmHealth.WATCHING) GcTone.Ok else GcTone.Warn,
                        icon = if (h == ChatPmHealth.WATCHING) Icons.Outlined.WhereToVote else Icons.Outlined.Info,
                        modifier = Modifier.testTag("place-message-editor-status"),
                    ) {
                        Column(Modifier.weight(1f)) {
                            placeStatusLines(ctx, editing, vm.deviceId, diag).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                            if (h in SETTINGS_FIXES) TextButton(onClick = { openAppSettings(ctx) }) { Text(stringResource(R.string.chat_pm_open_settings_any)) }
                        }
                    }
                } else if (readiness != FamiliePermissions.GeofenceReadiness.READY) {
                    DeviceNote(readiness, vm.deviceName) { openAppSettings(ctx) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GcFilterChip(stringResource(R.string.chat_pm_enter), d.trigger == ChatPlaceMessages.ENTER, { d = d.copy(trigger = ChatPlaceMessages.ENTER) })
                    GcFilterChip(stringResource(R.string.chat_pm_exit), d.trigger == ChatPlaceMessages.EXIT, { d = d.copy(trigger = ChatPlaceMessages.EXIT) })
                }

                GcSectionLabel(stringResource(R.string.chat_pm_place))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    familie.forEach { f ->
                        GcFilterChip(f.name, !showMap && d.place?.familieId == f.familieId, {
                            mapMode = false
                            noPointName = null
                            d = d.copy(place = f)
                        })
                    }
                    familieNoPoint.forEach { f ->
                        GcFilterChip(f.name, showMap && noPointName == f.name, {
                            // no position for it: the map with its name, the point is picked there
                            mapMode = true
                            noPointName = f.name
                            mapName = f.name.take(ChatPlaceMessages.NAME_MAX)
                            d = d.copy(place = null)
                        })
                    }
                    GcFilterChip(stringResource(R.string.chat_pm_other_place), showMap && noPointName == null, {
                        mapMode = true
                        noPointName = null
                        if (d.place?.familieId != null) d = d.copy(place = null)
                    })
                }
                noPointName?.let { n ->
                    GcBanner(tone = GcTone.Warn, icon = Icons.Outlined.Info, modifier = Modifier.testTag("place-no-point")) {
                        Text(stringResource(R.string.chat_pm_no_point, n), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    }
                }
                if (showMap) {
                    Text(stringResource(R.string.chat_pm_map_hint), style = MaterialTheme.typography.bodySmall, color = extra.muted)
                    val p = d.place?.takeIf { ChatPlaceMessages.hasPoint(it) }
                    OsmMap(
                        point = p?.let { LatLon(it.lat, it.lon) },
                        contentDescription = stringResource(R.string.chat_pm_map),
                        modifier = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(16.dp)),
                        zoom = if (p != null) 15.0 else 6.0,
                        radiusM = p?.radiusM?.toDouble(),
                        onTap = { pt -> d = d.copy(place = ChatPmPlace(null, mapName, pt.lat, pt.lon, d.place?.radiusM ?: ChatPlaceMessages.RADIUS_DEFAULT_M)) },
                    )
                    TextButton(
                        onClick = { vm.locateHere { lat, lon -> d = d.copy(place = ChatPmPlace(null, mapName, lat, lon, d.place?.radiusM ?: ChatPlaceMessages.RADIUS_DEFAULT_M)) } },
                        enabled = !locating,
                        modifier = Modifier.testTag("place-locate-here"),
                    ) { Text(stringResource(R.string.chat_pm_locate_here)) }
                    GcTextField(mapName, { mapName = it.take(ChatPlaceMessages.NAME_MAX) }, stringResource(R.string.chat_pm_place_name))
                } else {
                    Text(stringResource(R.string.chat_pm_familie_place), style = MaterialTheme.typography.bodySmall, color = extra.muted)
                    // where the Familie place is: the point and the circle the phone watches, to check it at a glance
                    d.place?.takeIf { it.familieId != null }?.let { fp ->
                        OsmMap(
                            point = LatLon(fp.lat, fp.lon),
                            contentDescription = stringResource(R.string.chat_pm_map_familie, fp.name),
                            modifier = Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(16.dp)),
                            zoom = 15.0,
                            radiusM = ChatPlaceWatch.fenceRadius(fp.radiusM).toDouble(),
                        )
                    }
                }
                d.place?.let { p ->
                    Text(stringResource(R.string.chat_pm_radius, p.radiusM), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                    if (p.radiusM < ChatPlaceWatch.FENCE_MIN_M) {
                        Text(stringResource(R.string.chat_pm_radius_min, ChatPlaceWatch.FENCE_MIN_M), style = MaterialTheme.typography.bodySmall, color = extra.muted)
                    }
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
                    onClick = save,
                    enabled = ready && !busy,
                    loading = busy,
                )
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

// ─── the chat info ──────────────────────────────────────────────────

/**
 * Chat-Info › „Ortsnachrichten“: the Ortsnachrichten of [conv] ([ChatPlaceMessages.forChat]), tap to change, delete
 * from the menu or with a swipe (both ask first), and „Alle Ortsnachrichten“. Nothing while the chat has none.
 */
@Composable
fun ChatInfoPlaceMessagesSection(
    conv: ChatConversation,
    onOpenAll: () -> Unit,
    vm: ChatPlaceMessagesViewModel = hiltViewModel(key = "place-messages-info-${conv.id}"),
) {
    val list by vm.list.collectAsStateWithLifecycle()
    val conversations by vm.conversations.collectAsStateWithLifecycle()
    val diag by vm.diag.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<ChatPlaceMessage?>(null) }
    LaunchedEffect(vm) { vm.refresh() }
    PlaceNotices(vm)
    val ctx = LocalContext.current
    ChatInfoPlaceMessages(
        items = ChatPlaceMessages.forChat(list?.items.orEmpty(), conv),
        status = { p -> placeStatusLines(ctx, p, vm.deviceId, diag) },
        onEdit = { editing = it },
        onDelete = { vm.delete(it.id) },
        onOpenAll = onOpenAll,
    )
    editing?.let { p ->
        // the list may have changed meanwhile (deleted on another phone): the form closes
        val now = list?.items?.firstOrNull { it.id == p.id }
        if (now == null && list != null) {
            editing = null
        } else {
            PlaceMessageEditor(vm = vm, editing = now ?: p, fixed = null, conversations = conversations, onDismiss = { editing = null })
        }
    }
}

/** The rows of [ChatInfoPlaceMessagesSection] (no view model: drawn as they are in the tests). */
@Composable
internal fun ChatInfoPlaceMessages(
    items: List<ChatPlaceMessage>,
    status: (ChatPlaceMessage) -> List<String> = { emptyList() },
    onEdit: (ChatPlaceMessage) -> Unit,
    onDelete: (ChatPlaceMessage) -> Unit,
    onOpenAll: () -> Unit,
) {
    if (items.isEmpty()) return
    val extra = GateControlTheme.extraColors
    var deleting by remember { mutableStateOf<ChatPlaceMessage?>(null) }
    val deleteLabel = stringResource(R.string.chat_pm_delete)
    val swipe = com.gatecontrol.android.ui.mail.SwipeSpec(SWIPE_DELETE, deleteLabel, Icons.Outlined.DeleteOutline, MaterialTheme.colorScheme.error)
    Column(Modifier.fillMaxWidth().testTag("chat-info-place-messages")) {
        GcSectionLabel(stringResource(R.string.chat_pm_title), Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
        items.forEach { p ->
            var menu by remember(p.id) { mutableStateOf(false) }
            com.gatecontrol.android.ui.mail.SwipeRow(left = swipe, right = null, onSwipe = { deleting = p }, modifier = Modifier.testTag("chat-info-pm-${p.id}")) {
                GcListRow(
                    title = stringResource(
                        R.string.chat_pm_head,
                        stringResource(if (p.trigger == ChatPlaceMessages.EXIT) R.string.chat_pm_exit_short else R.string.chat_pm_enter_short),
                        p.place.name,
                    ),
                    description = (listOf(ChatPlaceMessages.preview(p.text)) + status(p)).joinToString("\n"),
                    onClick = { onEdit(p) },
                    titleColor = if (p.active) MaterialTheme.colorScheme.onSurface else extra.muted,
                    leading = {
                        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(extra.accentBg), contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.WhereToVote, contentDescription = null, tint = extra.accentText, modifier = Modifier.size(22.dp))
                        }
                    },
                    trailing = {
                        Box {
                            GcIconButton(
                                com.gatecontrol.android.ui.components.GcIcons.MoreVertical,
                                stringResource(R.string.chat_pm_more),
                                onClick = { menu = true },
                                modifier = Modifier.testTag("chat-info-pm-menu-${p.id}"),
                            )
                            androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(stringResource(R.string.chat_edit)) },
                                    onClick = { menu = false; onEdit(p) },
                                )
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(deleteLabel, color = MaterialTheme.colorScheme.error) },
                                    onClick = { menu = false; deleting = p },
                                )
                            }
                        }
                    },
                )
            }
        }
        GcListRow(
            title = stringResource(R.string.chat_pm_all),
            onClick = onOpenAll,
            modifier = Modifier.testTag("chat-info-pm-all"),
            trailing = { com.gatecontrol.android.ui.components.GcChevron() },
        )
    }
    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            text = { Text(stringResource(R.string.chat_pm_delete_q)) },
            confirmButton = { TextButton(onClick = { deleting = null; onDelete(p) }) { Text(deleteLabel) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.chat_cancel)) } },
        )
    }
}

private const val SWIPE_DELETE = "delete"

/** The states a change in the Android settings of the app fixes (the location, the notifications). */
private val SETTINGS_FIXES = setOf(ChatPmHealth.NEED_LOCATION, ChatPmHealth.NEED_BACKGROUND, ChatPmHealth.NOTIFICATIONS_OFF)
