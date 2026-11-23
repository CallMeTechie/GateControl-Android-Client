package com.gatecontrol.android.common

import java.security.MessageDigest

/** Data cached on the device belongs to exactly one login (server + token). */
object LoginOwner {
    /** Short hash of server + token; empty without a server. */
    fun key(serverUrl: String, token: String): String {
        if (serverUrl.isBlank()) return ""
        val digest = MessageDigest.getInstance("SHA-256").digest("$serverUrl\n$token".toByteArray())
        return digest.take(12).joinToString("") { "%02x".format(it) }
    }
}
