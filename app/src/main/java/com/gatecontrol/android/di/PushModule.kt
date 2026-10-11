package com.gatecontrol.android.di

import com.gatecontrol.android.notify.PushPresenter
import com.gatecontrol.android.push.PushNotifier
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class PushModule {
    @Binds
    abstract fun bindPresenter(impl: PushNotifier): PushPresenter
}
