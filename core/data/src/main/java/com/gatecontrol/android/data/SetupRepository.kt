package com.gatecontrol.android.data

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SetupRepository @Inject constructor(private val storage: EncryptedStorage) {

    companion object {
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_API_TOKEN = "api_token"
        private const val KEY_PEER_ID = "peer_id"
        private const val KEY_CONFIG_HASH = "config_hash"
        private const val KEY_WG_CONFIG = "wg_config"
    }

    fun save(serverUrl: String, apiToken: String, peerId: Int) {
        storage.putString(KEY_SERVER_URL, serverUrl)
        storage.putString(KEY_API_TOKEN, apiToken)
        storage.putInt(KEY_PEER_ID, peerId)
    }

    fun getServerUrl(): String = storage.getString(KEY_SERVER_URL, "")

    fun getApiToken(): String = storage.getString(KEY_API_TOKEN, "")

    fun getPeerId(): Int = storage.getInt(KEY_PEER_ID, -1)

    fun saveConfigHash(hash: String) {
        storage.putString(KEY_CONFIG_HASH, hash)
    }

    fun getConfigHash(): String = storage.getString(KEY_CONFIG_HASH, "")

    fun saveWireGuardConfig(config: String) {
        storage.putString(KEY_WG_CONFIG, config)
    }

    fun getWireGuardConfig(): String = storage.getString(KEY_WG_CONFIG, "")

    fun hasWireGuardConfig(): Boolean = getWireGuardConfig().isNotEmpty()

    fun isConfigured(): Boolean = getServerUrl().isNotEmpty() && getApiToken().isNotEmpty()

    fun isRegistered(): Boolean = getPeerId() > 0

    fun clear() {
        storage.clear()
    }
}
