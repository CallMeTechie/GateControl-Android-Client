plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.gatecontrol.android.core.media.player"
    // 37.1: the Compose 1.13 AARs (BOM 2026.11) require compiling against API 37.1 or later.
    compileSdk {
        version = release(37) { minorApiLevel = 1 }
    }

    defaultConfig {
        minSdk = 28

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// The Media3 parts of the media player (stage 4a), shared by GateControl TV and, later, the phone app: a share's
// files as a data source, the sound tracks and subtitles, the progress reports, the player and its Jellyfin header,
// the HDMI sink's profile. No UI here: each app draws its own player.
dependencies {
    api(project(":core:media"))

    implementation(libs.core.ktx)
    implementation(libs.okhttp)
    api(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    api(libs.media3.datasource.okhttp)

    testImplementation(libs.junit5.api)
    testRuntimeOnly(libs.junit5.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.mockk)
}

tasks.withType<Test> {
    useJUnitPlatform()
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
