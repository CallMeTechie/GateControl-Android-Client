package com.gatecontrol.android.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.gatecontrol.android.ui.settings.LogsScreen
import com.gatecontrol.android.ui.settings.ServerSettingsScreen
import com.gatecontrol.android.ui.settings.SettingsScreen
import com.gatecontrol.android.ui.settings.SplitTunnelScreen
import com.gatecontrol.android.ui.setup.QrScannerScreen
import com.gatecontrol.android.ui.setup.SetupScreen
import com.gatecontrol.android.ui.theme.GateControlTheme
import com.gatecontrol.android.ui.vpn.VpnScreen

private val bottomBarRoutes = setOf(
    Screen.Vpn.route,
    Screen.Rdp.route,
    Screen.Network.route,
    Screen.Settings.route,
)

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
    /** gatecontrol://enroll link the app was opened with; consumed once shown. */
    pendingSetupLink: String? = null,
    onSetupLinkConsumed: () -> Unit = {},
) {
    val startDestination = if (isSetupComplete) Screen.Vpn.route else Screen.Setup.route

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val showBottomBar = currentRoute in bottomBarRoutes

    val navigateToTab: (String) -> Unit = { route ->
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    val tabs = buildList {
        add(TabItem(Screen.Vpn.route, Screen.Vpn.route, R.string.nav_start, GcIcons.Shield))
        if (hasRdpPermission) {
            add(TabItem(Screen.Rdp.route, Screen.Rdp.route, R.string.nav_remote, GcIcons.Monitor, onlineRdpHostCount))
        }
        if (hasServicesPermission || hasPiholePermission) {
            add(TabItem(Screen.Network.route, Screen.Network.create(), R.string.nav_network, GcIcons.Globe))
        }
        add(TabItem(Screen.Settings.route, Screen.Settings.route, R.string.nav_settings, GcIcons.Sliders))
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (showBottomBar) {
                GcTabBar(
                    tabs = tabs,
                    isSelected = { tab -> navBackStackEntry?.destination?.hierarchy?.any { it.route == tab.route } == true },
                    onSelect = { tab -> navigateToTab(tab.navigateTo) },
                )
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Screen.Setup.route) { backStackEntry ->
                val qrResult = backStackEntry.savedStateHandle.get<String>("qr_result")
                SetupScreen(
                    onSetupComplete = {
                        navController.navigate(Screen.Vpn.route) {
                            popUpTo(Screen.Setup.route) { inclusive = true }
                        }
                    },
                    onNavigateToQr = {
                        navController.navigate(Screen.QrScanner.route)
                    },
                    qrResult = qrResult,
                )
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
                SetupScreen(
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
                )
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
                VpnScreen(
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
                    onOpenSplitTunnel = { navController.navigate(Screen.SplitTunnel.route) },
                    onOpenLogs = { navController.navigate(Screen.Logs.route) },
                )
            }

            composable(Screen.Rdp.route) {
                RdpScreen()
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
                NetworkScreen(
                    hasServices = hasServicesPermission,
                    hasPihole = hasPiholePermission,
                    initialTab = backStackEntry.arguments?.getString(Screen.Network.ARG_TAB),
                    onGoToStart = { navigateToTab(Screen.Vpn.route) },
                )
            }

            composable(Screen.Settings.route) {
                SettingsScreen(
                    onNavigateToLogs = { navController.navigate(Screen.Logs.route) },
                    onNavigateToServer = { navController.navigate(Screen.SettingsServer.route) },
                    onNavigateToSplitTunnel = { navController.navigate(Screen.SplitTunnel.route) },
                )
            }

            composable(Screen.SettingsServer.route) {
                ServerSettingsScreen(
                    onBack = { navController.popBackStack() },
                    // Scans land on the setup screen, which understands both the
                    // setup QR (VPN + API access) and a bare WireGuard config.
                    onNavigateToQrScanner = { navController.navigate(Screen.Enroll.create()) },
                )
            }

            composable(Screen.SplitTunnel.route) {
                SplitTunnelScreen(onBack = { navController.popBackStack() })
            }

            composable(Screen.Logs.route) {
                LogsScreen(
                    onNavigateBack = { navController.popBackStack() },
                )
            }
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
                val label = stringResource(tab.label)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .height(72.dp)
                        .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(tab) })
                        .padding(top = 10.dp),
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
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected) scheme.onSurface else extra.muted,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
