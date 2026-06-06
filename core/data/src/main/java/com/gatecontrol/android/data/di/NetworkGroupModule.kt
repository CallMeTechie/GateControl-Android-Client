// FILE: core/data/src/main/java/com/gatecontrol/android/data/di/NetworkGroupModule.kt
//
// Hilt DI — provides Room database, DAO, and the repository.
// Placed in core:data alongside the existing DataModule.

package com.gatecontrol.android.data.di

import android.content.Context
import androidx.room.Room
import com.gatecontrol.android.data.NetworkGroupRepository
import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.data.db.NetworkGroupDao
import com.gatecontrol.android.data.db.NetworkGroupDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkGroupModule {

    @Provides
    @Singleton
    fun provideNetworkGroupDatabase(
        @ApplicationContext context: Context,
    ): NetworkGroupDatabase =
        Room.databaseBuilder(
            context,
            NetworkGroupDatabase::class.java,
            "gatecontrol_network_groups.db",
        )
            .fallbackToDestructiveMigration()   // v1 only; add proper migrations for v2+
            .build()

    @Provides
    @Singleton
    fun provideNetworkGroupDao(db: NetworkGroupDatabase): NetworkGroupDao =
        db.networkGroupDao()

    @Provides
    @Singleton
    fun provideNetworkGroupRepository(
        dao: NetworkGroupDao,
        settingsRepository: SettingsRepository,
        @ApplicationContext context: Context,
    ): NetworkGroupRepository =
        NetworkGroupRepository(dao, settingsRepository, context)
}


// ── libs.versions.toml additions (paste into the file) ───────────────────────
//
// [versions]
// room = "2.6.1"
//
// [libraries]
// room-runtime  = { group = "androidx.room", name = "room-runtime",  version.ref = "room" }
// room-ktx      = { group = "androidx.room", name = "room-ktx",      version.ref = "room" }
// room-compiler = { group = "androidx.room", name = "room-compiler",  version.ref = "room" }
//
// ── core/data/build.gradle.kts additions ─────────────────────────────────────
//
// plugins {
//     ...
//     id("com.google.devtools.ksp") version "1.9.25-1.0.20"   // matches kotlin 1.9.25
// }
//
// dependencies {
//     ...
//     implementation(libs.room.runtime)
//     implementation(libs.room.ktx)
//     ksp(libs.room.compiler)           // use ksp, not kapt, for Room 2.6+
// }
