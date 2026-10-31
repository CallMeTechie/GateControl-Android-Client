package com.gatecontrol.android.ui.start

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.layout.StartEntry
import com.gatecontrol.android.layout.StartPages
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcListRow
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcScreenTitle
import com.gatecontrol.android.ui.components.GcSecondaryButton
import com.gatecontrol.android.ui.components.GcSectionLabel
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.quickactions.QuickActionsEffects
import com.gatecontrol.android.ui.quickactions.QuickActionsViewModel
import com.gatecontrol.android.ui.quickactions.StartCards
import com.gatecontrol.android.ui.quickactions.StartTiles
import com.gatecontrol.android.ui.theme.GateControlTheme
import com.gatecontrol.android.ui.vpn.HomeColumn
import com.gatecontrol.android.ui.vpn.StartEditSheet
import kotlinx.coroutines.launch

/**
 * A page of Start to show: a tab's route opened (push, deep link, a tap
 * on Start) while its function is a page beside Start. [route] is the
 * route asked for (its arguments, e.g. the Netzwerk segment); [seq] makes
 * every request a new one.
 */
data class StartPageRequest(val page: String, val route: String? = null, val seq: Long = System.nanoTime())

/**
 * Start as a row of pages (mockup "Start-Seiten"): the start tab first,
 * then the pages pinned to it ([pages]: functions shown with their tab's
 * own screen via [feature], widget pages like Start), the plus page last
 * to pin another. Swiping changes the page; the dots below change it too
 * (with the badges of [badges], e.g. unread chats). Back on another page
 * goes to Start first. [names]: the bar is hidden, the dots get a line with
 * the pages' names. The quick actions' sheets and "Anordnen" are here once
 * for Start and its widget pages.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StartPager(
    pages: List<String>,
    candidates: List<String>,
    badges: Map<String, Int>,
    request: StartPageRequest?,
    /** The last request per page: its route's arguments stay with the page until the next one for it. */
    requests: Map<String, StartPageRequest>,
    names: Boolean,
    onArrangePages: () -> Unit,
    onOpenQuickActions: (page: String?) -> Unit,
    onNewReminder: ((actionId: String) -> Unit)?,
    onOpenDoor: (() -> Unit)?,
    start: @Composable (onArrange: () -> Unit) -> Unit,
    feature: @Composable (id: String, request: StartPageRequest?) -> Unit,
    viewModel: StartPagesViewModel = hiltViewModel(),
    quickActions: QuickActionsViewModel = hiltViewModel(),
) {
    val access by viewModel.access.collectAsStateWithLifecycle()
    val blocks by viewModel.blocks.collectAsStateWithLifecycle()
    val locked = access.locked
    val resources = LocalResources.current
    val snack = LocalPageSnack.current
    val scope = rememberCoroutineScope()
    var arranging by rememberSaveable { mutableStateOf<String?>(null) }
    var focus by remember { mutableStateOf<String?>(null) }
    var sheet by rememberSaveable { mutableStateOf(false) }
    var removing by rememberSaveable { mutableStateOf<String?>(null) }

    QuickActionsEffects(
        onArrange = { page -> arranging = page ?: START_PAGE },
        onNewReminder = onNewReminder,
        viewModel = quickActions,
    )

    val ids = remember(pages) { listOf(START_PAGE) + pages + PLUS_PAGE }
    val currentIds by rememberUpdatedState(ids)
    // Before arranging may replace the pages: afterwards the same page shows again.
    val pagerState = rememberPagerState { currentIds.size }

    // "Start-Tab anordnen" (or a widget page's) replaces the pages until "Fertig".
    val arrangingPage = arranging
    if (arrangingPage != null && !locked) {
        val page = arrangingPage.takeIf { it != START_PAGE }
        StartArrangeScreen(
            onDone = { arranging = null },
            onAddTile = { onOpenQuickActions(page) },
            page = page,
            title = page?.let { resources.getString(R.string.pages_widgets_arrange, startPageLabel(resources, it)) },
            viewModel = quickActions,
        )
        return
    }

    // A page asked for from outside (a tab's route, Start in the bar), once it exists.
    var handled by rememberSaveable { mutableLongStateOf(0L) }
    LaunchedEffect(request, ids) {
        val r = request ?: return@LaunchedEffect
        if (r.seq == handled) return@LaunchedEffect
        val index = ids.indexOf(r.page)
        if (index >= 0) {
            handled = r.seq
            pagerState.animateScrollToPage(index)
        }
    }
    // A page pinned here: shown as soon as it is there.
    LaunchedEffect(focus, ids) {
        val f = focus ?: return@LaunchedEffect
        val index = ids.indexOf(f)
        if (index >= 0) {
            focus = null
            pagerState.animateScrollToPage(index)
        }
    }
    // Back on a page beside Start: Start first, then the app as before.
    BackHandler(enabled = pagerState.currentPage > 0) {
        scope.launch { pagerState.animateScrollToPage(0) }
    }

    val pin: (String) -> Unit = { id ->
        sheet = false
        focus = id
        viewModel.pin(id) { undo -> snack(resources.getString(R.string.pages_snack_pinned, startPageLabel(resources, id)), undo) }
    }
    val addWidgets: () -> Unit = {
        sheet = false
        viewModel.addWidgets { id, undo ->
            focus = id
            snack(resources.getString(R.string.pages_snack_pinned, startPageLabel(resources, id)), undo)
        }
    }
    val unpinNow: (String) -> Unit = { id ->
        val message = if (StartPages.isWidgets(id)) {
            resources.getString(R.string.pages_snack_removed)
        } else if (StartPages.isPageOnly(id)) {
            resources.getString(R.string.pages_snack_in_mail, startPageLabel(resources, id))
        } else {
            resources.getString(R.string.pages_snack_unpinned, startPageLabel(resources, id))
        }
        viewModel.unpin(id) { undo -> snack(message, undo) }
    }
    // A widget page with blocks: ask first (its blocks go back to Start).
    val unpin: (String) -> Unit = { id ->
        if (StartPages.isWidgets(id) && blocks[id].orEmpty().isNotEmpty()) removing = id else unpinNow(id)
    }
    fun menu(id: String) = StartPageMenu(
        label = startPageLabel(resources, id),
        widgets = StartPages.isWidgets(id),
        locked = locked,
        onUnpin = { unpin(id) },
        onArrange = onArrangePages,
        pageOnly = StartPages.isPageOnly(id),
    )

    Column(Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            key = { index -> currentIds.getOrElse(index) { index } },
            verticalAlignment = Alignment.Top,
        ) { index ->
            val id = currentIds.getOrNull(index) ?: return@HorizontalPager
            Box(Modifier.fillMaxSize()) {
                when {
                    id == START_PAGE -> start { arranging = START_PAGE }
                    id == PLUS_PAGE -> PlusPage(onOpen = { sheet = true })
                    StartPages.isWidgets(id) -> CompositionLocalProvider(LocalInStartPager provides true) {
                        WidgetPage(
                            id = id,
                            menu = menu(id),
                            locked = locked,
                            onArrange = { arranging = id },
                            onOpenQuickActions = { onOpenQuickActions(id) },
                            onOpenDoor = onOpenDoor,
                            viewModel = quickActions,
                        )
                    }
                    else -> CompositionLocalProvider(LocalStartPageMenu provides menu(id), LocalInStartPager provides true) {
                        feature(id, requests[id])
                    }
                }
            }
        }
        PageDots(
            ids = ids,
            current = pagerState.currentPage,
            badges = badges,
            names = names,
            onSelect = { index -> scope.launch { pagerState.animateScrollToPage(index) } },
        )
    }

    if (sheet) {
        PinPageSheet(
            candidates = candidates,
            locked = locked,
            onPin = pin,
            onAddWidgets = addWidgets,
            onDismiss = { sheet = false },
        )
    }
    removing?.let { id ->
        RemovePageDialog(
            id = id,
            count = blocks[id].orEmpty().size,
            onConfirm = { removing = null; unpinNow(id) },
            onDismiss = { removing = null },
        )
    }
}

/** "„Widgets 2“ entfernen?" for a widget page with [count] blocks (they go back to Start). */
@Composable
internal fun RemovePageDialog(id: String, count: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pages_remove_confirm_title, startPageLabel(id))) },
        text = { Text(pluralStringResource(R.plurals.pages_remove_confirm_text, count, count)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.pages_remove_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/**
 * The page dots above the bar: the current one a wide pill in the accent
 * colour, the plus page a small "+"; a tap shows that page. A page's badge
 * (unread chats, Remote hosts online) as a small number at its dot. [names]
 * (no bar): a line with the pages' names below, the current one stronger.
 */
@Composable
private fun PageDots(ids: List<String>, current: Int, badges: Map<String, Int>, names: Boolean, onSelect: (Int) -> Unit) {
    val extra = GateControlTheme.extraColors
    val scheme = MaterialTheme.colorScheme
    val dotsLabel = stringResource(R.string.pages_dots_desc)
    Column(
        Modifier.fillMaxWidth().padding(top = 2.dp, bottom = if (names) 10.dp else 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            Modifier.semantics { contentDescription = dotsLabel },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ids.forEachIndexed { index, id ->
                val selected = index == current
                val label = startPageLabel(id)
                val badge = badges[id] ?: 0
                val description = if (badge > 0) {
                    stringResource(R.string.pages_dot_desc_badge, index + 1, ids.size, label, badge)
                } else {
                    stringResource(R.string.pages_dot_desc, index + 1, ids.size, label)
                }
                val width by animateDpAsState(if (selected) 18.dp else 6.dp, label = "dot")
                val color by animateColorAsState(if (selected) extra.accentText else extra.border2, label = "dotColor")
                Box(
                    Modifier
                        .heightIn(min = 32.dp)
                        .widthIn(min = 22.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(role = Role.Tab, onClick = { onSelect(index) })
                        .semantics {
                            contentDescription = description
                            this.selected = selected
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (id == PLUS_PAGE) {
                        Icon(GcIcons.Plus, contentDescription = null, tint = color, modifier = Modifier.size(10.dp))
                    } else {
                        Box(Modifier.height(6.dp).width(width).clip(RoundedCornerShape(3.dp)).background(color))
                    }
                    if (badge > 0) {
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 4.dp, y = 2.dp)
                                .height(14.dp)
                                .widthIn(min = 14.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .background(scheme.primary)
                                .padding(horizontal = 3.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                if (badge > 99) "99+" else badge.toString(),
                                color = scheme.onPrimary,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
        if (names) {
            val labels = ids.filter { it != PLUS_PAGE }.map { startPageLabel(it) }
            Text(
                buildAnnotatedString {
                    labels.forEachIndexed { index, label ->
                        if (index > 0) append(" · ")
                        if (index == current) withStyle(SpanStyle(color = scheme.onSurface, fontWeight = FontWeight.SemiBold)) { append(label) } else append(label)
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = extra.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // The dots read out each page already.
                modifier = Modifier.padding(horizontal = 16.dp).clearAndSetSemantics { },
            )
        }
    }
}

/** The plus page: a large dashed circle with "+" that opens [PinPageSheet]. */
@Composable
private fun PlusPage(onOpen: () -> Unit) {
    val extra = GateControlTheme.extraColors
    val ring = extra.border2
    val description = stringResource(R.string.pages_sheet_title)
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(112.dp)
                .clip(CircleShape)
                .drawBehind {
                    val stroke = 2.dp.toPx()
                    drawCircle(
                        color = ring,
                        radius = size.minDimension / 2 - stroke,
                        style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f))),
                    )
                }
                .clickable(role = Role.Button, onClick = onOpen)
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) {
            Icon(GcIcons.Plus, contentDescription = null, tint = extra.accentText, modifier = Modifier.size(40.dp))
        }
        Text(
            stringResource(R.string.pages_plus_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(top = 20.dp),
        )
        Text(
            stringResource(R.string.pages_plus_text),
            style = MaterialTheme.typography.bodyMedium,
            color = extra.muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp).widthIn(max = 300.dp),
        )
    }
}

/**
 * "Seite an Start anheften": the functions still in the bar ([candidates])
 * and "Neue Widget-Seite". [locked]: the admin fixes the order, nothing to pick.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PinPageSheet(
    candidates: List<String>,
    locked: Boolean,
    onPin: (String) -> Unit,
    onAddWidgets: () -> Unit,
    onDismiss: () -> Unit,
) {
    val extra = GateControlTheme.extraColors
    val titleColor = if (locked) extra.faint else MaterialTheme.colorScheme.onSurface
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(
                stringResource(R.string.pages_sheet_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Text(
                stringResource(R.string.pages_sheet_text),
                style = MaterialTheme.typography.bodySmall,
                color = extra.muted,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
            )
            if (locked) {
                GcBanner(tone = GcTone.Info, icon = GcIcons.Lock, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text(stringResource(R.string.arrange_locked), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                }
            }
            GcSectionLabel(stringResource(R.string.pages_sheet_from_bar), Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp))
            if (candidates.isEmpty()) {
                Text(
                    stringResource(R.string.pages_sheet_all_pinned),
                    style = MaterialTheme.typography.bodyMedium,
                    color = extra.muted,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            candidates.forEachIndexed { index, id ->
                if (index > 0) GcRowDivider(Modifier.padding(horizontal = 16.dp))
                GcListRow(
                    title = startPageLabel(id),
                    description = stringResource(R.string.pages_sheet_icon_leaves),
                    onClick = if (locked) null else ({ onPin(id) }),
                    titleColor = titleColor,
                    leading = { Icon(startPageIcon(id), contentDescription = null, tint = extra.muted, modifier = Modifier.size(22.dp)) },
                    trailing = { if (!locked) Icon(GcIcons.Pin, contentDescription = null, tint = extra.faint, modifier = Modifier.size(18.dp)) },
                )
            }
            GcSectionLabel(stringResource(R.string.pages_sheet_more_room), Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp))
            GcListRow(
                title = stringResource(R.string.pages_new_widgets),
                description = stringResource(R.string.pages_new_widgets_desc),
                onClick = if (locked) null else onAddWidgets,
                titleColor = titleColor,
                leading = { Icon(GcIcons.ViewGrid, contentDescription = null, tint = extra.muted, modifier = Modifier.size(22.dp)) },
                trailing = { if (!locked) Icon(GcIcons.Plus, contentDescription = null, tint = extra.faint, modifier = Modifier.size(18.dp)) },
            )
        }
    }
}

/**
 * A widget page beside Start: its name with "⋯", then its quick actions and
 * widgets like on Start (no Info and usage card). A long press on a free
 * spot opens the same menu as on Start (Schnellaktionen und Widgets,
 * Anordnen); empty, a dashed frame says how to fill it.
 */
@Composable
private fun WidgetPage(
    id: String,
    menu: StartPageMenu,
    locked: Boolean,
    onArrange: () -> Unit,
    onOpenQuickActions: () -> Unit,
    onOpenDoor: (() -> Unit)?,
    viewModel: QuickActionsViewModel,
) {
    val extra = GateControlTheme.extraColors
    val pages by viewModel.pages.collectAsStateWithLifecycle()
    val entries = pages[id].orEmpty()
    var editOpen by rememberSaveable(id) { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val openEdit: () -> Unit = {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        editOpen = true
    }
    if (editOpen) {
        StartEditSheet(
            title = stringResource(R.string.pages_widgets_edit_title),
            arrangeTitle = stringResource(R.string.pages_widgets_arrange, menu.label),
            onActions = { editOpen = false; onOpenQuickActions() },
            onInfoCard = null,
            onDismiss = { editOpen = false },
            onArrange = { editOpen = false; onArrange() },
            arrangeLocked = locked,
        )
    }
    HomeColumn(Modifier.padding(top = 12.dp), onLongPress = openEdit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GcScreenTitle(menu.label, Modifier.weight(1f).padding(start = 4.dp))
            StartPageMenuButton(menu)
        }
        if (entries.isEmpty()) {
            EmptyWidgetPage(onEdit = openEdit, onAdd = onOpenQuickActions)
        } else {
            StartTiles(onOpenDoor = onOpenDoor, page = id, viewModel = viewModel)
            StartCards(
                cards = entries.filter { it !is StartEntry.Tile && it !is StartEntry.Contact },
                info = {},
                usage = {},
                onOpenDoor = onOpenDoor,
                page = id,
                viewModel = viewModel,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val addLabel = stringResource(R.string.pages_widgets_add_desc)
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .dashedFrame(extra.border2, 16.dp)
                        .clickable(role = Role.Button, onClick = onOpenQuickActions)
                        .semantics { contentDescription = addLabel },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(GcIcons.Plus, contentDescription = null, tint = extra.muted, modifier = Modifier.size(20.dp))
                }
                Text(
                    stringResource(R.string.pages_widgets_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = extra.faint,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** An empty widget page: a dashed frame with what to do and "Widgets hinzufügen". */
@Composable
private fun EmptyWidgetPage(onEdit: () -> Unit, onAdd: () -> Unit) {
    val extra = GateControlTheme.extraColors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .dashedFrame(extra.border2, 20.dp)
            .clickable(role = Role.Button, onClick = onEdit)
            .padding(horizontal = 20.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            stringResource(R.string.pages_widgets_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = extra.muted,
            textAlign = TextAlign.Center,
        )
        GcSecondaryButton(text = stringResource(R.string.pages_widgets_add), onClick = onAdd, fillWidth = false)
    }
}

/** A dashed rounded frame (empty places to fill). */
private fun Modifier.dashedFrame(color: Color, radius: androidx.compose.ui.unit.Dp): Modifier = drawBehind {
    val stroke = 1.5.dp.toPx()
    val r = radius.toPx()
    drawRoundRect(
        color = color,
        topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2),
        size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius(r, r),
        style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
    )
}
