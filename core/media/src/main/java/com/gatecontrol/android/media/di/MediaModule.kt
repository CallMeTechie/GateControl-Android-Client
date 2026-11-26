package com.gatecontrol.android.media.di

import com.gatecontrol.android.media.server.MediaCastApi
import com.gatecontrol.android.media.server.MediaServerApi
import com.gatecontrol.android.media.server.MediaServerHttpApi
import com.gatecontrol.android.media.server.MediaServerSources
import com.gatecontrol.android.media.server.MediaServerSwitch
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** The plugin „Medien“ through the server's plugin bridge. The app binds [com.gatecontrol.android.media.MediaVpn] itself. */
@Module
@InstallIn(SingletonComponent::class)
abstract class MediaModule {
    @Binds
    abstract fun bindServerApi(impl: MediaServerHttpApi): MediaServerApi

    /** „Auf TV/PC abspielen“: the plugin's cast routes. */
    @Binds
    abstract fun bindCastApi(impl: MediaServerHttpApi): MediaCastApi

    /** A new server or token in the app: this device leaves Jellyfin first, the old server's sources go. */
    @Binds
    abstract fun bindServerSwitch(impl: MediaServerSources): MediaServerSwitch
}
