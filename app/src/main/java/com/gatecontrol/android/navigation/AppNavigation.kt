package com.gatecontrol.android.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import com.gatecontrol.android.ui.adaptive.GcTwoPane
import com.gatecontrol.android.ui.adaptive.GcWindowLayout
import com.gatecontrol.android.ui.adaptive.LocalGcWindowLayout
import com.gatecontrol.android.ui.adaptive.rememberGcWindowLayout
import com.gatecontrol.android.ui.notifications.NotificationDetailViewModel
import com.gatecontrol.android.ui.settings.SettingsPane
import com.gatecontrol.android.ui.settings.SettingsViewModel
import kotlinx.coroutines.flow.map
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import com.gatecontrol.android.R
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.network.NetworkScreen
import com.gatecontrol.android.ui.network.NetworkTabs
import com.gatecontrol.android.ui.infocard.InfoCardPickerScreen
import com.gatecontrol.android.ui.settings.ConnectionDetailsScreen
import com.gatecontrol.android.ui.settings.LogsScreen
import com.gatecontrol.android.ui.profile.ProfileScreen
import com.gatecontrol.android.ui.rules.RulesRoutes
import com.gatecontrol.android.ui.rules.rulesGraph
import com.gatecontrol.android.ui.settings.ServerSettingsScreen
import com.gatecontrol.android.ui.settings.SettingsScreen
import com.gatecontrol.android.ui.settings.SplitTunnelScreen
import com.gatecontrol.android.ui.settings.TrustedNetworksScreen
import com.gatecontrol.android.ui.location.LocationSharingScreen
import com.gatecontrol.android.ui.diagnosis.DiagnosisScreen
import com.gatecontrol.android.ui.quickactions.QuickActionsScreen
import com.gatecontrol.android.ui.setup.QrScannerScreen
import com.gatecontrol.android.ui.setup.SetupScreen
import com.gatecontrol.android.ui.theme.GateControlTheme
import com.gatecontrol.android.ui.vpn.VpnScreen
import com.gatecontrol.android.door.DoorDest
import com.gatecontrol.android.familie.FamilieDest
import com.gatecontrol.android.ui.door.DoorHomeScreen
import com.gatecontrol.android.ui.door.DoorPage
import com.gatecontrol.android.ui.door.DoorTwoPane
import com.gatecontrol.android.familie.PluginAvailability
import com.gatecontrol.android.ui.familie.FamilieHomeScreen
import com.gatecontrol.android.ui.familie.FamiliePage
import com.gatecontrol.android.ui.familie.FamilieTwoPane
import com.gatecontrol.android.ui.logbook.LogbookMapScreen
import com.gatecontrol.android.ui.logbook.LogbookScreen
import com.gatecontrol.android.ui.logbook.LogbookTripScreen
import com.gatecontrol.android.ui.logbook.LogbookTwoPane
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.push.PushNavTarget
import com.gatecontrol.android.push.PushNavigation
import com.gatecontrol.android.ui.notifications.InboxScreen
import com.gatecontrol.android.ui.notifications.InboxViewModel
import com.gatecontrol.android.ui.notifications.NotificationDetailScreen
import com.gatecontrol.android.ui.notifications.NotificationSettingsScreen
import com.gatecontrol.android.ui.notifications.NotifyRoutesScreen
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.gatecontrol.android.ui.notifications.notificationsAllowed
import com.gatecontrol.android.ui.notifications.bellState
import com.gatecontrol.android.ui.chat.ChatBadgeViewModel
import com.gatecontrol.android.ui.chat.ChatSecureGate
import com.gatecontrol.android.ui.chat.settings.ChatSettingsPage
import com.gatecontrol.android.ui.chat.settings.ChatSettingsScreen
import com.gatecontrol.android.ui.chat.status.StatusSheet
import com.gatecontrol.android.ui.chat.status.StoryComposerScreen
import com.gatecontrol.android.ui.chat.status.StoryViewerScreen
import com.gatecontrol.android.ui.chat.ChatImageViewer
import com.gatecontrol.android.ui.chat.ChatInfoScreen
import com.gatecontrol.android.ui.chat.ChatListScreen
import com.gatecontrol.android.ui.chat.ChatMediaScreen
import com.gatecontrol.android.ui.chat.ChatsTwoPane
import com.gatecontrol.android.ui.chat.ConversationScreen
import com.gatecontrol.android.ui.chat.NewChatScreen
import com.gatecontrol.android.chat.ChatShareHandover
import com.gatecontrol.android.ui.gatedrop.SharedScreen
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.gatecontrol.android.layout.StartPages
import com.gatecontrol.android.ui.start.LocalPageSnack
import com.gatecontrol.android.ui.start.START_PAGE
import com.gatecontrol.android.ui.start.StartPageRequest
import com.gatecontrol.android.ui.start.StartPager
import com.gatecontrol.android.ui.start.StartPagesArrangeScreen
import com.gatecontrol.android.ui.start.StartPagesSettingsScreen
import com.gatecontrol.android.ui.start.StartPagesViewModel
import com.gatecontrol.android.ui.start.startPageLabel
import kotlinx.coroutines.launch

private data class TabItem(
    val route: String,
    val navigateTo: String,
    val label: Int,
    val icon: ImageVector,
    val badge: Int = 0,
    /** The page id when the tab can be pinned to Start (all but Start, StartPages). */
    val page: String? = MainTabs.pageOf(route),
)

@Composable
fun AppNavigation(
    navController: NavHostController,
    isSetupComplete: Boolean,
    hasRdpPermission: Boolean,
    hasServicesPermission: Boolean,
    hasPiholePermission: Boolean,
    onlineRdpHostCount: Int = 0,
    /** What the server's plugins offer (Familie tab), cached on the device. */
    plugins: PluginAvailability = PluginAvailability.NONE,
    /** gatecontrol://enroll link the app was opened with; consumed once shown. */
    pendingSetupLink: String? = null,
    onSetupLinkConsumed: () -> Unit = {},
) {
    val startDestination = if (isSetupComplete) Screen.Vpn.route else Screen.Setup.route

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // Pages beside Start (StartPages): functions pinned there leave the bar.
    val pagesViewModel: StartPagesViewModel = hiltViewModel()
    val allPages by pagesViewModel.pages.collectAsStateWithLifecycle()
    val pagesAccess by pagesViewModel.access.collectAsStateWithLifecycle()
    val hideBarSetting by pagesViewModel.hideBar.collectAsStateWithLifecycle()
    val tabRoutes = MainTabs.routes(
        network = MainTabs.hasNetwork(services = hasServicesPermission, pihole = hasPiholePermission, rdp = hasRdpPermission),
        plugins = plugins,
    )
    /** The functions this login has besides Start (tabs or pages). */
    val features = MainTabs.features(tabRoutes)
    /** The pages shown beside Start, in their order. */
    val startPages = StartPages.visible(allPages, features)
    val pinnedFeatures = startPages.filter { StartPages.isFeature(it) }
    val barRoutes = MainTabs.visible(tabRoutes, pinnedFeatures)
    val barHidden = MainTabs.hideBar(barRoutes, startPages, hideBarSetting)
    val pinnedNow by rememberUpdatedState(pinnedFeatures)
    /** The page of Start to show next (a pinned tab's route asked for, Start tapped). */
    var pageRequest by remember { mutableStateOf<StartPageRequest?>(null) }
    /** The last request per page (its route's arguments stay with the page). */
    var pageRequests by remember { mutableStateOf(emptyMap<String, StartPageRequest>()) }
    val requestPage: (StartPageRequest) -> Unit = { request ->
        pageRequest = request
        pageRequests = pageRequests + (request.page to request)
    }

    val showBottomBar = currentRoute in MainTabs.BAR_ROUTES && !barHidden

    /** To Start, showing [page] of it (null: whichever page it shows). */
    val showStart: (StartPageRequest?) -> Unit = { request ->
        if (request != null) requestPage(request)
        navController.navigate(Screen.Vpn.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    // A tab pinned to Start opens as its page there (the route's arguments go along).
    val navigateToTab: (String) -> Unit = { route ->
        val page = MainTabs.pageFor(route)?.takeIf { it in pinnedNow }
        if (page != null) {
            showStart(StartPageRequest(page, route))
        } else {
            navController.navigate(route) {
                popUpTo(navController.graph.findStartDestination().id) {
                    saveState = true
                }
                launchSingleTop = true
                restoreState = true
            }
        }
    }
    /** A tab's route with arguments that must win over a saved state (no restoreState); a pinned one opens its page. */
    val openTab: (String) -> Unit = { route ->
        val page = MainTabs.pageFor(route)?.takeIf { it in pinnedNow }
        if (page != null) {
            showStart(StartPageRequest(page, route))
        } else {
            navController.navigate(route) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
            }
        }
    }
    /** A pinned tab's route was opened anyway (an older path): its page instead. */
    @Composable
    fun RedirectToPage(page: String, route: String) {
        LaunchedEffect(page, route) {
            requestPage(StartPageRequest(page, route))
            navController.navigate(Screen.Vpn.route) {
                popUpTo(Screen.Vpn.route) { inclusive = false }
                launchSingleTop = true
            }
        }
    }
    /** Start tapped in the bar: its first page, also when Start is shown already. */
    val goToStart: () -> Unit = { showStart(StartPageRequest(START_PAGE)) }

    // Pinning, taking off: a snackbar with "Rückgängig" (also from the settings).
    val snackbarHostState = remember { SnackbarHostState() }
    val snackScope = rememberCoroutineScope()
    val undoLabel = stringResource(R.string.pages_undo)
    val showSnack: (String, (() -> Unit)?) -> Unit = { message, undo ->
        snackScope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            val result = snackbarHostState.showSnackbar(
                message,
                actionLabel = if (undo != null) undoLabel else null,
                withDismissAction = undo == null,
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) undo?.invoke()
        }
    }
    val resources = LocalResources.current
    /** Long press on a tab → "An Start anheften": the page, shown on Start. */
    val pinFromBar: (String) -> Unit = { page ->
        pagesViewModel.pin(page) { undo -> showSnack(resources.getString(R.string.pages_snack_pinned, startPageLabel(resources, page)), undo) }
        showStart(StartPageRequest(page))
    }

    // The plugin's own tab icon (SVG path), else the house.
    val familieIcon = remember(plugins.familieIcon) {
        plugins.familieIcon?.let { path -> runCatching { GcIcons.fromPath("FamilieTab", path) }.getOrNull() } ?: GcIcons.Family
    }
    // Unread chats (messenger plugin) as the badge of the tab "Chats".
    val chatBadge = if (plugins.messengerTab) {
        val badgeViewModel: ChatBadgeViewModel = hiltViewModel()
        badgeViewModel.badge.collectAsStateWithLifecycle().value
    } else {
        0
    }
    // Start, Chats (messenger plugin), Netzwerk (Dienste, Pi-hole, Remote),
    // the plugin tab Familie (docs/FAMILIE.md). The door plugin has no tab
    // (docs/DOOR.md), the settings open from the profile (gear top right).
    val tabs = barRoutes.map { route ->
        when (route) {
            Screen.Network.route -> TabItem(route, Screen.Network.create(), R.string.nav_network, GcIcons.Globe, onlineRdpHostCount)
            Screen.Familie.route -> TabItem(route, Screen.Familie.create(), R.string.nav_familie, familieIcon)
            Screen.Chats.route -> TabItem(route, Screen.Chats.create(), R.string.nav_chats, Icons.AutoMirrored.Outlined.Chat, chatBadge)
            Screen.Vault.route -> TabItem(route, Screen.Vault.route, R.string.vault_tab, GcIcons.Vault)
            Screen.Drive.route -> TabItem(route, Screen.Drive.route, R.string.drive_tab, GcIcons.Cloud)
            Screen.Mail.route -> TabItem(route, Screen.Mail.route, R.string.mail_tab, GcIcons.Mail)
            else -> TabItem(route, Screen.Vpn.route, R.string.nav_start, GcIcons.Shield)
        }
    }

    // Tablet, unfolded foldable, phone in landscape: rail instead of the
    // bottom bar, list and detail side by side (docs/LARGE_SCREENS.md).
    val layout = rememberGcWindowLayout()

    /**
     * A settings page: beside the settings list on wide screens, else its
     * own route. The settings are a normal destination with back (no tab
     * since 1.35); the profile is the way in, so it opens on its own.
     */
    val openSettingsPane: (SettingsPane) -> Unit = { pane ->
        when {
            pane == SettingsPane.Profile -> navController.navigate(Screen.Profile.route) { launchSingleTop = true }
            layout.twoPane -> navController.navigate(Screen.Settings.create(pane.key)) { launchSingleTop = true }
            else -> navController.navigate(pane.route()) { launchSingleTop = true }
        }
    }
    /** The settings list (gear on the profile). */
    val openSettings: () -> Unit = { navController.navigate(Screen.Settings.create()) { launchSingleTop = true } }
    /** Settings → Profil: back to the profile when the settings were opened from it, else the profile on top. */
    val openProfileFromSettings: () -> Unit = {
        if (!navController.popBackStack(Screen.Profile.route, inclusive = false)) {
            navController.navigate(Screen.Profile.route) { launchSingleTop = true }
        }
    }
    /** Door screen "Tür & Kamera" (door plugin listed only): long press on a door quick action. */
    val openDoor: (() -> Unit)? = if (plugins.door) {
        { navController.navigate(Screen.Door.create()) { launchSingleTop = true } }
    } else {
        null
    }
    val isTabSelected: (TabItem) -> Boolean = { tab ->
        navBackStackEntry?.destination?.hierarchy?.any { it.route == tab.route } == true
    }

    // A tab's screen: as the tab (its route) or as a page beside Start (StartPager), the same either way.
    @Composable
    fun NetworkTab(initialTab: String?) {
        GcContentWidth(maxWidth = 840.dp) {
            NetworkScreen(
                hasServices = hasServicesPermission,
                hasPihole = hasPiholePermission,
                hasRemote = hasRdpPermission,
                initialTab = initialTab,
                onGoToStart = goToStart,
            )
        }
    }

    @Composable
    fun FamilieTab(dest: FamilieDest?) {
        if (layout.twoPane) {
            FamilieTwoPane(initial = dest, onOpenProfile = { openSettingsPane(SettingsPane.Profile) })
        } else {
            GcContentWidth {
                FamilieHomeScreen(
                    onOpen = { navController.navigate(Screen.FamiliePage.create(it.key)) },
                    onOpenProfile = { openSettingsPane(SettingsPane.Profile) },
                )
            }
        }
    }

    @Composable
    fun ChatsTab(conv: Long?, share: Boolean) {
        val openInbox: () -> Unit = { navController.navigate(Screen.Inbox.create()) { launchSingleTop = true } }
        val newChat: () -> Unit = { navController.navigate(Screen.ChatNew.create()) { launchSingleTop = true } }
        val chatSettings: () -> Unit = { navController.navigate(Screen.ChatSettings.create()) { launchSingleTop = true } }
        val openStory: (Long) -> Unit = { u -> navController.navigate(Screen.ChatStoryView.create(u)) { launchSingleTop = true } }
        val storyComposer: () -> Unit = { navController.navigate(Screen.ChatStoryCompose.create()) { launchSingleTop = true } }
        ChatSecureGate {
            if (layout.twoPane) {
                ChatsTwoPane(
                    initial = conv,
                    share = share,
                    onOpenInbox = openInbox,
                    onNewChat = newChat,
                    onOpenUpdates = openSettings,
                    onOpenInfo = { id -> navController.navigate(Screen.ChatInfo.create(id)) { launchSingleTop = true } },
                    onOpenImage = { media -> navController.navigate(Screen.ChatImage.create(media)) },
                    onOpenSettings = chatSettings,
                    onOpenStory = openStory,
                    onOpenStoryComposer = storyComposer,
                )
            } else {
                GcContentWidth {
                    ChatListScreen(
                        onOpen = { id ->
                            val sharing = share || ChatShareHandover.pending.value != null
                            navController.navigate(Screen.ChatConversation.create(id, sharing)) { launchSingleTop = true }
                        },
                        onOpenInbox = openInbox,
                        onNewChat = newChat,
                        onOpenUpdates = openSettings,
                        onOpenSettings = chatSettings,
                        onOpenStory = openStory,
                        onOpenStoryComposer = storyComposer,
                    )
                }
            }
        }
    }

    /**
     * A function's page beside Start: its tab's screen. [route] is the
     * tab's route last asked for (a push, a deep link) with its arguments;
     * a new one starts the screen afresh with them.
     */
    @Composable
    fun FeatureTab(id: String, route: String?) {
        when (id) {
            StartPages.NETWORK -> NetworkTab(route?.let { MainTabs.arg(it, Screen.Network.ARG_TAB) })
            StartPages.FAMILIE -> FamilieTab(FamilieDest.parse(route?.let { MainTabs.arg(it, Screen.Familie.ARG_DEST) }))
            StartPages.CHATS -> ChatsTab(
                conv = route?.let { MainTabs.arg(it, Screen.Chats.ARG_CONV) }?.toLongOrNull(),
                share = route?.let { MainTabs.arg(it, Screen.Chats.ARG_SHARE) } == "true",
            )
            StartPages.VAULT -> VaultHome(navController)
            StartPages.DRIVE -> DriveHome(navController, onShown = {})
            StartPages.MAIL -> MailHome(navController)
        }
    }

    val selectTab: (TabItem) -> Unit = { tab -> if (tab.route == Screen.Vpn.route) goToStart() else navigateToTab(tab.navigateTo) }
    val tabMenu = TabMenuActions(
        locked = pagesAccess.locked,
        onPin = pinFromBar,
        onSettings = { openSettingsPane(SettingsPane.Pages) },
    )

    CompositionLocalProvider(LocalGcWindowLayout provides layout, LocalPageSnack provides showSnack) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (showBottomBar && !layout.useRail) {
                GcTabBar(
                    tabs = tabs,
                    isSelected = isTabSelected,
                    onSelect = selectTab,
                    menu = tabMenu,
                )
            }
        },
    ) { innerPadding ->
        Row(Modifier.padding(innerPadding).fillMaxSize()) {
        if (showBottomBar && layout.useRail) {
            GcNavRail(
                tabs = tabs,
                isSelected = isTabSelected,
                onSelect = selectTab,
                menu = tabMenu,
            )
        }
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        ) {
            composable(Screen.Setup.route) { backStackEntry ->
                val qrResult = backStackEntry.savedStateHandle.get<String>("qr_result")
                GcContentWidth { SetupScreen(
                    onSetupComplete = {
                        navController.navigate(Screen.Vpn.route) {
                            popUpTo(Screen.Setup.route) { inclusive = true }
                        }
                    },
                    onNavigateToQr = {
                        navController.navigate(Screen.QrScanner.route)
                    },
                    qrResult = qrResult,
                ) }
            }

            composable(
                route = Screen.Enroll.route,
                arguments = listOf(
                    navArgument(Screen.Enroll.ARG_LINK) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { backStackEntry ->
                // A link opened from outside the app (camera, mail) arrives as
                // an argument; a scan from the in-app scanner via savedStateHandle.
                val openedLink = backStackEntry.arguments?.getString(Screen.Enroll.ARG_LINK)
                val qrResult = backStackEntry.savedStateHandle.get<String>("qr_result") ?: openedLink
                GcContentWidth { SetupScreen(
                    onSetupComplete = {
                        navController.navigate(Screen.Vpn.route) {
                            popUpTo(navController.graph.startDestinationId) { inclusive = true }
                        }
                    },
                    onNavigateToQr = {
                        navController.navigate(Screen.QrScanner.route)
                    },
                    qrResult = qrResult,
                    upgradeMode = true,
                    autoOpenScanner = openedLink == null,
                ) }
            }

            composable(Screen.QrScanner.route) {
                QrScannerScreen(
                    onQrScanned = { scannedData ->
                        // Pass scanned data back via savedStateHandle
                        navController.previousBackStackEntry
                            ?.savedStateHandle
                            ?.set("qr_result", scannedData)
                        navController.popBackStack()
                    },
                    onBack = {
                        navController.popBackStack()
                    },
                )
            }

            composable(Screen.Vpn.route) {
                val inboxViewModel: InboxViewModel = hiltViewModel()
                val unread by inboxViewModel.unreadCount.collectAsStateWithLifecycle()
                val bellInputs by inboxViewModel.bellInputs.collectAsStateWithLifecycle()
                val context = LocalContext.current
                var systemAllowed by remember { mutableStateOf(true) }
                LifecycleResumeEffect(Unit) {
                    systemAllowed = notificationsAllowed(context)
                    onPauseOrDispose { }
                }
                val bell = bellState(bellInputs?.first, bellInputs?.second ?: true, systemAllowed)
                StartPager(
                    pages = startPages,
                    candidates = features.filter { it !in allPages },
                    badges = mapOf(StartPages.CHATS to chatBadge, StartPages.NETWORK to onlineRdpHostCount),
                    request = pageRequest,
                    requests = pageRequests,
                    names = barHidden,
                    onArrangePages = { navController.navigate(Screen.StartPagesArrange.route) { launchSingleTop = true } },
                    onOpenQuickActions = { page -> navController.navigate(Screen.QuickActions.create(page)) { launchSingleTop = true } },
                    onNewReminder = { navController.navigate(Screen.ReminderNew.create(it)) { launchSingleTop = true } },
                    onOpenDoor = openDoor,
                    // A new request for the page starts its screen afresh with the route's arguments.
                    feature = { id, request -> key(request?.seq) { FeatureTab(id, request?.route) } },
                    start = { onArrange ->
                        VpnScreen(
                            unreadCount = unread,
                            bell = bell,
                            // Nothing arrives yet: the bell leads to where notifications are switched on.
                            onOpenInbox = {
                                if (bell.pushOff) openSettingsPane(SettingsPane.Notifications)
                                else navController.navigate(Screen.Inbox.create()) { launchSingleTop = true }
                            },
                            onEnableNotifications = { openSettingsPane(SettingsPane.Notifications) },
                            onDismissPushHint = inboxViewModel::dismissStartHint,
                            onTokenInvalid = {
                                navController.navigate(Screen.Setup.route) {
                                    popUpTo(navController.graph.startDestinationId) { inclusive = true }
                                }
                            },
                            onOpenLogs = { openSettingsPane(SettingsPane.Logs) },
                            onOpenQuickActions = { navController.navigate(Screen.QuickActions.create()) { launchSingleTop = true } },
                            onOpenLocationSettings = { openSettingsPane(SettingsPane.Location) },
                            onOpenDiagnosis = { openSettingsPane(SettingsPane.Diagnosis) },
                            onOpenProfile = { openSettingsPane(SettingsPane.Profile) },
                            onOpenInfoCardPicker = { navController.navigate(Screen.InfoCardPicker.route) { launchSingleTop = true } },
                            onOpenConnectionDetails = { openSettingsPane(SettingsPane.Connection) },
                            // Long press on a door quick action: the door screen, a normal destination — no tab.
                            onOpenDoor = openDoor,
                            onOpenStoryComposer = { navController.navigate(Screen.ChatStoryCompose.create()) { launchSingleTop = true } },
                            onOpenOwnStories = { navController.navigate(Screen.ChatStoryView.create(0L)) { launchSingleTop = true } },
                            onArrange = onArrange,
                        )
                    },
                )
            }

            // Former Remote tab: now the Remote segment of the Netzwerk tab.
            composable(Screen.Rdp.route) {
                LaunchedEffect(Unit) {
                    navController.navigate(MainTabs.redirect(Screen.Rdp.route) ?: Screen.Network.create()) {
                        popUpTo(Screen.Rdp.route) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            }

            composable(
                route = Screen.Network.route,
                arguments = listOf(
                    navArgument(Screen.Network.ARG_TAB) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { backStackEntry ->
                val tab = backStackEntry.arguments?.getString(Screen.Network.ARG_TAB)
                if (StartPages.NETWORK in pinnedNow) {
                    RedirectToPage(StartPages.NETWORK, Screen.Network.create(tab))
                } else {
                    NetworkTab(tab)
                }
            }

            composable(
                route = Screen.Settings.route,
                arguments = listOf(
                    navArgument(Screen.Settings.ARG_PANE) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { backStackEntry ->
                if (layout.twoPane) {
                    val requested = SettingsPane.fromKey(backStackEntry.arguments?.getString(Screen.Settings.ARG_PANE))
                    val settingsViewModel: SettingsViewModel = hiltViewModel()
                    val serverLocked by remember(settingsViewModel) {
                        settingsViewModel.uiState.map { it.policy.lockServer }
                    }.collectAsStateWithLifecycle(initialValue = false)
                    var paneKey by rememberSaveable(requested) { mutableStateOf(requested?.key) }
                    // Nothing picked yet: the first page that can be opened.
                    val pane = SettingsPane.fromKey(paneKey)
                        ?: if (serverLocked) SettingsPane.SplitTunnel else SettingsPane.Server
                    GcTwoPane(
                        list = {
                            SettingsScreen(
                                onBack = { navController.popBackStack() },
                                onNavigateToNotifications = { paneKey = SettingsPane.Notifications.key },
                                onNavigateToLogs = { paneKey = SettingsPane.Logs.key },
                                onNavigateToServer = { paneKey = SettingsPane.Server.key },
                                onNavigateToSplitTunnel = { paneKey = SettingsPane.SplitTunnel.key },
                                onNavigateToTrustedNetworks = { paneKey = SettingsPane.TrustedNetworks.key },
                                onNavigateToLocation = { paneKey = SettingsPane.Location.key },
                                onNavigateToDiagnosis = { paneKey = SettingsPane.Diagnosis.key },
                                onNavigateToProfile = { paneKey = SettingsPane.Profile.key },
                                onNavigateToConnection = { paneKey = SettingsPane.Connection.key },
                                onNavigateToShared = if (plugins.gatedrop) { { paneKey = SettingsPane.Shared.key } } else null,
                                onNavigateToDrive = if (plugins.drive) { { navController.navigate(Screen.DriveSettings.route) { launchSingleTop = true } } } else null,
                                onNavigateToPages = { paneKey = SettingsPane.Pages.key },
                                selectedPane = pane,
                                viewModel = settingsViewModel,
                            )
                        },
                        detail = {
                            key(pane) {
                                SettingsPaneContent(
                                    pane = pane,
                                    onOpenEnroll = { navController.navigate(Screen.Enroll.create()) },
                                    onOpenRoutes = { navController.navigate(Screen.NotifyRoutes.route) { launchSingleTop = true } },
                                    onOpenRules = { navController.navigate(RulesRoutes.LIST) { launchSingleTop = true } },
                                    onSelectPane = { paneKey = it.key },
                                    features = features,
                                    onArrangePages = { navController.navigate(Screen.StartPagesArrange.route) { launchSingleTop = true } },
                                )
                            }
                        },
                    )
                } else {
                    SettingsScreen(
                        onBack = { navController.popBackStack() },
                        onNavigateToNotifications = { navController.navigate(Screen.SettingsNotifications.route) },
                        onNavigateToLogs = { navController.navigate(Screen.Logs.route) },
                        onNavigateToServer = { navController.navigate(Screen.SettingsServer.route) },
                        onNavigateToSplitTunnel = { navController.navigate(Screen.SplitTunnel.route) },
                        onNavigateToTrustedNetworks = { navController.navigate(Screen.TrustedNetworks.route) },
                        onNavigateToLocation = { navController.navigate(Screen.LocationSharing.route) },
                        onNavigateToDiagnosis = { navController.navigate(Screen.Diagnosis.route) { launchSingleTop = true } },
                        onNavigateToProfile = openProfileFromSettings,
                        onNavigateToConnection = { navController.navigate(Screen.ConnectionDetails.route) { launchSingleTop = true } },
                        onNavigateToShared = if (plugins.gatedrop) { { navController.navigate(Screen.Shared.create()) { launchSingleTop = true } } } else null,
                        onNavigateToDrive = if (plugins.drive) { { navController.navigate(Screen.DriveSettings.route) { launchSingleTop = true } } } else null,
                        onNavigateToPages = { navController.navigate(Screen.SettingsPages.route) { launchSingleTop = true } },
                    )
                }
            }

            composable(
                route = Screen.Familie.route,
                arguments = listOf(
                    navArgument(Screen.Familie.ARG_DEST) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { backStackEntry ->
                val raw = backStackEntry.arguments?.getString(Screen.Familie.ARG_DEST)
                if (StartPages.FAMILIE in pinnedNow) {
                    RedirectToPage(StartPages.FAMILIE, Screen.Familie.create(raw))
                } else {
                    FamilieTab(FamilieDest.parse(raw))
                }
            }

            composable(
                route = Screen.FamiliePage.route,
                arguments = listOf(
                    navArgument(Screen.FamiliePage.ARG_DEST) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { backStackEntry ->
                val dest = FamilieDest.parse(backStackEntry.arguments?.getString(Screen.FamiliePage.ARG_DEST)) ?: FamilieDest.Home
                GcContentWidth {
                    FamiliePage(
                        dest = dest,
                        onBack = {
                            if (!navController.popBackStack()) navigateToTab(Screen.Familie.create())
                        },
                        onOpen = { navController.navigate(Screen.FamiliePage.create(it.key)) },
                        onOpenProfile = { openSettingsPane(SettingsPane.Profile) },
                    )
                }
            }

            composable(
                route = Screen.Chats.route,
                arguments = listOf(
                    navArgument(Screen.Chats.ARG_CONV) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                    navArgument(Screen.Chats.ARG_SHARE) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { backStackEntry ->
                val conv = backStackEntry.arguments?.getString(Screen.Chats.ARG_CONV)?.toLongOrNull()
                val share = backStackEntry.arguments?.getString(Screen.Chats.ARG_SHARE) == "true"
                if (StartPages.CHATS in pinnedNow) {
                    RedirectToPage(StartPages.CHATS, Screen.Chats.create(conv, share))
                } else {
                    ChatsTab(conv, share)
                }
            }

            composable(
                route = Screen.ChatConversation.route,
                arguments = listOf(
                    navArgument(Screen.ChatConversation.ARG_ID) { type = NavType.LongType },
                    navArgument(Screen.ChatConversation.ARG_SHARE) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { backStackEntry ->
                val id = backStackEntry.arguments?.getLong(Screen.ChatConversation.ARG_ID) ?: 0L
                val share = backStackEntry.arguments?.getString(Screen.ChatConversation.ARG_SHARE) == "true"
                ChatSecureGate { GcContentWidth(maxWidth = 840.dp) {
                    ConversationScreen(
                        conversationId = id,
                        share = share,
                        onBack = { if (!navController.popBackStack()) navigateToTab(Screen.Chats.create()) },
                        onOpenInfo = { navController.navigate(Screen.ChatInfo.create(id)) { launchSingleTop = true } },
                        onOpenImage = { media -> navController.navigate(Screen.ChatImage.create(media)) },
                        onOpenUpdates = openSettings,
                        onOpenProfile = { openSettingsPane(SettingsPane.Profile) },
                    )
                } }
            }

            composable(
                route = Screen.ChatNew.route,
                arguments = listOf(
                    navArgument(Screen.ChatNew.ARG_GROUP) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { backStackEntry ->
                val group = backStackEntry.arguments?.getString(Screen.ChatNew.ARG_GROUP) == "true"
                GcContentWidth {
                    NewChatScreen(
                        group = group,
                        onBack = { navController.popBackStack() },
                        onNewGroup = { navController.navigate(Screen.ChatNew.create(group = true)) },
                        onOpened = { id ->
                            // The new conversation replaces "Neuer Chat" (and "Neue Gruppe").
                            while (navController.currentDestination?.route == Screen.ChatNew.route) {
                                if (!navController.popBackStack()) break
                            }
                            if (layout.twoPane) {
                                openTab(Screen.Chats.create(conv = id))
                            } else {
                                navController.navigate(Screen.ChatConversation.create(id))
                            }
                        },
                    )
                }
            }

            composable(
                route = Screen.ChatInfo.route,
                arguments = listOf(navArgument(Screen.ChatInfo.ARG_ID) { type = NavType.LongType }),
            ) { backStackEntry ->
                val id = backStackEntry.arguments?.getLong(Screen.ChatInfo.ARG_ID) ?: 0L
                ChatSecureGate { GcContentWidth {
                    ChatInfoScreen(
                        conversationId = id,
                        onBack = { navController.popBackStack() },
                        onOpenMedia = { navController.navigate(Screen.ChatMedia.create(id)) { launchSingleTop = true } },
                        // Back to the list (the left group's chat is gone).
                        onLeft = {
                            if (!navController.popBackStack(Screen.Chats.route, inclusive = false)) navigateToTab(Screen.Chats.create())
                        },
                    )
                } }
            }

            composable(
                route = Screen.ChatMedia.route,
                arguments = listOf(navArgument(Screen.ChatMedia.ARG_ID) { type = NavType.LongType }),
            ) { backStackEntry ->
                val id = backStackEntry.arguments?.getLong(Screen.ChatMedia.ARG_ID) ?: 0L
                ChatSecureGate { GcContentWidth(maxWidth = 840.dp) {
                    ChatMediaScreen(
                        conversationId = id,
                        onBack = { navController.popBackStack() },
                        onOpenImage = { media -> navController.navigate(Screen.ChatImage.create(media)) },
                    )
                } }
            }

            composable(
                route = Screen.ChatSettings.route,
                arguments = listOf(
                    navArgument(Screen.ChatSettings.ARG_PAGE) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { backStackEntry ->
                val page = backStackEntry.arguments?.getString(Screen.ChatSettings.ARG_PAGE)?.takeIf { it in ChatSettingsPage.ALL } ?: ChatSettingsPage.MAIN
                var statusSheet by remember { mutableStateOf(false) }
                ChatSecureGate { GcContentWidth {
                    ChatSettingsScreen(
                        page = page,
                        onBack = { navController.popBackStack() },
                        onOpenPage = { p -> navController.navigate(Screen.ChatSettings.create(p)) },
                        onOpenStatus = { statusSheet = true },
                        onOpenQuietHours = { openSettingsPane(SettingsPane.Notifications) },
                        onOpenRoutes = { navController.navigate(Screen.NotifyRoutes.route) { launchSingleTop = true } },
                    )
                } }
                if (statusSheet) {
                    StatusSheet(
                        onDismiss = { statusSheet = false },
                        onOpenStoryComposer = {
                            statusSheet = false
                            navController.navigate(Screen.ChatStoryCompose.create()) { launchSingleTop = true }
                        },
                        onOpenProfile = { statusSheet = false; openSettingsPane(SettingsPane.Profile) },
                        onViewOwnStories = {
                            statusSheet = false
                            navController.navigate(Screen.ChatStoryView.create(0L)) { launchSingleTop = true }
                        },
                    )
                }
            }

            composable(
                route = Screen.ChatStoryCompose.route,
                arguments = listOf(
                    navArgument(Screen.ChatStoryCompose.ARG_KIND) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { backStackEntry ->
                StoryComposerScreen(
                    initialKind = backStackEntry.arguments?.getString(Screen.ChatStoryCompose.ARG_KIND) ?: com.gatecontrol.android.chat.ChatStory.KIND_TEXT,
                    onClose = { navController.popBackStack() },
                )
            }

            composable(
                route = Screen.ChatStoryView.route,
                arguments = listOf(navArgument(Screen.ChatStoryView.ARG_USER) { type = NavType.LongType }),
            ) { backStackEntry ->
                val user = backStackEntry.arguments?.getLong(Screen.ChatStoryView.ARG_USER) ?: 0L
                ChatSecureGate {
                    StoryViewerScreen(
                        userId = user,
                        onClose = { navController.popBackStack() },
                        onOpenComposer = { navController.navigate(Screen.ChatStoryCompose.create()) { launchSingleTop = true } },
                    )
                }
            }

            composable(
                route = Screen.ChatImage.route,
                arguments = listOf(navArgument(Screen.ChatImage.ARG_MEDIA) { type = NavType.StringType }),
            ) { backStackEntry ->
                val media = backStackEntry.arguments?.getString(Screen.ChatImage.ARG_MEDIA).orEmpty()
                ChatSecureGate { ChatImageViewer(mediaId = media, onBack = { navController.popBackStack() }) }
            }

            composable(
                route = Screen.Door.route,
                arguments = listOf(
                    navArgument(Screen.Door.ARG_DEST) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { backStackEntry ->
                val dest = DoorDest.parse(backStackEntry.arguments?.getString(Screen.Door.ARG_DEST))
                val back: () -> Unit = { if (!navController.popBackStack()) navigateToTab(Screen.Vpn.route) }
                if (layout.twoPane) {
                    DoorTwoPane(initial = dest, onBack = back)
                } else {
                    GcContentWidth {
                        DoorHomeScreen(onOpen = { navController.navigate(Screen.DoorPage.create(it.key)) }, onBack = back)
                    }
                }
            }

            composable(
                route = Screen.DoorPage.route,
                arguments = listOf(
                    navArgument(Screen.DoorPage.ARG_DEST) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { backStackEntry ->
                val dest = DoorDest.parse(backStackEntry.arguments?.getString(Screen.DoorPage.ARG_DEST)) ?: DoorDest.Home
                GcContentWidth {
                    DoorPage(
                        dest = dest,
                        onBack = {
                            if (!navController.popBackStack()) navController.navigate(Screen.Door.create())
                        },
                        onOpen = { navController.navigate(Screen.DoorPage.create(it.key)) },
                    )
                }
            }

            // Logbook of the vehicles plugin (no tab): opened by its quick action (docs/LOGBOOK.md).
            composable(
                route = Screen.Logbook.route,
                arguments = listOf(
                    navArgument(Screen.Logbook.ARG_VEHICLE) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { backStackEntry ->
                val vehicle = backStackEntry.arguments?.getString(Screen.Logbook.ARG_VEHICLE)?.toLongOrNull()
                val back: () -> Unit = { if (!navController.popBackStack()) navigateToTab(Screen.Vpn.route) }
                if (layout.twoPane) {
                    LogbookTwoPane(vehicleId = vehicle, onBack = back)
                } else {
                    GcContentWidth {
                        LogbookScreen(
                            vehicleId = vehicle,
                            onBack = back,
                            onOpenMap = { v, m -> navController.navigate(Screen.LogbookMap.create(v, m)) { launchSingleTop = true } },
                            onOpenTrip = { trip, v, m -> navController.navigate(Screen.LogbookTrip.create(trip.id, v, m)) },
                        )
                    }
                }
            }

            composable(
                route = Screen.LogbookMap.route,
                arguments = listOf(
                    navArgument(Screen.LogbookMap.ARG_VEHICLE) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                    navArgument(Screen.LogbookMap.ARG_MONTH) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { backStackEntry ->
                val vehicle = backStackEntry.arguments?.getString(Screen.LogbookMap.ARG_VEHICLE)?.toLongOrNull()
                val month = backStackEntry.arguments?.getString(Screen.LogbookMap.ARG_MONTH)
                val toList: () -> Unit = {
                    if (!navController.popBackStack(Screen.Logbook.route, inclusive = false)) {
                        navController.navigate(Screen.Logbook.create(vehicle)) {
                            popUpTo(Screen.LogbookMap.route) { inclusive = true }
                        }
                    }
                }
                LogbookMapScreen(
                    vehicleId = vehicle,
                    month = month,
                    onBack = toList,
                    onShowTrips = toList,
                    onOpenTrip = { trip, v, m -> navController.navigate(Screen.LogbookTrip.create(trip.id, v, m)) },
                )
            }

            composable(
                route = Screen.LogbookTrip.route,
                arguments = listOf(
                    navArgument(Screen.LogbookTrip.ARG_ID) { type = NavType.LongType },
                    navArgument(Screen.LogbookTrip.ARG_VEHICLE) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                    navArgument(Screen.LogbookTrip.ARG_MONTH) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { backStackEntry ->
                val args = backStackEntry.arguments
                GcContentWidth {
                    LogbookTripScreen(
                        tripId = args?.getLong(Screen.LogbookTrip.ARG_ID) ?: 0L,
                        vehicleId = args?.getString(Screen.LogbookTrip.ARG_VEHICLE)?.toLongOrNull(),
                        month = args?.getString(Screen.LogbookTrip.ARG_MONTH),
                        onBack = { if (!navController.popBackStack()) navController.navigate(Screen.Logbook.create()) },
                    )
                }
            }

            composable(Screen.SettingsServer.route) {
                GcContentWidth {
                    ServerSettingsScreen(
                        onBack = { navController.popBackStack() },
                        // Scans land on the setup screen, which understands both the
                        // setup QR (VPN + API access) and a bare WireGuard config.
                        onNavigateToQrScanner = { navController.navigate(Screen.Enroll.create()) },
                    )
                }
            }

            composable(
                route = Screen.Inbox.route,
                arguments = listOf(
                    navArgument(Screen.Inbox.ARG_SELECT) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { backStackEntry ->
                val requested = backStackEntry.arguments?.getString(Screen.Inbox.ARG_SELECT)?.toLongOrNull()
                if (layout.twoPane) {
                    var selected by rememberSaveable(requested) { mutableStateOf(requested) }
                    GcTwoPane(
                        listWidth = 420.dp,
                        list = {
                            InboxScreen(
                                onBack = { navController.popBackStack() },
                                onOpen = { id -> selected = id },
                                onOpenSettings = { openSettingsPane(SettingsPane.Notifications) },
                                selectedId = selected,
                            )
                        },
                        detail = {
                            val id = selected
                            if (id == null) {
                                InboxPanePlaceholder()
                            } else {
                                val detailViewModel: NotificationDetailViewModel = hiltViewModel(key = "inbox-pane")
                                LaunchedEffect(id) { detailViewModel.show(id) }
                                NotificationDetailScreen(
                                    onBack = { selected = null },
                                    onNavigate = { target ->
                                        if (target is PushNavTarget.Detail) selected = target.id
                                        else if (target != PushNavTarget.Inbox) openPushTarget(navController, target, navigateToTab, openTab, layout, plugins.familieTab, plugins.door, plugins.messengerTab, plugins.vault, plugins.drive, plugins.mail)
                                    },
                                    viewModel = detailViewModel,
                                )
                            }
                        },
                    )
                } else {
                    GcContentWidth {
                        InboxScreen(
                            onBack = { navController.popBackStack() },
                            onOpen = { id -> navController.navigate(Screen.NotificationDetail.create(id)) },
                            onOpenSettings = { openSettingsPane(SettingsPane.Notifications) },
                        )
                    }
                }
            }

            composable(
                route = Screen.NotificationDetail.route,
                arguments = listOf(navArgument(Screen.NotificationDetail.ARG_ID) { type = NavType.LongType }),
            ) {
                GcContentWidth {
                    NotificationDetailScreen(
                        onBack = {
                            if (!navController.popBackStack()) navController.navigate(Screen.Inbox.create())
                        },
                        onNavigate = { target -> openPushTarget(navController, target, navigateToTab, openTab, layout, plugins.familieTab, plugins.door, plugins.messengerTab, plugins.vault, plugins.drive, plugins.mail) },
                    )
                }
            }

            composable(Screen.SettingsNotifications.route) {
                GcContentWidth {
                    NotificationSettingsScreen(
                        onBack = { navController.popBackStack() },
                        onOpenRoutes = { navController.navigate(Screen.NotifyRoutes.route) { launchSingleTop = true } },
                    )
                }
            }

            composable(Screen.NotifyRoutes.route) {
                GcContentWidth { NotifyRoutesScreen(onBack = { navController.popBackStack() }) }
            }

            composable(Screen.Profile.route) {
                GcContentWidth {
                    ProfileScreen(
                        onBack = { navController.popBackStack() },
                        onOpenSettings = openSettings,
                        onOpenRules = { navController.navigate(RulesRoutes.LIST) { launchSingleTop = true } },
                    )
                }
            }

            // Familie "Regeln und Erinnerungen" incl. reminder/new?actionId=… (long press on a widget).
            rulesGraph(navController)

            composable(Screen.InfoCardPicker.route) {
                GcContentWidth { InfoCardPickerScreen(onBack = { navController.popBackStack() }) }
            }

            composable(Screen.ConnectionDetails.route) {
                GcContentWidth {
                    ConnectionDetailsScreen(
                        onBack = { navController.popBackStack() },
                        onOpenDiagnosis = { navController.navigate(Screen.Diagnosis.route) { launchSingleTop = true } },
                    )
                }
            }

            composable(Screen.SplitTunnel.route) {
                GcContentWidth { SplitTunnelScreen(onBack = { navController.popBackStack() }) }
            }

            composable(Screen.TrustedNetworks.route) {
                GcContentWidth { TrustedNetworksScreen(onBack = { navController.popBackStack() }) }
            }
            composable(Screen.LocationSharing.route) {
                GcContentWidth { LocationSharingScreen(onBack = { navController.popBackStack() }) }
            }
            composable(Screen.Diagnosis.route) {
                GcContentWidth { DiagnosisScreen(onBack = { navController.popBackStack() }) }
            }

            composable(
                route = Screen.QuickActions.route,
                arguments = listOf(
                    navArgument(Screen.QuickActions.ARG_PAGE) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { backStackEntry ->
                GcContentWidth { QuickActionsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenDoor = openDoor,
                    onNewReminder = { navController.navigate(Screen.ReminderNew.create(it)) { launchSingleTop = true } },
                    page = backStackEntry.arguments?.getString(Screen.QuickActions.ARG_PAGE)?.takeIf { StartPages.isWidgets(it) },
                ) }
            }

            // Pages beside Start: "Seiten anordnen" and Einstellungen → "Leiste und Seiten".
            composable(Screen.StartPagesArrange.route) {
                GcContentWidth { StartPagesArrangeScreen(available = features, onBack = { navController.popBackStack() }) }
            }
            composable(Screen.SettingsPages.route) {
                GcContentWidth {
                    StartPagesSettingsScreen(
                        available = features,
                        onBack = { navController.popBackStack() },
                        onArrangePages = { navController.navigate(Screen.StartPagesArrange.route) { launchSingleTop = true } },
                    )
                }
            }

            composable(
                route = Screen.Shared.route,
                arguments = listOf(
                    navArgument(Screen.Shared.ARG_TAB) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { backStackEntry ->
                GcContentWidth {
                    SharedScreen(
                        onBack = { if (!navController.popBackStack()) navigateToTab(Screen.Vpn.route) },
                        initialTab = backStackEntry.arguments?.getString(Screen.Shared.ARG_TAB) ?: "received",
                    )
                }
            }

            composable(Screen.Logs.route) {
                LogsScreen(
                    onNavigateBack = { navController.popBackStack() },
                )
            }

            vaultRoutes(navController, navigateToTab) {
                if (StartPages.VAULT in pinnedNow) RedirectToPage(StartPages.VAULT, Screen.Vault.route) else VaultHome(navController)
            }
            mailRoutes(navController, navigateToTab) {
                if (StartPages.MAIL in pinnedNow) RedirectToPage(StartPages.MAIL, Screen.Mail.route) else MailHome(navController)
            }
            driveRoutes(navController, navigateToTab) { entry ->
                if (StartPages.DRIVE in pinnedNow) {
                    RedirectToPage(StartPages.DRIVE, Screen.Drive.route)
                } else {
                    DriveHome(navController, onShown = { id -> entry.savedStateHandle[DRIVE_TAB_SHOWN] = id })
                }
            }
        }

        // A tapped push message (PushOpenActivity) — only once the app is set up.
        val pushTarget by PushNavigation.pending.collectAsStateWithLifecycle()
        LaunchedEffect(pushTarget, isSetupComplete) {
            val target = pushTarget ?: return@LaunchedEffect
            if (!isSetupComplete) return@LaunchedEffect
            PushNavigation.consume(target)
            openPushTarget(navController, target, navigateToTab, openTab, layout, plugins.familieTab, plugins.door, plugins.messengerTab, plugins.vault, plugins.drive, plugins.mail)
        }

        // After NavHost so the graph is set before navigating.
        LaunchedEffect(pendingSetupLink) {
            if (pendingSetupLink != null) {
                navController.navigate(Screen.Enroll.create(pendingSetupLink))
                onSetupLinkConsumed()
            }
        }
        }
    }
    }
}

/** Opens the screen a push message points to. */
private fun openPushTarget(
    navController: NavHostController,
    target: PushNavTarget,
    navigateToTab: (String) -> Unit,
    /** A tab's route whose arguments win over a saved state (a pinned tab: its page beside Start). */
    openTab: (String) -> Unit,
    layout: GcWindowLayout,
    familieTab: Boolean,
    /** The door plugin is listed: its pushes open the door screens. */
    door: Boolean = false,
    /** The messenger plugin is listed with its tab "Chats". */
    chats: Boolean = false,
    /** The Vault plugin is listed: its pushes open the tab or "Notfallzugriff". */
    vault: Boolean = false,
    /** The Drive plugin is listed: a deadline opens its file, a vault request the vault. */
    drive: Boolean = false,
    /** The Mail plugin is listed with its tab: a mail push opens the mail, a failed send the outbox. */
    mail: Boolean = false,
) {
    when (target) {
        is PushNavTarget.Mail -> {
            val message = com.gatecontrol.android.mail.MailPush.messageOf(target.dest)
            when {
                mail && message != null -> {
                    navigateToTab(Screen.Mail.route)
                    navController.navigate(Screen.MailMessage.create(message)) { launchSingleTop = true }
                }
                mail && com.gatecontrol.android.mail.MailPush.isOutbox(target.dest) -> {
                    navigateToTab(Screen.Mail.route)
                    navController.navigate(Screen.MailOutbox.route) { launchSingleTop = true }
                }
                target.messageId != null -> navController.navigate(Screen.NotificationDetail.create(target.messageId))
                else -> navigateToTab(Screen.Vpn.route)
            }
        }
        is PushNavTarget.Drive -> {
            val file = com.gatecontrol.android.drive.DrivePush.fileOf(target.dest)
            val vaultDest = com.gatecontrol.android.drive.DrivePush.vaultOf(target.dest)
            when {
                drive && file != null -> {
                    navigateToTab(Screen.Drive.route)
                    navController.navigate(Screen.DriveFile.create(file)) { launchSingleTop = true }
                }
                drive && vaultDest != null -> {
                    navigateToTab(Screen.Drive.route)
                    navController.navigate(Screen.DriveVault.create(vaultDest.first, vaultDest.second, requests = true)) { launchSingleTop = true }
                }
                target.messageId != null -> navController.navigate(Screen.NotificationDetail.create(target.messageId))
                else -> navigateToTab(Screen.Vpn.route)
            }
        }
        is PushNavTarget.Vault -> when {
            !vault -> if (target.messageId != null) navController.navigate(Screen.NotificationDetail.create(target.messageId)) else navigateToTab(Screen.Vpn.route)
            target.dest == "emergency" -> {
                navigateToTab(Screen.Vault.route)
                navController.navigate(Screen.VaultEmergency.route) { launchSingleTop = true }
            }
            else -> navigateToTab(Screen.Vault.route)
        }
        is PushNavTarget.Chat -> when {
            !chats -> navigateToTab(Screen.Vpn.route)
            // Messenger 1.4: the conversation picks up the message (and what to open) when it shows.
            else -> {
                if (target.conversationId != null && (target.messageId != null || target.open != null)) {
                    com.gatecontrol.android.chat.ChatDeepLink.request(target.conversationId, target.messageId, target.open)
                }
                when {
            target.conversationId == null -> navigateToTab(Screen.Chats.create())
            target.conversationId == null -> navigateToTab(Screen.Chats.create())
            layout.twoPane -> openTab(Screen.Chats.create(conv = target.conversationId))
            else -> {
                navigateToTab(Screen.Chats.create())
                navController.navigate(Screen.ChatConversation.create(target.conversationId)) { launchSingleTop = true }
            }
                }
            }
        }
        is PushNavTarget.ChatShare -> when {
            !chats -> ChatShareHandover.clear()
            target.conversationId == null || layout.twoPane -> openTab(Screen.Chats.create(conv = target.conversationId, share = true))
            else -> {
                navigateToTab(Screen.Chats.create())
                navController.navigate(Screen.ChatConversation.create(target.conversationId, share = true)) { launchSingleTop = true }
            }
        }
        PushNavTarget.Inbox -> navController.navigate(Screen.Inbox.create()) { launchSingleTop = true }
        // GateDrop "Geteilt": one at a time, the tab the notification asks for.
        is PushNavTarget.Shared -> navController.navigate(Screen.Shared.create(target.tab)) {
            popUpTo(Screen.Shared.route) { inclusive = true }
            launchSingleTop = true
        }
        // Wide screens: the message opens beside the inbox list.
        is PushNavTarget.Detail -> if (layout.twoPane) {
            navController.navigate(Screen.Inbox.create(target.id)) { launchSingleTop = true }
        } else {
            navController.navigate(Screen.NotificationDetail.create(target.id))
        }
        PushNavTarget.Vpn -> navigateToTab(Screen.Vpn.route)
        PushNavTarget.Services -> openTab(Screen.Network.create(NetworkTabs.SERVICES))
        PushNavTarget.Remote -> openTab(Screen.Network.create(NetworkTabs.REMOTE))
        is PushNavTarget.Familie -> {
            val dest = FamilieDest.parse(target.dest) ?: FamilieDest.Home
            when {
                // No Familie tab (plugin gone meanwhile): the message itself.
                !familieTab -> openPushTarget(
                    navController,
                    target.messageId?.let { PushNavTarget.Detail(it) } ?: PushNavTarget.Inbox,
                    navigateToTab,
                    openTab,
                    layout,
                    familieTab = false,
                    door = door,
                    chats = chats,
                )
                // Wide screens: the screen beside the Familie start screen. No
                // restoreState: the dest argument must win over a saved state.
                layout.twoPane -> openTab(Screen.Familie.create(dest.key))
                else -> {
                    navigateToTab(Screen.Familie.create())
                    if (dest != FamilieDest.Home) navController.navigate(Screen.FamiliePage.create(dest.key))
                }
            }
        }
        // A quick action with `opens` (the logbook): one logbook screen at a time, the vehicle's.
        is PushNavTarget.Logbook -> navController.navigate(Screen.Logbook.create(target.vehicleId)) {
            popUpTo(Screen.Logbook.route) { inclusive = true }
            launchSingleTop = true
        }
        is PushNavTarget.Door -> {
            val steps = DoorNavigation.forPush(target.dest, pluginListed = door, twoPane = layout.twoPane)
            // Plugin gone (or no right any more): the message itself.
            if (steps == null) {
                openPushTarget(
                    navController,
                    target.messageId?.let { PushNavTarget.Detail(it) } ?: PushNavTarget.Inbox,
                    navigateToTab,
                    openTab,
                    layout,
                    familieTab,
                    door = false,
                    chats = chats,
                )
                return
            }
            steps.forEach { step ->
                when (step) {
                    // One door screen at a time: an open one (and what lies on it) is replaced, so dest wins.
                    is DoorStep.Main -> navController.navigate(Screen.Door.create(step.selected?.key)) {
                        popUpTo(Screen.Door.route) { inclusive = true }
                        launchSingleTop = true
                    }
                    is DoorStep.Page -> navController.navigate(Screen.DoorPage.create(step.dest.key))
                }
            }
        }
    }
}

/**
 * A long press on a tab (all but Start): "An Start anheften" (the tab
 * becomes a page beside Start, [onPin] its page id) and "Leiste und Seiten
 * …" ([onSettings]); [locked]: the admin fixes the order, pinning is off.
 */
private class TabMenuActions(
    val locked: Boolean,
    val onPin: (String) -> Unit,
    val onSettings: () -> Unit,
)

/** A tab of the bar or the rail: tap selects it, a long press opens [TabMenu] (all but Start; [menuLabel] read out for it). */
private fun Modifier.tabClick(tab: TabItem, selected: Boolean, onSelect: (TabItem) -> Unit, onMenu: (() -> Unit)?, menuLabel: String): Modifier =
    combinedClickable(
        role = Role.Tab,
        onClick = { onSelect(tab) },
        onLongClick = onMenu,
        onLongClickLabel = if (onMenu != null) menuLabel else null,
    ).semantics { this.selected = selected }

/** The menu of a long-pressed tab (mockup "Tab lange drücken"). */
@Composable
private fun TabMenu(tab: TabItem, expanded: Boolean, actions: TabMenuActions, onDismiss: () -> Unit) {
    val page = tab.page ?: return
    val extra = GateControlTheme.extraColors
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        Text(
            stringResource(tab.label),
            style = MaterialTheme.typography.labelMedium,
            color = extra.muted,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        DropdownMenuItem(
            text = {
                Column {
                    Text(stringResource(R.string.pages_tab_pin))
                    Text(
                        stringResource(if (actions.locked) R.string.arrange_locked else R.string.pages_tab_pin_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = extra.muted,
                    )
                }
            },
            leadingIcon = { Icon(GcIcons.Pin, contentDescription = null, modifier = Modifier.size(20.dp)) },
            enabled = !actions.locked,
            onClick = { onDismiss(); actions.onPin(page) },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.pages_tab_settings)) },
            leadingIcon = { Icon(GcIcons.Sliders, contentDescription = null, modifier = Modifier.size(20.dp)) },
            onClick = { onDismiss(); actions.onSettings() },
        )
    }
}

/** Bottom tab bar from the mockup: pill indicator behind the icon, label below; a long press offers pinning to Start. */
@Composable
private fun GcTabBar(
    tabs: List<TabItem>,
    isSelected: (TabItem) -> Boolean,
    onSelect: (TabItem) -> Unit,
    menu: TabMenuActions,
) {
    var menuFor by remember { mutableStateOf<String?>(null) }
    val menuLabel = stringResource(R.string.pages_tab_options)
    val extra = GateControlTheme.extraColors
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .background(scheme.surface)
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(extra.border))
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 4.dp, bottom = 10.dp),
        ) {
            // Six tabs (all plugins) share a phone's width: a narrower pill.
            val pill = when {
                tabs.size > 6 -> 44.dp
                tabs.size > 5 -> 52.dp
                else -> 60.dp
            }
            tabs.forEach { tab ->
                val selected = isSelected(tab)
                Box(Modifier.weight(1f)) {
                    TabItemContent(
                        tab = tab,
                        selected = selected,
                        pillWidth = pill,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(72.dp)
                            .tabClick(tab, selected, onSelect, onMenu = tab.page?.let { { menuFor = tab.route } }, menuLabel = menuLabel)
                            .padding(top = 10.dp),
                    )
                    TabMenu(tab, expanded = menuFor == tab.route, actions = menu, onDismiss = { menuFor = null })
                }
            }
        }
    }
}

/** Icon with pill indicator, badge and label: one entry of the tab bar or the rail. */
@Composable
private fun TabItemContent(tab: TabItem, selected: Boolean, modifier: Modifier, pillWidth: Dp = 60.dp) {
    val extra = GateControlTheme.extraColors
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier
                .width(pillWidth)
                .height(32.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(if (selected) extra.accentBg else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                tab.icon,
                contentDescription = null,
                tint = if (selected) extra.accentText else extra.muted,
                modifier = Modifier.size(22.dp),
            )
            if (tab.badge > 0) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = (-6).dp, y = (-2).dp)
                        .height(18.dp)
                        .widthIn(min = 18.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(scheme.primary)
                        .padding(horizontal = 5.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        tab.badge.toString(),
                        color = scheme.onPrimary,
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp),
                    )
                }
            }
        }
        Text(
            stringResource(tab.label),
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) scheme.onSurface else extra.muted,
            maxLines = 1,
        )
    }
}

/** Navigation rail for wide windows: the tab bar's entries on the left edge. */
@Composable
private fun GcNavRail(
    tabs: List<TabItem>,
    isSelected: (TabItem) -> Boolean,
    onSelect: (TabItem) -> Unit,
    menu: TabMenuActions,
) {
    val extra = GateControlTheme.extraColors
    var menuFor by remember { mutableStateOf<String?>(null) }
    val menuLabel = stringResource(R.string.pages_tab_options)
    Row(Modifier.fillMaxHeight()) {
        Column(
            Modifier
                .fillMaxHeight()
                .width(GcWindowLayout.RAIL_WIDTH_DP.dp)
                .background(MaterialTheme.colorScheme.surface)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                GcIcons.ShieldCheck,
                contentDescription = null,
                tint = extra.accentText,
                modifier = Modifier.padding(bottom = 12.dp).size(28.dp),
            )
            tabs.forEach { tab ->
                val selected = isSelected(tab)
                Box {
                    TabItemContent(
                        tab = tab,
                        selected = selected,
                        modifier = Modifier
                            .width(80.dp)
                            .tabClick(tab, selected, onSelect, onMenu = tab.page?.let { { menuFor = tab.route } }, menuLabel = menuLabel)
                            .padding(vertical = 6.dp),
                    )
                    TabMenu(tab, expanded = menuFor == tab.route, actions = menu, onDismiss = { menuFor = null })
                }
            }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(extra.border))
    }
}

/** Single-column pages on wide windows: centred, at most [maxWidth] wide. */
@Composable
internal fun GcContentWidth(maxWidth: Dp = 720.dp, content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(Modifier.widthIn(max = maxWidth).fillMaxSize()) { content() }
    }
}

/** Route of a settings page on phones (own screen). */
private fun SettingsPane.route(): String = when (this) {
    SettingsPane.Profile -> Screen.Profile.route
    SettingsPane.Connection -> Screen.ConnectionDetails.route
    SettingsPane.Server -> Screen.SettingsServer.route
    SettingsPane.SplitTunnel -> Screen.SplitTunnel.route
    SettingsPane.TrustedNetworks -> Screen.TrustedNetworks.route
    SettingsPane.Location -> Screen.LocationSharing.route
    SettingsPane.Notifications -> Screen.SettingsNotifications.route
    SettingsPane.Pages -> Screen.SettingsPages.route
    SettingsPane.Shared -> Screen.Shared.create()
    SettingsPane.Logs -> Screen.Logs.route
    SettingsPane.Diagnosis -> Screen.Diagnosis.route
}

/** A settings page in the detail pane beside the settings list. */
@Composable
private fun SettingsPaneContent(
    pane: SettingsPane,
    onOpenEnroll: () -> Unit,
    onSelectPane: (SettingsPane) -> Unit,
    onOpenRules: () -> Unit = {},
    onOpenRoutes: (() -> Unit)? = null,
    features: List<String> = emptyList(),
    onArrangePages: () -> Unit = {},
) {
    when (pane) {
        SettingsPane.Profile -> ProfileScreen(onBack = {}, onOpenRules = onOpenRules)
        SettingsPane.Connection -> ConnectionDetailsScreen(onBack = {}, onOpenDiagnosis = { onSelectPane(SettingsPane.Diagnosis) })
        SettingsPane.Server -> ServerSettingsScreen(onBack = {}, onNavigateToQrScanner = onOpenEnroll)
        SettingsPane.SplitTunnel -> SplitTunnelScreen(onBack = {})
        SettingsPane.TrustedNetworks -> TrustedNetworksScreen(onBack = {})
        SettingsPane.Location -> LocationSharingScreen(onBack = {})
        SettingsPane.Notifications -> NotificationSettingsScreen(onBack = {}, onOpenRoutes = onOpenRoutes)
        SettingsPane.Pages -> StartPagesSettingsScreen(available = features, onBack = {}, onArrangePages = onArrangePages)
        SettingsPane.Shared -> SharedScreen(onBack = {})
        SettingsPane.Logs -> LogsScreen(onNavigateBack = {})
        SettingsPane.Diagnosis -> DiagnosisScreen(onBack = {})
    }
}

/** Detail pane of the inbox before a message is picked. */
@Composable
private fun InboxPanePlaceholder() {
    val extra = GateControlTheme.extraColors
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(GcIcons.Bell, contentDescription = null, tint = extra.faint, modifier = Modifier.size(40.dp))
        Text(
            stringResource(R.string.push_inbox_pick),
            style = MaterialTheme.typography.bodyLarge,
            color = extra.muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

/**
 * Vault (plugin gatecontrol-vault): the tab and its pages, each a route of its own so the tab bar shows on the list
 * only and back works as everywhere else.
 */
private fun NavGraphBuilder.vaultRoutes(navController: NavHostController, navigateToTab: (String) -> Unit, home: @Composable () -> Unit) {
    val back: () -> Unit = { if (!navController.popBackStack()) navigateToTab(Screen.Vault.route) }
    val go: (String) -> Unit = { route -> navController.navigate(route) { launchSingleTop = true } }
    val recover: () -> Unit = { go(Screen.VaultRecover.route) }
    composable(Screen.Vault.route) { home() }
    composable(Screen.VaultSetup.route) {
        GcContentWidth { com.gatecontrol.android.ui.vault.VaultSetupScreen(onBack = back, onDone = back) }
    }
    composable(Screen.VaultRecover.route) {
        GcContentWidth { com.gatecontrol.android.ui.vault.VaultRecoverScreen(onBack = back, onDone = back) }
    }
    composable(Screen.VaultEntry.route, arguments = listOf(navArgument(Screen.VaultEntry.ARG_ID) { type = NavType.StringType })) { e ->
        val id = e.arguments?.getString(Screen.VaultEntry.ARG_ID).orEmpty()
        GcContentWidth {
            com.gatecontrol.android.ui.vault.VaultEntryScreen(
                id = id, onBack = back, onForgot = recover,
                onEdit = { go(Screen.VaultEdit.create(id = it)) },
                onHistory = { go(Screen.VaultHistory.create(it)) },
            )
        }
    }
    composable(
        Screen.VaultEdit.route,
        arguments = listOf(
            navArgument(Screen.VaultEdit.ARG_ID) { type = NavType.StringType; nullable = true; defaultValue = null },
            navArgument(Screen.VaultEdit.ARG_TYPE) { type = NavType.StringType; nullable = true; defaultValue = null },
            navArgument(Screen.VaultEdit.ARG_VAULT) { type = NavType.StringType; nullable = true; defaultValue = null },
        ),
    ) { e ->
        val id = e.arguments?.getString(Screen.VaultEdit.ARG_ID)
        GcContentWidth {
            com.gatecontrol.android.ui.vault.VaultEditScreen(
                id = id,
                type = e.arguments?.getString(Screen.VaultEdit.ARG_TYPE),
                vaultHint = e.arguments?.getString(Screen.VaultEdit.ARG_VAULT),
                onBack = back, onForgot = recover,
                onDone = { saved ->
                    // a new entry: its page replaces the editor; an edited one: back to its page
                    if (id == null) {
                        navController.popBackStack()
                        go(Screen.VaultEntry.create(saved))
                    } else {
                        back()
                    }
                },
            )
        }
    }
    composable(Screen.VaultHistory.route, arguments = listOf(navArgument(Screen.VaultHistory.ARG_ID) { type = NavType.StringType })) { e ->
        GcContentWidth { com.gatecontrol.android.ui.vault.VaultHistoryScreen(e.arguments?.getString(Screen.VaultHistory.ARG_ID).orEmpty(), onBack = back, onForgot = recover) }
    }
    composable(Screen.VaultVaults.route) {
        GcContentWidth { com.gatecontrol.android.ui.vault.VaultVaultsScreen(onBack = back, onOpen = { go(Screen.VaultShare.create(it)) }, onForgot = recover) }
    }
    composable(Screen.VaultShare.route, arguments = listOf(navArgument(Screen.VaultShare.ARG_ID) { type = NavType.StringType })) { e ->
        GcContentWidth { com.gatecontrol.android.ui.vault.VaultShareScreen(e.arguments?.getString(Screen.VaultShare.ARG_ID).orEmpty(), onBack = back, onForgot = recover) }
    }
    composable(Screen.VaultTrash.route) {
        GcContentWidth { com.gatecontrol.android.ui.vault.VaultTrashScreen(onBack = back, onOpen = { go(Screen.VaultEntry.create(it)) }, onForgot = recover) }
    }
    composable(Screen.VaultEmergency.route) {
        GcContentWidth { com.gatecontrol.android.ui.vault.VaultEmergencyScreen(onBack = back, onOpen = { go(Screen.VaultEmergencyView.create(it)) }, onForgot = recover) }
    }
    composable(Screen.VaultEmergencyView.route, arguments = listOf(navArgument(Screen.VaultEmergencyView.ARG_ID) { type = NavType.StringType })) { e ->
        GcContentWidth { com.gatecontrol.android.ui.vault.VaultEmergencyViewScreen(e.arguments?.getString(Screen.VaultEmergencyView.ARG_ID).orEmpty(), onBack = back, onForgot = recover) }
    }
    composable(Screen.VaultImport.route) {
        GcContentWidth { com.gatecontrol.android.ui.vault.VaultImportScreen(onBack = back, onForgot = recover) }
    }
    composable(Screen.VaultSettings.route) {
        GcContentWidth { com.gatecontrol.android.ui.vault.VaultSettingsScreen(onBack = back, onForgot = recover) }
    }
}

/** The Vault tab's screen (as the tab or as a page beside Start); its pages are routes of their own. */
@Composable
private fun VaultHome(navController: NavHostController) {
    val go: (String) -> Unit = { route -> navController.navigate(route) { launchSingleTop = true } }
    GcContentWidth {
        com.gatecontrol.android.ui.vault.VaultScreen(
            com.gatecontrol.android.ui.vault.VaultNav(
                setup = { go(Screen.VaultSetup.route) },
                recover = { go(Screen.VaultRecover.route) },
                entry = { go(Screen.VaultEntry.create(it)) },
                newEntry = { type, vault -> go(Screen.VaultEdit.create(type = type, vault = vault)) },
                vaults = { go(Screen.VaultVaults.route) },
                emergency = { go(Screen.VaultEmergency.route) },
                import = { go(Screen.VaultImport.route) },
                trash = { go(Screen.VaultTrash.route) },
                settings = { go(Screen.VaultSettings.route) },
            ),
        )
    }
}

/** The folder the Drive tab's Files page shows (kept in the tab's back stack entry). */
private const val DRIVE_TAB_SHOWN = "drive_shown"

/** Drive's ways between its pages (the tab, a page beside Start and the routes on top share them). */
private class DriveNavigation(private val navController: NavHostController) {
    val go: (String) -> Unit = { route -> navController.navigate(route) { launchSingleTop = true } }
    /** The folder the page on top shows (null: no folder page). */
    private val topFolder: () -> Long? = {
        navController.currentBackStackEntry?.takeIf { it.destination.route == Screen.DriveFolder.route }
            ?.arguments?.getString(Screen.DriveFolder.ARG_ID)?.toLongOrNull()
    }
    // A folder is a page of its own. Not "launchSingleTop": a folder page on top of a folder page is the same
    // destination, the navigation would only give the page on top the new id (its view model keeps showing its folder).
    val folder: (Long) -> Unit = { id ->
        if (topFolder() != id) navController.navigate(Screen.DriveFolder.create(id))
    }
    // The path above a folder: back to the page that shows that folder already (the Drive tab too), else a new page.
    val crumb: (Long) -> Unit = { id ->
        val page = Screen.DriveFolder.create(id)
        val tab = runCatching { navController.getBackStackEntry(Screen.Drive.route) }.getOrNull()
        val step = com.gatecontrol.android.drive.DriveLogic.crumbStep(
            top = topFolder(),
            id = id,
            onStack = runCatching { navController.getBackStackEntry(page) }.isSuccess,
            tab = tab?.savedStateHandle?.get<Long>(DRIVE_TAB_SHOWN),
        )
        when (step) {
            com.gatecontrol.android.drive.DriveLogic.CrumbStep.STAY -> Unit
            com.gatecontrol.android.drive.DriveLogic.CrumbStep.BACK -> navController.popBackStack(page, inclusive = false)
            com.gatecontrol.android.drive.DriveLogic.CrumbStep.TAB -> navController.popBackStack(Screen.Drive.route, inclusive = false)
            com.gatecontrol.android.drive.DriveLogic.CrumbStep.NEW -> navController.navigate(page)
        }
    }
    val file: (Long) -> Unit = { go(Screen.DriveFile.create(it)) }
    val vault: (String, Long) -> Unit = { v, f -> navController.navigate(Screen.DriveVault.create(v, f)) }
}

/**
 * The Drive tab's screen (as the tab or as a page beside Start); [onShown]
 * gets the folder its Files page shows (the tab keeps it for the path above
 * a folder).
 */
@Composable
private fun DriveHome(navController: NavHostController, onShown: (Long?) -> Unit) {
    val nav = remember(navController) { DriveNavigation(navController) }
    GcContentWidth {
        com.gatecontrol.android.ui.drive.DriveHomeScreen(
            com.gatecontrol.android.ui.drive.DriveNav(
                folder = nav.folder,
                shown = onShown,
                search = { nav.go(Screen.DriveSearch.route) },
                trash = { nav.go(Screen.DriveTrash.route) },
                storage = { nav.go(Screen.DriveStorage.route) },
                folders = { nav.go(Screen.DriveFolders.route) },
                album = { nav.go(Screen.DriveAlbum.create(it)) },
                file = nav.file,
                deadlines = { nav.go(Screen.DriveDeadlines.route) },
                photoSearch = { nav.go(Screen.DrivePhotoSearch.route) },
                vault = nav.vault,
                settings = { nav.go(Screen.DriveSettings.route) },
                cleanup = { nav.go(Screen.DriveCleanup.route) },
            ),
        )
    }
}

/**
 * Drive (plugin gatecontrol-drive): the tab, a folder, the trash and the search, each a route of its own so the tab
 * bar shows on the tab only and back works as everywhere else.
 */
private fun NavGraphBuilder.driveRoutes(
    navController: NavHostController,
    navigateToTab: (String) -> Unit,
    home: @Composable (androidx.navigation.NavBackStackEntry) -> Unit,
) {
    val back: () -> Unit = { if (!navController.popBackStack()) navigateToTab(Screen.Drive.route) }
    val nav = DriveNavigation(navController)
    val go = nav.go
    val folder = nav.folder
    val crumb = nav.crumb
    val file = nav.file
    val vault = nav.vault
    composable(Screen.Drive.route) { entry -> home(entry) }
    composable(Screen.DriveFolder.route, arguments = listOf(navArgument(Screen.DriveFolder.ARG_ID) { type = NavType.StringType })) {
        GcContentWidth { com.gatecontrol.android.ui.drive.DriveFolderScreen(onBack = back, onFolder = folder, onFile = file, onVault = vault, onCrumb = crumb) }
    }
    composable(Screen.DriveFile.route, arguments = listOf(navArgument(Screen.DriveFile.ARG_ID) { type = NavType.StringType })) {
        GcContentWidth { com.gatecontrol.android.ui.drive.DriveFileScreen(onBack = back, onFolder = folder) }
    }
    composable(Screen.DriveDeadlines.route) {
        GcContentWidth { com.gatecontrol.android.ui.drive.DriveDeadlinesScreen(onBack = back, onFile = file) }
    }
    composable(Screen.DrivePhotoSearch.route) {
        GcContentWidth { com.gatecontrol.android.ui.drive.DrivePhotoSearchScreen(onBack = back) }
    }
    composable(
        Screen.DriveVault.route,
        arguments = listOf(
            navArgument(Screen.DriveVault.ARG_VAULT) { type = NavType.StringType },
            navArgument(Screen.DriveVault.ARG_FOLDER) { type = NavType.StringType },
            navArgument(Screen.DriveVault.ARG_REQUESTS) { type = NavType.StringType; nullable = true; defaultValue = null },
        ),
    ) {
        GcContentWidth { com.gatecontrol.android.ui.drive.DriveVaultScreen(onBack = back, onFolder = vault) }
    }
    composable(Screen.DriveTrash.route) {
        GcContentWidth { com.gatecontrol.android.ui.drive.DriveTrashScreen(onBack = back) }
    }
    composable(Screen.DriveSearch.route) {
        GcContentWidth { com.gatecontrol.android.ui.drive.DriveSearchScreen(onBack = back, onFolder = folder) }
    }
    composable(Screen.DriveSettings.route) {
        GcContentWidth {
            com.gatecontrol.android.ui.drive.DriveSettingsScreen(
                onBack = back,
                onBackup = { go(Screen.DriveBackup.route) },
                onFolders = { go(Screen.DriveFolders.route) },
            )
        }
    }
    composable(Screen.DriveBackup.route) {
        GcContentWidth { com.gatecontrol.android.ui.drive.DriveBackupScreen(onBack = back, onCleanup = { go(Screen.DriveCleanup.route) }) }
    }
    composable(Screen.DriveCleanup.route) {
        GcContentWidth { com.gatecontrol.android.ui.drive.DriveCleanupScreen(onBack = back) }
    }
    composable(Screen.DriveFolders.route) {
        GcContentWidth { com.gatecontrol.android.ui.drive.DriveFolderBackupScreen(onBack = back) }
    }
    composable(Screen.DriveStorage.route) {
        GcContentWidth { com.gatecontrol.android.ui.drive.DriveStorageScreen(onBack = back, onFolder = folder) }
    }
    composable(Screen.DriveAlbum.route, arguments = listOf(navArgument(Screen.DriveAlbum.ARG_ID) { type = NavType.StringType })) {
        GcContentWidth { com.gatecontrol.android.ui.drive.DriveAlbumScreen(onBack = back) }
    }
}
