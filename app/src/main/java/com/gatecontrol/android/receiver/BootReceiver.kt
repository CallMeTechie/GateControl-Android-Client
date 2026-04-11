package com.gatecontrol.android.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.network.ApiClientProvider
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
    @Inject lateinit var apiClientProvider: ApiClientProvider

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Timber.d("BootReceiver: BOOT_COMPLETED received")

        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val autoConnect = settingsRepository.getAutoConnect().first()
                val isConfigured = setupRepository.isConfigured()

                if (autoConnect && isConfigured) {
                    // Validate token before auto-connecting — skip if expired/deleted
                    val serverUrl = setupRepository.getServerUrl()
                    try {
                        val client = apiClientProvider.getClient(serverUrl)
                        client.ping()
                    } catch (e: retrofit2.HttpException) {
                        if (e.code() == 401 || e.code() == 403) {
                            Timber.w("BootReceiver: token invalid (${e.code()}), skipping auto-connect")
                            pendingResult.finish()
                            return@launch
                        }
                    } catch (_: Exception) {
                        // Network error — allow offline auto-connect
                    }

                    Timber.d("BootReceiver: auto-connect enabled, starting VpnForegroundService")
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
