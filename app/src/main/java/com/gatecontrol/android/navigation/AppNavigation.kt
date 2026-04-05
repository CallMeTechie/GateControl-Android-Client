package com.gatecontrol.android.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import com.gatecontrol.android.R
import com.gatecontrol.android.ui.services.ServicesScreen
import com.gatecontrol.android.ui.setup.QrScannerScreen
import com.gatecontrol.android.ui.setup.SetupScreen
import com.gatecontrol.android.ui.vpn.VpnScreen

private val bottomBarRoutes = setOf(
    Screen.Vpn.route,
    Screen.Rdp.route,
    Screen.Services.route,
    Screen.Settings.route,
)

@Composable
fun AppNavigation(
    navController: NavHostController,
    isSetupComplete: Boolean,
    hasRdpPermission: Boolean,
    hasServicesPermission: Boolean,
    onlineRdpHostCount: Int = 0,
) {
    val startDestination = if (isSetupComplete) Screen.Vpn.route else Screen.Setup.route

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val showBottomBar = currentRoute in bottomBarRoutes

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                GcBottomNavigationBar(
                    currentRoute = currentRoute,
                    hasRdpPermission = hasRdpPermission,
                    hasServicesPermission = hasServicesPermission,
                    onlineRdpHostCount = onlineRdpHostCount,
                    onNavigate = { route ->
                        navController.navigate(route) {
                            popUpTo(navController.graph.startDestinationId) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Screen.Setup.route) {
                SetupScreen(
                    onSetupComplete = {
                        navController.navigate(Screen.Vpn.route) {
                            popUpTo(Screen.Setup.route) { inclusive = true }
                        }
                    },
                    onNavigateToQr = {
                        navController.navigate(Screen.QrScanner.route)
                    },
                )
            }

            composable(Screen.QrScanner.route) {
                QrScannerScreen(
                    onQrScanned = { _ ->
                        navController.popBackStack()
                    },
                    onBack = {
                        navController.popBackStack()
                    },
                )
            }

            composable(Screen.Vpn.route) {
                VpnScreen()
            }

            composable(Screen.Rdp.route) {
                // RdpScreen implemented in separate task
                androidx.compose.foundation.layout.Box(modifier = Modifier)
            }

            composable(Screen.Services.route) {
                ServicesScreen()
            }

            composable(Screen.Settings.route) {
                // SettingsScreen implemented in separate task
                androidx.compose.foundation.layout.Box(modifier = Modifier)
            }

            composable(Screen.Logs.route) {
                // LogsScreen implemented in separate task
                androidx.compose.foundation.layout.Box(modifier = Modifier)
            }
        }
    }
}

@Composable
private fun GcBottomNavigationBar(
    currentRoute: String?,
    hasRdpPermission: Boolean,
    hasServicesPermission: Boolean,
    onlineRdpHostCount: Int,
    onNavigate: (String) -> Unit,
) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        // VPN tab
        NavigationBarItem(
            selected = currentRoute == Screen.Vpn.route,
            onClick = { onNavigate(Screen.Vpn.route) },
            icon = {
                Icon(
                    imageVector = Icons.Filled.Shield,
                    contentDescription = stringResource(R.string.nav_vpn),
                )
            },
            label = { Text(stringResource(R.string.nav_vpn)) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = MaterialTheme.colorScheme.primary,
                selectedTextColor = MaterialTheme.colorScheme.primary,
                indicatorColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.24f),
            ),
        )

        // RDP tab (visible when Pro license / permission)
        if (hasRdpPermission) {
            NavigationBarItem(
                selected = currentRoute == Screen.Rdp.route,
                onClick = { onNavigate(Screen.Rdp.route) },
                icon = {
                    BadgedBox(
                        badge = {
                            if (onlineRdpHostCount > 0) {
                                Badge {
                                    Text(onlineRdpHostCount.toString())
                                }
                            }
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Monitor,
                            contentDescription = stringResource(R.string.nav_rdp),
                        )
                    }
                },
                label = { Text(stringResource(R.string.nav_rdp)) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.24f),
                ),
            )
        }

        // Services tab (visible when API token present)
        if (hasServicesPermission) {
            NavigationBarItem(
                selected = currentRoute == Screen.Services.route,
                onClick = { onNavigate(Screen.Services.route) },
                icon = {
                    Icon(
                        imageVector = Icons.Filled.Language,
                        contentDescription = stringResource(R.string.nav_services),
                    )
                },
                label = { Text(stringResource(R.string.nav_services)) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.24f),
                ),
            )
        }

        // Settings tab
        NavigationBarItem(
            selected = currentRoute == Screen.Settings.route,
            onClick = { onNavigate(Screen.Settings.route) },
            icon = {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = stringResource(R.string.nav_settings),
                )
            },
            label = { Text(stringResource(R.string.nav_settings)) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = MaterialTheme.colorScheme.primary,
                selectedTextColor = MaterialTheme.colorScheme.primary,
                indicatorColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.24f),
            ),
        )
    }
}
