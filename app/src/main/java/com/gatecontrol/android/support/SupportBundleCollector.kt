package com.gatecontrol.android.support

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.gatecontrol.android.common.SupportRedactor
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.tunnel.TunnelManager
import com.gatecontrol.android.tunnel.TunnelState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.net.NetworkInterface
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Open admin request for a support bundle, reported by the heartbeat
 * (TunnelSupervisor). Value: request timestamp, "requested" for servers
 * without one, or null. Settings shows a hint and the user decides.
 */
object SupportRequestHolder {
    private val _request = MutableStateFlow<String?>(null)
    val request: StateFlow<String?> = _request.asStateFlow()

    fun update(requested: Boolean?, requestedAt: String?) {
        if (requested == null) return // older server: no information
        _request.value = if (requested) requestedAt?.takeIf { it.isNotBlank() } ?: "requested" else null
    }

    fun clear() { _request.value = null }
}

/**
 * Builds the support bundle (schema 1, gatecontrol docs/feature-support-bundle.md)
 * as a plain value tree:
 *   client   — product android, app version, Android version / API level, device, ABI, locale
 *   tunnel   — state, connected since, last handshake age, traffic
 *   settings — snapshot handed in by the caller, WITHOUT the API token
 *   wireguardConfig — stored config with PrivateKey / PresharedKey masked
 *   network  — interfaces (no MAC), active network: transports, DNS, routes
 *   logs     — last [MAX_LOG_LINES] lines of cacheDir/logs (FileLoggingTree)
 *   errors   — recent W/E lines
 * Every string passes [SupportRedactor]; the uploader redacts once more.
 */
@Singleton
class SupportBundleCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val setupRepository: SetupRepository,
    private val tunnelManager: TunnelManager,
) {

    fun collect(
        appVersion: String,
        locale: String,
        settings: Map<String, Any?>,
        reason: String = "user",
        nowMillis: Long = System.currentTimeMillis(),
    ): Map<String, Any?> {
        val notes = mutableListOf<String>()
        val logs = runCatching { readLogs(File(context.cacheDir, "logs")) }
            .onFailure { notes.add("logs: ${it.javaClass.simpleName}") }
            .getOrDefault(LogTail(emptyList(), 0, false))
        val lines = logs.lines.map { truncate(SupportRedactor.redactText(it), MAX_LINE_LENGTH) }

        val bundle = linkedMapOf<String, Any?>(
            "schema" to SCHEMA_VERSION,
            "createdAt" to Instant.ofEpochMilli(nowMillis).toString(),
            "reason" to if (reason == "admin_request") "admin_request" else "user",
            "client" to clientInfo(appVersion, locale),
            "tunnel" to runCatching { tunnelInfo(nowMillis) }.onFailure { notes.add("tunnel: ${it.javaClass.simpleName}") }.getOrNull(),
            "settings" to settingsSnapshot(settings),
            "wireguardConfig" to runCatching {
                setupRepository.getWireGuardConfig().takeIf { it.isNotBlank() }?.let { truncate(SupportRedactor.redactText(it), MAX_TEXT) }
            }.getOrNull(),
            "network" to networkInfo(notes),
            "logs" to mapOf("lines" to lines, "totalLines" to logs.totalLines, "truncated" to logs.truncated),
            "errors" to lines.filter { ERROR_LINE.containsMatchIn(it) }.takeLast(MAX_ERROR_LINES),
            "notes" to notes,
            "redaction" to "android-v1",
        )
        @Suppress("UNCHECKED_CAST")
        return SupportRedactor.redactValue(bundle) as Map<String, Any?>
    }

    private fun clientInfo(appVersion: String, locale: String): Map<String, Any?> = mapOf(
        "product" to "android",
        "version" to appVersion,
        "platform" to "android",
        "os" to "Android ${Build.VERSION.RELEASE.orEmpty()} (API ${Build.VERSION.SDK_INT})",
        "device" to listOfNotNull(Build.MANUFACTURER, Build.MODEL).joinToString(" ").ifBlank { null },
        "arch" to Build.SUPPORTED_ABIS?.firstOrNull(),
        "locale" to locale,
    )

    private fun tunnelInfo(nowMillis: Long): Map<String, Any?> {
        val state = tunnelManager.state.value
        val stats = tunnelManager.stats.value
        return mapOf(
            "state" to state.javaClass.simpleName,
            "connected" to (state is TunnelState.Connected),
            "connectedSince" to (state as? TunnelState.Connected)?.connectedSince?.let { Instant.ofEpochMilli(it).toString() },
            "error" to (state as? TunnelState.Error)?.message,
            "lastHandshakeAgeSec" to stats.lastHandshakeEpoch.takeIf { it > 0 }?.let { (nowMillis / 1000 - it).coerceAtLeast(0) },
            "rxBytes" to stats.rxBytes,
            "txBytes" to stats.txBytes,
            "peerId" to setupRepository.getPeerId(),
        )
    }

    private fun networkInfo(notes: MutableList<String>): Map<String, Any?> {
        val interfaces = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().map { nif ->
                mapOf(
                    "name" to nif.name,
                    "up" to nif.isUp,
                    "mtu" to nif.mtu,
                    "addresses" to nif.interfaceAddresses.map { "${it.address.hostAddress}/${it.networkPrefixLength}" },
                )
            }
        }.onFailure { notes.add("interfaces: ${it.javaClass.simpleName}") }.getOrDefault(emptyList())

        val active = runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return@runCatching null
            val network = cm.activeNetwork ?: return@runCatching null
            val caps = cm.getNetworkCapabilities(network)
            val lp = cm.getLinkProperties(network)
            mapOf(
                "transports" to listOfNotNull(
                    "wifi".takeIf { caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true },
                    "cellular".takeIf { caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true },
                    "ethernet".takeIf { caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true },
                    "vpn".takeIf { caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true },
                ),
                "validated" to (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true),
                "interface" to lp?.interfaceName,
                "dnsServers" to lp?.dnsServers?.mapNotNull { it.hostAddress }.orEmpty(),
                "privateDns" to lp?.isPrivateDnsActive,
                "routes" to lp?.routes?.map { it.toString() }.orEmpty().take(MAX_ROUTES),
            )
        }.onFailure { notes.add("activeNetwork: ${it.javaClass.simpleName}") }.getOrNull()

        return mapOf("interfaces" to interfaces, "activeNetwork" to active)
    }

    /** Settings without credentials: token-like keys are dropped by the redactor. */
    private fun settingsSnapshot(settings: Map<String, Any?>): Map<String, Any?> =
        settings.filterKeys { !SupportRedactor.isSecretKey(it) }

    data class LogTail(val lines: List<String>, val totalLines: Int, val truncated: Boolean)

    companion object {
        const val SCHEMA_VERSION = 1
        const val MAX_LOG_LINES = 2000
        private const val MAX_LINE_LENGTH = 2000
        private const val MAX_ERROR_LINES = 100
        private const val MAX_TEXT = 64 * 1024
        private const val MAX_ROUTES = 200
        private const val MAX_READ_BYTES = 2L * 1024 * 1024
        private val ERROR_LINE = Regex(" [EWA]/|\\b(error|exception|failed|fatal)\\b", RegexOption.IGNORE_CASE)

        private fun truncate(s: String, max: Int) =
            if (s.length > max) s.take(max) + "… [${s.length - max} chars truncated]" else s

        /**
         * Last [maxLines] lines of the app log: gatecontrol.log.1 (rotated)
         * followed by gatecontrol.log, at most [MAX_READ_BYTES] from each.
         */
        fun readLogs(logDir: File, maxLines: Int = MAX_LOG_LINES): LogTail {
            val files = listOf(File(logDir, "gatecontrol.log.1"), File(logDir, "gatecontrol.log")).filter { it.isFile }
            var truncated = false
            val all = mutableListOf<String>()
            for (f in files) {
                val len = f.length()
                val text = f.inputStream().use { input ->
                    if (len > MAX_READ_BYTES) {
                        truncated = true
                        input.skip(len - MAX_READ_BYTES)
                        input.readBytes().toString(Charsets.UTF_8).substringAfter('\n')
                    } else {
                        input.readBytes().toString(Charsets.UTF_8)
                    }
                }
                all += text.lines().filter { it.isNotBlank() }
            }
            if (all.size > maxLines) truncated = true
            return LogTail(all.takeLast(maxLines), all.size, truncated)
        }
    }
}
