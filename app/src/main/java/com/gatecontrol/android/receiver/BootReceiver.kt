package com.gatecontrol.android.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.service.VpnForegroundService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var setupRepository: SetupRepository

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Timber.d("BootReceiver: BOOT_COMPLETED received")

        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val autoConnect = settingsRepository.getAutoConnect().first()
                val isConfigured = setupRepository.isConfigured()

                if (autoConnect && isConfigured) {
                    Timber.d("BootReceiver: auto-connect enabled, starting VpnForegroundService")
                    val serverUrl = setupRepository.getServerUrl()
                    val serviceIntent = Intent(context, VpnForegroundService::class.java).apply {
                        putExtra(VpnForegroundService.EXTRA_SERVER, serverUrl)
                    }
                    context.startForegroundService(serviceIntent)
                } else {
                    Timber.d("BootReceiver: auto-connect disabled or not configured, skipping")
                }
            } catch (e: Exception) {
                Timber.e(e, "BootReceiver: error during auto-connect")
            } finally {
                pendingResult.finish()
            }
        }
    }
}
