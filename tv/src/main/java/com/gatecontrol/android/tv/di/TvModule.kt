package com.gatecontrol.android.tv.di

import com.gatecontrol.android.network.ClientPlatform
import com.gatecontrol.android.notify.PluginEventSink
import com.gatecontrol.android.notify.PushPresenter
import com.gatecontrol.android.notify.ServerControlSink
import com.gatecontrol.android.quickactions.QuickActionSurfacesHook
import com.gatecontrol.android.tv.chat.TvChatEvents
import com.gatecontrol.android.tv.push.TvPluginEventSink
import com.gatecontrol.android.tv.push.TvPluginListener
import com.gatecontrol.android.tv.push.TvPushPresenter
import com.gatecontrol.android.tv.tunnel.TvSupervisor
import com.gatecontrol.android.update.ApkUpdateChannel
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dagger.multibindings.Multibinds

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

    /** The plugins' realtime events: handed to the features that bound a [TvPluginListener] (none is fine). */
    @Binds
    abstract fun bindPluginEvents(impl: TvPluginEventSink): PluginEventSink

    @Multibinds
    abstract fun pluginListeners(): Set<TvPluginListener>

    /** Messenger (stage 3e): message.new, message.update and what changes a chat. */
    @Binds
    @IntoSet
    abstract fun bindChatEvents(impl: TvChatEvents): TvPluginListener
}
