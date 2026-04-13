package com.gatecontrol.android

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import java.util.Locale
import com.gatecontrol.android.data.LicenseRepository
import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.navigation.AppNavigation
import com.gatecontrol.android.service.VpnTileService
import com.gatecontrol.android.tunnel.TunnelManager
import com.gatecontrol.android.ui.theme.GateControlTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var setupRepository: SetupRepository
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var licenseRepository: LicenseRepository
    @Inject lateinit var tunnelManager: TunnelManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val deepLinkUrl = intent?.data?.getQueryParameter("url")
        val deepLinkToken = intent?.data?.getQueryParameter("token")

        setContent {
            val theme by settingsRepository.getTheme()
                .collectAsStateWithLifecycle(initialValue = "system")
            val sysLang = remember { java.util.Locale.getDefault().language }
            val locale by settingsRepository.getLocale()
                .collectAsStateWithLifecycle(initialValue = if (sysLang == "de") "de" else "en")

            // Apply locale change
            LaunchedEffect(locale) {
                val newLocale = Locale(locale)
                val config = Configuration(resources.configuration)
                config.setLocale(newLocale)
                @Suppress("DEPRECATION")
                resources.updateConfiguration(config, resources.displayMetrics)
            }

            val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
            val isDark = when (theme) {
                "dark" -> true
                "light" -> false
                else -> systemDark // "system" or first launch
            }
            GateControlTheme(darkTheme = isDark) {
                val navController = rememberNavController()
                val isSetupComplete =
                    setupRepository.isConfigured() || setupRepository.hasWireGuardConfig()
                val permissions by licenseRepository.permissions
                    .collectAsStateWithLifecycle()

                AppNavigation(
                    navController = navController,
                    isSetupComplete = isSetupComplete,
                    hasRdpPermission = permissions.rdp,
                    hasServicesPermission = permissions.services,
                )
            }
        }

        if (deepLinkUrl != null && deepLinkToken != null) {
            intent?.putExtra("deep_link_url", deepLinkUrl)
            intent?.putExtra("deep_link_token", deepLinkToken)
        }

        // Handle Quick Settings tile actions
        handleTileAction(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleTileAction(intent)
    }

    private fun handleTileAction(intent: Intent?) {
        val tileAction = intent?.getStringExtra(VpnTileService.EXTRA_TILE_ACTION) ?: return
        // Clear the extra so it doesn't re-trigger on configuration change
        intent.removeExtra(VpnTileService.EXTRA_TILE_ACTION)

        when (tileAction) {
            VpnTileService.ACTION_TILE_CONNECT -> {
                Timber.d("MainActivity: Tile connect action received")
                val prepareIntent = android.net.VpnService.prepare(this)
                if (prepareIntent != null) {
                    // VPN permission not yet granted — user needs to approve
                    // The permission dialog will show, but we can't auto-connect after
                    // For now, just open the app (user sees VPN screen and can tap Connect)
                    Timber.d("MainActivity: VPN permission required, showing app")
                } else {
                    // Permission already granted — connect immediately
                    val config = setupRepository.getWireGuardConfig()
                    if (config.isNotEmpty()) {
                        CoroutineScope(Dispatchers.IO).launch {
                            try {
                                tunnelManager.connect(config)
                                Timber.d("MainActivity: Tile connect succeeded")
                            } catch (e: Exception) {
                                Timber.e(e, "MainActivity: Tile connect failed")
                            }
                        }
                    }
                }
            }
            VpnTileService.ACTION_TILE_DISCONNECT -> {
                Timber.d("MainActivity: Tile disconnect action received")
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        tunnelManager.disconnect()
                        Timber.d("MainActivity: Tile disconnect succeeded")
                    } catch (e: Exception) {
                        Timber.e(e, "MainActivity: Tile disconnect failed")
                    }
                }
            }
        }
    }
}
