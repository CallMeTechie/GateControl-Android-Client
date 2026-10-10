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
        val piholeControl: Boolean = false
    )

    private val _permissions = MutableStateFlow(Permissions())
    val permissions: StateFlow<Permissions> = _permissions.asStateFlow()

    private var apiTokenMode = false

    fun updatePermissions(
        services: Boolean,
        traffic: Boolean,
        dns: Boolean,
        rdp: Boolean,
        pihole: Boolean = false,
        piholeControl: Boolean = false
    ) {
        _permissions.value = Permissions(
            services = services,
            traffic = traffic,
            dns = dns,
            rdp = rdp,
            pihole = pihole,
            piholeControl = piholeControl
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

    fun isApiTokenMode(): Boolean = apiTokenMode
}
