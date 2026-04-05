package com.gatecontrol.android.rdp

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

class RdpExternalClient(private val context: Context) {

    data class ExternalApp(val packageName: String, val name: String)

    private val knownClients = listOf(
        "com.microsoft.rdc.androidx",
        "com.freerdp.afreerdp"
    )

    private val knownClientNames = mapOf(
        "com.microsoft.rdc.androidx" to "Microsoft Remote Desktop",
        "com.freerdp.afreerdp" to "aFreeRDP"
    )

    /**
     * Returns the list of known RDP clients installed on the device.
     */
    fun findInstalledClients(): List<ExternalApp> {
        val pm = context.packageManager
        return knownClients.mapNotNull { pkg ->
            try {
                pm.getPackageInfo(pkg, 0)
                ExternalApp(packageName = pkg, name = knownClientNames[pkg] ?: pkg)
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
        }
    }

    /**
     * Returns true if at least one known RDP client is installed.
     */
    fun isAnyClientInstalled(): Boolean = findInstalledClients().isNotEmpty()

    /**
     * Build an RDP URI string for the given connection parameters.
     *
     * Format: rdp://full%20address=s:host:port[&username=s:user][&domain=s:domain]
     */
    fun buildRdpUri(host: String, port: Int, username: String?, domain: String?): String {
        val sb = StringBuilder("rdp://full%20address=s:${Uri.encode(host)}:$port")
        if (!username.isNullOrEmpty()) {
            sb.append("&username=s:${Uri.encode(username)}")
        }
        if (!domain.isNullOrEmpty()) {
            sb.append("&domain=s:${Uri.encode(domain)}")
        }
        return sb.toString()
    }

    /**
     * Build an Intent to launch an installed RDP client with the given connection parameters.
     * Tries the first installed known client; falls back to a generic ACTION_VIEW with the rdp:// URI.
     */
    fun launchIntent(host: String, port: Int, username: String?, domain: String?): Intent {
        val rdpUri = buildRdpUri(host, port, username, domain)
        val installedClients = findInstalledClients()

        return if (installedClients.isNotEmpty()) {
            val preferredPackage = installedClients.first().packageName
            Intent(Intent.ACTION_VIEW, Uri.parse(rdpUri)).apply {
                setPackage(preferredPackage)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            Intent(Intent.ACTION_VIEW, Uri.parse(rdpUri)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }
}
