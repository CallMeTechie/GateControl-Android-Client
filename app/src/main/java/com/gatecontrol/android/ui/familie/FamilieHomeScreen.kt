package com.gatecontrol.android.ui.familie

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.familie.FamilieDest
import com.gatecontrol.android.familie.FamilieOverview
import com.gatecontrol.android.familie.FamilieProfile
import com.gatecontrol.android.familie.FamilieTask
import com.gatecontrol.android.familie.LocateMode
import com.gatecontrol.android.familie.LocateRequest
import com.gatecontrol.android.familie.locate.FamiliePermissions
import com.gatecontrol.android.familie.shopping.ShoppingLogic
import com.gatecontrol.android.familie.shopping.ShoppingSnapshot
import com.gatecontrol.android.layout.FamilieLayout
import com.gatecontrol.android.location.DeviceLocator
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcChevron
import com.gatecontrol.android.ui.components.GcChip
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcListRow
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.profile.MyAvatarUi
import com.gatecontrol.android.ui.profile.MyAvatarViewModel
import com.gatecontrol.android.ui.start.StartIcons
import com.gatecontrol.android.ui.start.longPressAnywhere
import com.gatecontrol.android.ui.theme.GateControlTheme
import java.time.LocalDate

/**
 * Start screen of the Familie tab (mockup Main): who is in the family (me
 * first; a tap on my avatar opens my profile, [onOpenProfile]) and one card
 * per module that is on — today's tasks, shopping lists, locating the
 * children (parents), "Bin angekommen" & SOS (children), next dates, place
 * reminders. [selected] marks the screen shown beside it on wide windows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FamilieHomeScreen(
    onOpen: (FamilieDest) -> Unit,
    modifier: Modifier = Modifier,
    selected: FamilieDest? = null,
    onOpenProfile: () -> Unit = {},
    viewModel: FamilieHomeViewModel = hiltViewModel(),
    avatarViewModel: MyAvatarViewModel = hiltViewModel(),
) {
    val myAvatar by avatarViewModel.ui.collectAsStateWithLifecycle()
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val profileError by viewModel.profileError.collectAsStateWithLifecycle()
    val profileLoaded by viewModel.profileLoaded.collectAsStateWithLifecycle()
    val overview by viewModel.overview.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val shopping by viewModel.shoppingView.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val layout by viewModel.layout.collectAsStateWithLifecycle()
    val access by viewModel.layoutAccess.collectAsStateWithLifecycle()
    val schule by viewModel.schule.collectAsStateWithLifecycle()
    val schoolSnapshot by viewModel.schoolSnapshot.collectAsStateWithLifecycle()
    val schoolError by viewModel.schoolError.collectAsStateWithLifecycle()
    val schoolInbox by viewModel.schoolInbox.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val errorText = message?.let { familieErrorText(it) }
    var arranging by rememberSaveable { mutableStateOf(false) }
    val lockedHint = stringResource(R.string.arrange_locked)
    val haptic = LocalHapticFeedback.current
    // Long press on a card or the menu: "Familie anordnen" (a hint instead when only the admin may).
    val openArrange: () -> Unit = {
        if (access.locked) {
            Toast.makeText(context, lockedHint, Toast.LENGTH_SHORT).show()
        } else {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            arranging = true
        }
    }

    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    LaunchedEffect(errorText) {
        if (errorText != null) {
            Toast.makeText(context, errorText, Toast.LENGTH_LONG).show()
            viewModel.clearMessage()
        }
    }

    val arrangeFor = profile
    if (arranging && arrangeFor != null) {
        val available = FamilieCards.available(arrangeFor, schule)
        FamilieArrangeScreen(
            profile = arrangeFor,
            order = FamilieLayout.merge(layout.familie, available),
            hidden = layout.familieHidden.toSet(),
            scope = access,
            onDone = { order, hidden ->
                viewModel.saveArrangement(order, hidden, available)
                arranging = false
            },
            modifier = modifier,
        )
        return
    }

    PullToRefreshBox(
        isRefreshing = loading && profile != null,
        onRefresh = viewModel::refresh,
        modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val p = profile
            Header(profile, onArrange = if (p != null && p.modules.any) openArrange else null)
            when {
                p == null && !profileLoaded -> FamilieLoading()
                p == null -> FamilieErrorBanner(profileError, onRetry = viewModel::refresh)
                !p.modules.any -> FamilieEmpty(GcIcons.Family, stringResource(R.string.familie_all_off))
                else -> {
                    val order = FamilieLayout.visible(FamilieLayout.merge(layout.familie, FamilieCards.available(p, schule)), layout.familieHidden)
                    HomeCards(p, order, overview, shopping, selected, myAvatar, onOpen, onOpenProfile, viewModel::taskDone, openArrange) {
                        SchoolCard(schoolSnapshot, schoolError, selected is FamilieDest.School, onOpen, schoolInbox)
                    }
                }
            }
            if (p != null && profileError == "offline") {
                FamilieErrorBanner("offline")
            }
        }
    }
}

@Composable
private fun Header(profile: FamilieProfile?, onArrange: (() -> Unit)?) {
    val extra = GateControlTheme.extraColors
    val locale = appLocale()
    var menu by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(GcIcons.Family, contentDescription = null, tint = extra.accentText, modifier = Modifier.size(26.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.familie_title), style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onBackground)
            val today = FamilieFormat.day(LocalDate.now(), locale)
            Text(
                if (profile != null) stringResource(R.string.familie_greeting, profile.me.name, today) else today,
                style = MaterialTheme.typography.bodyMedium,
                color = extra.muted,
            )
        }
        if (onArrange != null) {
            Box {
                GcIconButton(icon = StartIcons.More, contentDescription = stringResource(R.string.familie_menu), onClick = { menu = true })
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.familie_arrange)) },
                        leadingIcon = { Icon(StartIcons.Arrange, contentDescription = null, modifier = Modifier.size(20.dp)) },
                        onClick = { menu = false; onArrange() },
                    )
                }
            }
        }
    }
}

/**
 * The cards in the person's [order] (FamilieLayout ids, hidden ones left
 * out). A long press on any card opens "Familie anordnen" ([onArrange]).
 */
@Composable
private fun HomeCards(
    p: FamilieProfile,
    order: List<String>,
    overview: FamilieOverview?,
    shopping: ShoppingSnapshot,
    selected: FamilieDest?,
    myAvatar: MyAvatarUi,
    onOpen: (FamilieDest) -> Unit,
    onOpenProfile: () -> Unit,
    onTaskDone: (Int) -> Unit,
    onArrange: () -> Unit,
    schoolCard: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val arrangeLabel = stringResource(R.string.familie_arrange)
    order.forEach { id ->
        key(id) {
            Column(
                Modifier.fillMaxWidth().longPressAnywhere(arrangeLabel, onArrange),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                when (id) {
                    FamilieLayout.MEMBERS -> {
                        Members(p, overview, myAvatar, onOpenProfile)
                        // The rings show who is home; the details and the rules hint stay one tap away for parents.
                        if (p.modules.presence && p.me.isParent) PresenceLink(selected == FamilieDest.Presence) { onOpen(FamilieDest.Presence) }
                    }
                    FamilieLayout.CHILD -> {
                        val needsBackground = p.modules.locate && FamiliePermissions.childNeedsBackground(
                            p.me.locateMode,
                            DeviceLocator.hasPermission(context),
                            DeviceLocator.hasBackgroundPermission(context),
                        )
                        if (needsBackground) {
                            GcBanner(tone = GcTone.Warn, icon = GcIcons.Pin, modifier = Modifier.clickable { onOpen(FamilieDest.Child) }) {
                                Text(stringResource(R.string.familie_child_bg_needed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                        ChildCard(p, selected == FamilieDest.Child, onOpen)
                    }
                    FamilieLayout.EMERGENCY -> EmergencyEntry(selected is FamilieDest.Emergency, onOpen)
                    FamilieLayout.TASKS -> TodayCard(p, overview, selected == FamilieDest.Tasks, onOpen, onTaskDone)
                    FamilieLayout.RIDES -> RidesCard(p, overview, selected is FamilieDest.Rides, onOpen)
                    FamilieLayout.SHOPPING -> ShoppingCard(shopping, selected, onOpen)
                    FamilieLayout.LOCATE -> LocateCard(p, overview, selected, onOpen)
                    FamilieLayout.DATES -> DatesCard(overview, selected == FamilieDest.Dates, onOpen)
                    FamilieLayout.SCHOOL -> schoolCard()
                    FamilieLayout.MEALS -> MealsCard(p, overview, selected is FamilieDest.Meals, onOpen)
                    FamilieLayout.BOARD -> BoardCard(overview, selected == FamilieDest.Board, onOpen)
                    FamilieLayout.ALLOWANCE -> AllowanceCard(p, overview, selected is FamilieDest.Allowance, onOpen)
                    FamilieLayout.WISHES -> NavCard(
                        icon = GcIcons.Gift,
                        title = stringResource(R.string.familie_wishes_title),
                        subtitle = overview?.wishes?.takeIf { it.mine > 0 || it.reservedByMe > 0 }?.let { w ->
                            listOfNotNull(
                                w.mine.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.familie_wishes_home_mine, it, it) },
                                w.reservedByMe.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.familie_wishes_home_reserved, it, it) },
                            ).joinToString(" · ")
                        } ?: stringResource(R.string.familie_wishes_sub),
                        selected = selected is FamilieDest.Wishes,
                        onClick = { onOpen(FamilieDest.Wishes()) },
                    )
                    FamilieLayout.EXPENSES -> NavCard(
                        icon = GcIcons.Receipt,
                        title = stringResource(R.string.familie_expenses_title),
                        subtitle = overview?.expenses?.let { x ->
                            stringResource(R.string.familie_expenses_home, money(x.sharedCents)) +
                                if (x.settled) " · " + stringResource(R.string.familie_expenses_done_chip) else ""
                        } ?: stringResource(R.string.familie_expenses_sub),
                        selected = selected is FamilieDest.Expenses,
                        onClick = { onOpen(FamilieDest.Expenses()) },
                    )
                    FamilieLayout.PLACES -> NavCard(
                        icon = GcIcons.Pin,
                        title = stringResource(R.string.familie_places_title),
                        subtitle = stringResource(R.string.familie_places_sub),
                        selected = selected == FamilieDest.Places,
                        onClick = { onOpen(FamilieDest.Places) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Members(p: FamilieProfile, overview: FamilieOverview?, myAvatar: MyAvatarUi, onOpenProfile: () -> Unit) {
    val extra = GateControlTheme.extraColors
    val now = System.currentTimeMillis()
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FamilieMemberRow.ordered(p).forEach { m ->
            val isMe = m.userId == p.me.userId
            // Mockup: "im Laden" / "angekommen 13:10" under the avatar (last 3 hours).
            val going = overview?.going.orEmpty().filter { it.userId == m.userId }
                .mapNotNull { g -> FamilieFormat.parseInstant(g.at)?.takeIf { now - it.toEpochMilli() < RECENT_MS } }
                .maxOrNull()
            val arrived = overview?.arrivals.orEmpty().filter { it.userId == m.userId }
                .mapNotNull { a -> FamilieFormat.parseInstant(a.at)?.takeIf { now - it.toEpochMilli() < RECENT_MS } }
                .maxOrNull()
            val status = when {
                going != null && (arrived == null || going > arrived) -> stringResource(R.string.familie_member_shopping)
                arrived != null -> stringResource(R.string.familie_member_arrived, FamilieFormat.time(arrived))
                // Home / away: the ring alone shows it.
                else -> null
            }
            val profileDescription = if (isMe) stringResource(R.string.profile_avatar_desc, m.name) else null
            Column(
                Modifier
                    .width(72.dp)
                    .then(
                        if (isMe) {
                            Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable(role = Role.Button, onClickLabel = profileDescription, onClick = onOpenProfile)
                        } else {
                            Modifier
                        },
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FamilieMemberAvatar(
                    m, p, myAvatar,
                    // Others: the overview's presence when it has it (fresher), else the member object's.
                    serverState = overview?.presence?.firstOrNull { it.userId == m.userId }?.state,
                    size = 48.dp,
                    contentDescription = profileDescription,
                )
                Text(
                    if (m.age != null && m.isChild) "${m.name} · ${m.age}" else m.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    status ?: if (isMe) stringResource(R.string.familie_you)
                    else stringResource(if (m.isChild) R.string.familie_role_child else R.string.familie_role_parent),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (status != null) extra.accentText else extra.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Parents: a small link to the presence details (since when, who does not share, rules hint) — no card. */
@Composable
private fun PresenceLink(selected: Boolean, onClick: () -> Unit) {
    val extra = GateControlTheme.extraColors
    Row(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(GcIcons.Home, contentDescription = null, tint = if (selected) MaterialTheme.colorScheme.primary else extra.accentText, modifier = Modifier.size(16.dp))
        Text(
            stringResource(R.string.familie_presence_details_link),
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.primary else extra.accentText,
        )
    }
}

private const val RECENT_MS = 3 * 60 * 60_000L

/** Card with a title row (icon, title, optional link) — the mockup's sections. */
@Composable
fun FamilieSectionCard(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    link: String? = null,
    onLink: (() -> Unit)? = null,
    selected: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val extra = GateControlTheme.extraColors
    GcCard(
        modifier = modifier,
        borderColor = if (selected) MaterialTheme.colorScheme.primary else extra.border,
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (onLink != null) Modifier.clickable(role = Role.Button, onClick = onLink) else Modifier)
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(icon, contentDescription = null, tint = extra.accentText, modifier = Modifier.size(20.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            if (link != null) Text(link, style = MaterialTheme.typography.labelLarge, color = extra.accentText)
        }
        content()
    }
}

@Composable
private fun NavCard(icon: ImageVector, title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    GcCard(
        borderColor = if (selected) MaterialTheme.colorScheme.primary else GateControlTheme.extraColors.border,
        contentPadding = PaddingValues(0.dp),
        verticalArrangement = Arrangement.Top,
    ) {
        GcListRow(
            title = title,
            description = subtitle,
            onClick = onClick,
            leading = { Icon(icon, contentDescription = null, tint = GateControlTheme.extraColors.accentText, modifier = Modifier.size(22.dp)) },
            trailing = { GcChevron() },
            selected = selected,
        )
    }
}

@Composable
private fun TodayCard(p: FamilieProfile, overview: FamilieOverview?, selected: Boolean, onOpen: (FamilieDest) -> Unit, onTaskDone: (Int) -> Unit) {
    val extra = GateControlTheme.extraColors
    FamilieSectionCard(
        title = stringResource(R.string.familie_today_title),
        icon = GcIcons.Tasks,
        link = stringResource(R.string.familie_all_tasks),
        onLink = { onOpen(FamilieDest.Tasks) },
        selected = selected,
    ) {
        val stars = overview?.stars
        if (p.me.isChild && p.modules.stars && stars != null) {
            Text(
                pluralStringResource(R.plurals.familie_stars_week, stars.goal, stars.week, stars.goal),
                style = MaterialTheme.typography.bodyMedium,
                color = extra.accentText,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        val mine = overview?.myTasks.orEmpty()
        if (mine.isEmpty()) {
            Text(
                stringResource(if (overview == null) R.string.familie_loading_short else R.string.familie_no_tasks_today),
                style = MaterialTheme.typography.bodyMedium,
                color = extra.muted,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        } else {
            mine.take(5).forEach { t -> TaskLine(t, p, onDone = { onTaskDone(t.id) }) }
        }
    }
}

@Composable
private fun TaskLine(t: FamilieTask, p: FamilieProfile, onDone: () -> Unit) {
    val extra = GateControlTheme.extraColors
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GcIconButton(
            icon = if (t.doneToday) GcIcons.Check else GcIcons.Minus,
            contentDescription = stringResource(if (t.doneToday) R.string.familie_task_done_cd else R.string.familie_task_mark_done_cd, t.title),
            onClick = { if (!t.doneToday) onDone() },
            tint = if (t.doneToday) MaterialTheme.colorScheme.primary else extra.faint,
            enabled = !t.doneToday,
        )
        Column(Modifier.weight(1f)) {
            Text(t.title, style = MaterialTheme.typography.titleSmall, color = if (t.doneToday) extra.muted else MaterialTheme.colorScheme.onSurface)
            Text(taskMeta(t, p), style = MaterialTheme.typography.bodySmall, color = extra.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ShoppingCard(view: ShoppingSnapshot, selected: FamilieDest?, onOpen: (FamilieDest) -> Unit) {
    val extra = GateControlTheme.extraColors
    val counts = ShoppingLogic.openCounts(view)
    FamilieSectionCard(
        title = stringResource(R.string.familie_shopping_title),
        icon = GcIcons.Cart,
        onLink = { onOpen(FamilieDest.Shopping()) },
        link = stringResource(R.string.familie_open),
        selected = selected is FamilieDest.Shopping,
    ) {
        if (view.lists.isEmpty()) {
            Text(
                stringResource(R.string.familie_shopping_none),
                style = MaterialTheme.typography.bodyMedium,
                color = extra.muted,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        view.lists.forEachIndexed { i, list ->
            if (i > 0) GcRowDivider()
            val open = counts[list.id] ?: 0
            val preview = view.items.filter { it.listId == list.id && !it.checked }.take(3).joinToString(", ") { it.text }
            GcListRow(
                title = list.name,
                description = if (open == 0) stringResource(R.string.familie_shopping_all_done) else preview,
                onClick = { onOpen(FamilieDest.Shopping(list.id)) },
                trailing = { GcChip(pluralStringResource(R.plurals.familie_shopping_open_count, open, open), tone = if (open > 0) GcTone.Ok else GcTone.Neutral, small = true) },
                selected = (selected as? FamilieDest.Shopping)?.listId == list.id,
            )
        }
    }
}

@Composable
private fun LocateCard(p: FamilieProfile, overview: FamilieOverview?, selected: FamilieDest?, onOpen: (FamilieDest) -> Unit) {
    val extra = GateControlTheme.extraColors
    FamilieSectionCard(title = stringResource(R.string.familie_locate_title), icon = GcIcons.Crosshair) {
        p.children.forEachIndexed { i, child ->
            if (i > 0) GcRowDivider()
            val last = overview?.lastLocates?.firstOrNull { it.childId == child.userId }
            val disabled = child.locatable == false || child.locateMode == LocateMode.OFF
            GcListRow(
                title = child.name,
                description = when {
                    disabled -> stringResource(R.string.familie_locate_disabled)
                    last != null -> locateStatusLine(last.status, last.at)
                    else -> stringResource(R.string.familie_locate_never)
                },
                leading = { FamilieAvatar(child.name, child.color, face = child.face) },
                onClick = { onOpen(FamilieDest.Locate(child.userId)) },
                trailing = {
                    if (!disabled) GcChip(stringResource(R.string.familie_locate_action), tone = GcTone.Ok, small = true)
                    else Text("–", color = extra.faint)
                },
                selected = (selected as? FamilieDest.Locate)?.childId == child.userId,
            )
        }
    }
}

@Composable
private fun ChildCard(p: FamilieProfile, selected: Boolean, onOpen: (FamilieDest) -> Unit) {
    FamilieSectionCard(
        title = stringResource(R.string.familie_child_title),
        icon = GcIcons.Phone,
        link = stringResource(R.string.familie_open),
        onLink = { onOpen(FamilieDest.Child) },
        selected = selected,
    ) {
        Text(
            when (p.me.locateMode) {
                LocateMode.AUTO -> stringResource(R.string.familie_child_mode_auto)
                LocateMode.CONFIRM -> stringResource(R.string.familie_child_mode_confirm)
                else -> stringResource(R.string.familie_child_mode_off)
            },
            style = MaterialTheme.typography.bodySmall,
            color = GateControlTheme.extraColors.muted,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        if (p.modules.arrivalSos) {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GcOutlineButton(
                    text = stringResource(R.string.familie_arrived),
                    onClick = { onOpen(FamilieDest.Child) },
                    icon = GcIcons.Flag,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(24.dp))
                        .background(MaterialTheme.colorScheme.error)
                        .clickable(role = Role.Button) { onOpen(FamilieDest.Child) }
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.familie_sos_short), color = MaterialTheme.colorScheme.onError, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun DatesCard(overview: FamilieOverview?, selected: Boolean, onOpen: (FamilieDest) -> Unit) {
    val extra = GateControlTheme.extraColors
    FamilieSectionCard(
        title = stringResource(R.string.familie_dates_title),
        icon = GcIcons.Calendar,
        link = stringResource(R.string.familie_open),
        onLink = { onOpen(FamilieDest.Dates) },
        selected = selected,
    ) {
        val next = overview?.nextDates.orEmpty()
        if (next.isEmpty()) {
            Text(
                stringResource(if (overview == null) R.string.familie_loading_short else R.string.familie_dates_none_soon),
                style = MaterialTheme.typography.bodyMedium,
                color = extra.muted,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        next.take(3).forEach { e ->
            GcListRow(
                title = e.title,
                description = listOfNotNull(relativeDay(e.date), e.time, e.note.takeIf { it.isNotBlank() }).joinToString(" · "),
                leading = { Icon(if (e.isBirthday) GcIcons.Star else GcIcons.Calendar, contentDescription = null, tint = extra.accentText, modifier = Modifier.size(20.dp)) },
                onClick = { onOpen(FamilieDest.Dates) },
            )
        }
    }
}

/** Abholen & Fahrten (1.1): the next rides (24 h), how many are open; children: "Bitte abholen". */
@Composable
private fun RidesCard(p: FamilieProfile, overview: FamilieOverview?, selected: Boolean, onOpen: (FamilieDest) -> Unit) {
    val extra = GateControlTheme.extraColors
    val rides = overview?.rides
    FamilieSectionCard(
        title = stringResource(R.string.familie_rides_title),
        icon = GcIcons.Car,
        link = stringResource(R.string.familie_open),
        onLink = { onOpen(FamilieDest.Rides()) },
        selected = selected,
    ) {
        if (rides != null && rides.open > 0) {
            GcChip(
                pluralStringResource(R.plurals.familie_rides_open_count, rides.open, rides.open),
                tone = GcTone.Warn,
                small = true,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        val next = rides?.next.orEmpty()
        if (next.isEmpty()) {
            Text(
                stringResource(if (overview == null) R.string.familie_loading_short else R.string.familie_rides_home_none),
                style = MaterialTheme.typography.bodyMedium,
                color = extra.muted,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        next.take(3).forEach { r ->
            val nobody = r.isOpen && r.driver == null
            GcListRow(
                title = r.title,
                description = rideWhen(r) + " · " + rideDriverLine(r, p.me),
                leading = { Icon(GcIcons.Car, contentDescription = null, tint = if (nobody) extra.warn else extra.accentText, modifier = Modifier.size(20.dp)) },
                onClick = { onOpen(FamilieDest.Rides(r.id)) },
            )
        }
        if (!p.me.isParent) {
            GcOutlineButton(
                text = stringResource(R.string.familie_ride_request),
                onClick = { onOpen(FamilieDest.Rides()) },
                icon = GcIcons.Car,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}

/** Notfall-Karte entry (1.1): always reachable, also offline. */
@Composable
private fun EmergencyEntry(selected: Boolean, onOpen: (FamilieDest) -> Unit) {
    GcCard(
        borderColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error.copy(alpha = 0.35f),
        contentPadding = PaddingValues(0.dp),
        verticalArrangement = Arrangement.Top,
    ) {
        GcListRow(
            title = stringResource(R.string.familie_emergency_title),
            description = stringResource(R.string.familie_emergency_sub),
            onClick = { onOpen(FamilieDest.Emergency()) },
            leading = { Icon(GcIcons.Medical, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(22.dp)) },
            trailing = { GcChevron() },
            selected = selected,
        )
    }
}

/** "Lena · reihum · bis 18:00 · 2 ★" */
@Composable
fun taskMeta(t: FamilieTask, p: FamilieProfile): String {
    val who = t.responsible().mapNotNull { p.nameOf(it) }.joinToString(", ")
    val mode = when (t.mode) {
        FamilieTask.MODE_ROTATE -> stringResource(R.string.familie_task_mode_rotate)
        FamilieTask.MODE_FIRST -> stringResource(R.string.familie_task_mode_first)
        else -> null
    }
    val due = t.dueTime?.let { stringResource(R.string.familie_task_until, it) }
    val stars = if (t.stars > 0) "${t.stars} ★" else null
    return listOfNotNull(who.ifBlank { null }, mode, due, stars).joinToString(" · ")
}

/** "heute 13:10 · gesendet" etc. for the last locate request of a child. */
@Composable
fun locateStatusLine(status: String, at: String?): String {
    val stamp = relativeStamp(at)
    val text = when (status) {
        LocateRequest.ANSWERED -> stringResource(R.string.familie_locate_status_answered)
        LocateRequest.PENDING -> stringResource(R.string.familie_locate_status_pending)
        LocateRequest.DECLINED -> stringResource(R.string.familie_locate_status_declined)
        LocateRequest.FAILED -> stringResource(R.string.familie_locate_status_failed)
        LocateRequest.EXPIRED -> stringResource(R.string.familie_locate_status_expired)
        else -> status
    }
    return "$stamp · $text"
}

/** Member name or a fallback. */
fun memberName(p: FamilieProfile?, id: Int?): String = p?.nameOf(id) ?: (id?.let { "#$it" } ?: "–")

/** Essensplan (1.2): "Essen: Pfannkuchen" for today. */
@Composable
private fun MealsCard(p: FamilieProfile, overview: FamilieOverview?, selected: Boolean, onOpen: (FamilieDest) -> Unit) {
    val today = overview?.meals
    val meals = listOfNotNull(today?.lunch, today?.dinner)
    NavCard(
        icon = GcIcons.Meal,
        title = stringResource(R.string.familie_meals_title),
        subtitle = when {
            today == null -> stringResource(R.string.familie_meals_sub)
            meals.isEmpty() -> stringResource(R.string.familie_meals_today_none)
            else -> stringResource(R.string.familie_meals_today, meals.joinToString(" · ") { m ->
                m.title + (m.cook?.let { " (" + memberName(p, it) + ")" } ?: "")
            })
        },
        selected = selected,
        onClick = { onOpen(FamilieDest.Meals()) },
    )
}

/** Pinnwand (1.2): pinned notes, open lists. */
@Composable
private fun BoardCard(overview: FamilieOverview?, selected: Boolean, onOpen: (FamilieDest) -> Unit) {
    val extra = GateControlTheme.extraColors
    val board = overview?.board
    FamilieSectionCard(
        title = stringResource(R.string.familie_board_title),
        icon = GcIcons.Board,
        link = stringResource(R.string.familie_open),
        onLink = { onOpen(FamilieDest.Board) },
        selected = selected,
    ) {
        board?.pinnedTexts.orEmpty().forEach { t ->
            Text(
                t,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        val parts = listOfNotNull(
            board?.pinned?.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.familie_board_home_pinned, it, it) },
            board?.openChecklists?.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.familie_board_home_lists, it, it) },
        )
        Text(
            if (parts.isEmpty()) stringResource(R.string.familie_board_sub) else parts.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = extra.muted,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}

/** Taschengeld (1.2): a child's balance, for parents the sum and open convert requests. */
@Composable
private fun AllowanceCard(p: FamilieProfile, overview: FamilieOverview?, selected: Boolean, onOpen: (FamilieDest) -> Unit) {
    val a = overview?.allowance
    GcCard(
        borderColor = if (selected) MaterialTheme.colorScheme.primary else GateControlTheme.extraColors.border,
        contentPadding = PaddingValues(0.dp),
        verticalArrangement = Arrangement.Top,
    ) {
        GcListRow(
            title = stringResource(if (p.me.isChild) R.string.familie_allowance_mine else R.string.familie_allowance_title),
            description = when {
                a == null -> stringResource(R.string.familie_money_bookkeeping)
                p.me.isChild -> money(a.cents) + (a.nextPayout?.takeIf { a.amountCents > 0 }?.let { " · " + stringResource(R.string.familie_allowance_home_next, relativeDay(it), money(a.amountCents)) } ?: "")
                else -> stringResource(R.string.familie_allowance_sum, money(a.cents))
            },
            onClick = { onOpen(FamilieDest.Allowance()) },
            leading = { Icon(GcIcons.Wallet, contentDescription = null, tint = GateControlTheme.extraColors.accentText, modifier = Modifier.size(22.dp)) },
            trailing = {
                if (a != null && a.pending > 0 && p.me.isParent) {
                    GcChip(pluralStringResource(R.plurals.familie_allowance_home_pending, a.pending, a.pending), tone = GcTone.Warn, small = true)
                } else {
                    GcChevron()
                }
            },
            selected = selected,
        )
    }
}

