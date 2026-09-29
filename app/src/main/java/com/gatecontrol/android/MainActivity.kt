package com.gatecontrol.android

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import java.util.Locale
import com.gatecontrol.android.common.EnrollmentLink
import com.gatecontrol.android.data.LicenseRepository
import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.navigation.AppNavigation
import com.gatecontrol.android.ui.theme.GateControlTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Exported entry point (launcher + gatecontrol:// links). It deliberately
 * acts on no intent extras: Quick Settings tile actions run in the
 * non-exported [com.gatecontrol.android.service.TileActionActivity], and
 * setup links only open a confirmation dialog.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var setupRepository: SetupRepository
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var licenseRepository: LicenseRepository

    /** gatecontrol://enroll link from outside the app, shown on the setup screen for confirmation. */
    private var pendingSetupLink by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (savedInstanceState == null) captureSetupLink(intent)

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
                    hasPiholePermission = permissions.pihole,
                    pendingSetupLink = pendingSetupLink,
                    onSetupLinkConsumed = { pendingSetupLink = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        captureSetupLink(intent)
    }

    private fun captureSetupLink(intent: Intent?) {
        val raw = intent?.dataString ?: return
        if (EnrollmentLink.parse(raw) != null) pendingSetupLink = raw
    }
}
