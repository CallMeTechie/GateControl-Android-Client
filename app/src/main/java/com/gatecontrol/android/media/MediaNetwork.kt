package com.gatecontrol.android.media

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Whether the phone is on mobile data now (the limit of question 28): a cellular network and no Wi-Fi or Ethernet. */
@Singleton
class MediaNetwork @Inject constructor(@ApplicationContext private val context: Context) {
    fun cellular(): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        @Suppress("DEPRECATION")
        val caps = cm.allNetworks.mapNotNull { cm.getNetworkCapabilities(it) }.filterNot { it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
        val lan = caps.any { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) }
        !lan && caps.any { it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) }
    }.getOrDefault(false)
}
