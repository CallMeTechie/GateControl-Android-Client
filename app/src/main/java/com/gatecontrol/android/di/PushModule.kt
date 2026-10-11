package com.gatecontrol.android.di

import com.gatecontrol.android.notify.PushPresenter
import com.gatecontrol.android.notify.ServerControlSink
import com.gatecontrol.android.push.PushNotifier
import com.gatecontrol.android.service.ServerControlHandler
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class PushModule {
    @Binds
    abstract fun bindPresenter(impl: PushNotifier): PushPresenter

    /** Control events and `hello.state` of the push stream run the heartbeat's code paths. */
    @Binds
    abstract fun bindServerControl(impl: ServerControlHandler): ServerControlSink
}
