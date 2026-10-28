package com.gatecontrol.android.drive

import com.gatecontrol.android.notify.model.PushMessage
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Where a tap on a Drive push leads (pure, unit-tested): `deadline` (Drive 1.8, `data.drive = { node, area, deadline }`)
 * opens the file's page, `vault` (Drive 1.9, a device asks: `data.drive = { vault, node, area, event }`) the vault with
 * its requests. Other Drive pushes keep the message's own detail.
 */
object DrivePush {
    private const val TOPIC_PREFIX = "plugin:$DRIVE_PLUGIN:"

    fun isDriveMessage(message: PushMessage): Boolean = message.topic.startsWith(TOPIC_PREFIX)

    /** `file:<node>` | `vault:<vault>:<node>`; null for other messages. */
    fun destination(message: PushMessage): String? {
        if (!isDriveMessage(message)) return null
        val drive = runCatching { JsonParser.parseString(message.dataJson ?: return null).asJsonObject.get("drive") }.getOrNull()
            ?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        return when (message.topic.removePrefix(TOPIC_PREFIX)) {
            "deadline" -> drive.id("node")?.let { "file:$it" }
            "vault" -> {
                val vault = DriveJson.vaultId(drive.get("vault")?.takeIf { it.isJsonPrimitive }?.asString) ?: return null
                drive.id("node")?.let { "vault:$vault:$it" }
            }
            else -> null
        }
    }

    private fun JsonObject.id(key: String): Long? = get(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asLong }.getOrNull() }?.takeIf { it > 0 }

    /** A destination of [destination] → the file's node id, or null. */
    fun fileOf(dest: String): Long? = dest.takeIf { it.startsWith("file:") }?.removePrefix("file:")?.toLongOrNull()?.takeIf { it > 0 }

    /** A destination of [destination] → vault id and its folder's node id, or null. */
    fun vaultOf(dest: String): Pair<String, Long>? {
        val parts = dest.split(':')
        if (parts.size != 3 || parts[0] != "vault") return null
        val vault = DriveJson.vaultId(parts[1]) ?: return null
        val node = parts[2].toLongOrNull()?.takeIf { it > 0 } ?: return null
        return vault to node
    }
}
