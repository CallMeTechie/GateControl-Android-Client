package com.gatecontrol.android.tv.di

import com.gatecontrol.android.network.ClientPlatform
import com.gatecontrol.android.notify.PluginEventSink
import com.gatecontrol.android.notify.PushPresenter
import com.gatecontrol.android.notify.ServerControlSink
import com.gatecontrol.android.quickactions.QuickActionSurfacesHook
import com.gatecontrol.android.tv.push.TvPushPresenter
import com.gatecontrol.android.tv.tunnel.TvSupervisor
import com.gatecontrol.android.update.ApkUpdateChannel
import dagger.Binds
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

    /** The plugins' realtime events (chats, GateDrop, Drive) have no TV feature yet. */
    @Provides
    fun providePluginEvents(): PluginEventSink = PluginEventSink.NONE

    /** No app shortcuts, Quick Settings tiles or home-screen widgets on a TV: the pins are only the start page. */
    @Provides
    fun provideQuickActionSurfaces(): QuickActionSurfacesHook = QuickActionSurfacesHook.NONE
}

@Module
@InstallIn(SingletonComponent::class)
abstract class TvBindings {
    /** Messages of the push stream: system notification plus the TV's own overlay. */
    @Binds
    abstract fun bindPresenter(impl: TvPushPresenter): PushPresenter

    /** `control` events and `hello.state` of the push stream: peer off, new config. */
    @Binds
    abstract fun bindServerControl(impl: TvSupervisor): ServerControlSink
}
