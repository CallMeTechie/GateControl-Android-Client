package com.gatecontrol.android.gatedrop

import com.gatecontrol.android.common.DeviceMessage

/** A device message read from GateDrop: its JSON, the device that sent it, when the server took it. */
data class GdDeviceMessage(val sendId: String, val fromDevice: String, val json: String, val at: Long?)

/**
 * Messages between the person's own devices over GateDrop ([DeviceMessage], docs/CAST_PROTOCOL.md): a text to one
 * own device, never a file. No server change: it is an ordinary send of the plugin.
 *
 * * Sending: the text goes to exactly one own device (`to.devices`). A direct transfer the plugin plans for it (both
 *   devices in the same network and one listening) is answered at once as failed with [DIRECT_DECLINED], so the
 *   plugin delivers over the server and the other end gets the push without waiting for a connection.
 * * Receiving: only a send the server marks as one's own (`from.own`) from a known device counts; it is confirmed
 *   (`opened`) so it leaves `GET pending` and the plugin's waiting list.
 */
class GateDropDeviceMessages(private val api: GateDropApi) {

    /** [json] as a device message to the own device [deviceId]; the send's id. */
    suspend fun send(deviceId: String, json: String): GdResult<String> {
        val text = DeviceMessage.wrap(json) ?: return GdResult.Failed(GateDropApi.CODE_INVALID, 0)
        val clean = GateDropJson.text(text) ?: return GdResult.Failed(GateDropApi.CODE_INVALID, 0)
        return when (val r = api.send(emptyList(), clean, null, null, GdRecipients(devices = listOf(deviceId)))) {
            is GdResult.Ok -> {
                for (d in r.value.direct) runCatching { api.transferResult(d.transferId, false, error = DIRECT_DECLINED) }
                GdResult.Ok(r.value.id)
            }
            is GdResult.Failed -> r
            GdResult.Offline -> GdResult.Offline
            GdResult.NotConfigured -> GdResult.NotConfigured
        }
    }

    /**
     * The device message of the send [sendId] (named by a push or `GET pending`), confirmed as received; null when it
     * is none, not from an own device, or cannot be read now.
     */
    suspend fun read(sendId: String): GdDeviceMessage? {
        val entry = (api.sendDetail(sendId) as? GdResult.Ok)?.value ?: return null
        return take(entry)
    }

    /** [entry] (from `GET pending` or `GET sends/<id>`) as a device message, confirmed; null when it is none. */
    suspend fun take(entry: GdEntry): GdDeviceMessage? {
        val message = of(entry) ?: return null
        runCatching { api.opened(entry.id) }
        return message
    }

    companion object {
        /** The reason a sender gives for a direct transfer of a device message (the server way is quicker). */
        const val DIRECT_DECLINED = "unsupported"

        /** [entry] as a device message: one's own, from a device, a text with no files. Pure. */
        fun of(entry: GdEntry): GdDeviceMessage? {
            if (entry.fromOwn != true || entry.items.isNotEmpty()) return null
            val device = entry.fromDevice ?: return null
            val json = DeviceMessage.unwrap(entry.text) ?: return null
            return GdDeviceMessage(entry.id, device, json, entry.at)
        }

        /** A send that is (or may be) a device message: it must not be shown as a received text. */
        fun isDeviceMessage(entry: GdEntry): Boolean = DeviceMessage.looksLike(entry.text)
    }
}
