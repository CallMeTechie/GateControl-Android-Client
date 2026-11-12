package com.gatecontrol.android.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LicenseRepository @Inject constructor() {

    data class Permissions(
        val services: Boolean = false,
        val traffic: Boolean = false,
        val dns: Boolean = false,
        val rdp: Boolean = false,
        val pihole: Boolean = false,
        val piholeControl: Boolean = false,
        /** DNS protection of the server (replaces Pi-hole in the app); null = none. */
        val vpnDns: VpnDns? = null
    )

    /** [plugin]: the plugin the app asks; [state]: `starting` | `filtering` | `fallback`. */
    data class VpnDns(val plugin: String, val state: String)

    private val _permissions = MutableStateFlow(Permissions())
    val permissions: StateFlow<Permissions> = _permissions.asStateFlow()

    private var apiTokenMode = false

    fun updatePermissions(
        services: Boolean,
        traffic: Boolean,
        dns: Boolean,
        rdp: Boolean,
        pihole: Boolean = false,
        piholeControl: Boolean = false,
        vpnDns: VpnDns? = null
    ) {
        _permissions.value = Permissions(
            services = services,
            traffic = traffic,
            dns = dns,
            rdp = rdp,
            pihole = pihole,
            piholeControl = piholeControl,
            vpnDns = vpnDns
        )
        apiTokenMode = true
    }

    fun hasFeature(feature: String): Boolean {
        val perms = _permissions.value
        return when (feature) {
            "rdp" -> perms.rdp
            "services" -> perms.services
            "traffic" -> perms.traffic
            "dns" -> perms.dns
            "pihole" -> perms.pihole
            "piholeControl" -> perms.piholeControl
            else -> false
        }
    }

    /** The server no longer reports a DNS protection (its plugin answered "gone"). */
    fun clearVpnDns() {
        _permissions.value = _permissions.value.copy(vpnDns = null)
    }

    fun isApiTokenMode(): Boolean = apiTokenMode

    companion object {
        /** A plugin id as it may stand in a path: one plain segment. */
        private val PLUGIN_ID = Regex("^[a-z0-9][a-z0-9_-]{0,63}$")

        /**
         * `permissions.vpnDns` of the server as the app uses it: null when the
         * field is missing or null (no DNS protection) or names no usable plugin.
         */
        fun vpnDnsOf(plugin: String?, state: String?): VpnDns? =
            plugin?.takeIf { PLUGIN_ID.matches(it) }?.let { VpnDns(it, state.orEmpty()) }
    }
}
