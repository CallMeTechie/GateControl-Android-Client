package com.gatecontrol.android.tunnel

import android.content.Context
import android.net.VpnService
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.config.Interface
import com.wireguard.config.InetAddresses
import com.wireguard.config.InetNetwork
import com.wireguard.config.Peer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WireGuard 隧道管理器（集成客户端防检测功能）。
 *
 * 在原有功能基础上增加了 [StealthEngine] 集成：
 * - 连接前执行时序抖动、诱饵 DNS、端口跳变等防检测预处理
 * - MTU 调整影响包长分布
 * - Keepalive 随机化打破固定间隔指纹
 * - 检测到疑似封锁时自动切换端口重连（[StealthConfig.autoReconnectOnBlock]）
 *
 * 防检测配置通过 [connect] 的 [stealthConfig] 参数传入，默认关闭所有功能
 * 以保持完全向后兼容。
 */
@Singleton
class TunnelManager @Inject constructor(private val context: Context) {

    private val _state = MutableStateFlow<TunnelState>(TunnelState.Disconnected)
    val state: StateFlow<TunnelState> = _state.asStateFlow()

    private val _stats = MutableStateFlow(TunnelStats())
    val stats: StateFlow<TunnelStats> = _stats.asStateFlow()

    private var backend: Backend? = null
    private var tunnel: Tunnel? = null

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
    }

    /**
     * 向后兼容的连接入口（无防检测配置，所有功能关闭）。
     */
    suspend fun connect(
        configString: String,
        splitTunnelRoutes: List<String> = emptyList(),
        excludedApps: List<String> = emptyList()
    ) {
        val splitConfig = if (splitTunnelRoutes.isNotEmpty() || excludedApps.isNotEmpty()) {
            SplitTunnelConfig(
                mode = "include",
                networks = splitTunnelRoutes,
                apps = excludedApps,
            )
        } else {
            SplitTunnelConfig()
        }
        connectInternal(configString, splitConfig, StealthConfig())
    }

    /**
     * 使用分流配置连接（无防检测）。
     */
    suspend fun connect(configString: String, splitConfig: SplitTunnelConfig) {
        connectInternal(configString, splitConfig, StealthConfig())
    }

    /**
     * 完整连接入口：支持分流配置 + 防检测配置。
     *
     * @param configString WireGuard 配置字符串
     * @param splitConfig  分流隧道配置
     * @param stealthConfig 客户端防检测配置（默认全部关闭）
     */
    suspend fun connect(
        configString: String,
        splitConfig: SplitTunnelConfig,
        stealthConfig: StealthConfig,
    ) {
        connectInternal(configString, splitConfig, stealthConfig)
    }

    private suspend fun connectInternal(
        configString: String,
        splitConfig: SplitTunnelConfig,
        stealthConfig: StealthConfig,
    ) {
        withContext(Dispatchers.IO) {
            try {
                // 保存配置供自动重连使用
                currentRawConfig = configString
                currentSplitConfig = splitConfig
                currentStealthConfig = stealthConfig

                val parsedConfig = TunnelConfig.parse(configString)

                // ── 防检测预处理 ──────────────────────────────────────────
                // StealthEngine 在此阶段执行：时序抖动、诱饵DNS、端口跳变、
                // MTU调整、Keepalive随机化
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
                // ─────────────────────────────────────────────────────────

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

                val currentBackend = backend ?: run {
                    initialize()
                    backend
                } ?: throw IllegalStateException("Backend not available")

                val currentTunnel = tunnel
                    ?: throw IllegalStateException("Tunnel not initialized")

                currentBackend.setState(currentTunnel, Tunnel.State.UP, wgConfig)

                prevRxBytes = 0L
                prevTxBytes = 0L
                prevStatsTime = System.currentTimeMillis()

                _state.value = TunnelState.Connected()
                Timber.i("TunnelManager: tunnel connected successfully")
            } catch (e: Exception) {
                Timber.e(e, "TunnelManager: failed to connect tunnel")
                _state.value = TunnelState.Error(e.message ?: "Unknown error")
            }
        }
    }

    suspend fun disconnect() {
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

                _state.value = TunnelState.Disconnected
                Timber.i("TunnelManager: tunnel disconnected")
            } catch (e: Exception) {
                Timber.e(e, "TunnelManager: failed to disconnect tunnel")
                _state.value = TunnelState.Error(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * 在疑似端口封锁时切换端口自动重连。
     * 由 [TunnelMonitor] 检测到握手超时时调用。
     * 只在 [StealthConfig.autoReconnectOnBlock] 启用时有效。
     */
    suspend fun reconnectWithPortHop() {
        val stealth = currentStealthConfig
        if (!stealth.autoReconnectOnBlock || currentRawConfig.isEmpty()) {
            Timber.d("TunnelManager: autoReconnectOnBlock disabled or no config, skipping port hop")
            return
        }

        withContext(Dispatchers.IO) {
            try {
                Timber.w("TunnelManager: handshake stale — attempting port-hop reconnect")

                // 断开当前连接
                val currentBackend = backend
                val currentTunnel = tunnel
                if (currentBackend != null && currentTunnel != null) {
                    currentBackend.setState(currentTunnel, Tunnel.State.DOWN, null)
                }

                val parsedConfig = TunnelConfig.parse(currentRawConfig)
                val currentPort = parsedConfig.getServerPort()

                // 选择不同的端口
                val newPort = stealthEngine.nextPort(stealth, currentPort)
                val newEndpoint = stealthEngine.replacePort(parsedConfig.endpoint, newPort)
                Timber.d("TunnelManager: port-hop $currentPort → $newPort, endpoint=$newEndpoint")

                val rewrittenConfig = parsedConfig.copy(endpoint = newEndpoint)

                // 对新端口的配置再做一轮完整 stealth 处理（重新抖动、decoy 等）
                val stealthedConfig = stealthEngine.prepareConnection(stealth, rewrittenConfig)
                val wgConfig = buildWgConfig(stealthedConfig, currentSplitConfig)

                _state.value = TunnelState.Connecting

                val ensuredBackend = backend ?: run {
                    initialize()
                    backend
                } ?: throw IllegalStateException("Backend not available after port hop")

                val ensuredTunnel = tunnel
                    ?: throw IllegalStateException("Tunnel not initialized after port hop")

                ensuredBackend.setState(ensuredTunnel, Tunnel.State.UP, wgConfig)

                prevRxBytes = 0L
                prevTxBytes = 0L
                prevStatsTime = System.currentTimeMillis()

                _state.value = TunnelState.Connected()
                Timber.i("TunnelManager: port-hop reconnect succeeded on port $newPort")
            } catch (e: Exception) {
                Timber.e(e, "TunnelManager: port-hop reconnect failed")
                _state.value = TunnelState.Error(e.message ?: "Port-hop reconnect failed")
            }
        }
    }

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

    private fun buildWgConfig(
        parsed: TunnelConfig,
        splitConfig: SplitTunnelConfig,
    ): Config {
        val ifaceBuilder = Interface.Builder()
            .parsePrivateKey(parsed.privateKey)
            .parseAddresses(parsed.address)

        parsed.dns.forEach { dns ->
            ifaceBuilder.parseDnsServers(dns)
        }
        parsed.mtu?.let { ifaceBuilder.setMtu(it) }

        when (splitConfig.mode) {
            "exclude" -> {
                if (splitConfig.apps.isNotEmpty()) {
                    ifaceBuilder.excludeApplications(splitConfig.apps.toSet())
                }
            }
            "include" -> {
                if (splitConfig.apps.isNotEmpty()) {
                    ifaceBuilder.includeApplications(splitConfig.apps.toSet())
                }
            }
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
                if (splitConfig.networks.isEmpty()) {
                    parsed.allowedIps
                } else {
                    val complement = CidrComplement.computeAllowedIps(splitConfig.networks)
                    (complement + listOf("::/0") + dnsIps + VPN_SUBNET).distinct().joinToString(",")
                }
            }
            "include" -> {
                (splitConfig.networks + dnsIps + VPN_SUBNET).distinct().joinToString(",")
            }
            else -> {
                parsed.allowedIps
            }
        }
        peerBuilder.parseAllowedIPs(allowedIpsRaw)

        return Config.Builder()
            .setInterface(ifaceBuilder.build())
            .addPeer(peerBuilder.build())
            .build()
    }

    companion object {
        private const val TUNNEL_NAME = "gatecontrol"
        private const val VPN_SUBNET = "10.8.0.0/24"
    }
}
