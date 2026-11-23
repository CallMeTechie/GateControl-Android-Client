plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.gatecontrol.android.core.media"
    compileSdk = 37

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

// Media of the own NAS (stage 2 of GateControl TV, later the phone and Windows apps): the sources (Jellyfin, SMB and
// WebDAV shares behind the same [com.gatecontrol.android.media.MediaLibrary]), their stored access (encrypted),
// the server search in the home network, the device profile and the choice of quality by bandwidth. No UI here.
dependencies {
    implementation(project(":core:data"))

    implementation(libs.core.ktx)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.okhttp)
    // Gson's tree (JsonObject) only: the JSON is read and written by hand, nothing for R8 to keep
    implementation(libs.retrofit.gson)
    implementation(libs.coroutines.core)
    implementation(libs.timber)
    // SMB2/3 shares (pure Java, also on the 32-bit Fire TV Stick). Its NTLM and SMB3 signing/encryption run on
    // Bouncy Castle's own classes (Android has no MD4 in its JCE), pinned to the version the vault uses.
    implementation(libs.smbj)
    implementation(libs.bouncycastle.prov)

    testImplementation(libs.junit5.api)
    testRuntimeOnly(libs.junit5.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.mockwebserver)
}

tasks.withType<Test> {
    useJUnitPlatform()
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
