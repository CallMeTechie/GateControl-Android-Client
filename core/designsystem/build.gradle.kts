plugins {
    alias(libs.plugins.android.library)
}

// Design tokens shared by the phone app (:app, Material3) and GateControl TV (:tv, Compose for TV):
// the colors of the 2026 redesign and the three typefaces. Each app builds its own theme on top.
android {
    namespace = "com.gatecontrol.android.core.designsystem"
    // 37.1: the Compose 1.13 AARs (BOM 2026.11) require compiling against API 37.1 or later.
    compileSdk {
        version = release(37) { minorApiLevel = 1 }
    }

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    api(libs.compose.ui.graphics)
    api(libs.compose.ui.text)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
