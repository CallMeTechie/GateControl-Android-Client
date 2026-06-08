package com.gatecontrol.android.tunnel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.net.InetAddress
import kotlin.random.Random

/**
 * 客户端防检测引擎。
 *
 * 在不依赖任何外部服务的情况下，对 WireGuard 连接实施多层流量混淆：
 *
 * ## 实现的防检测层
 *
 * ### 层1：时序抖动 (Timing Jitter)
 * 在发起握手前注入 [StealthConfig.jitterMinMs]..[StealthConfig.jitterMaxMs] 范围
 * 内的随机延迟。WireGuard 握手固定在连接触发后立即发生，这种确定性时序会被
 * 机器学习分类器识别。随机延迟打破时序指纹。
 *
 * ### 层2：端口跳变 (Port Hopping)
 * 从候选端口列表中随机选择连接端口。使用 443/80 等常见端口时，流量在端口层面
 * 与正常 HTTPS/HTTP 流量混同，降低端口规则命中概率。
 *
 * ### 层3：MTU 调整 (Packet Length Obfuscation)
 * 将 WireGuard Interface 的 MTU 设置为非标准值（默认 1280），使加密后的
 * UDP 包长分布偏离 WireGuard 默认值（1420）。不同 MTU 导致不同的分片模式，
 * 使包长特征模糊。
 *
 * ### 层4：Keepalive 随机化 (Keepalive Jitter)
 * 对 PersistentKeepalive 值添加随机偏移（±N秒）。固定 25 秒的 keepalive
 * 间隔是 WireGuard 的强特征之一，随机化后难以被流量间隔分析识别。
 *
 * ### 层5：诱饵 DNS 查询 (Decoy DNS)
 * 连接前向 Google/Apple CDN 等无害域名发起真实 DNS 解析，在流量时序上
 * 制造正常浏览行为的前缀，使首个 WireGuard 握手包不在"静默背景"下突然出现。
 *
 * ### 层6：端点指纹随机化
 * 对同一 IP 的不同端口尝试添加轻微的连接时序偏差，避免批量测试特征。
 */
class StealthEngine {

    /**
     * 在实际连接前执行所有启用的预连接防检测动作。
     *
     * @param config 防检测配置
     * @param originalConfig 原始 WireGuard 配置（用于读取 endpoint/keepalive）
     * @return 经过防检测处理后修改的 WireGuard 配置字符串
     */
    suspend fun prepareConnection(
        config: StealthConfig,
        originalConfig: TunnelConfig,
    ): TunnelConfig = withContext(Dispatchers.IO) {
        var result = originalConfig

        // 层5：诱饵 DNS（最先执行，制造前置流量）
        if (config.decoyDnsEnabled) {
            runDecoyDns(config.decoyDomains)
        }

        // 层1：时序抖动（握手前随机延迟）
        if (config.timingJitterEnabled) {
            val delayMs = Random.nextLong(config.jitterMinMs, config.jitterMaxMs + 1)
            Timber.d("StealthEngine: timing jitter delay=${delayMs}ms")
            kotlinx.coroutines.delay(delayMs)
        }

        // 层2：端口跳变（修改 endpoint 端口）
        if (config.portHoppingEnabled) {
            val newPort = pickPort(config, originalConfig)
            val newEndpoint = replacePort(originalConfig.endpoint, newPort)
            Timber.d("StealthEngine: port hop ${originalConfig.endpoint} → $newEndpoint")
            result = result.copy(endpoint = newEndpoint)
        }

        // 层3：MTU 调整（包长混淆）
        if (config.packetPaddingEnabled) {
            val targetMtu = config.paddingTargetMtu
                .coerceIn(MTU_MIN, MTU_MAX)
                .let { addMtuNoise(it) }  // 加少量随机噪声避免固定值
            Timber.d("StealthEngine: MTU adjusted to $targetMtu")
            result = result.copy(mtu = targetMtu)
        }

        // 层4：Keepalive 随机化
        if (config.keepaliveRandomEnabled) {
            val base = result.persistentKeepalive ?: DEFAULT_KEEPALIVE
            val jitter = Random.nextInt(-config.keepaliveJitterSec, config.keepaliveJitterSec + 1)
            val jitteredKeepalive = (base + jitter).coerceIn(KEEPALIVE_MIN, KEEPALIVE_MAX)
            Timber.d("StealthEngine: keepalive $base → $jitteredKeepalive (jitter=$jitter)")
            result = result.copy(persistentKeepalive = jitteredKeepalive)
        }

        result
    }

    /**
     * 选择连接端口。
     * 优先选择常见端口（443/80），其次随机选择候选列表中的端口。
     */
    private fun pickPort(config: StealthConfig, tunnelConfig: TunnelConfig): Int {
        val preferred = listOf(443, 80, 8080, 8443)
        val available = config.candidatePorts

        // 尝试从候选列表中找到高权重端口
        val highPriority = available.intersect(preferred.toSet())
        if (highPriority.isNotEmpty()) {
            return highPriority.random()
        }

        // 否则从完整候选列表中随机选择
        return if (available.isNotEmpty()) {
            available.random()
        } else {
            tunnelConfig.getServerPort()  // 保持原端口
        }
    }

    /**
     * 替换 endpoint 字符串中的端口号。
     * 支持 IPv4 (host:port) 和 IPv6 ([host]:port) 格式。
     */
    fun replacePort(endpoint: String, newPort: Int): String {
        return if (endpoint.startsWith("[")) {
            // IPv6: [host]:port
            val bracketEnd = endpoint.lastIndexOf(']')
            if (bracketEnd >= 0) "${endpoint.substring(0, bracketEnd + 1)}:$newPort"
            else endpoint
        } else {
            // IPv4: host:port
            val lastColon = endpoint.lastIndexOf(':')
            if (lastColon >= 0) "${endpoint.substring(0, lastColon)}:$newPort"
            else "$endpoint:$newPort"
        }
    }

    /**
     * 在目标 MTU 上添加小范围随机噪声（±8 字节），避免固定 MTU 值本身
     * 成为指纹。
     */
    private fun addMtuNoise(targetMtu: Int): Int {
        val noise = Random.nextInt(-8, 9)
        return (targetMtu + noise).coerceIn(MTU_MIN, MTU_MAX)
    }

    /**
     * 发送诱饵 DNS 查询。
     * 对无害域名发起真实解析，产生正常浏览前置流量。
     * 失败静默忽略（网络离线时不应阻塞连接）。
     */
    private suspend fun runDecoyDns(domains: List<String>) {
        val shuffled = domains.shuffled().take(DECOY_DNS_COUNT)
        Timber.d("StealthEngine: sending ${shuffled.size} decoy DNS queries")
        for (domain in shuffled) {
            try {
                withContext(Dispatchers.IO) {
                    InetAddress.getAllByName(domain)
                }
                // 查询间加入微小随机间隔，使 DNS 流量本身也有时序抖动
                val interQueryDelay = Random.nextLong(DECOY_DNS_INTERVAL_MIN, DECOY_DNS_INTERVAL_MAX)
                kotlinx.coroutines.delay(interQueryDelay)
                Timber.d("StealthEngine: decoy DNS resolved $domain")
            } catch (e: Exception) {
                Timber.d("StealthEngine: decoy DNS $domain failed (ignored): ${e.message}")
            }
        }
    }

    /**
     * 从候选端口列表中依次尝试（用于自动重连/自愈场景）。
     * 返回下一个不同于当前端口的候选端口。
     */
    fun nextPort(config: StealthConfig, currentPort: Int): Int {
        val others = config.candidatePorts.filter { it != currentPort }
        return if (others.isNotEmpty()) others.random() else currentPort
    }

    companion object {
        private const val MTU_MIN = 1024
        private const val MTU_MAX = 1500
        private const val DEFAULT_KEEPALIVE = 25
        private const val KEEPALIVE_MIN = 10
        private const val KEEPALIVE_MAX = 120
        private const val DECOY_DNS_COUNT = 3
        private const val DECOY_DNS_INTERVAL_MIN = 50L
        private const val DECOY_DNS_INTERVAL_MAX = 200L
    }
}
