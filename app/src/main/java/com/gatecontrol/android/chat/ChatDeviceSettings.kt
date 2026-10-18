package com.gatecontrol.android.chat

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.gatecontrol.android.support.CrashReports
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private val Context.chatDeviceStore: DataStore<Preferences> by preferencesDataStore(name = "chat_device_settings")

/**
 * Chat settings of this device only (never sent to the server): sounds in
 * the open chat, wallpaper, font size, own bubble colour, Enter sends,
 * automatic downloads, sending quality, app lock, screenshots and which
 * home actions the attach sheet offers.
 */
data class ChatDeviceConfig(
    val sounds: Boolean = true,
    /** none | a preset colour key ([ChatLook.WALLPAPERS]) | photo. */
    val wallpaper: String = ChatLook.WALLPAPER_NONE,
    /** small | medium | large | xlarge. */
    val fontSize: String = ChatLook.FONT_MEDIUM,
    /** A key of [ChatLook.BUBBLES]. */
    val bubbleColor: String = ChatLook.BUBBLE_DEFAULT,
    val enterSends: Boolean = false,
    /** all | photos | none. */
    val autoWifi: String = ChatAutoDownload.ALL,
    val autoMobile: String = ChatAutoDownload.PHOTOS,
    /** standard | saver | original. */
    val sendQuality: String = ChatQuality.STANDARD,
    val appLock: Boolean = false,
    val secure: Boolean = false,
    /** Ids of home actions hidden from the attach sheet. */
    val hiddenActions: Set<String> = emptySet(),
)

@Singleton
class ChatDeviceSettings @Inject constructor(@ApplicationContext private val context: Context) {
    private val store = context.chatDeviceStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashReports.coroutineHandler)

    val config: StateFlow<ChatDeviceConfig> = store.data
        .catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }
        .map { p ->
            ChatDeviceConfig(
                sounds = p[SOUNDS] ?: true,
                wallpaper = p[WALLPAPER] ?: ChatLook.WALLPAPER_NONE,
                fontSize = p[FONT]?.takeIf { it in ChatLook.FONTS } ?: ChatLook.FONT_MEDIUM,
                bubbleColor = p[BUBBLE]?.takeIf { it in ChatLook.BUBBLES } ?: ChatLook.BUBBLE_DEFAULT,
                enterSends = p[ENTER] ?: false,
                autoWifi = p[AUTO_WIFI]?.takeIf { it in ChatAutoDownload.CHOICES } ?: ChatAutoDownload.ALL,
                autoMobile = p[AUTO_MOBILE]?.takeIf { it in ChatAutoDownload.CHOICES } ?: ChatAutoDownload.PHOTOS,
                sendQuality = p[QUALITY]?.takeIf { it in ChatQuality.CHOICES } ?: ChatQuality.STANDARD,
                appLock = p[APP_LOCK] ?: false,
                secure = p[SECURE] ?: false,
                hiddenActions = p[HIDDEN_ACTIONS].orEmpty(),
            )
        }
        .stateIn(scope, SharingStarted.Eagerly, ChatDeviceConfig())

    suspend fun update(change: (ChatDeviceConfig) -> ChatDeviceConfig) {
        val next = change(config.value)
        store.edit { p ->
            p[SOUNDS] = next.sounds
            p[WALLPAPER] = next.wallpaper
            p[FONT] = next.fontSize
            p[BUBBLE] = next.bubbleColor
            p[ENTER] = next.enterSends
            p[AUTO_WIFI] = next.autoWifi
            p[AUTO_MOBILE] = next.autoMobile
            p[QUALITY] = next.sendQuality
            p[APP_LOCK] = next.appLock
            p[SECURE] = next.secure
            p[HIDDEN_ACTIONS] = next.hiddenActions
        }
    }

    /** The own wallpaper photo (in the app's private files). */
    val wallpaperFile: File get() = File(context.filesDir, "chat-wallpaper.jpg")

    /** Copies (scaled) [uri] as the wallpaper photo; true when it worked. */
    suspend fun setWallpaperPhoto(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val ok = runCatching {
            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
            var sample = 1
            while (maxOf(opts.outWidth, opts.outHeight) / sample > 2400) sample *= 2
            val bmp = context.contentResolver.openInputStream(uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return@runCatching false
            wallpaperFile.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }
            bmp.recycle()
            true
        }.getOrDefault(false)
        if (ok) update { it.copy(wallpaper = ChatLook.WALLPAPER_PHOTO) }
        ok
    }

    /** Whether the device is on an unmetered network (WLAN) right now. */
    fun unmetered(): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return@runCatching false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }.getOrDefault(false)

    private companion object {
        val SOUNDS = booleanPreferencesKey("sounds")
        val WALLPAPER = stringPreferencesKey("wallpaper")
        val FONT = stringPreferencesKey("font_size")
        val BUBBLE = stringPreferencesKey("bubble_color")
        val ENTER = booleanPreferencesKey("enter_sends")
        val AUTO_WIFI = stringPreferencesKey("auto_wifi")
        val AUTO_MOBILE = stringPreferencesKey("auto_mobile")
        val QUALITY = stringPreferencesKey("send_quality")
        val APP_LOCK = booleanPreferencesKey("app_lock")
        val SECURE = booleanPreferencesKey("secure")
        val HIDDEN_ACTIONS = stringSetPreferencesKey("hidden_actions")
    }
}

/** Looks of the conversation (pure). */
object ChatLook {
    const val WALLPAPER_NONE = "none"
    const val WALLPAPER_PHOTO = "photo"
    /** Preset wallpapers: key → ARGB (dark, light). */
    val WALLPAPERS: Map<String, Pair<Long, Long>> = linkedMapOf(
        "night" to (0xFF0B141CL to 0xFFE9EEF2L),
        "teal" to (0xFF0D2420L to 0xFFDDF2EBL),
        "blue" to (0xFF0F1B2DL to 0xFFDCE8F7L),
        "plum" to (0xFF1E1426L to 0xFFEEE3F4L),
        "sand" to (0xFF221C12L to 0xFFF4ECDDL),
    )

    const val FONT_SMALL = "small"
    const val FONT_MEDIUM = "medium"
    const val FONT_LARGE = "large"
    const val FONT_XLARGE = "xlarge"
    val FONTS = listOf(FONT_SMALL, FONT_MEDIUM, FONT_LARGE, FONT_XLARGE)

    fun fontScale(key: String): Float = when (key) {
        FONT_SMALL -> 0.9f
        FONT_LARGE -> 1.15f
        FONT_XLARGE -> 1.3f
        else -> 1f
    }

    const val BUBBLE_DEFAULT = "accent"
    /** Own bubble colours: key → ARGB (dark, light). */
    val BUBBLE_COLORS: Map<String, Pair<Long, Long>> = linkedMapOf(
        BUBBLE_DEFAULT to (0x2E3DDC97L to 0x1F0B8A5CL),
        "blue" to (0x3379B8FFL to 0x261A56C4L),
        "purple" to (0x33B79CFFL to 0x266A3FD6L),
        "orange" to (0x33F5B544L to 0x33D68A00L),
        "pink" to (0x33F472B6L to 0x26DB2777L),
        "gray" to (0xFF2A323DL to 0xFFE2E7EDL),
    )
    val BUBBLES: Set<String> get() = BUBBLE_COLORS.keys
}

/** Automatic downloads per network (pure, unit-tested). */
object ChatAutoDownload {
    const val ALL = "all"
    const val PHOTOS = "photos"
    const val NONE = "none"
    val CHOICES = listOf(ALL, PHOTOS, NONE)

    /** May a picture's full file load by itself on this network? */
    fun photos(config: ChatDeviceConfig, unmetered: Boolean): Boolean =
        (if (unmetered) config.autoWifi else config.autoMobile) != NONE

    /** May videos, voice messages and files load ahead by themselves? */
    fun everything(config: ChatDeviceConfig, unmetered: Boolean): Boolean =
        (if (unmetered) config.autoWifi else config.autoMobile) == ALL
}

/** Picture quality when sending (pure, unit-tested). */
object ChatQuality {
    const val STANDARD = "standard"
    const val SAVER = "saver"
    const val ORIGINAL = "original"
    val CHOICES = listOf(STANDARD, SAVER, ORIGINAL)

    /** Long side of a sent picture; null = sent unchanged as a file. */
    fun maxSide(choice: String, serverMax: Int): Int? = when (choice) {
        SAVER -> minOf(1280, serverMax)
        ORIGINAL -> null
        else -> serverMax
    }
}
