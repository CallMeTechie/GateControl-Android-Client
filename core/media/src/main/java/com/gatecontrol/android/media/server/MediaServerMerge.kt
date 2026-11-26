package com.gatecontrol.android.media.server

import com.gatecontrol.android.media.MediaAddress
import com.gatecontrol.android.media.MediaLogic
import com.gatecontrol.android.media.MediaSource
import com.gatecontrol.android.media.MediaSourceKind
import java.util.Locale

/** One source set up on this device before the plugin, and what became of it (the sheet „Medien kommen jetzt vom Server“). */
data class MediaMigration(
    /** Its id on the device (kept when taken over: progress and downloads stay). */
    val id: String,
    val kind: MediaSourceKind,
    /** As it was shown („Jellyfin · DS918+“, `\\nas\filme`). */
    val label: String,
    /** Taken over as this source of the server (its name); null: the server does not have it, it is removed. */
    val takenAs: String?,
)

/** What [MediaServerMerge.merge] gives. */
data class MediaMergeResult(
    val list: List<MediaSource>,
    /** Sources that went (revoked, no longer in the list): their downloads go too. */
    val gone: List<String>,
    /** Jellyfin accesses to sign out at the server (taken over: the plugin's own access replaces them). */
    val signOut: List<MediaSource>,
    /** Sources set up on this device, taken over or to be removed (shown once). Empty: none were left. */
    val migration: List<MediaMigration>,
)

/**
 * The server's list (`v1/sources`) merged with the store: each source of the server once, with the device's own
 * values kept (its Jellyfin access, a share's password while its `secretRev` is the same, „Immer Original“). A source
 * set up on this device before (question 33 a) is taken over when the server has the same Jellyfin server (`serverId`)
 * or the same share address: same id, so progress and downloads stay. The rest stays until the sheet was seen
 * ([migration]), then it is removed. Pure, unit-tested.
 */
object MediaServerMerge {

    /** The id of a share's WebDAV half when it is new (its SMB half has the plugin's id). */
    fun webdavId(remoteId: String) = "$remoteId~webdav"

    private fun sameServer(a: String?, b: String?) = a != null && b != null && a.lowercase(Locale.ROOT) == b.lowercase(Locale.ROOT)

    fun merge(local: List<MediaSource>, server: MediaServerList): MediaMergeResult {
        val used = HashSet<String>()
        val signOut = ArrayList<MediaSource>()
        val taken = HashMap<String, String>()
        val out = ArrayList<MediaSource>()

        fun managed(remoteId: String, kind: MediaSourceKind) =
            local.firstOrNull { it.managed && it.remoteId == remoteId && it.kind == kind && it.id !in used }

        fun legacy(match: (MediaSource) -> Boolean) = local.firstOrNull { !it.managed && it.id !in used && match(it) }

        for (r in server.sources) {
            when (r.kind) {
                MediaServerSource.Kind.JELLYFIN -> {
                    val home = r.homeAddress ?: continue
                    val mine = managed(r.id, MediaSourceKind.JELLYFIN)
                    val old = mine ?: legacy { it.kind == MediaSourceKind.JELLYFIN && sameServer(it.serverId, r.serverId) }
                    old?.let { used += it.id }
                    // the access stays while it is this device's (the plugin's DeviceId) of the linked user
                    var token = old?.token
                    if (old != null && !old.managed && token != null) {
                        signOut += old
                        token = null
                    }
                    if (token != null && ((r.userId != null && old?.userId != r.userId) || old?.deviceId != r.deviceId || r.userId == null)) token = null
                    if (old != null && !old.managed) taken[old.id] = r.name
                    out += MediaSource(
                        id = old?.id ?: r.id,
                        kind = MediaSourceKind.JELLYFIN,
                        name = r.name,
                        address = home,
                        userId = if (token != null) old?.userId else null,
                        userName = if (token != null) old?.userName else r.userName,
                        token = token,
                        serverId = r.serverId ?: old?.serverId,
                        alwaysOriginal = old?.alwaysOriginal ?: r.alwaysOriginal,
                        managed = true,
                        remoteId = r.id,
                        deviceId = r.deviceId,
                        deviceName = r.deviceName,
                        linkedUserId = r.userId,
                        linkedUserName = r.userName,
                        awayAddress = r.awayAddress,
                        awayVpnAddress = r.awayVpnAddress,
                        downloads = r.downloads,
                        problem = r.problem,
                    )
                }
                MediaServerSource.Kind.SHARE -> {
                    fun half(kind: MediaSourceKind, address: String?, defaultId: String): MediaSource? {
                        address ?: return null
                        val mine = managed(r.id, kind)
                        val old = mine ?: legacy { it.kind == kind && MediaAddress.normalizeShare(kind, it.address) == address }
                        old?.let { used += it.id }
                        if (old != null && !old.managed) taken[old.id] = r.name
                        // the password stays while the server's version of it is the same (a taken-over one until fetched)
                        val keep = old != null && (!old.managed || old.secretRev == r.secretRev)
                        return MediaSource(
                            id = old?.id ?: defaultId,
                            kind = kind,
                            name = r.name,
                            address = address,
                            userName = r.shareUser ?: old?.userName,
                            password = if (keep) old?.password else null,
                            managed = true,
                            remoteId = r.id,
                            awayAddress = if (kind == MediaSourceKind.WEBDAV) r.awayAddress else null,
                            awayVpnAddress = if (kind == MediaSourceKind.WEBDAV) r.awayVpnAddress else null,
                            certSha256 = if (kind == MediaSourceKind.WEBDAV) r.certSha256 else null,
                            secretRev = if (keep && old?.managed == true) old.secretRev else 0,
                            downloads = r.downloads,
                        )
                    }
                    val smb = half(MediaSourceKind.SMB, r.smb, r.id)
                    val webdav = half(MediaSourceKind.WEBDAV, r.webdav, if (smb != null) webdavId(r.id) else r.id)
                    // the SMB half of a pair keeps the titles' id; an id taken over twice cannot be both halves
                    if (smb != null && webdav != null && smb.id != webdav.id) {
                        out += smb.copy(pairWith = webdav.id)
                        out += webdav.copy(pairWith = smb.id)
                    } else {
                        listOfNotNull(smb, webdav).distinctBy { it.id }.forEach { out += it.copy(pairWith = null) }
                    }
                }
            }
        }
        val gone = local.filter { it.managed && it.id !in used }.map { it.id }
        // without the right „Medien“ nothing of the device's own stays either; else they wait for the sheet
        val legacyLeft = local.filter { !it.managed && it.id !in used }
        val migration = ArrayList<MediaMigration>()
        local.filter { !it.managed }.forEach { s ->
            migration += MediaMigration(s.id, s.kind, label(s), taken[s.id])
        }
        val keepLegacy = if (server.media) legacyLeft else emptyList()
        val removedLegacy = if (server.media) emptyList() else legacyLeft
        signOut += removedLegacy.filter { it.kind == MediaSourceKind.JELLYFIN && it.token != null }
        return MediaMergeResult(
            list = out.distinctBy { it.id } + keepLegacy.map { it.copy(pairWith = it.pairWith?.takeIf { p -> keepLegacy.any { l -> l.id == p } }) },
            gone = gone + removedLegacy.map { it.id },
            signOut = signOut,
            migration = if (server.media) migration else emptyList(),
        )
    }

    /** „Medien kommen jetzt vom Server“ seen („Verstanden“): the device's own sources the server does not have go. */
    fun dropLegacy(list: List<MediaSource>): Pair<List<MediaSource>, List<MediaSource>> = list.partition { it.managed }

    private fun label(s: MediaSource): String = if (s.kind.share) MediaLogic.shareAddress(s) else "Jellyfin · ${s.name}"
}
