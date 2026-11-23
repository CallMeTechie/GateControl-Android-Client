package com.gatecontrol.android.drive

/**
 * Single photos for the photo frame of the GateControl TV (Drive 1.31, feature `framePhotos`): what the "Mehr" sheet of
 * ticked photos offers in its section „Fotorahmen“, and why photos were left out.
 */
object DriveFrameLogic {

    /** What the section „Fotorahmen“ offers: „Für Fotorahmen freigeben“ ([add]), „Aus Fotorahmen entfernen“ ([remove]). */
    data class Offer(val add: Boolean, val remove: Boolean) {
        val any: Boolean get() = add || remove

        companion object {
            val NONE = Offer(add = false, remove = false)
        }
    }

    /** Why a photo was left out, in the order the snackbar names them. */
    enum class Skip { VIDEO, NOT_IMAGE, VAULT, BLOCKED, CHILD, NOT_FOUND, OTHER }

    /** A picture the photo frame can show (no folder, no video, nothing of a vault). */
    fun isImage(n: DNode): Boolean = !n.isDir && n.vaultId == null && DriveLogic.kind(n) == DriveLogic.Kind.IMAGE

    /**
     * The section's entries for [nodes]: only with the feature and at least one picture among them. [shared]: those of
     * them shared already (`GET frame/photos?ids=`); null while unknown (then only „freigeben“). All shared: only
     * „entfernen“; some: both.
     */
    fun offer(me: DMe?, nodes: List<DNode>, shared: Set<Long>?): Offer {
        if (me?.has(DMe.FEATURE_FRAME_PHOTOS) != true) return Offer.NONE
        val images = nodes.filter(::isImage)
        if (images.isEmpty()) return Offer.NONE
        if (shared == null) return Offer(add = true, remove = false)
        val on = images.count { it.id in shared }
        return Offer(add = on < images.size, remove = on > 0)
    }

    /** The ids to ask about or to send: the pictures among [nodes] (a video would only come back as skipped). */
    fun ids(nodes: List<DNode>): List<Long> = nodes.filter(::isImage).map { it.id }.distinct()

    /** The server's [reason] as a [Skip]; `not_image` of a video ([node]) says "Video". */
    fun skip(reason: String, node: DNode? = null): Skip = when (reason) {
        "not_image" -> if (node != null && DriveMediaLogic.isVideo(node)) Skip.VIDEO else Skip.NOT_IMAGE
        "vault" -> Skip.VAULT
        "blocked" -> Skip.BLOCKED
        "child" -> Skip.CHILD
        "not_found" -> Skip.NOT_FOUND
        else -> Skip.OTHER
    }

    /**
     * How many were left out and why: the ones the server [skipped] and the ones not sent at all (videos and other
     * files among [nodes], [sent] the ids that went). Ordered as [Skip].
     */
    fun skipped(nodes: List<DNode>, sent: Collection<Long>, skipped: List<DFrameSkip>): Map<Skip, Int> {
        val byId = nodes.associateBy { it.id }
        val out = sortedMapOf<Skip, Int>()
        nodes.filter { !it.isDir && it.id !in sent }.forEach { n ->
            val why = if (n.vaultId != null) Skip.VAULT else skip("not_image", n)
            out[why] = (out[why] ?: 0) + 1
        }
        skipped.distinctBy { it.id }.forEach { s ->
            val why = skip(s.reason, byId[s.id])
            out[why] = (out[why] ?: 0) + 1
        }
        return LinkedHashMap(out)
    }

    /** `403 forbidden` (a child, or no right to write in „Meine Dateien“): this account may not share photos. */
    fun forbidden(r: DResult<*>): Boolean = r is DResult.Failed && r.code == "forbidden"
}
