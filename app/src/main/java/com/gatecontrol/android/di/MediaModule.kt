package com.gatecontrol.android.di

import com.gatecontrol.android.media.MediaDevice
import com.gatecontrol.android.media.MediaPlayback
import com.gatecontrol.android.media.MediaPlayerSession
import com.gatecontrol.android.media.PhoneMediaDevice
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
}
