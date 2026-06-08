package com.gatecontrol.android.tunnel

/**
 * 客户端防检测配置。
 *
 * 所有功能均在客户端独立实现，无需服务端配合：
 *
 * 1. [portHoppingEnabled] — 每次连接从候选端口列表中随机选择端口，
 *    避免固定端口被封锁。候选列表优先使用常见端口（443/80/8080）以降低
 *    端口层面的 DPI 识别率。
 *
 * 2. [timingJitterEnabled] — 在建立隧道前注入随机延迟（[jitterMinMs]~
 *    [jitterMaxMs] ms），打乱流量时序指纹。ML 分类器对固定时序非常敏感。
 *
 * 3. [packetPaddingEnabled] — 通过 MTU 调整间接影响包长分布，使包长
 *    趋向常见 HTTPS 流量的分布特征，破坏 WireGuard 固定包长指纹。
 *    目标 MTU 由 [paddingTargetMtu] 指定（建议 1280~1400）。
 *
 * 4. [keepaliveRandomEnabled] — 在 PersistentKeepalive 基础上添加随机
 *    偏移（±[keepaliveJitterSec] 秒），避免固定间隔被流量分析识别。
 *
 * 5. [decoyDnsEnabled] — 连接前发送若干次对无害域名的 DNS 查询，在
 *    流量时序上制造"正常浏览"的前置行为，降低首包异常评分。
 *
 * 6. [autoReconnectOnBlock] — 检测到握手超时（疑似被封）后，自动从
 *    备用端口列表重新连接，实现端口轮换自愈。
 */
data class StealthConfig(
    /** 端口跳变：每次连接随机选择端口 */
    val portHoppingEnabled: Boolean = false,

    /** 候选端口列表（优先使用常见端口） */
    val candidatePorts: List<Int> = DEFAULT_PORTS,

    /** 时序抖动：连接前注入随机延迟 */
    val timingJitterEnabled: Boolean = false,

    /** 最小抖动延迟（ms） */
    val jitterMinMs: Long = 100L,

    /** 最大抖动延迟（ms） */
    val jitterMaxMs: Long = 800L,

    /** 包长混淆：通过 MTU 调整影响包长分布 */
    val packetPaddingEnabled: Boolean = false,

    /** 目标 MTU（影响分片大小，间接影响包长特征） */
    val paddingTargetMtu: Int = 1280,

    /** Keepalive 随机化：添加随机偏移避免固定间隔 */
    val keepaliveRandomEnabled: Boolean = false,

    /** Keepalive 抖动范围（±秒） */
    val keepaliveJitterSec: Int = 5,

    /** 诱饵 DNS 查询：连接前发送无害 DNS 请求 */
    val decoyDnsEnabled: Boolean = false,

    /** 诱饵域名列表 */
    val decoyDomains: List<String> = DEFAULT_DECOY_DOMAINS,

    /** 检测到封锁时自动切换端口重连 */
    val autoReconnectOnBlock: Boolean = false,
) {
    companion object {
        /** 优先尝试的端口：443/80/8080 最不容易被端口层面封锁 */
        val DEFAULT_PORTS = listOf(443, 80, 8080, 8443, 53, 123, 51820)

        val DEFAULT_DECOY_DOMAINS = listOf(
            "www.googleapis.com",
            "connectivitycheck.gstatic.com",
            "www.gstatic.com",
            "clients1.google.com",
        )

        /** 返回一个随机选择的候选端口 */
        fun StealthConfig.pickRandomPort(): Int =
            candidatePorts.shuffled().firstOrNull() ?: 51820

        /** 计算带抖动的 Keepalive 值（秒），结果在 [10, 120] 范围内 */
        fun StealthConfig.jitteredKeepalive(baseKeepalive: Int): Int {
            val jitter = (Math.random() * 2 * keepaliveJitterSec - keepaliveJitterSec).toInt()
            return (baseKeepalive + jitter).coerceIn(10, 120)
        }
    }
}
