package com.gatecontrol.android.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import android.content.SharedPreferences
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EncryptedStorage @Inject constructor(context: Context) {

    private val prefs by lazy {
        try {
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            EncryptedSharedPreferences.create(
                "gatecontrol_secure",
                masterKeyAlias,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            // Fallback to plain SharedPreferences if KeyStore fails
            context.getSharedPreferences("gatecontrol_secure_fallback", Context.MODE_PRIVATE)
        }
    }

    fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    /**
     * Write multiple key-value pairs in a single synchronous commit.
     * Use this when subsequent code reads from prefs immediately after writing.
     */
    fun commitBatch(vararg entries: Pair<String, Any>) {
        val editor = prefs.edit()
        for ((key, value) in entries) {
            when (value) {
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                is Boolean -> editor.putBoolean(key, value)
                else -> throw IllegalArgumentException("Unsupported type for key $key")
            }
        }
        editor.commit()
    }

    fun getString(key: String, default: String): String {
        return prefs.getString(key, default) ?: default
    }

    fun putInt(key: String, value: Int) {
        prefs.edit().putInt(key, value).apply()
    }

    fun getInt(key: String, default: Int): Int {
        return prefs.getInt(key, default)
    }

    fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
