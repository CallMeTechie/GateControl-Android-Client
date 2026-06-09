package com.gatecontrol.android.tunnel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.config.Interface
import com.wireguard.config.InetAddresses
import com.wireguard.config.InetNetwork
import com.wireguard.config.Peer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WireGuard 隧道管理器（集成客户端防检测功能 + 双轨探测引擎）。
 *
 * ## 双轨探测引擎（Screen-On 且有网络时激活）
 *
 * - 轨道 A（直连流）：向用户配置的国内公共 IP 发 TCP 探测，不经过 VpnService。
 *   反映本地物理网络是否可用。
 * - 轨道 B（隧道流）：向隧道对端 IP 发 UDP 探测（GoBackend 会将其加密后通过
 *   WireGuard 隧道转发）。反映出海隧道是否畅通。
 *
 * ## 三态裁决
 *
 * | A  | B  | 结论           | 动作                          |
 * |----|----|----------------|-------------------------------|
 * | 通 | 通 | 网络全通       | 重置失败计数                  |
 * | 断 | 断 | 环境断网       | 锁死状态，不重连              |
 * | 通 | 断 | 精准阻断（被封）| 失败计数 +1，触发熔断重连    |
 *
 * ## 冷却锁（防 Cannot set address）
 *
 * Android 的 `jniSetAddresses` 要求上一次 DOWN 的 TUN fd 在内核侧完全关闭后
 * 才能再次调用 `establish()`。连续快速重建会触发 `IllegalArgumentException:
 * Cannot set address`。冷却锁强制两次 establish() 之间至少间隔 [RECONNECT_COOLDOWN_MS]。
 *
 * ## 屏幕状态感知
 *
 * 屏幕熄灭时挂起高频双轨探测（停止 while-loop），切换为被动监听（仅依赖
 * WireGuard 原生 Keepalive 保活）。亮屏时恢复主动探测。
 */
@Singleton
class TunnelManager @Inject constructor(private val context: Context) {

    private val _state = MutableStateFlow<TunnelState>(TunnelState.Disconnected)
    val state: StateFlow<TunnelState> = _state.asStateFlow()

    private val _stats = MutableStateFlow(TunnelStats())
    val stats: StateFlow<TunnelStats> = _stats.asStateFlow()

    private var backend: Backend? = null
    private var tunnel: Tunnel? = null

    /** 防止并发 connect/reconnect 竞争（含冷却锁保护） */
    private val connectMutex = Mutex()

    /** 上次 establish() 调用的时间戳（毫秒），用于冷却锁计算 */
    private val lastEstablishMs = AtomicLong(0L)

    private var prevRxBytes: Long = 0L
    private var prevTxBytes: Long = 0L
    private var prevStatsTime: Long = 0L

    /** 当前使用的防检测配置（用于自动重连时读取） */
    private var currentStealthConfig: StealthConfig = StealthConfig()

    /** 当前使用的原始配置字符串（用于自动重连时重新准备） */
    private var currentRawConfig: String = ""

    /** 当前使用的分流配置（用于自动重连） */
    private var currentSplitConfig: SplitTunnelConfig = SplitTunnelConfig()

    private val stealthEngine = StealthEngine()

    // ── 双轨探测引擎状态 ───────────────────────────────────────────────────

    /** 连续探测失败计数（仅"A通+B断"时递增） */
    private var probeFailureCount: Int = 0

    /** 探测协程 Scope（亮屏时启动，熄屏时取消） */
    private var probeScope: CoroutineScope? = null

    /** 屏幕是否亮屏 */
    private val isScreenOn = AtomicBoolean(true)

    /** 物理网络是否可用 */
    private val isNetworkAvailable = AtomicBoolean(true)

    /** 屏幕状态广播接收器 */
    private var screenReceiver: BroadcastReceiver? = null

    /** ConnectivityManager 网络回调 */
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // ── 用户可配置探测参数（提供默认值，可通过 configureProbe 覆盖） ────

    /**
     * 直连探测目标（轨道 A）。
     * 推荐填国内公共 DNS IP，如 114.114.114.114，该地址不应被加入 AllowedIPs。
     */
    var bypassProbeTarget: String = DEFAULT_BYPASS_PROBE

    /**
     * 隧道探测目标（轨道 B）。
     * 必须是在 AllowedIPs 内的海外 IP，GoBackend 会将其流量送入隧道。
     * 默认使用 VPN 网关地址（10.8.0.1），始终在 AllowedIPs 内。
     */
    var tunnelProbeTarget: String = DEFAULT_TUNNEL_PROBE

    /** 单次探测超时（毫秒） */
    var probeTimeoutMs: Long = DEFAULT_PROBE_TIMEOUT_MS

    /** 熔断重连阈值：连续"精准阻断"次数达到此值时触发端口跳变 */
    var failureThreshold: Int = DEFAULT_FAILURE_THRESHOLD

    // ── 初始化 ─────────────────────────────────────────────────────────────

    fun initialize() {
        try {
            backend = GoBackend(context)
            tunnel = object : Tunnel {
                override fun getName(): String = TUNNEL_NAME
                override fun onStateChange(newState: Tunnel.State) {
                    Timber.d("Tunnel state changed: $newState")
                }
            }
            Timber.d("TunnelManager initialized")
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize TunnelManager")
        }

        registerScreenReceiver()
        registerNetworkCallback()
    }

    // ── 公开连接接口 ────────────────────────────────────────────────────────

    /** 向后兼容入口（无防检测配置） */
    suspend fun connect(
        configString: String,
        splitTunnelRoutes: List<String> = emptyList(),
        excludedApps: List<String> = emptyList()
    ) {
        val splitConfig = if (splitTunnelRoutes.isNotEmpty() || excludedApps.isNotEmpty()) {
            SplitTunnelConfig(mode = "include", networks = splitTunnelRoutes, apps = excludedApps)
        } else {
            SplitTunnelConfig()
        }
        connectInternal(configString, splitConfig, StealthConfig())
    }

    /** 使用分流配置连接（无防检测） */
    suspend fun connect(configString: String, splitConfig: SplitTunnelConfig) {
        connectInternal(configString, splitConfig, StealthConfig())
    }

    /** 完整连接入口：支持分流配置 + 防检测配置 */
    suspend fun connect(
        configString: String,
        splitConfig: SplitTunnelConfig,
        stealthConfig: StealthConfig,
    ) {
        connectInternal(configString, splitConfig, stealthConfig)
    }

    // ── 核心连接实现 ────────────────────────────────────────────────────────

    private suspend fun connectInternal(
        configString: String,
        splitConfig: SplitTunnelConfig,
        stealthConfig: StealthConfig,
    ) {
        withContext(Dispatchers.IO) {
            connectMutex.withLock {
                try {
                    currentRawConfig = configString
                    currentSplitConfig = splitConfig
                    currentStealthConfig = stealthConfig

                    val parsedConfig = TunnelConfig.parse(configString)

                    val stealthedConfig = if (stealthConfig.portHoppingEnabled
                        || stealthConfig.timingJitterEnabled
                        || stealthConfig.packetPaddingEnabled
                        || stealthConfig.keepaliveRandomEnabled
                        || stealthConfig.decoyDnsEnabled
                    ) {
                        Timber.d("TunnelManager: running stealth pre-processing")
                        stealthEngine.prepareConnection(stealthConfig, parsedConfig)
                    } else {
                        parsedConfig
                    }

                    val wgConfig = buildWgConfig(stealthedConfig, splitConfig)

                    _state.value = TunnelState.Connecting
                    Timber.d(
                        "TunnelManager: connecting (mode=%s, stealth: port=%b jitter=%b padding=%b keepalive=%b decoy=%b)",
                        splitConfig.mode,
                        stealthConfig.portHoppingEnabled,
                        stealthConfig.timingJitterEnabled,
                        stealthConfig.packetPaddingEnabled,
                        stealthConfig.keepaliveRandomEnabled,
                        stealthConfig.decoyDnsEnabled,
                    )

                    val currentBackend = backend ?: run { initialize(); backend }
                        ?: throw IllegalStateException("Backend not available")
                    val currentTunnel = tunnel
                        ?: throw IllegalStateException("Tunnel not initialized")

                    // 冷却锁：确保上次 establish 到本次至少间隔 RECONNECT_COOLDOWN_MS
                    enforceReconnectCooldown()

                    currentBackend.setState(currentTunnel, Tunnel.State.UP, wgConfig)
                    lastEstablishMs.set(System.currentTimeMillis())

                    prevRxBytes = 0L
                    prevTxBytes = 0L
                    prevStatsTime = System.currentTimeMillis()
                    probeFailureCount = 0

                    _state.value = TunnelState.Connected()
                    Timber.i("TunnelManager: tunnel connected successfully")

                    // 连接成功后启动双轨探测（仅在 autoReconnectOnBlock 开启时）
                    if (stealthConfig.autoReconnectOnBlock && stealthConfig.portHoppingEnabled) {
                        startProbeEngine()
                    }
                } catch (e: Exception) {
                    Timber.e(e, "TunnelManager: failed to connect tunnel")
                    _state.value = TunnelState.Error(e.message ?: "Unknown error")
                }
            }
        }
    }

    suspend fun disconnect() {
        stopProbeEngine()
        withContext(Dispatchers.IO) {
            try {
                _state.value = TunnelState.Disconnecting
                Timber.d("TunnelManager: disconnecting...")

                val currentBackend = backend
                val currentTunnel = tunnel
                if (currentBackend != null && currentTunnel != null) {
                    currentBackend.setState(currentTunnel, Tunnel.State.DOWN, null)
                }

                _stats.value = TunnelStats()
                prevRxBytes = 0L
                prevTxBytes = 0L
                prevStatsTime = 0L
                probeFailureCount = 0

                _state.value = TunnelState.Disconnected
                Timber.i("TunnelManager: tunnel disconnected")
            } catch (e: Exception) {
                Timber.e(e, "TunnelManager: failed to disconnect tunnel")
                _state.value = TunnelState.Error(e.message ?: "Unknown error")
            }
        }
    }

    // ── 双轨探测引擎 ────────────────────────────────────────────────────────

    /**
     * 启动双轨探测协程。
     * 仅在亮屏 + 有网络时激活；熄屏或物理断网时自动挂起。
     */
    private fun startProbeEngine() {
        stopProbeEngine()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        probeScope = scope

        scope.launch {
            Timber.d("TunnelProbe: engine started (bypass=%s, tunnel=%s, timeout=%dms, threshold=%d)",
                bypassProbeTarget, tunnelProbeTarget, probeTimeoutMs, failureThreshold)
            while (isActive) {
                delay(PROBE_INTERVAL_MS)

                // 熄屏或物理断网时跳过，不触发重连
                if (!isScreenOn.get()) {
                    Timber.d("TunnelProbe: screen off, skipping probe cycle")
                    continue
                }
                if (!isNetworkAvailable.get()) {
                    Timber.d("TunnelProbe: no network, skipping probe cycle")
                    probeFailureCount = 0   // 不是隧道问题，重置计数
                    continue
                }
                if (_state.value !is TunnelState.Connected) continue

                val trackA = probeBypass(bypassProbeTarget, probeTimeoutMs)
                val trackB = probeTunnel(tunnelProbeTarget, probeTimeoutMs)

                when {
                    trackA && trackB -> {
                        // 全通：隧道正常
                        if (probeFailureCount > 0) {
                            Timber.d("TunnelProbe: A+B both reachable, resetting failure count (was %d)", probeFailureCount)
                        }
                        probeFailureCount = 0
                    }
                    !trackA && !trackB -> {
                        // 双断：本地无网，不是隧道问题
                        Timber.d("TunnelProbe: A+B both unreachable — local network down, holding state")
                        probeFailureCount = 0
                    }
                    trackA && !trackB -> {
                        // 精准阻断：本地通但隧道断
                        probeFailureCount++
                        Timber.w("TunnelProbe: A reachable, B blocked — possible port block (failure %d/%d)",
                            probeFailureCount, failureThreshold)
                        if (probeFailureCount >= failureThreshold) {
                            Timber.w("TunnelProbe: threshold reached, triggering port-hop reconnect")
                            probeFailureCount = 0
                            reconnectWithPortHop()
                        }
                    }
                    else -> {
                        // A断+B通：理论上不可能（流量必须经过本地网络），忽略
                        Timber.d("TunnelProbe: A unreachable but B reachable — anomaly, ignoring")
                    }
                }
            }
        }
    }

    private fun stopProbeEngine() {
        probeScope?.cancel()
        probeScope = null
        probeFailureCount = 0
    }

    /**
     * 轨道 A：直连探测。
     * 向 [target] 发起 TCP 连接（端口 443），不绑定 VPN 网络接口。
     * 探测包不会进入 WireGuard 隧道（该 IP 不在 AllowedIPs 内）。
     */
    private fun probeBypass(target: String, timeoutMs: Long): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(target, 443), timeoutMs.toInt())
                true
            }
        } catch (e: Exception) {
            Timber.d("TunnelProbe: bypass probe %s failed: %s", target, e.message)
            false
        }
    }

        /**
     * 轨道 B：隧道探测。
     * 向 [target]（海外常用域名/IP，如 www.google.com）发起 TCP 连接（端口 443）。
     * 流量会自动走系统默认路由进入 WireGuard 隧道；若隧道被精准阻断，TCP 握手将超时返回 false。
     */
    private fun probeTunnel(target: String, timeoutMs: Long): Boolean {
        return try {
            java.net.Socket().use { socket ->
                // 使用和轨道 A 相同的 TCP 握手，走海外最通用的 HTTPS 443 端口
                socket.connect(java.net.InetSocketAddress(target, 443), timeoutMs.toInt())
                true
            }
        } catch (e: Exception) {
            Timber.d("TunnelProbe: tunnel probe %s failed: %s", target, e.message)
            false
        }
    }


    // ── 端口跳变重连 ────────────────────────────────────────────────────────

    /**
     * 在疑似端口封锁时切换端口自动重连。
     * 由双轨探测引擎或 VpnViewModel 的握手超时检测调用。
     * 只在 [StealthConfig.autoReconnectOnBlock] 启用时有效。
     */
    suspend fun reconnectWithPortHop() {
        val stealth = currentStealthConfig
        if (!stealth.autoReconnectOnBlock || currentRawConfig.isEmpty()) {
            Timber.d("TunnelManager: autoReconnectOnBlock disabled or no config, skipping port hop")
            return
        }

        withContext(Dispatchers.IO) {
            connectMutex.withLock {
                try {
                    Timber.w("TunnelManager: handshake stale — attempting port-hop reconnect")

                    // Step 1: 彻底销毁旧网卡
                    val currentBackend = backend
                    val currentTunnel = tunnel
                    if (currentBackend != null && currentTunnel != null) {
                        currentBackend.setState(currentTunnel, Tunnel.State.DOWN, null)
                    }

                    val parsedConfig = TunnelConfig.parse(currentRawConfig)
                    val currentPort = parsedConfig.getServerPort()

                    // Step 2: 选择不同的端口
                    val newPort = stealthEngine.nextPort(stealth, currentPort)
                    val newEndpoint = stealthEngine.replacePort(parsedConfig.endpoint, newPort)
                    Timber.d("TunnelManager: port-hop %d → %d, endpoint=%s", currentPort, newPort, newEndpoint)

                    val rewrittenConfig = parsedConfig.copy(endpoint = newEndpoint)

                    // Step 3: 执行 Stealth 变换（重新抖动、decoy 等）
                    val stealthedConfig = stealthEngine.prepareConnection(stealth, rewrittenConfig)
                    val wgConfig = buildWgConfig(stealthedConfig, currentSplitConfig)

                    _state.value = TunnelState.Connecting

                    val ensuredBackend = backend ?: run { initialize(); backend }
                        ?: throw IllegalStateException("Backend not available after port hop")
                    val ensuredTunnel = tunnel
                        ?: throw IllegalStateException("Tunnel not initialized after port hop")

                    // Step 4: 冷却锁 + 重新建立网卡
                    enforceReconnectCooldown()
                    ensuredBackend.setState(ensuredTunnel, Tunnel.State.UP, wgConfig)
                    lastEstablishMs.set(System.currentTimeMillis())

                    prevRxBytes = 0L
                    prevTxBytes = 0L
                    prevStatsTime = System.currentTimeMillis()

                    _state.value = TunnelState.Connected()
                    Timber.i("TunnelManager: port-hop reconnect succeeded on port %d", newPort)
                } catch (e: Exception) {
                    Timber.e(e, "TunnelManager: port-hop reconnect failed")
                    _state.value = TunnelState.Error(e.message ?: "Port-hop reconnect failed")
                }
            }
        }
    }

    // ── 统计 ────────────────────────────────────────────────────────────────

    fun getStatistics(): TunnelStats? {
        return try {
            val currentBackend = backend ?: return null
            val currentTunnel = tunnel ?: return null
            if (_state.value !is TunnelState.Connected) return null

            val statistics = currentBackend.getStatistics(currentTunnel)
            val now = System.currentTimeMillis()
            val elapsedSec = if (prevStatsTime > 0) (now - prevStatsTime) / 1000.0 else 1.0

            val totalRx = statistics.totalRx()
            val totalTx = statistics.totalTx()

            var latestHandshake = 0L
            try {
                val peersMethod = statistics.javaClass.getMethod("peers")
                val peerKeys = peersMethod.invoke(statistics) as? Set<*>
                peerKeys?.forEach { key ->
                    try {
                        val peerMethod = statistics.javaClass.getMethod("peer", key!!.javaClass)
                        val peerStats = peerMethod.invoke(statistics, key)
                        if (peerStats != null) {
                            val hsField = peerStats.javaClass.getField("latestHandshakeEpochMillis")
                            val hs = hsField.getLong(peerStats)
                            if (hs > latestHandshake) latestHandshake = hs
                        }
                    } catch (_: Exception) { }
                }
            } catch (_: Exception) {
                Timber.d("TunnelManager: handshake timestamp not available from Statistics API")
            }

            val rxSpeed = if (elapsedSec > 0) ((totalRx - prevRxBytes) / elapsedSec).toLong() else 0L
            val txSpeed = if (elapsedSec > 0) ((totalTx - prevTxBytes) / elapsedSec).toLong() else 0L

            prevRxBytes = totalRx
            prevTxBytes = totalTx
            prevStatsTime = now

            val tunnelStats = TunnelStats(
                rxBytes = totalRx,
                txBytes = totalTx,
                rxSpeed = maxOf(0L, rxSpeed),
                txSpeed = maxOf(0L, txSpeed),
                lastHandshakeEpoch = latestHandshake / 1000
            )

            _stats.value = tunnelStats
            tunnelStats
        } catch (e: Exception) {
            Timber.e(e, "TunnelManager: failed to get statistics")
            null
        }
    }

    fun isConnected(): Boolean = _state.value is TunnelState.Connected

    // ── 屏幕 & 网络状态监听 ──────────────────────────────────────────────────

    private fun registerScreenReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_ON -> {
                        isScreenOn.set(true)
                        Timber.d("TunnelProbe: screen on — resuming active probe")
                        // 若当前已连接且探测引擎已停，重新启动
                        if (_state.value is TunnelState.Connected
                            && currentStealthConfig.autoReconnectOnBlock
                            && currentStealthConfig.portHoppingEnabled
                            && probeScope == null) {
                            startProbeEngine()
                        }
                    }
                    Intent.ACTION_SCREEN_OFF -> {
                        isScreenOn.set(false)
                        Timber.d("TunnelProbe: screen off — suspending active probe (passive mode)")
                        // 熄屏时停止高频探测协程，释放 CPU WakeLock
                        stopProbeEngine()
                    }
                }
            }
        }
        context.registerReceiver(receiver, filter)
        screenReceiver = receiver
    }

    // ACCESS_NETWORK_STATE is a normal permission declared in the app module's AndroidManifest.xml.
    // Lint cannot see the app manifest from this library module, hence the suppression.
    @android.annotation.SuppressLint("MissingPermission")
    private fun registerNetworkCallback() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                isNetworkAvailable.set(true)
                Timber.d("TunnelProbe: network available")
            }
            override fun onLost(network: Network) {
                // 仍有其他网络时不应标记为断网；ConnectivityManager 在最后一个网络
                // 丢失时才会触发 onLost 且不再回调 onAvailable
                isNetworkAvailable.set(false)
                Timber.d("TunnelProbe: network lost — suspending probe, holding tunnel state")
            }
        }
        cm.registerNetworkCallback(request, callback)
        networkCallback = callback
    }

    fun release() {
        stopProbeEngine()
        screenReceiver?.let {
            try { context.unregisterReceiver(it) } catch (_: Exception) {}
        }
        screenReceiver = null
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        networkCallback?.let {
            try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {}
        }
        networkCallback = null
    }

    // ── 冷却锁 ──────────────────────────────────────────────────────────────

    /**
     * 强制两次 `establish()` 之间至少间隔 [RECONNECT_COOLDOWN_MS]。
     * 防止 Android `jniSetAddresses` 因 TUN fd 未释放而抛出
     * `IllegalArgumentException: Cannot set address`。
     */
    private suspend fun enforceReconnectCooldown() {
        val last = lastEstablishMs.get()
        if (last == 0L) return
        val elapsed = System.currentTimeMillis() - last
        val remaining = RECONNECT_COOLDOWN_MS - elapsed
        if (remaining > 0) {
            Timber.d("TunnelManager: cooldown — waiting %dms before establish()", remaining)
            delay(remaining)
        }
    }

    // ── WireGuard 配置构建 ───────────────────────────────────────────────────

    private fun buildWgConfig(
        parsed: TunnelConfig,
        splitConfig: SplitTunnelConfig,
    ): Config {
        require(parsed.address.isNotBlank()) {
            "WireGuard Interface Address is empty — cannot establish VPN tunnel. " +
            "The server configuration is missing the [Interface] Address field."
        }

        val ifaceBuilder = Interface.Builder()
            .parsePrivateKey(parsed.privateKey)
            .parseAddresses(parsed.address)

        parsed.dns.forEach { dns -> ifaceBuilder.parseDnsServers(dns) }
        parsed.mtu?.let { ifaceBuilder.setMtu(it) }

        when (splitConfig.mode) {
            "exclude" -> if (splitConfig.apps.isNotEmpty()) ifaceBuilder.excludeApplications(splitConfig.apps.toSet())
            "include" -> if (splitConfig.apps.isNotEmpty()) ifaceBuilder.includeApplications(splitConfig.apps.toSet())
        }

        val peerBuilder = Peer.Builder()
            .parsePublicKey(parsed.publicKey)
            .parseEndpoint(parsed.endpoint)

        parsed.presharedKey?.let { peerBuilder.parsePreSharedKey(it) }
        parsed.persistentKeepalive?.let { peerBuilder.setPersistentKeepalive(it) }

        val dnsIps = parsed.dns.map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { if (it.contains(":")) "$it/128" else "$it/32" }

        val allowedIpsRaw = when (splitConfig.mode) {
            "exclude" -> {
                if (splitConfig.networks.isEmpty()) parsed.allowedIps
                else {
                    val complement = CidrComplement.computeAllowedIps(splitConfig.networks)
                    (complement + listOf("::/0") + dnsIps + VPN_SUBNET).distinct().joinToString(",")
                }
            }
            "include" -> (splitConfig.networks + dnsIps + VPN_SUBNET).distinct().joinToString(",")
            else -> parsed.allowedIps
        }
        peerBuilder.parseAllowedIPs(allowedIpsRaw)

        return Config.Builder()
            .setInterface(ifaceBuilder.build())
            .addPeer(peerBuilder.build())
            .build()
    }

    companion object {
        private const val TUNNEL_NAME = "gatecontrol"
        private const val VPN_SUBNET = "0.0.0.0/32"

        /** 两次 establish() 之间的最小冷却时间（毫秒） */
        const val RECONNECT_COOLDOWN_MS = 5_000L

        /** 双轨探测间隔（毫秒） */
        private const val PROBE_INTERVAL_MS = 15_000L

        /** 轨道 A 默认探测目标（国内公共 DNS，不在 AllowedIPs 内） */
        const val DEFAULT_BYPASS_PROBE = "bing.com"

        /** 轨道 B 默认探测目标（VPN 网关，在 AllowedIPs 内） */
        const val DEFAULT_TUNNEL_PROBE = "google.com"

        /** 单次探测超时（毫秒） */
        const val DEFAULT_PROBE_TIMEOUT_MS = 3_000L

        /** 连续精准阻断次数达到此值时触发熔断 */
        const val DEFAULT_FAILURE_THRESHOLD = 3
    }
}
