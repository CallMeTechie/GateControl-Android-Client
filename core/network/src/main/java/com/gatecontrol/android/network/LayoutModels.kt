package com.gatecontrol.android.network

import com.google.gson.JsonElement

/**
 * Order of the start and Familie tabs as the server keeps it for the
 * account (`GET /api/v1/client/layout`, core 1.181+; 404 before).
 * [scope]: `device` (the app keeps it on the device, [layout] null),
 * `user` (per person) or `global` (one for everybody, [editable] for
 * admins only). [updatedAt] is a number or an ISO text, kept as it came.
 */
data class ClientLayoutResponse(
    val ok: Boolean? = null,
    val scope: String? = null,
    val editable: Boolean? = null,
    val layout: ClientLayoutDto? = null,
    val updatedAt: JsonElement? = null,
)

/** The lists of entry ids (≤ 100 each, 1–120 chars of `[A-Za-z0-9:_./@-]`). */
data class ClientLayoutDto(
    val start: List<String>? = null,
    val startHidden: List<String>? = null,
    val familie: List<String>? = null,
    val familieHidden: List<String>? = null,
)

/** `PUT /api/v1/client/layout`; 409 `device_scope`, 403 `forbidden` (global, not an admin). */
data class ClientLayoutRequest(val layout: ClientLayoutDto)

data class ClientLayoutPutResponse(
    val ok: Boolean? = null,
    val updatedAt: JsonElement? = null,
    val error: String? = null,
)
