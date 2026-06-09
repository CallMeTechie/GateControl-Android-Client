package com.gatecontrol.android.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
        Timber.d("BootReceiver: 接收到开机广播 BOOT_COMPLETED")

        // 启用异步非阻塞生命周期，防止 BroadcastReceiver 在 10 秒内被系统强杀
        val pendingResult = goAsync()

        // 顶层协程异常捕捉器，防止内部未捕获的严重异常导致应用直接崩溃
        val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
            Timber.e(throwable, "BootReceiver: 发生全局未捕获异常")
            try { pendingResult.finish() } catch (_: Exception) {}
        }

        CoroutineScope(Dispatchers.IO + exceptionHandler).launch {
            try {
                // 1. 低电量保护：若电量过低且未充电，跳过自启以保护手机电池
                val batteryStatus: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                batteryStatus?.let {
                    val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    val batteryPct = level * 100 / scale.toFloat()
                    val status = it.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

                    if (batteryPct < 8 && !isCharging) {
                        Timber.w("BootReceiver: 当前电量极低 (${batteryPct}%) 且未充电，放弃开机自启")
                        return@launch
                    }
                }

                // 2. 系统权限预检：检查系统 Always-on VPN 是否被其他应用强行占用
                val vpnPreparePackage = VpnService.prepare(context)
                if (vpnPreparePackage != null && vpnPreparePackage != context.packageName) {
                    Timber.w("BootReceiver: 发现系统 VPN 权限当前被其他应用 [%s] 占用，终止自启", vpnPreparePackage)
                    return@launch
                }

                // 3. 稳健读取 DataStore 配置（此处已完全移除 serverUrl 读取）
                // 使用 runCatching 牢牢包裹，拦截文件级全0物理损坏导致的读取闪退
                val checkResult = runCatching {
                    val autoConnect = settingsRepository.getAutoConnect().first()
                    val isConfigured = setupRepository.isConfigured()
                    Pair(autoConnect, isConfigured)
                }

                if (checkResult.isFailure) {
                    // 如果文件底层坏了，打印日志并熔断退出，不抛出异常，应用便不会闪退
                    Timber.e(checkResult.exceptionOrNull(), "BootReceiver: 读取 DataStore 失败（本地 preferences_pb 文件物理损坏）")
                    return@launch
                }

                val (autoConnect, isConfigured) = checkResult.getOrThrow()

                // 4. 业务条件判断
                if (autoConnect && isConfigured) {
                    Timber.d("BootReceiver: 满足启动基础条件，延迟 3 秒避开开机 CPU 峰值...")
                    delay(3000)

                    // 5. 拉起前台 VPN 服务（不携带额外的 serverUrl 参数）
                    Timber.d("BootReceiver: 正在向系统申请拉起 VpnForegroundService...")
                    val serviceIntent = Intent(context, VpnForegroundService::class.java)
                    
                    // 必须切回主线程进行系统前台调用，以满足 Android 14+ 后台拉起前台服务的时序和性能审计要求
                    withContext(Dispatchers.Main) {
                        context.startForegroundService(serviceIntent)
                    }
                } else {
                    Timber.d("BootReceiver: 用户未开启自动连接($autoConnect) 或 设备未完成基础配置($isConfigured)，跳过自启")
                }

            } catch (e: Exception) {
                Timber.e(e, "BootReceiver: 运行时发生未知异常")
            } finally {
                Timber.d("BootReceiver: 广播异步生命周期安全结束")
                try { pendingResult.finish() } catch (_: Exception) {}
            }
        }
    }
}
