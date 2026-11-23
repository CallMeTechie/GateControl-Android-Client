package com.gatecontrol.android.gatedrop

import com.gatecontrol.android.scan.MediaScan
import java.io.File

// Kept by the phone app (GateDropStore, GateDropDirect) and read by the shared logic ([GateDropLogic]).

/** A received file as this device knows it: [uri] once saved in Downloads/GateControl. */
data class GdReceivedFile(
    val name: String,
    val mime: String,
    val bytes: Long,
    val mediaId: String? = null,
    val uri: String? = null,
    /** The host's virus scan (GateDrop 1.4) when the server named one; blocked: not downloaded, a mark instead. */
    val scan: MediaScan? = null,
)

/** Something received on this device (direct or over the server). */
data class GdReceived(
    /** `s:<sendId>` or `t:<transferId>`. */
    val key: String,
    val sendId: String?,
    val from: String?,
    val at: Long,
    val text: String? = null,
    val link: String? = null,
    val note: String? = null,
    val files: List<GdReceivedFile> = emptyList(),
)

/** A file waiting to be sent (copied into the app's cache). */
data class GdJobFile(val path: String, val name: String, val mime: String, val size: Long)

/** One send waiting for (or running in) [GateDropSendWorker]. */
data class GdJob(
    val id: String,
    val files: List<GdJobFile>,
    val text: String?,
    val link: String?,
    val note: String?,
    val to: GdRecipients,
    /** Ids of the chosen devices that were direct-capable when sending. */
    val directDevices: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    /** Media ids of the files uploaded so far (in file order). */
    val uploaded: List<String> = emptyList(),
    /** `POST sends` went through: the send's id. */
    val sendId: String? = null,
    /** A direct transfer failed: the files still have to reach the server (`POST sends/<id>/items`). */
    val fallback: Boolean = false,
    /** Item indexes the server still needs after a failed direct transfer (`missing`). */
    val missing: List<Int> = emptyList(),
)

/** A local file to send directly (index = position in the send). */
data class GdLocalFile(val file: File, val name: String, val size: Long)

/** Outcome of a direct transfer. */
sealed interface GdDirectOutcome {
    data class Done(val bytes: Long) : GdDirectOutcome

    /** Nothing reachable / refused / broken: the server path takes over. */
    data class Failed(val reason: String) : GdDirectOutcome
}
