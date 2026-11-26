// Termux terminal-emulator + terminal-view (Apache-2.0) from the fixed release termux-app v0.118.3,
// as one library of their own: the VT emulation, the view with selection and pinch zoom. Without the
// JNI part (no local process): the SSH terminal of „Fernzugriff“ feeds the bytes of the remote shell
// (TerminalSession.java is changed for that, see its header). Licence and notice ship in the app:
// src/main/assets/termux-terminal/ (LICENSE.txt, NOTICE.txt); NOTICE of the app.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.termux.view"
    // 37.1: the Compose 1.13 AARs (BOM 2026.11) require compiling against API 37.1 or later.
    compileSdk {
        version = release(37) { minorApiLevel = 1 }
    }

    defaultConfig {
        minSdk = 31
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
}

dependencies {
    implementation(libs.annotation)
}
