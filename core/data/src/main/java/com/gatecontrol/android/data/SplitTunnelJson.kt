package com.gatecontrol.android.data

import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * The JSON format split-tunnel networks and apps are stored in
 * ([SettingsRepository.getSplitTunnelNetworks] / [SettingsRepository.getSplitTunnelAppsV2]).
 * Single place for reading and writing it — the connect path and the
 * settings screen used to carry their own copies.
 */
object SplitTunnelJson {

    data class Network(val cidr: String, val label: String)

    fun decodeNetworks(json: String): List<Network> = decode(json, "networks") { obj ->
        Network(obj.getString("cidr"), obj.optString("label", ""))
    }

    fun encodeNetworks(networks: List<Network>): String {
        val arr = JSONArray()
        networks.forEach { arr.put(JSONObject().put("cidr", it.cidr).put("label", it.label)) }
        return arr.toString()
    }

    fun decodeApps(json: String): List<String> = decode(json, "apps") { it.getString("package") }

    fun encodeApps(packages: List<String>): String {
        val arr = JSONArray()
        packages.forEach { arr.put(JSONObject().put("package", it).put("label", "")) }
        return arr.toString()
    }

    private fun <T> decode(json: String, what: String, item: (JSONObject) -> T): List<T> {
        if (json.isBlank() || json == "[]") return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { item(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            Timber.w(e, "Failed to parse split-tunnel %s JSON, falling back to empty", what)
            emptyList()
        }
    }
}
