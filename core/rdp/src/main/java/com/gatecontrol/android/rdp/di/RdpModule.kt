package com.gatecontrol.android.rdp.di

import android.content.Context
import com.gatecontrol.android.rdp.RdpCredentialHandler
import com.gatecontrol.android.rdp.RdpEmbeddedClient
import com.gatecontrol.android.rdp.RdpExternalClient
import com.gatecontrol.android.rdp.RdpManager
import com.gatecontrol.android.rdp.RdpMonitor
import com.gatecontrol.android.rdp.WolClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RdpModule {

    @Provides
    @Singleton
    fun provideRdpCredentialHandler(): RdpCredentialHandler = RdpCredentialHandler()

    @Provides
    @Singleton
    fun provideRdpExternalClient(@ApplicationContext context: Context): RdpExternalClient =
        RdpExternalClient(context)

    @Provides
    @Singleton
    fun provideRdpMonitor(): RdpMonitor = RdpMonitor()

    @Provides
    @Singleton
    fun provideWolClient(): WolClient = WolClient()

    @Provides
    @Singleton
    fun provideRdpEmbeddedClient(): RdpEmbeddedClient = RdpEmbeddedClient()

    @Provides
    @Singleton
    fun provideRdpManager(
        @ApplicationContext context: Context,
        credentialHandler: RdpCredentialHandler,
        externalClient: RdpExternalClient,
        embeddedClient: RdpEmbeddedClient,
        monitor: RdpMonitor,
        wolClient: WolClient
    ): RdpManager = RdpManager(
        context = context,
        credentialHandler = credentialHandler,
        externalClient = externalClient,
        embeddedClient = embeddedClient,
        monitor = monitor,
        wolClient = wolClient
    )
}
