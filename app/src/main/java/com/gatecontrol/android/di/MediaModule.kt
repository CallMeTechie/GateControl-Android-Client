package com.gatecontrol.android.di

import com.gatecontrol.android.media.MediaDevice
import com.gatecontrol.android.media.MediaPlayback
import com.gatecontrol.android.media.MediaPlayerSession
import com.gatecontrol.android.media.MediaVpn
import com.gatecontrol.android.media.PhoneMediaDevice
import com.gatecontrol.android.media.PhoneMediaVpn
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

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
}
