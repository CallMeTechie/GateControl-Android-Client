package com.gatecontrol.android.tv.di

import com.gatecontrol.android.network.ClientPlatform
import com.gatecontrol.android.update.ApkUpdateChannel
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** What the shared core modules need to know about GateControl TV. */
@Module
@InstallIn(SingletonComponent::class)
object TvModule {
    /**
     * `X-Client-Platform: android-tv` on every request, `platform` of pairing and enrollment, `client` and `platform`
     * of the update check: the server shows the TV as a TV and hands it the TV APK.
     */
    @Provides
    fun provideClientPlatform(): ClientPlatform = ClientPlatform.ANDROID_TV

    /** The TV is sideloaded (no store): it always loads and installs its updates itself. */
    @Provides
    fun provideApkUpdateChannel(): ApkUpdateChannel = ApkUpdateChannel { true }
}
