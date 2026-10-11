package com.gatecontrol.android.notify.di

import android.content.Context
import com.gatecontrol.android.notify.AndroidPushPlatform
import com.gatecontrol.android.notify.DefaultPushEndpoint
import com.gatecontrol.android.notify.InboxCache
import com.gatecontrol.android.notify.PushEndpoint
import com.gatecontrol.android.notify.PushPlatform
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class NotifyModule {

    @Binds
    abstract fun bindEndpoint(impl: DefaultPushEndpoint): PushEndpoint

    @Binds
    abstract fun bindPlatform(impl: AndroidPushPlatform): PushPlatform

    companion object {
        @Provides
        @Singleton
        fun provideInboxCache(@ApplicationContext context: Context): InboxCache =
            InboxCache(File(File(context.filesDir, "push"), "inbox.json"))
    }
}
