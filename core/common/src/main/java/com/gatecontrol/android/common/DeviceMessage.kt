package com.gatecontrol.android.common

/**
 * A message between the person's own devices, carried as a GateDrop text (docs/CAST_PROTOCOL.md): one line, the
 * prefix [PREFIX] and then a JSON object. The apps (phone, TV, Windows) take such a text out of GateDrop before it is
 * shown — it never becomes a notification, an inbox entry or a received text — and hand it to the feature its `type`
 * names (`cast.*`: "Auf GateControl TV / PC abspielen").
 *
 * GateDrop's push body begins with the text (whitespace folded, at most 300 characters), so [looksLike] already knows
 * from the push that a send is one; the JSON itself is read from `GET sends/<id>`. Pure (no Android).
 */
object DeviceMessage {
    /** The first characters of every device message. */
    const val PREFIX = "gcmsg/1 "

    /** GateDrop takes texts up to 20 000 characters; a device message stays far below. */
    const val MAX_LENGTH = 16_000

    /** [json] (one JSON object) as the text of a send; null when it is too long or no object. */
    fun wrap(json: String): String? {
        val body = json.trim()
        if (!body.startsWith("{") || !body.endsWith("}")) return null
        if (body.any { it == '\n' || it == '\r' }) return null
        val text = PREFIX + body
        return text.takeIf { it.length <= MAX_LENGTH }
    }

    /** The JSON of a received text; null when [text] is no device message. */
    fun unwrap(text: String?): String? {
        val t = text?.trim() ?: return null
        if (t.length > MAX_LENGTH || !t.startsWith(PREFIX)) return null
        return t.substring(PREFIX.length).trim().takeIf { it.startsWith("{") && it.endsWith("}") }
    }

    /** A push body or text that begins like a device message (the push only has the start of the text). */
    fun looksLike(text: String?): Boolean = text?.trimStart()?.startsWith(PREFIX) == true
}
