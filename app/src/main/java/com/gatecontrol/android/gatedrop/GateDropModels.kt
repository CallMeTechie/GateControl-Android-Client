package com.gatecontrol.android.gatedrop

/** Plugin id of GateDrop ("Teilen" over the own server, docs: GateDrop contract B). */
const val GATEDROP_PLUGIN = "gatecontrol-gatedrop"

/** What the caller may do beyond people and own devices (`targets.me`). */
data class GdMe(val canLink: Boolean = false, val canNas: Boolean = false)

/** A person (`kind` user) or the family (`kind` family) of `targets.people`. */
data class GdPerson(
    val id: String,
    val name: String,
    /** Relative avatar path of the own server (validated), null = initials. */
    val avatar: String? = null,
    val kind: String = KIND_USER,
    val initials: String? = null,
    /** Favourite colour (`#RRGGBB`) for the initials. */
    val colorHex: String? = null,
) {
    val isFamily: Boolean get() = kind == KIND_FAMILY

    companion object {
        const val KIND_USER = "user"
        const val KIND_FAMILY = "family"
        /** Recipient id of the whole family in `to.people`. */
        const val FAMILY_ID = "family"
    }
}

/** An own installation (Android, Windows, …) of `targets.devices`. */
data class GdDevice(
    val id: String,
    val name: String,
    val platform: String? = null,
    val online: Boolean = false,
    /** Same network as this device and listening: the files may go directly. */
    val direct: Boolean = false,
)

/** A NAS folder files may be put into (`targets.nas`). */
data class GdNas(val id: String, val label: String, val path: String? = null)

/** `GET targets`. */
data class GdTargets(
    val me: GdMe = GdMe(),
    val people: List<GdPerson> = emptyList(),
    val devices: List<GdDevice> = emptyList(),
    val nas: List<GdNas> = emptyList(),
    val linksEnabled: Boolean = false,
) {
    /** "Ablegen" is offered: allowed for the caller and folders exist. */
    val nasOffered: Boolean get() = me.canNas && nas.isNotEmpty()

    /** "Link für Externe" is offered: allowed for the caller and switched on by the administrator. */
    val linkOffered: Boolean get() = me.canLink && linksEnabled
}

/** Validity of a link for externals (`to.link.expires`). */
enum class GdExpiry(val wire: String) {
    HOUR("1h"), DAY("1d"), WEEK("7d"), ONCE("once");

    companion object {
        fun fromWire(value: String?): GdExpiry? = entries.firstOrNull { it.wire == value }
    }
}

/** Link options chosen in the sheet. */
data class GdLinkRequest(val expiry: GdExpiry = GdExpiry.DAY, val pin: Boolean = true)

/** A NAS folder chosen (`to.nas[]`). */
data class GdNasRef(val id: String, val path: String? = null)

/** Recipients of one send (`to`). */
data class GdRecipients(
    val people: List<String> = emptyList(),
    val devices: List<String> = emptyList(),
    val nas: List<GdNasRef> = emptyList(),
    val link: GdLinkRequest? = null,
) {
    val isEmpty: Boolean get() = people.isEmpty() && devices.isEmpty() && nas.isEmpty() && link == null
}

/**
 * One item of `POST sends`: an uploaded file ([mediaId]) or — only for
 * direct-capable devices — a file still on this device ([local]).
 */
data class GdSendItem(
    val name: String,
    val mime: String,
    val bytes: Long,
    val mediaId: String? = null,
    val local: Boolean = false,
)

/** Transfer mode of protocol C. */
enum class GdMode(val wire: String) {
    /** The sender posts the files to the listening receiver. */
    PUSH("push"),

    /** The receiver fetches the files from the listening sender. */
    PULL("pull");

    companion object {
        fun fromWire(value: String?): GdMode? = entries.firstOrNull { it.wire == value }
    }
}

/** A LAN address of a listener (`candidates[]`, `ip:port`). */
data class GdCandidate(val host: String, val port: Int) {
    override fun toString(): String = "$host:$port"
}

/** `direct[]` of the `POST sends` answer: this device sends one transfer itself. */
data class GdDirect(
    val deviceId: String,
    val transferId: String,
    val mode: GdMode,
    val candidates: List<GdCandidate>,
    val key: ByteArray,
    val ticket: String,
) {
    override fun equals(other: Any?): Boolean =
        other is GdDirect && other.deviceId == deviceId && other.transferId == transferId && other.mode == mode &&
            other.candidates == candidates && other.key.contentEquals(key) && other.ticket == ticket

    override fun hashCode(): Int = transferId.hashCode()

    override fun toString(): String = "GdDirect(device=$deviceId, transfer=$transferId, mode=$mode, candidates=${candidates.size})"
}

/** State of one recipient of a send (`deliveries[]`). */
data class GdDelivery(
    val kind: String,
    val target: String?,
    val targetName: String? = null,
    val status: String,
    val via: String? = null,
    val error: String? = null,
) {
    companion object {
        const val PENDING = "pending"
        /** The target device was offline when sent: it fetches the send once it is online (GateDrop 1.1). */
        const val WAITING = "waiting"
        const val DIRECT = "direct"
        const val DELIVERED = "delivered"
        const val OPENED = "opened"
        const val FAILED = "failed"
        const val EXPIRED = "expired"
    }
}

/** The created link (`link` of the `POST sends` answer); [pin] null = without PIN. */
data class GdLinkResult(val url: String, val pin: String?)

/** Answer of `POST sends`. */
data class GdSendResult(
    val id: String,
    val deliveries: List<GdDelivery> = emptyList(),
    val link: GdLinkResult? = null,
    val direct: List<GdDirect> = emptyList(),
)

/** A file of a received transfer offer (`items[]` of the silent push). */
data class GdOfferItem(val name: String, val bytes: Long, val mime: String)

/**
 * Silent data push `{ gatedrop: { transfer, mode, from, items, candidates,
 * key, ticket } }`: this device should fetch (pull) or receive (push) a
 * transfer directly in the LAN.
 */
data class GdTransferOffer(
    val transferId: String,
    val mode: GdMode,
    val from: String?,
    val items: List<GdOfferItem>,
    val candidates: List<GdCandidate>,
    val key: ByteArray,
    val ticket: String,
    val sendId: String? = null,
    /** Number of files; more than [items] when the push left them out (`GET transfers/<id>`). */
    val itemCount: Int = items.size,
) {
    /** The push carried no file list: ask `GET transfers/<id>`. */
    val needsDetails: Boolean get() = items.isEmpty() || items.size < itemCount

    override fun equals(other: Any?): Boolean =
        other is GdTransferOffer && other.transferId == transferId && other.mode == mode && other.from == from &&
            other.items == items && other.candidates == candidates && other.key.contentEquals(key) && other.ticket == ticket &&
            other.sendId == sendId

    override fun hashCode(): Int = transferId.hashCode()

    override fun toString(): String = "GdTransferOffer(transfer=$transferId, mode=$mode, items=${items.size})"
}

/** A file of a send (`items[]` of `sends/<id>` and the inbox). */
data class GdItem(
    val mediaId: String?,
    val name: String,
    val mime: String,
    val bytes: Long,
    /** The file can be fetched from the server now (`available`, GateDrop 1.1; older builds: always). */
    val available: Boolean = true,
)

/** One entry of `GET inbox?kind=received|sent` or `GET sends/<id>`. */
data class GdEntry(
    val id: String,
    val at: Long?,
    /** Sender (device or person name) of a received send. */
    val from: String? = null,
    val text: String? = null,
    val link: String? = null,
    val note: String? = null,
    val items: List<GdItem> = emptyList(),
    val deliveries: List<GdDelivery> = emptyList(),
    val opened: Boolean = false,
    /** The send expired: its files are gone from the server. */
    val expired: Boolean = false,
)

/** One link for externals (`GET inbox?kind=links`). */
data class GdLink(
    val token: String,
    val url: String?,
    val title: String?,
    val expiresAt: Long?,
    val downloads: Int = 0,
    val maxDownloads: Int? = null,
    val pin: Boolean = false,
    /** active | expired | locked | revoked. */
    val state: String = STATE_ACTIVE,
) {
    val active: Boolean get() = state == STATE_ACTIVE

    companion object {
        const val STATE_ACTIVE = "active"
        const val STATE_EXPIRED = "expired"
        const val STATE_LOCKED = "locked"
        const val STATE_REVOKED = "revoked"
    }
}

/** Kind of an inbox list. */
enum class GdInboxKind(val wire: String) { RECEIVED("received"), SENT("sent"), LINKS("links") }

/** Outcome of a call to the GateDrop app API (as ChatResult). */
sealed interface GdResult<out T> {
    data class Ok<T>(val value: T) : GdResult<T>

    /** The server answered with an error: [code] from `{ok:false, code|error}` or `http_<status>`. */
    data class Failed(val code: String, val http: Int) : GdResult<Nothing>

    /** No answer (network, VPN, timeout). */
    data object Offline : GdResult<Nothing>

    data object NotConfigured : GdResult<Nothing>
}

fun <T> GdResult<T>.valueOrNull(): T? = (this as? GdResult.Ok)?.value

/** One answered part of a resumable upload ([complete] null: the server does not know resumable uploads). */
data class GdUploadPart(val complete: Boolean?, val mediaId: String?, val offset: Long?, val code: String? = null)

/** Answer of `POST transfers/<id>/result`. */
data class GdTransferAnswer(val fallback: String? = null, val missing: List<Int>? = null)
