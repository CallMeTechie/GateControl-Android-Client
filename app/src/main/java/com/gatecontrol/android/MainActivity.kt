package com.gatecontrol.android

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
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
import com.gatecontrol.android.familie.PluginDirectory
import com.gatecontrol.android.navigation.AppNavigation
import com.gatecontrol.android.push.PushController
import com.gatecontrol.android.ui.theme.GateControlTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Exported entry point (launcher + gatecontrol:// links). It deliberately
 * acts on no intent extras: Quick Settings tile actions run in the
 * non-exported [com.gatecontrol.android.service.TileActionActivity], and
 * setup links only open a confirmation dialog.
 *
 * A FragmentActivity (a ComponentActivity) so the door screens can ask for the
 * device unlock with androidx.biometric's BiometricPrompt.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var setupRepository: SetupRepository
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var licenseRepository: LicenseRepository
    @Inject lateinit var pushController: PushController
    @Inject lateinit var pluginDirectory: PluginDirectory
    @Inject lateinit var profileRepository: com.gatecontrol.android.profile.ProfileRepository
    @Inject lateinit var chatRepository: com.gatecontrol.android.chat.ChatRepository

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
                // Cached on the device: the Familie tab is there from the first frame.
                val plugins by pluginDirectory.state.collectAsStateWithLifecycle()

                AppNavigation(
                    navController = navController,
                    isSetupComplete = isSetupComplete,
                    hasRdpPermission = permissions.rdp,
                    hasServicesPermission = permissions.services,
                    hasPiholePermission = permissions.pihole,
                    plugins = plugins,
                    pendingSetupLink = pendingSetupLink,
                    onSetupLinkConsumed = { pendingSetupLink = null },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Battery-optimisation state may have changed in the system settings;
        // a push service refused in the background can start now.
        pushController.onForeground()
        // Plugin list (Familie tab): refreshed in the foreground, at most every few minutes.
        pluginDirectory.refreshIfStale()
        // Own profile (avatar, favourite colour): cached, refreshed in the foreground.
        profileRepository.refreshIfStale()
        // Chats (messenger plugin): list and waiting messages, at most every few seconds.
        chatRepository.refreshIfStale()
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
