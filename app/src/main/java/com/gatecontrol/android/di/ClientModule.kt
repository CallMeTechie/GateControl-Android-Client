package com.gatecontrol.android.di

import com.gatecontrol.android.network.ClientPlatform
import com.gatecontrol.android.update.ApkUpdateChannel
import com.gatecontrol.android.update.PlayUpdater
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** What the shared core modules need to know about this app: the phone app (GateControl TV binds its own). */
@Module
@InstallIn(SingletonComponent::class)
object ClientModule {
    /** `X-Client-Platform`, enrollment and update check: the phone app. */
    @Provides
    fun provideClientPlatform(): ClientPlatform = ClientPlatform.ANDROID

    /** The GitHub build loads its updates itself, the Play build only through Play. */
    @Provides
    fun provideApkUpdateChannel(): ApkUpdateChannel = ApkUpdateChannel { !PlayUpdater.enabled }
}
