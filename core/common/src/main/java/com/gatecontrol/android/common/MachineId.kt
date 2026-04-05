package com.gatecontrol.android.common

import android.content.Context
import android.provider.Settings
import java.security.MessageDigest

object MachineId {
    private var cached: String? = null

    fun getFingerprint(context: Context): String {
        cached?.let { return it }
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "unknown"
        val fingerprint = fingerprintFromString(androidId)
        cached = fingerprint
        return fingerprint
    }

    fun fingerprintFromString(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(input.toByteArray())
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
}
