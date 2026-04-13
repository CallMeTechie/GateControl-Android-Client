package com.gatecontrol.android.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import timber.log.Timber

/**
 * Detects the current WiFi network's subnet CIDR.
 * Uses ConnectivityManager.getLinkProperties (not deprecated WifiManager.connectionInfo).
 * Returns null if not connected to WiFi.
 */
object WifiSubnetDetector {

    /**
     * @return WiFi subnet CIDR (e.g. "192.168.178.0/24") or null
     */
    fun detect(context: Context): String? {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            // Iterate ALL networks to find the WiFi one — activeNetwork may be
            // the VPN interface (10.8.0.x) when the tunnel is active.
            val wifiNetwork = cm.allNetworks.firstOrNull { net ->
                val caps = cm.getNetworkCapabilities(net) ?: return@firstOrNull false
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) &&
                    !caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)
            } ?: return null

            val linkProps = cm.getLinkProperties(wifiNetwork) ?: return null
            val linkAddr = linkProps.linkAddresses.firstOrNull { it.address is java.net.Inet4Address }
                ?: return null

            val prefix = linkAddr.prefixLength
            val ip = linkAddr.address as java.net.Inet4Address
            val ipBytes = ip.address
            val mask = (-1 shl (32 - prefix))
            val networkBytes = ByteArray(4)
            for (i in 0..3) {
                networkBytes[i] = (ipBytes[i].toInt() and (mask shr (24 - i * 8) and 0xFF)).toByte()
            }
            val networkAddr = java.net.InetAddress.getByAddress(networkBytes) as java.net.Inet4Address
            "${networkAddr.hostAddress}/$prefix"
        } catch (e: Exception) {
            Timber.w(e, "WifiSubnetDetector: failed to detect subnet")
            null
        }
    }
}
