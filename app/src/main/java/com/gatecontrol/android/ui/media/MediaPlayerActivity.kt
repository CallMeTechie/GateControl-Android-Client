package com.gatecontrol.android.ui.media

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.os.PowerManager
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.media.MediaPlaybackService
import com.gatecontrol.android.media.MediaPlayerLogic
import com.gatecontrol.android.media.MediaPlayerSession
import com.gatecontrol.android.ui.theme.GateControlTheme
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import java.util.Locale
import javax.inject.Inject

/**
 * The window of the phone's player (stage 4c): upright the picture on top and the infos below, turned in full screen
 * (the system bars hidden). The player lives in [MediaPlayerSession], so a turn, a fold or picture-in-picture does
 * not stop it (rotation does not even make the window anew). Leaving the app while it plays goes to
 * picture-in-picture; with the screen locked the sound goes on ([MediaPlaybackService]); left another way it pauses.
 * Back or closing the picture-in-picture ends the playback.
 */
@AndroidEntryPoint
class MediaPlayerActivity : ComponentActivity() {

    @Inject lateinit var phoneCast: com.gatecontrol.android.media.PhoneCast
    @Inject lateinit var session: MediaPlayerSession
    @Inject lateinit var settingsRepository: SettingsRepository

    private val pip = mutableStateOf(false)

    /** The landscape forced by "Vollbild" (not by turning the phone). */
    private var forcedLandscape = false

    private var aspect = VideoSize.UNKNOWN
    private var playing = false

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            playing = isPlaying
            if (isPlaying) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            updatePip()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            aspect = videoSize
            updatePip()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (session.ui.value == null) {
            finish()
            return
        }
        startPlaybackService()
        setContent {
            val theme by settingsRepository.getTheme().collectAsStateWithLifecycle(initialValue = "system")
            val sysLang = rememberSaveable { Locale.getDefault().language }
            val locale by settingsRepository.getLocale().collectAsStateWithLifecycle(initialValue = if (sysLang == "de") "de" else "en")
            LaunchedEffect(locale) {
                val config = Configuration(resources.configuration).apply { setLocale(Locale.forLanguageTag(locale)) }
                @Suppress("DEPRECATION")
                resources.updateConfiguration(config, resources.displayMetrics)
            }
            val dark = when (theme) {
                "dark" -> true
                "light" -> false
                else -> isSystemInDarkTheme()
            }
            val ui by session.ui.collectAsStateWithLifecycle()
            val player by session.player.collectAsStateWithLifecycle()
            LaunchedEffect(ui == null) { if (ui == null) finish() }
            DisposableEffect(player) {
                val p = player
                p?.addListener(listener)
                playing = p?.isPlaying == true
                aspect = p?.videoSize ?: VideoSize.UNKNOWN
                updatePip()
                onDispose { p?.removeListener(listener) }
            }
            val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
            LaunchedEffect(landscape, pip.value) { systemBars(show = !landscape && !pip.value) }
            GateControlTheme(darkTheme = dark) {
                val current = ui ?: return@GateControlTheme
                // "Abspielen auf …": paused here, the other device starts at this place, the phone becomes its remote
                var casting by remember { mutableStateOf<Long?>(null) }
                // only when the plugin allows it and an own TV / PC is there
                val castable by phoneCast.available.collectAsStateWithLifecycle()
                LaunchedEffect(Unit) { phoneCast.refreshTargets() }
                casting?.let { at ->
                    MediaCastSheet(
                        current.title,
                        positionMs = at,
                        audio = current.choices?.audioIndex,
                        subtitle = current.choices?.subtitleIndex,
                        onDismiss = { casting = null },
                        onStarted = {
                            casting = null
                            session.close()
                        },
                    )
                }
                MediaPlayerScreen(
                    ui = current,
                    player = player,
                    landscape = landscape,
                    pip = pip.value,
                    mobileCap = session.mobileCap,
                    actions = MediaPlayerActions(
                        onClose = ::finish,
                        onPip = ::enterPip,
                        onFullscreen = ::fullscreen,
                        onAudio = session::chooseAudio,
                        onSubtitle = session::chooseSubtitle,
                        onQuality = session::chooseQuality,
                        onTrack = session::chooseTrack,
                        onSubtitlesOff = session::subtitlesOff,
                        onNext = session::next,
                        onPlayAt = session::playAt,
                        onCancelCountdown = session::cancelCountdown,
                        onRetry = session::retry,
                        onVpnConsent = session::vpnConsent,
                        onMobileHintRead = session::mobileHintRead,
                        onCast = {
                            session.pause()
                            casting = player?.currentPosition?.coerceAtLeast(0) ?: 0
                        }.takeIf { !pip.value && castable && phoneCast.canCast(current.title) },
                    ),
                )
            }
        }
    }

    /** The media session (lock screen, headphones, the sound with the screen locked). Started while visible. */
    private fun startPlaybackService() {
        try {
            startService(Intent(this, MediaPlaybackService::class.java))
        } catch (e: IllegalStateException) {
            Timber.w("Medien: playback service not started (%s)", e.javaClass.simpleName)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // another title from the notification or the app: the session has it already
        startPlaybackService()
    }

    /** "Vollbild": turned to landscape; back: as the phone is held (upright stays upright until the player closes). */
    private fun fullscreen(on: Boolean) {
        if (on) {
            forcedLandscape = true
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            requestedOrientation = if (forcedLandscape) ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            forcedLandscape = false
        }
    }

    private fun systemBars(show: Boolean) {
        val c = WindowCompat.getInsetsController(window, window.decorView)
        if (show) {
            c.show(WindowInsetsCompat.Type.systemBars())
        } else {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun pipParams(): PictureInPictureParams {
        val (w, h) = MediaPlayerLogic.pipRatio(aspect.width, aspect.height)
        return PictureInPictureParams.Builder()
            .setAspectRatio(Rational(w, h))
            // leaving the app while it plays goes to picture-in-picture by itself (question 29 a)
            .setAutoEnterEnabled(playing)
            .setSeamlessResizeEnabled(true)
            .build()
    }

    private fun updatePip() {
        if (isFinishing || isDestroyed) return
        runCatching { setPictureInPictureParams(pipParams()) }
    }

    private fun enterPip() {
        runCatching { enterPictureInPictureMode(pipParams()) }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pip.value = isInPictureInPictureMode
        // the small window closed (not back to full size): the playback ends
        if (!isInPictureInPictureMode && !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            session.pause()
            finish()
        }
    }

    override fun onStop() {
        super.onStop()
        if (isChangingConfigurations || isInPictureInPictureMode || isFinishing) return
        // the screen locked: the sound goes on (media session); the app left another way: paused
        val interactive = getSystemService(PowerManager::class.java)?.isInteractive != false
        if (interactive) session.pause()
    }

    override fun onDestroy() {
        session.player.value?.removeListener(listener)
        if (isFinishing && !isChangingConfigurations) session.close()
        super.onDestroy()
    }
}
