package com.gatecontrol.android.ui.drive

import android.content.res.Resources
import com.gatecontrol.android.R
import com.gatecontrol.android.drive.DriveFrameLogic

/** The words of a reason a photo was left out of the photo frame ("2 Videos", "1 im Tresor" …). */
internal fun frameSkipRes(skip: DriveFrameLogic.Skip): Int = when (skip) {
    DriveFrameLogic.Skip.VIDEO -> R.plurals.drive_frame_skip_video
    DriveFrameLogic.Skip.NOT_IMAGE -> R.plurals.drive_frame_skip_not_image
    DriveFrameLogic.Skip.VAULT -> R.plurals.drive_frame_skip_vault
    DriveFrameLogic.Skip.BLOCKED -> R.plurals.drive_frame_skip_blocked
    DriveFrameLogic.Skip.CHILD -> R.plurals.drive_frame_skip_child
    DriveFrameLogic.Skip.NOT_FOUND -> R.plurals.drive_frame_skip_not_found
    DriveFrameLogic.Skip.OTHER -> R.plurals.drive_frame_skip_other
}

/**
 * The snackbar's sentence after „Für Fotorahmen freigeben“ / „Aus Fotorahmen entfernen“: how many, then on a line of its
 * own what was skipped and why ("Übersprungen: 2 Videos, 1 im Tresor").
 */
internal fun frameSnackText(r: Resources, s: DrivePhotosViewModel.FrameSnack): String {
    if (s.forbidden) return r.getString(R.string.drive_frame_forbidden)
    val base = when {
        s.add && s.count > 0 -> r.getQuantityString(R.plurals.drive_frame_added, s.count, s.count)
        s.add -> if (s.skipped.isEmpty()) r.getString(R.string.drive_frame_added_none) else null
        s.count > 0 -> r.getQuantityString(R.plurals.drive_frame_removed, s.count, s.count)
        else -> r.getString(R.string.drive_frame_removed_none)
    }
    val skipped = s.skipped.filterValues { it > 0 }.entries.joinToString(", ") { (why, n) -> r.getQuantityString(frameSkipRes(why), n, n) }
    val line = if (skipped.isEmpty()) null else r.getString(R.string.drive_frame_skipped, skipped)
    return listOfNotNull(base, line).joinToString("\n")
}
