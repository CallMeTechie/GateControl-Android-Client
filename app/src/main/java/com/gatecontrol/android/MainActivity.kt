package com.gatecontrol.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import com.gatecontrol.android.data.LicenseRepository
import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.navigation.AppNavigation
import com.gatecontrol.android.ui.theme.GateControlTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var setupRepository: SetupRepository
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var licenseRepository: LicenseRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val deepLinkUrl = intent?.data?.getQueryParameter("url")
        val deepLinkToken = intent?.data?.getQueryParameter("token")

        setContent {
            val theme by settingsRepository.getTheme()
                .collectAsStateWithLifecycle(initialValue = "dark")

            GateControlTheme(darkTheme = theme == "dark") {
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
            // Deep-link received at cold start — navigate to setup and auto-register
            // Handled via intent extras forwarded to SetupViewModel
            intent?.putExtra("deep_link_url", deepLinkUrl)
            intent?.putExtra("deep_link_token", deepLinkToken)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Deep-link from running app: the NavHost will pick up the new intent
        // via the updated setIntent call above.
    }
}
