package com.gatecontrol.android.drive

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * How much of the gallery the app sees. Android 14 has "Ausgewählte Fotos" (`READ_MEDIA_VISUAL_USER_SELECTED`): the
 * app then sees only what the person picked, new photos and videos not at all. That permission is also granted with
 * full access — so it alone never means "everything": full is `READ_MEDIA_IMAGES` (and `READ_MEDIA_VIDEO` for videos).
 */
object DriveMediaAccess {

    enum class Access { FULL, PARTIAL, NONE }

    /** Pure part (unit-tested): the grants → what the app sees of photos (and videos with [videos]). */
    fun of(sdk: Int, images: Boolean, video: Boolean, selected: Boolean, legacy: Boolean, videos: Boolean): Access = when {
        sdk < 33 -> if (legacy) Access.FULL else Access.NONE
        images && (!videos || video) -> Access.FULL
        // photos in full, the videos not (never asked for, or only some): videos only as picked
        images -> Access.PARTIAL
        sdk >= 34 && selected -> Access.PARTIAL
        else -> Access.NONE
    }

    fun of(context: Context, videos: Boolean): Access {
        fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
        return of(
            sdk = Build.VERSION.SDK_INT,
            images = Build.VERSION.SDK_INT >= 33 && granted(Manifest.permission.READ_MEDIA_IMAGES),
            video = Build.VERSION.SDK_INT >= 33 && granted(Manifest.permission.READ_MEDIA_VIDEO),
            selected = Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED),
            legacy = Build.VERSION.SDK_INT < 33 && granted(Manifest.permission.READ_EXTERNAL_STORAGE),
            videos = videos,
        )
    }

    /**
     * What to ask for to see everything: the photos (and videos), their original location data, on Android 14 also
     * "Ausgewählte Fotos" (the system then offers both), on Android 13+ the notification of the backup's progress.
     */
    fun permissions(videos: Boolean, notifications: Boolean = true): Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= 33) {
            add(Manifest.permission.READ_MEDIA_IMAGES)
            if (videos) add(Manifest.permission.READ_MEDIA_VIDEO)
            if (Build.VERSION.SDK_INT >= 34) add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            if (notifications) add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        add(Manifest.permission.ACCESS_MEDIA_LOCATION)
    }.toTypedArray()

    /** What the line of "Orte nachtragen" says. */
    enum class FillLine { PERMISSION, PARTIAL, RUNNING, NOTHING, LAST, INTRO }

    /**
     * "Orte nachtragen": no permission; only picked photos (nothing new is found); running; a pass that looked at no
     * photo with full access ("Nichts nachzutragen …"); the last pass's counts; else the introduction.
     */
    fun fillLine(canLook: Boolean, partial: Boolean, running: Boolean, lastRunAt: Long, looked: Int): FillLine = when {
        !canLook -> FillLine.PERMISSION
        partial && !running -> FillLine.PARTIAL
        running -> FillLine.RUNNING
        lastRunAt > 0 && looked == 0 -> FillLine.NOTHING
        lastRunAt > 0 -> FillLine.LAST
        else -> FillLine.INTRO
    }
}
