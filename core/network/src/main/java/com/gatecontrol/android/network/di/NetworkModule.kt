package com.gatecontrol.android.network.di

import android.content.Context
import com.gatecontrol.android.common.MachineId
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.network.ApiClientProvider
import com.gatecontrol.android.network.AuthInterceptor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideAuthInterceptor(
        setupRepository: SetupRepository,
        @ApplicationContext context: Context
    ): AuthInterceptor = AuthInterceptor(
        tokenProvider = { setupRepository.getApiToken() },
        versionProvider = {
            try {
                context.packageManager
                    .getPackageInfo(context.packageName, 0)
                    .versionName ?: "1.0.0"
            } catch (_: Exception) {
                "1.0.0"
            }
        },
        platformProvider = { "android" },
        fingerprintProvider = { MachineId.getFingerprint(context) }
    )

    @Provides
    @Singleton
    fun provideApiClientProvider(authInterceptor: AuthInterceptor): ApiClientProvider =
        ApiClientProvider(authInterceptor)
}
