package com.gatecontrol.android.ui.mail

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.mail.MAccount
import com.gatecontrol.android.mail.MFolder
import com.gatecontrol.android.mail.MMe
import com.gatecontrol.android.mail.MSummary
import com.gatecontrol.android.mail.MUndo
import com.gatecontrol.android.mail.MailLogic
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.mail.calendar.CalendarMenuDialogs
import com.gatecontrol.android.ui.mail.calendar.CalendarMenuState
import com.gatecontrol.android.ui.mail.calendar.CalendarNav
import com.gatecontrol.android.ui.mail.calendar.MailCalendarContent
import com.gatecontrol.android.ui.mail.calendar.MailCalendarViewModel
import com.gatecontrol.android.ui.mail.calendar.calendarDrawerItems
import com.gatecontrol.android.ui.mail.contacts.ContactsNav
import com.gatecontrol.android.ui.mail.contacts.MailContactsContent
import com.gatecontrol.android.ui.mail.contacts.MailContactsViewModel
import com.gatecontrol.android.ui.mail.contacts.contactsDrawerItems
import com.gatecontrol.android.ui.theme.GateControlTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Where the Mail tab leads. */
data class MailNav(
    val open: (Long) -> Unit,
    /** "Neue Mail" (from this account, null: the box's or the first). */
    val compose: (Long?) -> Unit,
    /** A draft to write on (undo after sending, the outbox). */
    val draft: (Long) -> Unit,
    val search: () -> Unit,
    val filing: () -> Unit,
    /** "Konto hinzufügen" of the empty tab: the settings with "Konto hinzufügen" on top (the menu has no "+"). */
    val addAccount: () -> Unit,
    val settings: () -> Unit,
    val account: (Long) -> Unit,
    val outbox: () -> Unit,
    /** Calendar and contacts (since Mail 1.2.0): an occurrence, a new event, a contact, a group, the duplicates. */
    val event: (eventId: Long, occurrence: String?) -> Unit = { _, _ -> },
    val newEvent: (start: Long?, end: Long?, allDay: Boolean) -> Unit = { _, _, _ -> },
    val contact: (Long) -> Unit = {},
    val newContact: () -> Unit = {},
    val group: (Long) -> Unit = {},
    val duplicates: () -> Unit = {},
    /** "Neu anmelden" of a Microsoft account (calendar and contacts rights). */
    val relogin: (Long) -> Unit = {},
)

/**
 * The Mail tab (mockups "Posteingang" and "Seitenmenü"): the menu top left with "Alle Posteingänge", "Zum Ablegen"
 * and the accounts with their folders, the chips to switch between all inboxes and one account, Relevant /
 * Sonstige, the mails (swipes as the person set them), "Neue Mail" and the bar to undo a send.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MailHomeScreen(
    nav: MailNav,
    /** The area shown first: mail, or calendar / contacts when that page is pinned to Start. */
    initialArea: String = MailDrawerFoot.MAIL,
    viewModel: MailHomeViewModel = hiltViewModel(key = "mail-home-$initialArea"),
) {
    val me by viewModel.me.collectAsStateWithLifecycle()
    val meError by viewModel.meError.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val box by viewModel.box.collectAsStateWithLifecycle()
    val tab by viewModel.tab.collectAsStateWithLifecycle()
    val list by viewModel.list.collectAsStateWithLifecycle()
    val filing by viewModel.filing.collectAsStateWithLifecycle()
    val outbox by viewModel.outbox.collectAsStateWithLifecycle()
    val undo by viewModel.undo.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val reopen by viewModel.reopen.collectAsStateWithLifecycle()
    val folds by viewModel.folds.collectAsStateWithLifecycle()
    val context = mailContext()
    val scope = rememberCoroutineScope()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val extra = GateControlTheme.extraColors
    var actionsFor by remember { mutableStateOf<MSummary?>(null) }
    var moveFor by remember { mutableStateOf<MSummary?>(null) }
    // the areas of the tab (contract D2): mail, calendar, contacts — switched by the menu's foot
    var area by rememberSaveable(initialArea) { mutableStateOf(initialArea) }
    var calendarUsed by rememberSaveable { mutableStateOf(initialArea == MailDrawerFoot.CALENDAR) }
    var contactsUsed by rememberSaveable { mutableStateOf(initialArea == MailDrawerFoot.CONTACTS) }
    if (area == MailDrawerFoot.CALENDAR) calendarUsed = true
    if (area == MailDrawerFoot.CONTACTS) contactsUsed = true
    // the view models of an area only once it was opened (their first calls need the feature)
    val calendarVm: MailCalendarViewModel? = if (calendarUsed) hiltViewModel(key = "mail-cal-$initialArea") else null
    val contactsVm: MailContactsViewModel? = if (contactsUsed) hiltViewModel(key = "mail-con-$initialArea") else null
    val calendarMenu = remember { CalendarMenuState() }
    // an area the server no longer offers: back to the mail
    LaunchedEffect(me, area) {
        val m = me ?: return@LaunchedEffect
        if ((area == MailDrawerFoot.CALENDAR && !MailDrawerFoot.calendar(m)) || (area == MailDrawerFoot.CONTACTS && !MailDrawerFoot.contacts(m))) area = MailDrawerFoot.MAIL
    }

    LifecycleResumeEffect(viewModel) {
        viewModel.onShown()
        onPauseOrDispose { }
    }
    LaunchedEffect(message) {
        val m = message ?: return@LaunchedEffect
        toast(
            context,
            when (m) {
                MailHomeViewModel.UNDONE -> context.getString(R.string.mail_undone)
                MailHomeViewModel.TOO_LATE -> context.getString(R.string.mail_undo_too_late)
                else -> mailText(context, m)
            },
        )
        viewModel.clearMessage()
    }
    LaunchedEffect(reopen) {
        reopen?.let {
            viewModel.reopened()
            nav.draft(it)
        }
    }
    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }
    // a folder of an account: back goes to the account's inbox first
    BackHandler(enabled = !drawer.isOpen && area == MailDrawerFoot.MAIL && box.folderId != null) { viewModel.selectBox(MailLogic.Box(box.accountId)) }
    // calendar or contacts: back goes to the area the screen started with (the mail in the tab)
    BackHandler(enabled = !drawer.isOpen && area != initialArea) { area = initialArea }
    val toggle: (String) -> Unit = { a -> area = if (area == a) MailDrawerFoot.MAIL else a }

    val accounts = me?.accounts.orEmpty()
    val account = me?.account(box.accountId)
    val inbox = box.isInbox(folders[box.accountId].orEmpty())
    val folder = folders[box.accountId]?.firstOrNull { it.id == box.folderId }

    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = drawer.isOpen,
        drawerContent = {
            // the screen already sits below the status bar: no second inset at the top of the menu
            ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surface, windowInsets = WindowInsets(0)) {
                val close: () -> Unit = { scope.launch { drawer.close() } }
                MailDrawer(
                    me = me,
                    folders = folders,
                    box = box,
                    filing = filing,
                    outbox = outbox,
                    folds = folds,
                    onFold = viewModel::toggleFold,
                    onBox = { close(); viewModel.selectBox(it) },
                    onFiling = { close(); nav.filing() },
                    onOutbox = { close(); nav.outbox() },
                    onSettings = { close(); nav.settings() },
                    onContacts = { toggle(MailDrawerFoot.CONTACTS) },
                    onCalendar = { toggle(MailDrawerFoot.CALENDAR) },
                    area = area,
                    onMail = { close(); area = MailDrawerFoot.MAIL },
                    calendarVm = calendarVm,
                    calendarMenu = calendarMenu,
                    contactsVm = contactsVm,
                    onContactsFilter = { close() },
                    onDuplicates = { close(); nav.duplicates() },
                )
            }
        },
    ) {
        val openMenu: () -> Unit = { scope.launch { drawer.open() } }
        when {
            area == MailDrawerFoot.CALENDAR && calendarVm != null -> MailCalendarContent(
                CalendarNav(menu = openMenu, event = nav.event, newEvent = nav.newEvent, relogin = nav.relogin),
                calendarVm,
            )
            area == MailDrawerFoot.CONTACTS && contactsVm != null -> MailContactsContent(
                ContactsNav(menu = openMenu, contact = nav.contact, newContact = nav.newContact, group = nav.group, duplicates = nav.duplicates, relogin = nav.relogin),
                contactsVm,
            )
            else -> MailArea(nav, viewModel, me, meError, folders, box, tab, list, undo, accounts, account, inbox, folder, openMenu) { actionsFor = it }
        }
    }
    calendarVm?.let { CalendarMenuDialogs(calendarMenu, it, it.calendars.collectAsStateWithLifecycle().value) }

    actionsFor?.let { item ->
        RowActions(
            item = item,
            inbox = inbox,
            hasFolders = folders[item.accountId].orEmpty().isNotEmpty(),
            onAction = { action ->
                actionsFor = null
                if (action == "move") moveFor = item else viewModel.act(item, action)
            },
            onDismiss = { actionsFor = null },
        )
    }
    moveFor?.let { item ->
        FolderPicker(
            folders = folders[item.accountId].orEmpty().filter { it.id != item.folderId },
            onPick = { f ->
                moveFor = null
                viewModel.act(item, "move", f.id)
            },
            onDismiss = { moveFor = null },
        )
    }
}

/** The mail area: the bar, the boxes, Relevant / Sonstige, the list, "Neue Mail" and the bar to undo a send. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MailArea(
    nav: MailNav,
    viewModel: MailHomeViewModel,
    me: MMe?,
    meError: String?,
    folders: Map<Long, List<MFolder>>,
    box: MailLogic.Box,
    tab: String,
    list: MailListState,
    undo: MUndo?,
    accounts: List<MAccount>,
    account: MAccount?,
    inbox: Boolean,
    folder: MFolder?,
    openMenu: () -> Unit,
    onActions: (MSummary) -> Unit,
) {
    val context = mailContext()
    val extra = GateControlTheme.extraColors
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // the bar: menu, title with the unread count, search, "⋯" when pinned to Start
            val unread = when {
                folder != null -> folder.unread
                box.accountId != null -> me?.unreadByAccount?.get(box.accountId) ?: 0
                else -> me?.unreadAll ?: 0
            }
            val title = when {
                folder != null && !inbox -> folder.name
                box.all -> stringResource(R.string.mail_all_inboxes)
                else -> stringResource(R.string.mail_inbox)
            }
            val where = if (box.all) pluralStringResource(R.plurals.mail_accounts_n, accounts.size, accounts.size) else account?.label.orEmpty()
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GcIconButton(
                    GcIcons.Menu,
                    contentDescription = stringResource(R.string.mail_menu),
                    onClick = openMenu,
                    tint = MaterialTheme.colorScheme.onBackground,
                )
                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(title, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (me != null) {
                        Text(
                            listOf(where, pluralStringResource(R.plurals.mail_unread_n, unread, unread)).filter { it.isNotEmpty() }.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = extra.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                GcIconButton(GcIcons.Search, contentDescription = stringResource(R.string.mail_search), onClick = nav.search, tint = MaterialTheme.colorScheme.onBackground)
                com.gatecontrol.android.ui.start.StartPageMenuButton()
            }
            if (accounts.size > 1) {
                BoxChips(accounts = accounts, box = box, onBox = viewModel::selectBox)
            }
            if (inbox) {
                TabSwitch(
                    tab = tab,
                    other = list.counts?.other,
                    onTab = viewModel::selectTab,
                )
            }
            MailBanners(me = me, meError = meError, offline = list.offline, onAccount = nav.account)
            when {
                me != null && accounts.isEmpty() -> NoAccounts(onAdd = nav.addAccount)
                me == null && meError != null && list.items.isEmpty() -> ErrorBox(mailText(context, meError), onRetry = { viewModel.load(refresh = true) })
                else -> {
                    val listState = rememberLazyListState()
                    LaunchedEffect(listState, list.next) {
                        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
                            if (list.next != null && last >= list.items.size - 8) viewModel.loadMore()
                        }
                    }
                    PullToRefreshBox(
                        isRefreshing = list.refreshing,
                        onRefresh = { viewModel.load(refresh = true) },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    ) {
                        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 96.dp)) {
                            if (list.items.isEmpty()) {
                                item(key = "empty") {
                                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                        if (list.loading) {
                                            CircularProgressIndicator(Modifier.size(28.dp))
                                        } else {
                                            Text(
                                                list.error?.let { mailText(context, it) } ?: stringResource(if (inbox && tab == MSummary.TAB_OTHER) R.string.mail_empty_other else R.string.mail_empty),
                                                color = extra.muted,
                                                style = MaterialTheme.typography.bodyMedium,
                                            )
                                        }
                                    }
                                }
                            }
                            items(list.items, key = { it.id }) { item ->
                                val settings = me?.settings
                                SwipeRow(
                                    left = swipeSpec(settings?.swipeLeft, item.unread),
                                    right = swipeSpec(settings?.swipeRight, item.unread),
                                    onSwipe = { viewModel.act(item, it.action) },
                                ) {
                                    Column {
                                        MailRow(
                                            item = item,
                                            account = me?.account(item.accountId),
                                            onClick = { nav.open(item.id) },
                                            onLongClick = { onActions(item) },
                                        )
                                        MailDivider()
                                    }
                                }
                            }
                            if (list.loadingMore) {
                                item(key = "more") {
                                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator(Modifier.size(24.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (accounts.isNotEmpty()) {
            NewMailButton(
                onClick = { nav.compose(box.accountId) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = if (undo != null) 88.dp else 16.dp),
            )
        }
        undo?.let { u ->
            UndoBar(u, onUndo = { viewModel.undoSend(u) }, onExpired = { viewModel.undoExpired(u) }, modifier = Modifier.align(Alignment.BottomCenter))
        }
    }
}

/** "Alle" and one chip per account (mockup: colored dot, the active one in the accent). */
@Composable
private fun BoxChips(accounts: List<MAccount>, box: MailLogic.Box, onBox: (MailLogic.Box) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BoxChip(stringResource(R.string.mail_box_all), MaterialTheme.colorScheme.onBackground, box.all) { onBox(MailLogic.Box()) }
        accounts.forEach { a ->
            BoxChip(a.label, accountColor(a), box.accountId == a.id && box.folderId == null) { onBox(MailLogic.Box(a.id)) }
        }
    }
}

@Composable
private fun BoxChip(label: String, dot: Color, on: Boolean, onClick: () -> Unit) {
    val extra = GateControlTheme.extraColors
    Row(
        Modifier
            .heightIn(min = 36.dp)
            .clip(RoundedCornerShape(999.dp))
            .then(if (on) Modifier.background(extra.accentBg) else Modifier.border(1.dp, extra.border2, RoundedCornerShape(999.dp)))
            .selectable(selected = on, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        MailDot(dot)
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = if (on) extra.accentText else MaterialTheme.colorScheme.onBackground, maxLines = 1)
    }
}

/** Relevant | Sonstige · n (mockup: tabs with the accent line). */
@Composable
private fun TabSwitch(tab: String, other: Int?, onTab: (String) -> Unit) {
    val extra = GateControlTheme.extraColors
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            listOf(
                MSummary.TAB_RELEVANT to stringResource(R.string.mail_tab_relevant),
                MSummary.TAB_OTHER to (stringResource(R.string.mail_tab_other) + (other?.let { " · $it" } ?: "")),
            ).forEach { (id, label) ->
                val on = tab == id
                Column(
                    Modifier.weight(1f).heightIn(min = 44.dp).selectable(selected = on, role = Role.Tab) { onTab(id) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = if (on) extra.accentText else extra.muted, modifier = Modifier.padding(vertical = 12.dp))
                    Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp).heightIn(min = 2.dp, max = 2.dp).background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent))
                }
            }
        }
        MailDivider()
    }
}

/** Accounts that need the person (sign-in refused, server unreachable), and "offline". */
@Composable
private fun MailBanners(me: MMe?, meError: String?, offline: Boolean, onAccount: (Long) -> Unit) {
    val failing = me?.accounts.orEmpty().filter { it.state == MAccount.STATE_AUTH_FAILED || it.state == MAccount.STATE_UNREACHABLE }
    if (!offline && failing.isEmpty() && !(me != null && meError == "offline")) return
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (offline || (me != null && meError == "offline")) {
            GcBanner(tone = GcTone.Neutral, icon = GcIcons.Wifi) {
                Text(stringResource(R.string.mail_offline_copy), style = MaterialTheme.typography.bodySmall)
            }
        }
        failing.take(3).forEach { a ->
            GcBanner(tone = GcTone.Warn, icon = GcIcons.Alert, modifier = Modifier.clickable { onAccount(a.id) }) {
                Text(
                    stringResource(if (a.state == MAccount.STATE_AUTH_FAILED) R.string.mail_account_auth_failed else R.string.mail_account_unreachable, a.label),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                GcIcons.ChevronRight.let { Icon(it, contentDescription = null, modifier = Modifier.size(16.dp)) }
            }
        }
    }
}

@Composable
private fun NoAccounts(onAdd: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(GcIcons.Mail, contentDescription = null, tint = GateControlTheme.extraColors.muted, modifier = Modifier.size(40.dp))
        Text(stringResource(R.string.mail_no_accounts_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.mail_no_accounts_text), style = MaterialTheme.typography.bodyMedium, color = GateControlTheme.extraColors.muted)
        GcPrimaryButton(stringResource(R.string.mail_add_account), onClick = onAdd, icon = GcIcons.Plus)
    }
}

@Composable
internal fun ErrorBox(text: String, onRetry: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = GateControlTheme.extraColors.muted)
        if (onRetry != null) TextButton(onClick = onRetry) { Text(stringResource(R.string.mail_retry)) }
    }
}

/** "Neue Mail" (mockup: accent pill with the pen). */
@Composable
private fun NewMailButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(scheme.primary)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(MailIcons.Pen, contentDescription = null, tint = scheme.onPrimary, modifier = Modifier.size(20.dp))
        Text(stringResource(R.string.mail_new), color = scheme.onPrimary, fontWeight = FontWeight.Bold)
    }
}

/** "Wird gesendet · Rückgängig" for the undo window (a planned send: "Geplant für …"). */
@Composable
private fun UndoBar(u: MUndo, onUndo: () -> Unit, onExpired: () -> Unit, modifier: Modifier = Modifier) {
    var left by remember(u.outboxId) { mutableLongStateOf((u.untilElapsed - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0)) }
    val total = remember(u.outboxId) { left.coerceAtLeast(1) }
    LaunchedEffect(u.outboxId) {
        while (true) {
            left = (u.untilElapsed - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0)
            if (left <= 0) break
            delay(200)
        }
        onExpired()
    }
    val extra = GateControlTheme.extraColors
    Column(
        modifier
            .padding(12.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, extra.border, RoundedCornerShape(16.dp)),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(MailIcons.Send, contentDescription = null, tint = extra.accentText, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                if (u.planned && u.sendAt != null) stringResource(R.string.mail_planned_for, mailDateTime(u.sendAt)) else stringResource(R.string.mail_sending, ((left + 999) / 1000).toInt()),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onUndo) { Text(stringResource(R.string.mail_undo), fontWeight = FontWeight.Bold) }
        }
        LinearProgressIndicator(progress = { left.toFloat() / total }, modifier = Modifier.fillMaxWidth())
    }
}

/** The menu of a mail (long press): read, flag, Relevant / Sonstige, archive, delete, spam, move. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RowActions(item: MSummary, inbox: Boolean, hasFolders: Boolean, onAction: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(
                item.subject.ifEmpty { stringResource(R.string.mail_no_subject) },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp).semantics { heading() },
            )
            SheetRow(MailIcons.Unread, stringResource(if (item.unread) R.string.mail_mark_read else R.string.mail_mark_unread)) { onAction(MailLogic.SWIPE_READ) }
            SheetRow(GcIcons.Flag, stringResource(if (item.flagged) R.string.mail_unflag else R.string.mail_flag)) { onAction("flag") }
            if (inbox) {
                SheetRow(MailIcons.Inbox, stringResource(if (item.tab == MSummary.TAB_OTHER) R.string.mail_to_relevant else R.string.mail_to_other)) { onAction("tab") }
            }
            SheetRow(GcIcons.Archive, stringResource(R.string.mail_archive)) { onAction(MailLogic.SWIPE_ARCHIVE) }
            if (hasFolders) SheetRow(GcIcons.FolderMove, stringResource(R.string.mail_move)) { onAction("move") }
            SheetRow(GcIcons.ShieldX, stringResource(R.string.mail_junk)) { onAction("junk") }
            SheetRow(GcIcons.Trash, stringResource(R.string.mail_delete), MaterialTheme.colorScheme.error) { onAction(MailLogic.SWIPE_DELETE) }
        }
    }
}

@Composable
internal fun SheetRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = tint)
    }
}

/** "Verschieben": the account's folders. */
@Composable
internal fun FolderPicker(folders: List<MFolder>, onPick: (MFolder) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mail_move_title)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(MailFolders.ordered(folders), key = { it.id }) { f ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onPick(f) }.padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(MailFolders.icon(f.role), contentDescription = null, modifier = Modifier.size(20.dp), tint = GateControlTheme.extraColors.muted)
                        Text(MailFolders.label(f), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.mail_cancel)) } },
    )
}

/** The folders of an account in the menu's order: inbox, sent, drafts, archive, the own ones, junk, trash. */
internal object MailFolders {
    private val ORDER = listOf(MFolder.ROLE_INBOX, MFolder.ROLE_SENT, MFolder.ROLE_DRAFTS, MFolder.ROLE_ARCHIVE, null, MFolder.ROLE_JUNK, MFolder.ROLE_TRASH)

    fun ordered(folders: List<MFolder>): List<MFolder> = folders.sortedWith(compareBy({ ORDER.indexOf(it.role) }, { it.path.lowercase() }))

    fun icon(role: String?) = when (role) {
        MFolder.ROLE_INBOX -> MailIcons.Inbox
        MFolder.ROLE_SENT -> MailIcons.Send
        MFolder.ROLE_DRAFTS -> MailIcons.Pen
        MFolder.ROLE_ARCHIVE -> GcIcons.Archive
        MFolder.ROLE_JUNK -> GcIcons.ShieldX
        MFolder.ROLE_TRASH -> GcIcons.Trash
        else -> GcIcons.Folder
    }

    /** The well-known folders by their role in the app's language, the own ones by name. */
    @Composable
    fun label(f: MFolder): String = when (f.role) {
        MFolder.ROLE_INBOX -> stringResource(R.string.mail_inbox)
        MFolder.ROLE_SENT -> stringResource(R.string.mail_folder_sent)
        MFolder.ROLE_DRAFTS -> stringResource(R.string.mail_folder_drafts)
        MFolder.ROLE_ARCHIVE -> stringResource(R.string.mail_folder_archive)
        MFolder.ROLE_JUNK -> stringResource(R.string.mail_folder_junk)
        MFolder.ROLE_TRASH -> stringResource(R.string.mail_folder_trash)
        else -> f.name
    }
}

/**
 * The menu (mockups "Seitenmenü" and "Seitenmenü neu"): "Mail", "Alle Posteingänge", "Zum Ablegen" (with Drive),
 * "Postausgang" (when something waits), the accounts with their folders, the storage bar and the foot: the settings
 * on the left, contacts and calendar on the right ([MailDrawerFoot]). A tap on an account opens or closes its folders
 * (the menu stays, [folds] keeps it for the session); picking its "Posteingang" or a folder closes the menu. An
 * account is added in the settings only (no "+" here since phase 2).
 */
@Composable
private fun MailDrawer(
    me: MMe?,
    folders: Map<Long, List<MFolder>>,
    box: MailLogic.Box,
    filing: Int,
    outbox: Int,
    folds: Map<Long, Boolean>,
    onFold: (Long) -> Unit,
    onBox: (MailLogic.Box) -> Unit,
    onFiling: () -> Unit,
    onOutbox: () -> Unit,
    onSettings: () -> Unit,
    onContacts: (() -> Unit)? = null,
    onCalendar: (() -> Unit)? = null,
    area: String = MailDrawerFoot.MAIL,
    onMail: () -> Unit = {},
    calendarVm: MailCalendarViewModel? = null,
    calendarMenu: CalendarMenuState? = null,
    contactsVm: MailContactsViewModel? = null,
    onContactsFilter: () -> Unit = {},
    onDuplicates: () -> Unit = {},
) {
    val extra = GateControlTheme.extraColors
    val cals = calendarVm?.calendars?.collectAsStateWithLifecycle()?.value
    val books = contactsVm?.books?.collectAsStateWithLifecycle()?.value
    val contactsState = contactsVm?.state?.collectAsStateWithLifecycle()?.value
    val contactsTotal = contactsVm?.total?.collectAsStateWithLifecycle()?.value?.size ?: 0
    Column(Modifier.fillMaxHeight().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 16.dp)) {
        Text(
            stringResource(
                when (area) {
                    MailDrawerFoot.CALENDAR -> R.string.mail_calendar
                    MailDrawerFoot.CONTACTS -> R.string.mail_contacts
                    else -> R.string.mail_tab
                },
            ),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 12.dp).semantics { heading() },
        )
        val inboxRow: @Composable () -> Unit = { DrawerItem(MailIcons.Inbox, stringResource(R.string.mail_inboxes), me?.unreadAll ?: 0, on = false, onClick = onMail) }
        val label: @Composable (String) -> Unit = { DrawerLabel(it) }
        LazyColumn(Modifier.weight(1f)) {
            if (area == MailDrawerFoot.CALENDAR && calendarVm != null && calendarMenu != null) {
                calendarDrawerItems(me, cals, calendarMenu, inboxRow, label, onToggle = calendarVm::toggleCalendar, onToggleOverlay = calendarVm::toggleOverlay)
            } else if (area == MailDrawerFoot.CONTACTS && contactsVm != null && contactsState != null) {
                contactsDrawerItems(
                    me, books, contactsTotal, contactsState.duplicates, contactsState.filter, inboxRow, label,
                    row = { icon, text, count, on, click -> DrawerItem(icon, text, count, on, onClick = click) },
                    onFilter = { f -> contactsVm.setFilter(f); onContactsFilter() },
                    onToggleBook = contactsVm::toggleBook,
                    onDuplicates = onDuplicates,
                )
            } else {
                mailDrawerItems(me, folders, box, filing, outbox, folds, onFold, onBox, onFiling, onOutbox)
            }
            me?.limits?.let { l ->
                MailLogic.quotaPercent(l.usedBytes, l.quotaBytes)?.let { p ->
                    item(key = "quota") {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(stringResource(R.string.mail_quota, mailSize(l.usedBytes), mailSize(l.quotaBytes)), style = MaterialTheme.typography.bodySmall, color = extra.muted)
                            LinearProgressIndicator(progress = { p / 100f }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
        MailDrawerFoot(me = me, area = area, onSettings = onSettings, onContacts = onContacts, onCalendar = onCalendar)
    }
}

/** The mail's part of the menu: "Alle Posteingänge", "Zum Ablegen", "Postausgang", the accounts and their folders. */
private fun androidx.compose.foundation.lazy.LazyListScope.mailDrawerItems(
    me: MMe?,
    folders: Map<Long, List<MFolder>>,
    box: MailLogic.Box,
    filing: Int,
    outbox: Int,
    folds: Map<Long, Boolean>,
    onFold: (Long) -> Unit,
    onBox: (MailLogic.Box) -> Unit,
    onFiling: () -> Unit,
    onOutbox: () -> Unit,
) {
            item(key = "all") {
                DrawerItem(MailIcons.Inbox, stringResource(R.string.mail_all_inboxes), me?.unreadAll ?: 0, on = box.all, accent = true) { onBox(MailLogic.Box()) }
            }
            if (me?.has(MMe.FEATURE_DRIVE) == true) {
                item(key = "filing") { DrawerItem(GcIcons.Folder, stringResource(R.string.mail_filing), filing, on = false) { onFiling() } }
            }
            if (outbox > 0) {
                item(key = "outbox") { DrawerItem(MailIcons.Outbox, stringResource(R.string.mail_outbox), outbox, on = false) { onOutbox() } }
            }
            val accounts = me?.accounts.orEmpty()
            if (accounts.isNotEmpty()) {
                item(key = "accounts") { DrawerLabel(stringResource(R.string.mail_accounts)) }
            }
            accounts.forEach { a ->
                val open = MailLogic.foldOpen(folds, a.id, box)
                item(key = "a${a.id}") {
                    DrawerAccount(a, me?.unreadByAccount?.get(a.id) ?: 0, on = !open && box.accountId == a.id, open = open) { onFold(a.id) }
                }
                if (open) {
                    val list = MailFolders.ordered(folders[a.id].orEmpty())
                    // the account's inbox is always there (also before its folders came)
                    if (list.none { it.role == MFolder.ROLE_INBOX }) {
                        item(key = "i${a.id}") {
                            DrawerFolder(stringResource(R.string.mail_inbox), 0, on = box.accountId == a.id && box.folderId == null) { onBox(MailLogic.Box(a.id)) }
                        }
                    }
                    list.forEach { f ->
                        item(key = "f${f.id}") {
                            val inbox = f.role == MFolder.ROLE_INBOX
                            DrawerFolder(
                                MailFolders.label(f),
                                f.unread,
                                on = if (inbox) box.accountId == a.id && box.folderId == null else box.folderId == f.id,
                            ) { onBox(MailLogic.Box(a.id, f.id.takeIf { !inbox })) }
                        }
                    }
                }
            }
}

/**
 * The menu's foot (mockup "Seitenmenü neu"): the settings on the left; contacts and calendar on the right, each only
 * when the server offers it (`features.contacts` / `features.calendar`) and the app can open it, the active area in the
 * accent. A tap on the active one goes back to the mail.
 */
@Composable
internal fun MailDrawerFoot(
    me: MMe?,
    area: String,
    onSettings: () -> Unit,
    onContacts: (() -> Unit)?,
    onCalendar: (() -> Unit)?,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DrawerIcon(GcIcons.Settings, stringResource(R.string.mail_settings), on = false, onClick = onSettings)
        Spacer(Modifier.weight(1f))
        if (onContacts != null && MailDrawerFoot.contacts(me)) {
            DrawerIcon(GcIcons.User, stringResource(R.string.mail_contacts), on = area == MailDrawerFoot.CONTACTS, onClick = onContacts)
        }
        if (onCalendar != null && MailDrawerFoot.calendar(me)) {
            DrawerIcon(GcIcons.Calendar, stringResource(R.string.mail_calendar), on = area == MailDrawerFoot.CALENDAR, onClick = onCalendar)
        }
    }
}

/** What the menu's foot shows (pure, unit-tested). */
internal object MailDrawerFoot {
    const val MAIL = "mail"
    const val CALENDAR = "calendar"
    const val CONTACTS = "contacts"

    fun calendar(me: MMe?): Boolean = me?.has(MMe.FEATURE_CALENDAR) == true

    fun contacts(me: MMe?): Boolean = me?.has(MMe.FEATURE_CONTACTS) == true
}

@Composable
private fun DrawerLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = GateControlTheme.extraColors.faint,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 12.dp, top = 14.dp, bottom = 6.dp).semantics { heading() },
    )
}

@Composable
private fun DrawerItem(icon: androidx.compose.ui.graphics.vector.ImageVector?, label: String, count: Int, on: Boolean, accent: Boolean = false, onClick: () -> Unit) {
    val extra = GateControlTheme.extraColors
    val fg = if (on) extra.accentText else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (on) extra.accentBg else Color.Transparent)
            .selectable(selected = on, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, color = fg, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (count > 0 || accent) {
            Text(count.toString(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = if (accent && count > 0) extra.accentText else extra.muted)
        }
    }
}

/** A folder of an account in the menu (indented below it). */
@Composable
private fun DrawerFolder(label: String, unread: Int, on: Boolean, onClick: () -> Unit) {
    val extra = GateControlTheme.extraColors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (on) extra.accentBg else Color.Transparent)
            .selectable(selected = on, role = Role.Tab, onClick = onClick)
            .padding(start = 58.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = if (on) extra.accentText else MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (unread > 0) Text(unread.toString(), style = MaterialTheme.typography.labelMedium, color = extra.muted, fontWeight = FontWeight.Bold)
    }
}

/**
 * An account in the menu (like the Outlook app): its picture or the provider's mark, the name, the address below and
 * the arrow; a tap opens or closes its folders.
 */
@Composable
private fun DrawerAccount(a: MAccount, count: Int, on: Boolean, open: Boolean, onClick: () -> Unit) {
    val extra = GateControlTheme.extraColors
    val state = stringResource(if (open) R.string.mail_folders_shown else R.string.mail_folders_hidden)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (on) extra.accentBg else Color.Transparent)
            .clickable(role = Role.Button, onClickLabel = stringResource(if (open) R.string.mail_folders_hide else R.string.mail_folders_show), onClick = onClick)
            .semantics { stateDescription = state }
            .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MailAccountAvatar(a, size = 34.dp, ring = if (on) extra.accentBg else MaterialTheme.colorScheme.surface)
        MailAccountText(a, Modifier.weight(1f), color = if (on) extra.accentText else MaterialTheme.colorScheme.onSurface, bold = on)
        if (count > 0) Text(count.toString(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = extra.muted)
        Icon(if (open) GcIcons.ChevronUp else GcIcons.ChevronDown, contentDescription = null, tint = extra.muted, modifier = Modifier.size(18.dp))
    }
}

/** A square button of the menu's foot (44×44); [on]: the area shown now, in the accent (`aria-pressed`). */
@Composable
private fun DrawerIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    val extra = GateControlTheme.extraColors
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            .size(44.dp)
            .clip(shape)
            .then(if (on) Modifier.background(extra.accentBg) else Modifier.border(1.dp, extra.border, shape))
            .selectable(selected = on, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (on) extra.accentText else extra.muted, modifier = Modifier.size(20.dp))
    }
}
