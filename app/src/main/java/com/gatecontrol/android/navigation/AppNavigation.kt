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
import androidx.compose.foundation.selection.selectable
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
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import com.gatecontrol.android.R
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.network.NETWORK_TAB_PIHOLE
import com.gatecontrol.android.ui.network.NetworkScreen
import com.gatecontrol.android.ui.rdp.RdpScreen
import com.gatecontrol.android.ui.settings.ConnectionDetailsScreen
import com.gatecontrol.android.ui.settings.LogsScreen
import com.gatecontrol.android.ui.profile.ProfileScreen
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.push.PushNavTarget
import com.gatecontrol.android.push.PushNavigation
import com.gatecontrol.android.ui.notifications.InboxScreen
import com.gatecontrol.android.ui.notifications.InboxViewModel
import com.gatecontrol.android.ui.notifications.NotificationDetailScreen
import com.gatecontrol.android.ui.notifications.NotificationSettingsScreen
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.gatecontrol.android.ui.notifications.notificationsAllowed
import com.gatecontrol.android.ui.notifications.bellState

private data class TabItem(
    val route: String,
    val navigateTo: String,
    val label: Int,
    val icon: ImageVector,
    val badge: Int = 0,
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

    val showBottomBar = currentRoute in MainTabs.BAR_ROUTES

    val navigateToTab: (String) -> Unit = { route ->
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    // The plugin's own tab icon (SVG path), else the house.
    val familieIcon = remember(plugins.familieIcon) {
        plugins.familieIcon?.let { path -> runCatching { GcIcons.fromPath("FamilieTab", path) }.getOrNull() } ?: GcIcons.Family
    }
    // Plugin tab (Familie only) after the built-in ones, settings stay last
    // (docs/FAMILIE.md). The door plugin has no tab (docs/DOOR.md).
    val tabs = MainTabs.routes(
        rdp = hasRdpPermission,
        network = hasServicesPermission || hasPiholePermission,
        plugins = plugins,
    ).map { route ->
        when (route) {
            Screen.Vpn.route -> TabItem(route, Screen.Vpn.route, R.string.nav_start, GcIcons.Shield)
            Screen.Rdp.route -> TabItem(route, Screen.Rdp.route, R.string.nav_remote, GcIcons.Monitor, onlineRdpHostCount)
            Screen.Network.route -> TabItem(route, Screen.Network.create(), R.string.nav_network, GcIcons.Globe)
            Screen.Familie.route -> TabItem(route, Screen.Familie.create(), R.string.nav_familie, familieIcon)
            else -> TabItem(route, Screen.Settings.create(), R.string.nav_settings, GcIcons.Sliders)
        }
    }

    // Tablet, unfolded foldable, phone in landscape: rail instead of the
    // bottom bar, list and detail side by side (docs/LARGE_SCREENS.md).
    val layout = rememberGcWindowLayout()

    /** A settings page: beside the settings list on wide screens, else its own route. */
    val openSettingsPane: (SettingsPane) -> Unit = { pane ->
        if (layout.twoPane) {
            // No restoreState: the pane argument must win over a saved settings state.
            navController.navigate(Screen.Settings.create(pane.key)) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
            }
        } else {
            navController.navigate(pane.route()) { launchSingleTop = true }
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

    CompositionLocalProvider(LocalGcWindowLayout provides layout) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (showBottomBar && !layout.useRail) {
                GcTabBar(
                    tabs = tabs,
                    isSelected = isTabSelected,
                    onSelect = { tab -> navigateToTab(tab.navigateTo) },
                )
            }
        },
    ) { innerPadding ->
        Row(Modifier.padding(innerPadding).fillMaxSize()) {
        if (showBottomBar && layout.useRail) {
            GcNavRail(
                tabs = tabs,
                isSelected = isTabSelected,
                onSelect = { tab -> navigateToTab(tab.navigateTo) },
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
                    onOpenPihole = {
                        // No restoreState: the tab argument must win over a saved Netzwerk state.
                        navController.navigate(Screen.Network.create(NETWORK_TAB_PIHOLE)) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                        }
                    },
                    onOpenSplitTunnel = { openSettingsPane(SettingsPane.SplitTunnel) },
                    onOpenLogs = { openSettingsPane(SettingsPane.Logs) },
                    onOpenQuickActions = { navController.navigate(Screen.QuickActions.route) { launchSingleTop = true } },
                    onOpenLocationSettings = { openSettingsPane(SettingsPane.Location) },
                    onOpenDiagnosis = { openSettingsPane(SettingsPane.Diagnosis) },
                    onOpenProfile = { openSettingsPane(SettingsPane.Profile) },
                    // Long press on a door quick action: the door screen, a normal destination — no tab.
                    onOpenDoor = openDoor,
                )
            }

            composable(Screen.Rdp.route) {
                GcContentWidth(maxWidth = 840.dp) { RdpScreen() }
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
                GcContentWidth(maxWidth = 840.dp) {
                    NetworkScreen(
                        hasServices = hasServicesPermission,
                        hasPihole = hasPiholePermission,
                        initialTab = backStackEntry.arguments?.getString(Screen.Network.ARG_TAB),
                        onGoToStart = { navigateToTab(Screen.Vpn.route) },
                    )
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
                                onNavigateToNotifications = { paneKey = SettingsPane.Notifications.key },
                                onNavigateToLogs = { paneKey = SettingsPane.Logs.key },
                                onNavigateToServer = { paneKey = SettingsPane.Server.key },
                                onNavigateToSplitTunnel = { paneKey = SettingsPane.SplitTunnel.key },
                                onNavigateToTrustedNetworks = { paneKey = SettingsPane.TrustedNetworks.key },
                                onNavigateToLocation = { paneKey = SettingsPane.Location.key },
                                onNavigateToDiagnosis = { paneKey = SettingsPane.Diagnosis.key },
                                onNavigateToProfile = { paneKey = SettingsPane.Profile.key },
                                onNavigateToConnection = { paneKey = SettingsPane.Connection.key },
                                selectedPane = pane,
                                viewModel = settingsViewModel,
                            )
                        },
                        detail = {
                            key(pane) {
                                SettingsPaneContent(
                                    pane = pane,
                                    onOpenEnroll = { navController.navigate(Screen.Enroll.create()) },
                                    onSelectPane = { paneKey = it.key },
                                )
                            }
                        },
                    )
                } else {
                    SettingsScreen(
                        onNavigateToNotifications = { navController.navigate(Screen.SettingsNotifications.route) },
                        onNavigateToLogs = { navController.navigate(Screen.Logs.route) },
                        onNavigateToServer = { navController.navigate(Screen.SettingsServer.route) },
                        onNavigateToSplitTunnel = { navController.navigate(Screen.SplitTunnel.route) },
                        onNavigateToTrustedNetworks = { navController.navigate(Screen.TrustedNetworks.route) },
                        onNavigateToLocation = { navController.navigate(Screen.LocationSharing.route) },
                        onNavigateToDiagnosis = { navController.navigate(Screen.Diagnosis.route) { launchSingleTop = true } },
                        onNavigateToProfile = { navController.navigate(Screen.Profile.route) { launchSingleTop = true } },
                        onNavigateToConnection = { navController.navigate(Screen.ConnectionDetails.route) { launchSingleTop = true } },
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
                val dest = FamilieDest.parse(backStackEntry.arguments?.getString(Screen.Familie.ARG_DEST))
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
                                        else if (target != PushNavTarget.Inbox) openPushTarget(navController, target, navigateToTab, layout, plugins.familieTab, plugins.door)
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
                        onNavigate = { target -> openPushTarget(navController, target, navigateToTab, layout, plugins.familieTab, plugins.door) },
                    )
                }
            }

            composable(Screen.SettingsNotifications.route) {
                GcContentWidth { NotificationSettingsScreen(onBack = { navController.popBackStack() }) }
            }

            composable(Screen.Profile.route) {
                GcContentWidth { ProfileScreen(onBack = { navController.popBackStack() }) }
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

            composable(Screen.QuickActions.route) {
                GcContentWidth { QuickActionsScreen(onBack = { navController.popBackStack() }, onOpenDoor = openDoor) }
            }

            composable(Screen.Logs.route) {
                LogsScreen(
                    onNavigateBack = { navController.popBackStack() },
                )
            }
        }

        // A tapped push message (PushOpenActivity) — only once the app is set up.
        val pushTarget by PushNavigation.pending.collectAsStateWithLifecycle()
        LaunchedEffect(pushTarget, isSetupComplete) {
            val target = pushTarget ?: return@LaunchedEffect
            if (!isSetupComplete) return@LaunchedEffect
            PushNavigation.consume(target)
            openPushTarget(navController, target, navigateToTab, layout, plugins.familieTab, plugins.door)
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
    layout: GcWindowLayout,
    familieTab: Boolean,
    /** The door plugin is listed: its pushes open the door screens. */
    door: Boolean = false,
) {
    when (target) {
        PushNavTarget.Inbox -> navController.navigate(Screen.Inbox.create()) { launchSingleTop = true }
        // Wide screens: the message opens beside the inbox list.
        is PushNavTarget.Detail -> if (layout.twoPane) {
            navController.navigate(Screen.Inbox.create(target.id)) { launchSingleTop = true }
        } else {
            navController.navigate(Screen.NotificationDetail.create(target.id))
        }
        PushNavTarget.Vpn -> navigateToTab(Screen.Vpn.route)
        PushNavTarget.Services -> navController.navigate(Screen.Network.create()) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
        }
        is PushNavTarget.Familie -> {
            val dest = FamilieDest.parse(target.dest) ?: FamilieDest.Home
            when {
                // No Familie tab (plugin gone meanwhile): the message itself.
                !familieTab -> openPushTarget(
                    navController,
                    target.messageId?.let { PushNavTarget.Detail(it) } ?: PushNavTarget.Inbox,
                    navigateToTab,
                    layout,
                    familieTab = false,
                    door = door,
                )
                // Wide screens: the screen beside the Familie start screen. No
                // restoreState: the dest argument must win over a saved state.
                layout.twoPane -> navController.navigate(Screen.Familie.create(dest.key)) {
                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                    launchSingleTop = true
                }
                else -> {
                    navigateToTab(Screen.Familie.create())
                    if (dest != FamilieDest.Home) navController.navigate(Screen.FamiliePage.create(dest.key))
                }
            }
        }
        is PushNavTarget.Door -> {
            val steps = DoorNavigation.forPush(target.dest, pluginListed = door, twoPane = layout.twoPane)
            // Plugin gone (or no right any more): the message itself.
            if (steps == null) {
                openPushTarget(
                    navController,
                    target.messageId?.let { PushNavTarget.Detail(it) } ?: PushNavTarget.Inbox,
                    navigateToTab,
                    layout,
                    familieTab,
                    door = false,
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

/** Bottom tab bar from the mockup: pill indicator behind the icon, label below. */
@Composable
private fun GcTabBar(
    tabs: List<TabItem>,
    isSelected: (TabItem) -> Boolean,
    onSelect: (TabItem) -> Unit,
) {
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
            tabs.forEach { tab ->
                val selected = isSelected(tab)
                TabItemContent(
                    tab = tab,
                    selected = selected,
                    modifier = Modifier
                        .weight(1f)
                        .height(72.dp)
                        .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(tab) })
                        .padding(top = 10.dp),
                )
            }
        }
    }
}

/** Icon with pill indicator, badge and label: one entry of the tab bar or the rail. */
@Composable
private fun TabItemContent(tab: TabItem, selected: Boolean, modifier: Modifier) {
    val extra = GateControlTheme.extraColors
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier
                .width(60.dp)
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
) {
    val extra = GateControlTheme.extraColors
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
                TabItemContent(
                    tab = tab,
                    selected = selected,
                    modifier = Modifier
                        .width(80.dp)
                        .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(tab) })
                        .padding(vertical = 6.dp),
                )
            }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(extra.border))
    }
}

/** Single-column pages on wide windows: centred, at most [maxWidth] wide. */
@Composable
private fun GcContentWidth(maxWidth: Dp = 720.dp, content: @Composable () -> Unit) {
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
    SettingsPane.Logs -> Screen.Logs.route
    SettingsPane.Diagnosis -> Screen.Diagnosis.route
}

/** A settings page in the detail pane beside the settings list. */
@Composable
private fun SettingsPaneContent(pane: SettingsPane, onOpenEnroll: () -> Unit, onSelectPane: (SettingsPane) -> Unit) {
    when (pane) {
        SettingsPane.Profile -> ProfileScreen(onBack = {})
        SettingsPane.Connection -> ConnectionDetailsScreen(onBack = {}, onOpenDiagnosis = { onSelectPane(SettingsPane.Diagnosis) })
        SettingsPane.Server -> ServerSettingsScreen(onBack = {}, onNavigateToQrScanner = onOpenEnroll)
        SettingsPane.SplitTunnel -> SplitTunnelScreen(onBack = {})
        SettingsPane.TrustedNetworks -> TrustedNetworksScreen(onBack = {})
        SettingsPane.Location -> LocationSharingScreen(onBack = {})
        SettingsPane.Notifications -> NotificationSettingsScreen(onBack = {})
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
