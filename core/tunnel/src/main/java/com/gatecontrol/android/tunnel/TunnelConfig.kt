package com.gatecontrol.android.tunnel

data class TunnelConfig(
    val privateKey: String,
    val address: String,
    val dns: List<String>,
    val mtu: Int?,
    val publicKey: String,
    val presharedKey: String?,
    val endpoint: String,
    val allowedIps: String,
    val persistentKeepalive: Int?
) {
    fun getServerHost(): String = endpoint.substringBeforeLast(":")

    fun getServerPort(): Int = endpoint.substringAfterLast(":").toIntOrNull() ?: 51820

    fun toWgQuick(): String {
        val sb = StringBuilder()
        sb.appendLine("[Interface]")
        sb.appendLine("PrivateKey = $privateKey")
        sb.appendLine("Address = $address")
        if (dns.isNotEmpty()) {
            sb.appendLine("DNS = ${dns.joinToString(", ")}")
        }
        mtu?.let { sb.appendLine("MTU = $it") }
        sb.appendLine()
        sb.appendLine("[Peer]")
        sb.appendLine("PublicKey = $publicKey")
        presharedKey?.let { sb.appendLine("PresharedKey = $it") }
        sb.appendLine("Endpoint = $endpoint")
        sb.appendLine("AllowedIPs = $allowedIps")
        persistentKeepalive?.let { sb.appendLine("PersistentKeepalive = $it") }
        return sb.toString()
    }

    companion object {
        fun parse(raw: String): TunnelConfig {
            require(raw.isNotBlank()) { "Config input must not be empty" }

            val lines = raw.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }

            val interfaceMap = mutableMapOf<String, String>()
            val peerMap = mutableMapOf<String, String>()
            var currentSection = ""

            for (line in lines) {
                when {
                    line.equals("[Interface]", ignoreCase = true) -> currentSection = "interface"
                    line.equals("[Peer]", ignoreCase = true) -> currentSection = "peer"
                    line.contains("=") -> {
                        val key = line.substringBefore("=").trim()
                        val value = line.substringAfter("=").trim()
                        when (currentSection) {
                            "interface" -> interfaceMap[key.lowercase()] = value
                            "peer" -> peerMap[key.lowercase()] = value
                        }
                    }
                }
            }

            val privateKey = interfaceMap["privatekey"]
                ?: throw IllegalArgumentException("Missing PrivateKey in [Interface]")
            val address = interfaceMap["address"] ?: ""
            val dnsRaw = interfaceMap["dns"] ?: ""
            val dns = if (dnsRaw.isBlank()) emptyList()
                      else dnsRaw.split(",").map { it.trim() }.filter { it.isNotBlank() }
            val mtu = interfaceMap["mtu"]?.toIntOrNull()

            val publicKey = peerMap["publickey"]
                ?: throw IllegalArgumentException("Missing PublicKey in [Peer]")
            val presharedKey = peerMap["presharedkey"]
            val endpoint = peerMap["endpoint"]
                ?: throw IllegalArgumentException("Missing Endpoint in [Peer]")
            val allowedIps = peerMap["allowedips"]
                ?: throw IllegalArgumentException("Missing AllowedIPs in [Peer]")
            val persistentKeepalive = peerMap["persistentkeepalive"]?.toIntOrNull()

            return TunnelConfig(
                privateKey = privateKey,
                address = address,
                dns = dns,
                mtu = mtu,
                publicKey = publicKey,
                presharedKey = presharedKey,
                endpoint = endpoint,
                allowedIps = allowedIps,
                persistentKeepalive = persistentKeepalive
            )
        }
    }
}
