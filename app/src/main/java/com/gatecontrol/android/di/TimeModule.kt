package com.gatecontrol.android.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class NowMillis

@Module
@InstallIn(SingletonComponent::class)
object TimeModule {
    /** Wall-clock source, injected so ViewModels can be tested with a fake clock. */
    @Provides
    @NowMillis
    fun provideNowMillis(): () -> Long = { System.currentTimeMillis() }
}
