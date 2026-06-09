package com.gatecontrol.android.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.BatteryManager
import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.service.VpnForegroundService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var setupRepository: SetupRepository

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Timber.d("BootReceiver: BOOT_COMPLETED")

        val pendingResult = goAsync()

        val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
            Timber.e(throwable, "BootReceiver: CoroutineExceptionHandler")
            try { pendingResult.finish() } catch (_: Exception) {}
        }

        CoroutineScope(Dispatchers.IO + exceptionHandler).launch {
            try {
                val batteryStatus: Intent? = runCatching {
                    context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                }.getOrNull()

                batteryStatus?.let {
                    val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    val batteryPct = level * 100 / scale.toFloat()
                    val status = it.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

                    if (batteryPct < 8 && !isCharging) {
                        Timber.w("BootReceiver: low battery")
                        return@launch
                    }
                }

                val vpnPreparePackage = VpnService.prepare(context)
                if (vpnPreparePackage?.`package` != context.packageName) {
                    Timber.w("BootReceiver: vpn occupied")
                    return@launch
                }

                val checkResult = runCatching {
                    val autoConnect = settingsRepository.getAutoConnect().first()
                    val isConfigured = setupRepository.isConfigured()
                    Pair(autoConnect, isConfigured)
                }

                if (checkResult.isFailure) {
                    Timber.e(checkResult.exceptionOrNull(), "BootReceiver: DataStore failure")
                    return@launch
                }

                val (autoConnect, isConfigured) = checkResult.getOrThrow()

                if (autoConnect && isConfigured) {
                    delay(3000)

                    val serviceIntent = Intent(context, VpnForegroundService::class.java)

                    withContext(Dispatchers.Main) {
                        context.startForegroundService(serviceIntent)
                    }
                } else {
                    Timber.d("BootReceiver: skip connect")
                }

            } catch (e: Exception) {
                Timber.e(e, "BootReceiver: unexpected error")
            } finally {
                try { pendingResult.finish() } catch (_: Exception) {}
            }
        }
    }
}