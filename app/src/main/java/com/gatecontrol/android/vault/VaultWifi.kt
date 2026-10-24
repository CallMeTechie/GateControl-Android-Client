package com.gatecontrol.android.vault

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.gatecontrol.android.trustednet.TrustedNetworkMonitor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** A WLAN in range as the WLAN editor lists it; [level] 0–4 bars. */
data class NearbyWifi(val ssid: String, val security: String, val level: Int, val connected: Boolean)

/** Pure parts of the WLAN scan: security from the capabilities, one row per network name. */
object VaultWifi {
    /** One raw scan result: name, Android's capabilities string (e.g. `[RSN-PSK-CCMP][ESS]`), signal in dBm. */
    data class Raw(val ssid: String, val capabilities: String, val rssi: Int)

    /**
     * The `security` value of a WLAN entry, in the words the portal's QR code understands
     * (WPA2, WPA3, WPA2/WPA3, WPA, WEP; [open] for a network without a password).
     */
    fun securityOf(capabilities: String, open: String): String {
        val c = capabilities.uppercase()
        val sae = "SAE" in c
        val rsn = "RSN" in c || "WPA2" in c
        return when {
            "WEP" in c -> "WEP"
            "EAP" in c -> if (sae) "WPA3-Enterprise" else "WPA2-Enterprise"
            sae && "PSK" in c -> "WPA2/WPA3"
            sae -> "WPA3"
            rsn && "PSK" in c -> "WPA2"
            "WPA" in c && "PSK" in c -> "WPA"
            // OWE ("Enhanced Open") is encrypted but has no password.
            else -> open
        }
    }

    /** Signal bars 0–4 from dBm. */
    fun level(rssi: Int): Int = when {
        rssi >= -55 -> 4
        rssi >= -66 -> 3
        rssi >= -77 -> 2
        rssi >= -88 -> 1
        else -> 0
    }

    /**
     * One row per network name (the strongest access point), hidden networks left out;
     * the connected network first, then by signal, then by name.
     */
    fun rows(raw: List<Raw>, connected: String?, open: String): List<NearbyWifi> =
        raw.asSequence()
            .map { it.copy(ssid = it.ssid.trim('\u0000')) }
            .filter { it.ssid.isNotBlank() }
            .groupBy { it.ssid }
            .map { (ssid, aps) ->
                val best = aps.maxBy { it.rssi }
                NearbyWifi(ssid, securityOf(best.capabilities, open), level(best.rssi), ssid == connected) to best.rssi
            }
            .sortedWith(compareByDescending<Pair<NearbyWifi, Int>> { it.first.connected }.thenByDescending { it.second }.thenBy { it.first.ssid.lowercase() })
            .map { it.first }
}

/**
 * Scans for WLANs in range for the WLAN editor (Android only: a browser cannot see
 * WLANs). Android hands out scan results only with the precise location permission
 * and location switched on; nothing here is stored or sent anywhere.
 */
@Singleton
class VaultWifiScanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val monitor: TrustedNetworkMonitor,
) {
    sealed interface State {
        data object NoPermission : State
        data object LocationOff : State
        data object WifiOff : State
        data class Results(val networks: List<NearbyWifi>, val scanning: Boolean) : State
    }

    private val wifi: WifiManager? get() = context.getSystemService(WifiManager::class.java)

    /** What would stop a scan right now, or null. */
    fun blocker(): State? = when {
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED -> State.NoPermission
        context.getSystemService(LocationManager::class.java)?.isLocationEnabled == false -> State.LocationOff
        wifi?.isWifiEnabled == false -> State.WifiOff
        else -> null
    }

    /**
     * The cached results at once, then fresh ones when Android finishes the scan
     * (Android may throttle scans: then the cached results stay).
     */
    fun scan(open: String): Flow<State> = callbackFlow {
        blocker()?.let {
            trySend(it)
            close()
            return@callbackFlow
        }
        val wm = wifi ?: run {
            trySend(State.WifiOff)
            close()
            return@callbackFlow
        }
        // a just-granted permission only reaches a newly registered callback
        monitor.refresh()
        fun results(scanning: Boolean) = State.Results(VaultWifi.rows(read(wm), monitor.network.value.ssid, open), scanning)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                trySend(results(scanning = false))
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
        @Suppress("DEPRECATION")
        val started = runCatching { wm.startScan() }.getOrDefault(false)
        trySend(results(scanning = started))
        awaitClose { runCatching { context.unregisterReceiver(receiver) } }
    }

    private fun read(wm: WifiManager): List<VaultWifi.Raw> = try {
        wm.scanResults.map { VaultWifi.Raw(ssidOf(it), it.capabilities.orEmpty(), it.level) }
    } catch (e: SecurityException) {
        Timber.w(e, "Vault: WLAN scan results refused")
        emptyList()
    }

    private fun ssidOf(r: ScanResult): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            r.wifiSsid?.bytes?.let { String(it, Charsets.UTF_8) }.orEmpty()
        } else {
            @Suppress("DEPRECATION")
            r.SSID.orEmpty()
        }
}
