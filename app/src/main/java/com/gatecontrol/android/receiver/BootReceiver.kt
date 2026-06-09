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

        // 立即告知系统该广播进入异步长生命周期操作
        val pendingResult = goAsync()

        // 全局协程异常守护，防止网络或解析严重错误击穿应用，确保最终能释放 pendingResult
        val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
            Timber.e(throwable, "BootReceiver: 全局未捕获异常")
            try { pendingResult.finish() } catch (_: Exception) {}
        }

        CoroutineScope(Dispatchers.IO + exceptionHandler).launch {
            try {
                // 1. 防御策略：低电量保护
                val batteryStatus: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                batteryStatus?.let {
                    val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    val batteryPct = level * 100 / scale.toFloat()
                    val status = it.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

                    if (batteryPct < 8 && !isCharging) {
                        Timber.w("BootReceiver: 电池电量极低 (${batteryPct}%) 且未充电，放弃开机自启以保护系统")
                        return@launch
                    }
                }

                // 2. 防御策略：Always-on VPN 冲突预检
                val vpnPreparePackage = VpnService.prepare(context)
                if (vpnPreparePackage != null && vpnPreparePackage != context.packageName) {
                    Timber.w("BootReceiver: 发现系统 VPN 权限当前被其他应用 [%s] 占用，终止自启避免冲突", vpnPreparePackage)
                    return@launch
                }

                // 3. 稳健读取 DataStore（通过 runCatching 杜绝 Protocol Buffer 文件物理损坏导致闪退）
                val checkResult = runCatching {
                    val autoConnect = settingsRepository.getAutoConnect().first()
                    val isConfigured = setupRepository.isConfigured()
                    val serverUrl = setupRepository.getServerUrl()
                    Triple(autoConnect, isConfigured, serverUrl)
                }

                if (checkResult.isFailure) {
                    Timber.e(checkResult.exceptionOrNull(), "BootReceiver: 读取 DataStore 配置失败（可能本地文件遭遇全0损坏）")
                    return@launch
                }

                val (autoConnect, isConfigured, serverUrl) = checkResult.getOrThrow()

                if (autoConnect && isConfigured) {
                    // 4. 开机避峰延迟：让出开机瞬间极高负载的系统 I/O 资源
                    Timber.d("BootReceiver: 满足启动基础条件，延迟 3 秒避开开机高峰...")
                    delay(3000)

                    // 5. 动态挂起：精准等待网络管道（Wi-Fi/蜂窝）分配到有效互联网 IP（最高等待 10 秒）
                    Timber.d("BootReceiver: 正在监听系统互联网通道就绪...")
                    val isNetworkReady = withTimeoutOrNull(10000) {
                        waitForNetwork(context)
                    } ?: false

                    if (isNetworkReady) {
                        Timber.d("BootReceiver: 可用网络已通，准备发起 Token 验证...")
                    } else {
                        Timber.w("BootReceiver: 等待网络超时，当前环境无可用网络，将进入离线盲启模式")
                    }

                    // 6. 服务端身份预检 (Token 校验)
                    try {
                        val client = apiClientProvider.getClient(serverUrl)
                        client.ping()
                    } catch (e: retrofit2.HttpException) {
                        if (e.code() == 401 || e.code() == 403) {
                            Timber.w("BootReceiver: 账号 Token 已失效 (${e.code()})，中断自动连接")
                            return@launch
                        }
                    } catch (_: Exception) {
                        Timber.d("BootReceiver: 无法连接至服务器或网络不通，允许建立本地离线 VPN 隧道")
                    }

                    // 7. 安全拉起前台 VPN 服务
                    Timber.d("BootReceiver: 正在向系统申请拉起 VpnForegroundService...")
                    val serviceIntent = Intent(context, VpnForegroundService::class.java).apply {
                        putExtra(VpnForegroundService.EXTRA_SERVER, serverUrl)
                    }
                    
                    // 必须切回主线程进行系统调用，以满足 Android 14 后台启动前台服务的时序要求
                    withContext(Dispatchers.Main) {
                        context.startForegroundService(serviceIntent)
                    }
                } else {
                    Timber.d("BootReceiver: 用户未开启自动连接或设备未配置，跳过")
                }
            } catch (e: Exception) {
                Timber.e(e, "BootReceiver: 运行时发生未知异常")
            } finally {
                // 确保不论是执行完毕、中途 return、还是发生异常，都会通知系统关闭此广播流
                Timber.d("BootReceiver: 广播异步生命周期完美结束")
                try { pendingResult.finish() } catch (_: Exception) {}
            }
        }
    }

    /**
     * 挂起等待，直到系统回调通知互联网网络可用
     */
    private suspend fun waitForNetwork(context: Context): Boolean = suspendCancellableCoroutine { continuation ->
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        // 快速检查：如果当前网络已经是通的，直接放行
        val activeNetwork = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
        if (capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            if (continuation.isActive) continuation.resume(true)
            return@suspendCancellableCoroutine
        }

        // 动态注册监听
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (continuation.isActive) continuation.resume(true)
            }
        }

        connectivityManager.registerNetworkCallback(networkRequest, callback)

        // 统一在取消或结束时注销监听，杜绝内存泄漏和重复解绑带来的 IllegalArgumentException 崩溃
        continuation.invokeOnCancellation {
            runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        }
    }
}
