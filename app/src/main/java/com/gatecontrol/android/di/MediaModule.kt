package com.gatecontrol.android.di

import android.content.Context
import com.gatecontrol.android.media.MediaDevice
import com.gatecontrol.android.media.MediaRowsCache
import com.gatecontrol.android.media.MediaPlayback
import com.gatecontrol.android.media.MediaPlayerSession
import com.gatecontrol.android.media.MediaVpn
import com.gatecontrol.android.media.PhoneMediaDevice
import com.gatecontrol.android.media.PhoneMediaVpn
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/** Medien (stage 4): the phone for the shared hub, and where playing starts (the player of stage 4c). */
@Module
@InstallIn(SingletonComponent::class)
abstract class MediaModule {
    @Binds
    abstract fun bindMediaDevice(impl: PhoneMediaDevice): MediaDevice

    @Binds
    abstract fun bindMediaPlayback(impl: MediaPlayerSession): MediaPlayback

    /** Away media go only through the VPN; the app starts it on opening or playing them (Netz-Regel). */
    @Binds
    abstract fun bindMediaVpn(impl: PhoneMediaVpn): MediaVpn

    companion object {
        /** The overview's rows last loaded, per source (the app's cache folder: shown at once on opening Medien). */
        @Provides
        @Singleton
        fun provideMediaRowsCache(@ApplicationContext context: Context): MediaRowsCache {
            val files = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "media-rows").apply { isDaemon = true } }
            return MediaRowsCache(File(context.cacheDir, "media-rows"), writer = { files.execute(it) })
        }
    }
}
