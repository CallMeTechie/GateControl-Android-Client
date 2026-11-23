package com.gatecontrol.android.di

import android.content.Context
import com.gatecontrol.android.network.ClientPlatform
import com.gatecontrol.android.quickactions.QuickActionSurfaces
import com.gatecontrol.android.quickactions.QuickActionSurfacesHook
import com.gatecontrol.android.update.ApkUpdateChannel
import com.gatecontrol.android.update.PlayUpdater
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
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

    /** Pinned quick actions also feed the app shortcuts, the Quick Settings tiles and the home-screen widget. */
    @Provides
    fun provideQuickActionSurfaces(@ApplicationContext context: Context): QuickActionSurfacesHook =
        QuickActionSurfacesHook { pins -> QuickActionSurfaces.refresh(context, pins) }
}
