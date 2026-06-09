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
import com.gatecontrol.android.network.ApiClientProvider
import com.gatecontrol.android.service.VpnForegroundService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject
import kotlin.coroutines.resume

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var setupRepository: SetupRepository
    @Inject lateinit var apiClientProvider: ApiClientProvider

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Timber.d("BootReceiver: BOOT_COMPLETED received")

        val pendingResult = goAsync()

        val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
            Timber.e(throwable, "BootReceiver: 全局未捕获异常")
            try { pendingResult.finish() } catch (_: Exception) {}
        }

        CoroutineScope(Dispatchers.IO + exceptionHandler).launch {
            try {
                // 1. 低电量保护策略
                val batteryStatus: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                batteryStatus?.let {
                    val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    val batteryPct = level * 100 / scale.toFloat()
                    val status = it.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

                    if (batteryPct < 8 && !isCharging) {
                        Timber.w("BootReceiver: 电池电量极低 (${batteryPct}%) 且未充电，放弃开机自启")
                        return@launch
                    }
                }

                // 2. 始终开启的 VPN (Always-on) 冲突预检
                val vpnPreparePackage = VpnService.prepare(context)
                if (vpnPreparePackage != null && vpnPreparePackage != context.packageName) {
                    Timber.w("BootReceiver: 发现系统 VPN 权限当前被其他应用 [%s] 占用，终止自启", vpnPreparePackage)
                    return@launch
                }

                // 读取配置
                val checkResult = runCatching {
                    val autoConnect = settingsRepository.getAutoConnect().first()
                    val isConfigured = setupRepository.isConfigured()
                    val serverUrl = setupRepository.getServerUrl()
                    Triple(autoConnect, isConfigured, serverUrl)
                }

                if (checkResult.isFailure) {
                    Timber.e(checkResult.exceptionOrNull(), "BootReceiver: 读取 DataStore 失败")
                    return@launch
                }

                val (autoConnect, isConfigured, serverUrl) = checkResult.getOrThrow()

                if (autoConnect && isConfigured) {
                    // 开机避峰延迟
                    Timber.d("BootReceiver: 准备就绪，延迟 3 秒避开开机 I/O 高峰...")
                    delay(3000)

                    // 3. 动态等待开机网络就绪（最多等待 10 秒）
                    Timber.d("BootReceiver: 正在等待系统网络（Wi-Fi/蜂窝）分配有效 IP...")
                    val isNetworkReady = withTimeoutOrNull(10000) {
                        waitForNetwork(context)
                    } ?: false

                    if (isNetworkReady) {
                        Timber.d("BootReceiver: 检测到可用网络已就绪，开始执行 Token 验证")
                    } else {
                        Timber.w("BootReceiver: 等待超时，当前开机环境无可用网络，将进入离线盲启模式")
                    }

                    // Token 验证
                    try {
                        val client = apiClientProvider.getClient(serverUrl)
                        client.ping()
                    } catch (e: retrofit2.HttpException) {
                        if (e.code() == 401 || e.code() == 403) {
                            Timber.w("BootReceiver: token invalid (${e.code()}), skipping auto-connect")
                            return@launch
                        }
                    } catch (_: Exception) {
                        Timber.d("BootReceiver: Ping 失败，允许离线状态下强行建立 VPN 本地隧道")
                    }

                    Timber.d("BootReceiver: 条件全面满足，正在拉起前台 VPN 服务...")
                    val serviceIntent = Intent(context, VpnForegroundService::class.java).apply {
                        putExtra(VpnForegroundService.EXTRA_SERVER, serverUrl)
                    }
                    
                    // 确保切回主线程安全发起系统调用，提高 Android 14 兼容性
                    withContext(Dispatchers.Main) {
                        context.startForegroundService(serviceIntent)
                    }
                } else {
                    Timber.d("BootReceiver: 未开启自动连接或未配置，跳过")
                }
            } catch (e: Exception) {
                Timber.e(e, "BootReceiver: 运行期异常")
            } finally {
                Timber.d("BootReceiver: 广播处理生命周期结束")
                try { pendingResult.finish() } catch (_: Exception) {}
            }
        }
    }

    /**
     * 利用系统的 ConnectivityManager 挂起等待，直到有可用的互联网连接
     */
    private suspend fun waitForNetwork(context: Context): Boolean = suspendCancellableCoroutine { continuation ->
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        // 检查当前是不是其实已经有网了
        val activeNetwork = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
        if (capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            if (continuation.isActive) continuation.resume(true)
            return@suspendCancellableCoroutine
        }

        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (continuation.isActive) continuation.resume(true)
            }
        }

        connectivityManager.registerNetworkCallback(networkRequest, callback)

        // 统一在取消或结束时安全注销监听，防止多重注销崩溃
        continuation.invokeOnCancellation {
            runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        }
    }
}
