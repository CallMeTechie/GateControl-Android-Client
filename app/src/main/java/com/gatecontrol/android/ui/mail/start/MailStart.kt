package com.gatecontrol.android.ui.mail.start

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.R
import com.gatecontrol.android.layout.StartArrangement
import com.gatecontrol.android.mail.AgendaView
import com.gatecontrol.android.mail.CallStep
import com.gatecontrol.android.mail.MCalendars
import com.gatecontrol.android.mail.MFavorite
import com.gatecontrol.android.mail.MMe
import com.gatecontrol.android.mail.MUpcoming
import com.gatecontrol.android.mail.MUpcomingItem
import com.gatecontrol.android.mail.MailContactLogic
import com.gatecontrol.android.mail.MailLogic
import com.gatecontrol.android.mail.MailRepository
import com.gatecontrol.android.mail.MailStartPins
import com.gatecontrol.android.mail.StartContactPin
import com.gatecontrol.android.mail.TileTap
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.mail.calendar.calColor
import com.gatecontrol.android.ui.mail.calendar.clock
import com.gatecontrol.android.ui.mail.contacts.initialsColor
import com.gatecontrol.android.ui.mail.contacts.rememberCardPhoto
import com.gatecontrol.android.ui.theme.GateControlTheme
import com.gatecontrol.android.ui.update.currentLocale
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.ZoneId
import javax.inject.Inject

/** Where Mail's blocks on Start lead (given by the navigation; null: they show nothing). */
class MailStartNav(
    val contact: (Long) -> Unit,
    val event: (eventId: Long, occurrence: String?) -> Unit,
    /** The calendar (its page beside Start, else Mail's calendar). */
    val calendar: () -> Unit,
)

val LocalMailStartNav = staticCompositionLocalOf<MailStartNav?> { null }

/**
 * Mail's blocks on Start (Mail 1.4.0): favourites as tiles and the calendar widget, pinned on this device like the
 * plugins' buttons and widgets. Hidden while the server has no favourites / no such events (older than 1.4.0, the
 * function off).
 */
@HiltViewModel
class MailStartViewModel @Inject constructor(
    repository: MailRepository,
    private val arrangement: StartArrangement,
) : ViewModel() {
    private val pim = repository.pim
    val me: StateFlow<MMe?> = repository.me
    val pins: StateFlow<MailStartPins> = pim.startPins
    val favorites: StateFlow<List<MFavorite>?> = pim.favorites
    val favoritesSupported: StateFlow<Boolean?> = pim.favoritesSupported
    val upcoming: StateFlow<MUpcoming?> = pim.upcoming
    val upcomingSupported: StateFlow<Boolean?> = pim.upcomingSupported
    val calendars: StateFlow<MCalendars?> = pim.calendars

    fun refreshFavorites() = pim.refreshFavoritesSoon()

    fun refreshUpcoming() = pim.refreshUpcomingSoon()

    fun pinContact(f: MFavorite, on: Boolean, page: String? = null) {
        viewModelScope.launch { arrangement.pinContact(MailContactLogic.pinOf(f), on, page) }
    }

    fun pinAgenda(on: Boolean, page: String? = null) {
        viewModelScope.launch { arrangement.pinAgenda(on, page) }
    }
}

/** Whether Mail's calendar widget can be offered: the calendar is on and the server has its events (or not asked yet). */
internal fun agendaAvailable(me: MMe?, supported: Boolean?): Boolean = me?.has(MMe.FEATURE_CALENDAR) == true && supported != false

/** Whether favourites can be offered: the contacts are on and the server has favourites. */
internal fun favoritesAvailable(me: MMe?, supported: Boolean?): Boolean = me?.has(MMe.FEATURE_CONTACTS) == true && supported != false

// ─── favourite tiles ────────────────────────────────────────────────

/** Whether the person answered "don't ask again" to calling straight away (Android shows no question any more then). */
private object DirectCallPrefs {
    private const val PREFS = "mail_call"
    private const val KEY_DENIED = "denied_for_good"

    fun deniedForGood(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DENIED, false)

    fun setDeniedForGood(context: Context, on: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DENIED, false) != on) prefs.edit().putBoolean(KEY_DENIED, on).apply()
    }
}

/**
 * Starts the call of [number] with [action] (`ACTION_CALL` straight away, `ACTION_DIAL` the dialer). The number goes
 * in as the parts of a `tel:` uri (`#` and `*` escaped), never parsed from a string. Refused: the dialer instead.
 */
internal fun startCall(context: Context, number: String, action: String) {
    val intent = Intent(action, Uri.fromParts("tel", number, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // no phone app: nothing to call with
    } catch (_: SecurityException) {
        if (action != MailContactLogic.ACTION_DIAL) startCall(context, number, MailContactLogic.ACTION_DIAL)
    }
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/**
 * A favourite's call (the user wants a tap to call the main number straight away): with the permission to place calls
 * the call, without it once a short hint and Android's question — allowed: the call, denied: this time the dialer,
 * "don't ask again": from then on the dialer without asking. Service codes and devices without telephony: the dialer.
 */
@Composable
private fun rememberTileCall(): (String) -> Unit {
    val context = LocalContext.current
    var hint by rememberSaveable { mutableStateOf<String?>(null) }
    var asking by rememberSaveable { mutableStateOf<String?>(null) }
    var rationaleBefore by rememberSaveable { mutableStateOf(false) }
    fun rationale() = context.activity()?.let { ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.CALL_PHONE) } == true
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val number = asking ?: return@rememberLauncherForActivityResult
        asking = null
        if (MailContactLogic.deniedForGood(granted, rationaleBefore, rationale())) DirectCallPrefs.setDeniedForGood(context, true)
        startCall(context, number, MailContactLogic.callAction(number, granted))
    }
    hint?.let { number ->
        AlertDialog(
            onDismissRequest = { hint = null },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.extraLarge,
            title = { Text(stringResource(R.string.fav_call_perm_title), style = MaterialTheme.typography.titleLarge) },
            text = { Text(stringResource(R.string.fav_call_perm_text), style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = {
                    hint = null
                    rationaleBefore = rationale()
                    asking = number
                    launcher.launch(Manifest.permission.CALL_PHONE)
                }) { Text(stringResource(R.string.fav_call_perm_continue), fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = {
                    hint = null
                    startCall(context, number, MailContactLogic.ACTION_DIAL)
                }) { Text(stringResource(R.string.fav_call_perm_dial)) }
            },
        )
    }
    return { number ->
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        // Allowed later in Android's settings: a later withdrawal may ask again.
        if (granted) DirectCallPrefs.setDeniedForGood(context, false)
        val telephony = context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
        when (MailContactLogic.callStep(number, telephony, granted, DirectCallPrefs.deniedForGood(context))) {
            CallStep.CALL -> startCall(context, number, MailContactLogic.ACTION_CALL)
            CallStep.DIAL -> startCall(context, number, MailContactLogic.ACTION_DIAL)
            CallStep.ASK -> hint = number
        }
    }
}

private val TileShape = RoundedCornerShape(18.dp)

/**
 * A favourite on Start: the photo or the initials as the tile, the name below (like the plugins' buttons, [width]).
 * A tap calls the main number straight away (see [rememberTileCall]; without one it opens the contact), a long press
 * opens the contact.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ContactTile(pin: StartContactPin, width: Dp) {
    val nav = LocalMailStartNav.current
    val extra = GateControlTheme.extraColors
    val photo = rememberCardPhoto(pin.id.takeIf { pin.photoEtag != null }, pin.photoEtag)
    val tap = MailContactLogic.tap(pin)
    val call = rememberTileCall()
    Column(
        Modifier
            .width(width)
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                role = Role.Button,
                onClickLabel = stringResource(if (tap is TileTap.Call) R.string.con_call else R.string.fav_open),
                onLongClickLabel = stringResource(R.string.fav_open),
                onLongClick = { nav?.contact?.invoke(pin.id) },
                onClick = {
                    when (tap) {
                        is TileTap.Call -> call(tap.number)
                        is TileTap.Open -> nav?.contact?.invoke(tap.id)
                    }
                },
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.size(width).clip(TileShape).background(initialsColor(pin.name)).border(1.dp, extra.border2, TileShape),
            contentAlignment = Alignment.Center,
        ) {
            if (photo != null) {
                Image(photo, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(width))
            } else {
                Text(MailLogic.initials(pin.name.ifBlank { null }, "?"), color = Color(0xFF0D1015), fontWeight = FontWeight.Bold, fontSize = (width.value * 0.32f).sp)
            }
            if (tap is TileTap.Call) {
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(4.dp).size(18.dp).clip(RoundedCornerShape(99.dp)).background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(GcIcons.Phone, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(11.dp))
                }
            }
        }
        val labelStyle = MaterialTheme.typography.labelSmall
        BasicText(
            pin.name.ifBlank { "–" },
            style = labelStyle.copy(
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                hyphens = Hyphens.Auto,
                lineBreak = LineBreak.Paragraph,
                lineHeight = labelStyle.fontSize * 1.15f,
            ),
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis,
            autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = labelStyle.fontSize, stepSize = 0.5.sp),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** The favourites pinned to a page of Start that can be shown (the server has favourites). */
@Composable
fun rememberStartContacts(viewModel: MailStartViewModel = hiltViewModel()): Map<Long, StartContactPin> {
    val pins by viewModel.pins.collectAsStateWithLifecycle()
    val supported by viewModel.favoritesSupported.collectAsStateWithLifecycle()
    val me by viewModel.me.collectAsStateWithLifecycle()
    LaunchedEffect(pins.contacts.isNotEmpty()) { if (pins.contacts.isNotEmpty()) viewModel.refreshFavorites() }
    // Without Mail's contacts (switched off, an older server) the tiles are not shown; `me` unknown yet: the copy.
    if (supported == false || (me != null && me?.has(MMe.FEATURE_CONTACTS) != true)) return emptyMap()
    return pins.contacts.associateBy { it.id }
}

// ─── the calendar widget ────────────────────────────────────────────

/**
 * The calendar widget on Start (Mail 1.4.0): the next events of today and tomorrow — time, title, the calendar's
 * colour; a tap opens the event, a tap on the head the calendar, a long press offers taking it off Start. Asked for
 * again when Start shows, at midnight and with each change of the calendars (live); offline the last copy.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MailAgendaCard(modifier: Modifier = Modifier, page: String? = null, viewModel: MailStartViewModel = hiltViewModel()) {
    val upcoming by viewModel.upcoming.collectAsStateWithLifecycle()
    val supported by viewModel.upcomingSupported.collectAsStateWithLifecycle()
    val me by viewModel.me.collectAsStateWithLifecycle()
    val cals by viewModel.calendars.collectAsStateWithLifecycle()
    val nav = LocalMailStartNav.current
    val extra = GateControlTheme.extraColors
    val locale = currentLocale()
    val zone = remember { ZoneId.systemDefault() }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var menu by remember { mutableStateOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                now = System.currentTimeMillis()
                viewModel.refreshUpcoming()
                // again at midnight (a new day); in between each minute the ones over drop out
                val next = MailContactLogic.nextDay(now, zone)
                var t = System.currentTimeMillis()
                while (t < next) {
                    delay(minOf(60_000L, next - t + 1_000L))
                    t = System.currentTimeMillis()
                    now = t
                }
            }
        }
    }
    // Without Mail's calendar (switched off, an older server) the widget is not shown; `me` unknown yet: the copy.
    if (supported == false || (me != null && !agendaAvailable(me, supported))) return
    val view: AgendaView? = remember(upcoming, now) { upcoming?.let { MailContactLogic.agenda(it, now, zone) } }

    GcCard(modifier = modifier, contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.Top) {
        Box {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .combinedClickable(
                        role = Role.Button,
                        onClickLabel = stringResource(R.string.agenda_open_calendar),
                        onLongClickLabel = stringResource(R.string.agenda_menu),
                        onLongClick = { menu = true },
                        onClick = { nav?.calendar?.invoke() },
                    )
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(GcIcons.Calendar, contentDescription = null, tint = extra.accentText, modifier = Modifier.size(18.dp))
                Text(
                    stringResource(R.string.agenda_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                Icon(GcIcons.ChevronRight, contentDescription = null, tint = extra.muted, modifier = Modifier.size(18.dp))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.agenda_remove)) },
                    leadingIcon = { Icon(GcIcons.Trash, contentDescription = null, modifier = Modifier.size(20.dp)) },
                    onClick = { menu = false; viewModel.pinAgenda(false, page) },
                )
            }
        }
        when {
            view == null -> Text(
                stringResource(R.string.agenda_loading),
                style = MaterialTheme.typography.bodySmall,
                color = extra.muted,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            )
            view.empty -> Text(
                stringResource(R.string.agenda_none),
                style = MaterialTheme.typography.bodySmall,
                color = extra.muted,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            )
            else -> view.days.forEach { d ->
                Text(
                    stringResource(if (d.today) R.string.agenda_today else R.string.agenda_tomorrow),
                    style = MaterialTheme.typography.labelMedium,
                    color = extra.muted,
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 2.dp),
                )
                d.items.forEach { x ->
                    AgendaRow(x, cals, zone, locale) {
                        val e = x.eventId
                        if (e != null) nav?.event?.invoke(e, x.occurrence) else nav?.calendar?.invoke()
                    }
                }
            }
        }
        if (view != null && view.more > 0) {
            Text(
                pluralStringResource(R.plurals.agenda_more, view.more, view.more),
                style = MaterialTheme.typography.bodySmall,
                color = extra.accentText,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { nav?.calendar?.invoke() }.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun AgendaRow(x: MUpcomingItem, cals: MCalendars?, zone: ZoneId, locale: java.util.Locale, onClick: () -> Unit) {
    val extra = GateControlTheme.extraColors
    val color = calColor(x.color ?: x.calendarId?.let { cals?.calendar(it)?.color }, extra.accentText)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            if (x.allDay) stringResource(R.string.agenda_all_day) else clock(x.start, zone, locale),
            style = MaterialTheme.typography.bodySmall,
            color = extra.muted,
            maxLines = 1,
            modifier = Modifier.width(64.dp),
        )
        Box(Modifier.width(3.dp).height(28.dp).clip(RoundedCornerShape(2.dp)).background(if (x.tentative) color.copy(alpha = 0.45f) else color))
        Column(Modifier.weight(1f)) {
            Text(x.title.ifBlank { stringResource(R.string.agenda_no_title) }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            x.location?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = extra.muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

// ─── "Schnellaktionen und Widgets": what Mail can put on Start ──────

/**
 * Mail's part of the list of what can go on Start: the calendar widget and each favourite as a tile, each with a
 * switch (they join [page], a widget page beside Start, null: Start). Nothing while Mail offers neither.
 */
@Composable
fun MailStartChooser(page: String?, modifier: Modifier = Modifier, viewModel: MailStartViewModel = hiltViewModel()) {
    val me by viewModel.me.collectAsStateWithLifecycle()
    val pins by viewModel.pins.collectAsStateWithLifecycle()
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val favSupported by viewModel.favoritesSupported.collectAsStateWithLifecycle()
    val agendaSupported by viewModel.upcomingSupported.collectAsStateWithLifecycle()
    val extra = GateControlTheme.extraColors
    LaunchedEffect(Unit) {
        viewModel.refreshFavorites()
        viewModel.refreshUpcoming()
    }
    val agenda = agendaAvailable(me, agendaSupported) && agendaSupported == true
    val favs = favoritesAvailable(me, favSupported) && favSupported == true
    if (!agenda && !favs) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        com.gatecontrol.android.ui.components.GcSectionLabel(stringResource(R.string.mail_tab), Modifier.padding(start = 4.dp, top = 4.dp))
        GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.Top) {
            if (agenda) {
                com.gatecontrol.android.ui.components.GcSwitchRow(
                    label = stringResource(R.string.agenda_title),
                    description = stringResource(R.string.agenda_pin_desc),
                    checked = pins.agenda,
                    onCheckedChange = { viewModel.pinAgenda(it, page) },
                    icon = GcIcons.Calendar,
                )
            }
            if (favs) {
                val list = favorites.orEmpty()
                if (list.isEmpty()) {
                    Text(
                        stringResource(R.string.fav_none_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = extra.muted,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
                list.forEach { f ->
                    com.gatecontrol.android.ui.components.GcSwitchRow(
                        label = f.name.ifBlank { f.email ?: "–" },
                        description = f.phone?.value ?: stringResource(R.string.fav_no_phone),
                        checked = pins.has(f.id),
                        onCheckedChange = { viewModel.pinContact(f, it, page) },
                        icon = GcIcons.User,
                    )
                }
            }
        }
    }
}
