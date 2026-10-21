package com.gatecontrol.android.gatedrop

/** What the person picked in the share sheet (multi-select). */
data class GdSelection(
    val people: Set<String> = emptySet(),
    val devices: Set<String> = emptySet(),
    val nas: Set<String> = emptySet(),
    val link: GdLinkRequest? = null,
) {
    val isEmpty: Boolean get() = people.isEmpty() && devices.isEmpty() && nas.isEmpty() && link == null

    fun togglePerson(id: String) = copy(people = people.toggle(id))
    fun toggleDevice(id: String) = copy(devices = devices.toggle(id))
    fun toggleNas(id: String) = copy(nas = nas.toggle(id))

    private fun Set<String>.toggle(id: String) = if (id in this) this - id else this + id
}

/** How a device row reads ("online · direkt im WLAN", "offline · kommt an, sobald online"). */
enum class GdDeviceState { DIRECT, ONLINE, OFFLINE }

/** How the files of one send travel. */
data class GdSendPlan(
    /** Upload the files to the server before `POST sends`. */
    val uploadFirst: Boolean,
    /** Send the files as `local` items: only direct-capable devices are chosen. */
    val localItems: Boolean,
)

/**
 * Decisions of GateDrop that do not need Android: which targets are
 * shown and can be sent to, how the files travel, when the server path
 * takes over. Pure, unit-tested.
 */
object GateDropLogic {
    /** Files above this size use the resumable upload (contract A1). */
    const val RESUMABLE_FROM = 50L * 1024 * 1024
    /** Per file (contract: ≤ 2 GB). */
    const val MAX_FILE_BYTES = 2L * 1024 * 1024 * 1024
    /** Received files up to this total are saved right away also on a metered network. */
    const val AUTO_SAVE_METERED_BYTES = 25L * 1024 * 1024
    /** Connect timeout of a LAN candidate (contract C: 2 s, then the server path). */
    const val CONNECT_TIMEOUT_MS = 2_000L

    fun deviceState(d: GdDevice): GdDeviceState = when {
        d.online && d.direct -> GdDeviceState.DIRECT
        d.online -> GdDeviceState.ONLINE
        else -> GdDeviceState.OFFLINE
    }

    /**
     * The selection as `to`, limited to what [targets] still offer: unknown
     * ids are dropped, NAS and link only when they are offered.
     */
    fun recipients(selection: GdSelection, targets: GdTargets): GdRecipients {
        val people = targets.people.filter { it.id in selection.people }.map { if (it.isFamily) GdPerson.FAMILY_ID else it.id }
        val devices = targets.devices.filter { it.id in selection.devices }.map { it.id }
        val nas = if (targets.nasOffered) targets.nas.filter { it.id in selection.nas }.map { GdNasRef(it.id, it.path) } else emptyList()
        val link = selection.link?.takeIf { targets.linkOffered }
        return GdRecipients(people = people.distinct(), devices = devices, nas = nas, link = link)
    }

    /** Names of the chosen targets in sheet order (people, devices, NAS); the link is named by the caller. */
    fun chosenNames(selection: GdSelection, targets: GdTargets): List<String> =
        targets.people.filter { it.id in selection.people }.map { it.name } +
            targets.devices.filter { it.id in selection.devices }.map { it.name } +
            (if (targets.nasOffered) targets.nas.filter { it.id in selection.nas }.map { it.label } else emptyList())

    /** Something to send and somewhere to send it. */
    fun canSend(selection: GdSelection, targets: GdTargets, fileCount: Int, hasText: Boolean): Boolean {
        if (fileCount == 0 && !hasText) return false
        if (fileCount > GateDropJson.MAX_ITEMS) return false
        val to = recipients(selection, targets)
        if (to.isEmpty) return false
        // A link for externals carries files only.
        if (to.link != null && fileCount == 0 && to.people.isEmpty() && to.devices.isEmpty() && to.nas.isEmpty()) return false
        return true
    }

    /**
     * Upload first unless every recipient is an own device that can be
     * reached directly: people, NAS and links always need the files on the
     * server, a device that is not direct gets them from there.
     */
    fun plan(fileCount: Int, to: GdRecipients, devices: List<GdDevice>): GdSendPlan {
        if (fileCount == 0) return GdSendPlan(uploadFirst = false, localItems = false)
        val onlyDevices = to.people.isEmpty() && to.nas.isEmpty() && to.link == null && to.devices.isNotEmpty()
        val allDirect = onlyDevices && to.devices.all { id -> devices.firstOrNull { it.id == id }?.let { it.online && it.direct } == true }
        return if (allDirect) GdSendPlan(uploadFirst = false, localItems = true) else GdSendPlan(uploadFirst = true, localItems = false)
    }

    /**
     * Files sent as `local` items: which of them the server still needs
     * after the direct transfers. A transfer answered `fallback: "upload"`
     * needs its `missing` indexes (all when not named); a failed transfer
     * whose result did not reach the plugin needs all. `fallback: "server"`
     * (the files are on the server already) and successes need nothing.
     */
    fun missingAfterDirect(localItems: Boolean, fileCount: Int, results: List<Pair<Boolean, GdTransferAnswer?>>): List<Int> {
        if (!localItems || fileCount == 0) return emptyList()
        val all = (0 until fileCount).toList()
        val out = sortedSetOf<Int>()
        for ((ok, answer) in results) {
            when {
                answer != null && serverWantsUpload(answer.fallback) -> out += answer.missing?.filter { it in all }?.ifEmpty { all } ?: all
                !ok && answer == null -> out += all
            }
        }
        return out.toList()
    }

    /** The answer of `transfers/<id>/result` asks the sender to upload the files. */
    fun serverWantsUpload(fallback: String?): Boolean = fallback == "upload"

    /** `POST sends` refused `local` items (`409 upload_required`): upload and send again. */
    fun uploadRequired(code: String, http: Int): Boolean = code == "upload_required" || (http == 409 && code == "http_409")

    /** A shared text that is one web address goes as `link`, anything else as `text`. */
    fun asLink(text: String?): String? {
        val t = text?.trim() ?: return null
        if (t.contains('\n') || t.contains(' ')) return null
        return GateDropJson.webUrl(t)
    }

    /** Save received files right away: on Wi-Fi / Ethernet always, on mobile data only small ones. */
    fun autoSave(totalBytes: Long, unmetered: Boolean): Boolean = unmetered || totalBytes <= AUTO_SAVE_METERED_BYTES

    /** IPv4 [ip] lies in [net]/[prefix]. */
    fun inSubnet(ip: String, net: String, prefix: Int): Boolean {
        val a = ipv4(ip) ?: return false
        val b = ipv4(net) ?: return false
        if (prefix !in 0..32) return false
        val mask = if (prefix == 0) 0L else (0xFFFF_FFFFL shl (32 - prefix)) and 0xFFFF_FFFFL
        return (a and mask) == (b and mask)
    }

    fun ipv4(ip: String): Long? {
        val parts = ip.split('.')
        if (parts.size != 4) return null
        var v = 0L
        for (p in parts) {
            val n = p.toIntOrNull()?.takeIf { it in 0..255 && p.length in 1..3 } ?: return null
            v = (v shl 8) or n.toLong()
        }
        return v
    }

    /** Candidates in one of this device's own subnets first (order kept otherwise). */
    fun orderCandidates(candidates: List<GdCandidate>, lan: List<Pair<String, Int>>): List<GdCandidate> =
        candidates.sortedBy { c -> if (lan.any { (ip, prefix) -> inSubnet(c.host, ip, prefix) }) 0 else 1 }

    /** Delivery summary of a sent entry: the "worst" state wins so a waiting device stays visible. */
    fun overallStatus(deliveries: List<GdDelivery>): String? {
        if (deliveries.isEmpty()) return null
        val order = listOf(GdDelivery.FAILED, GdDelivery.EXPIRED, GdDelivery.PENDING, GdDelivery.DIRECT, GdDelivery.DELIVERED, GdDelivery.OPENED)
        return deliveries.map { it.status }.minByOrNull { s -> order.indexOf(s).let { if (it < 0) order.size else it } }
    }

    /**
     * The server's received sends with this device's saved copies,
     * plus what arrived directly only here; newest first. Pure.
     */
    fun mergeReceived(server: List<GdEntry>?, local: List<GdReceived>): List<GdReceived> {
        val fromServer = server.orEmpty().map { e ->
            val key = "s:${e.id}"
            // Saved here already: over the server (same key) or directly (same send).
            val known = local.firstOrNull { it.key == key } ?: local.firstOrNull { it.sendId == e.id }
            GdReceived(
                key = key,
                sendId = e.id,
                from = e.from ?: known?.from,
                at = e.at ?: known?.at ?: 0L,
                text = e.text,
                link = e.link,
                note = e.note,
                files = e.items.map { item ->
                    val copy = known?.files?.firstOrNull { it.mediaId != null && it.mediaId == item.mediaId }
                        ?: known?.files?.firstOrNull { it.name == item.name && it.bytes == item.bytes }
                    GdReceivedFile(item.name, item.mime, item.bytes, item.mediaId, copy?.uri)
                },
            )
        }
        val serverKeys = fromServer.map { it.key }.toSet()
        val serverSends = fromServer.mapNotNull { it.sendId }.toSet()
        val onlyLocal = local.filter { it.key !in serverKeys && (it.sendId == null || it.sendId !in serverSends) }
        return (fromServer + onlyLocal).sortedByDescending { it.at }
    }
}
