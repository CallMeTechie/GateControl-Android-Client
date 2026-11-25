package com.gatecontrol.android.media

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.gatecontrol.android.ui.media.MediaPlayerActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The media session of Medien (question 29 a): the player of [MediaPlayerSession] on the lock screen and in the
 * notification, the buttons of headphones and Bluetooth, the sound going on with the screen locked (a foreground
 * service of type mediaPlayback while it plays; Media3 starts and stops that itself). Only this app and the system
 * (lock screen, notification, Bluetooth) may control it. Goes when the playback is closed.
 */
@AndroidEntryPoint
@OptIn(UnstableApi::class)
class MediaPlaybackService : MediaSessionService() {

    @Inject lateinit var playback: MediaPlayerSession

    private val scope = MainScope()
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            playback.player.collect { p ->
                if (p == null) {
                    end()
                    return@collect
                }
                val shown = playback.forSession(p)
                val s = session
                if (s == null) {
                    session = MediaSession.Builder(this@MediaPlaybackService, shown)
                        .setSessionActivity(openPlayer())
                        .setCallback(callback)
                        .build()
                        .also { addSession(it) }
                } else {
                    s.player = shown
                }
            }
        }
    }

    /** Only this app (its own notification) and trusted controllers of the system. */
    private val callback = object : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
            if (controller.packageName == packageName || controller.isTrusted) {
                super.onConnect(session, controller)
            } else {
                MediaSession.ConnectionResult.reject()
            }
    }

    /** A tap on the notification or the lock screen: back to the player. */
    private fun openPlayer(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MediaPlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Swiped out of the recent apps: a paused playback ends, a playing one goes on (lock screen, notification). */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) {
            playback.close()
            stopSelf()
        }
    }

    private fun end() {
        session?.let {
            removeSession(it)
            it.release()
        }
        session = null
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        session?.let {
            removeSession(it)
            it.release()
        }
        session = null
        super.onDestroy()
    }
}
