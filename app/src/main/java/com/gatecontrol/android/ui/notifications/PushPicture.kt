package com.gatecontrol.android.ui.notifications

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import com.gatecontrol.android.push.NotifyMediaLoader
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface NotifyMediaEntryPoint {
    fun notifyMediaLoader(): NotifyMediaLoader
}

/**
 * The picture of a push message ([path] = `imageUrl`, on the own server
 * only), loaded through [NotifyMediaLoader] into memory; null while
 * loading, without picture, expired or offline.
 */
@Composable
fun rememberPushPicture(path: String?, maxSidePx: Int): ImageBitmap? {
    if (path == null || LocalInspectionMode.current) return null
    val context = LocalContext.current.applicationContext
    val loader = remember(context) {
        runCatching { EntryPointAccessors.fromApplication(context, NotifyMediaEntryPoint::class.java).notifyMediaLoader() }.getOrNull()
    } ?: return null
    val bitmap by produceState(loader.peek(path, maxSidePx)?.asImageBitmap(), path, maxSidePx) {
        if (value == null) value = loader.load(path, maxSidePx)?.asImageBitmap()
    }
    return bitmap
}
