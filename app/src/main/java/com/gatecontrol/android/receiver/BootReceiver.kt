package com.gatecontrol.android.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.network.ApiClientProvider
import com.gatecontrol.android.service.TunnelConnector
import com.gatecontrol.android.tunnel.TunnelManager
import com.gatecontrol.android.tunnel.TunnelState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var setupRepository: SetupRepository
    @Inject lateinit var apiClientProvider: ApiClientProvider
    @Inject lateinit var tunnelConnector: TunnelConnector
    @Inject lateinit var tunnelManager: TunnelManager

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Timber.d("BootReceiver: BOOT_COMPLETED received")

        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val autoConnect = settingsRepository.getAutoConnect().first()
                val isConfigured = setupRepository.isConfigured()

                if (autoConnect && isConfigured) {
                    // Without a granted VPN consent the backend cannot start;
                    // the user has to connect once from the app first.
                    if (VpnService.prepare(context) != null) {
                        Timber.w("BootReceiver: VPN permission not granted, skipping auto-connect")
                        return@launch
                    }
                    // Always-on VPN may already have brought the tunnel up.
                    if (tunnelManager.state.value !is TunnelState.Disconnected) {
                        Timber.d("BootReceiver: tunnel already active, nothing to do")
                        return@launch
                    }
                    // Validate token before auto-connecting — skip if expired/deleted
                    val serverUrl = setupRepository.getServerUrl()
                    try {
                        val client = apiClientProvider.getClient(serverUrl)
                        client.ping()
                    } catch (e: retrofit2.HttpException) {
                        // Only 401 means the token is gone — a 403 may come from a WAF or proxy.
                        if (e.code() == 401) {
                            Timber.w("BootReceiver: token invalid (${e.code()}), skipping auto-connect")
                            return@launch
                        }
                    } catch (_: Exception) {
                        // Network error — allow offline auto-connect
                    }

                    Timber.d("BootReceiver: auto-connect enabled, connecting tunnel")
                    // Stay inside the broadcast's time budget (goAsync).
                    val ok = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                        tunnelConnector.connectWithUserSettings()
                    } == true
                    Timber.i("BootReceiver: auto-connect %s", if (ok) "requested" else "failed")
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

    private companion object {
        const val CONNECT_TIMEOUT_MS = 50_000L
    }
}
