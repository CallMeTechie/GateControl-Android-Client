plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.gatecontrol.android.core.familie"
    compileSdk = 37

    defaultConfig {
        minSdk = 28

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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

// Familie and Alltag of the home (gatecontrol-familie, gatecontrol-alltag): the app API's calls through the client
// plugin bridge, dates, the Alltag calendar with the waste collection, the merged agenda and the shopping lists with
// their offline copy and outbox — shared by the phone app and GateControl TV. School, money, rides, places and
// locating stay in the phone app (:app), so do all screens.
dependencies {
    // LoginOwner: whose offline copy a file is
    implementation(project(":core:common"))
    implementation(project(":core:data"))
    implementation(project(":core:network"))

    implementation(libs.core.ktx)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.okhttp)
    // Response of the plugin bridge (ApiClient) is in the API of the calls
    api(libs.retrofit)
    // JsonObject of the plugin bridge is in the API of the calls and parsers
    api(libs.retrofit.gson)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.timber)

    testImplementation(libs.junit5.api)
    testRuntimeOnly(libs.junit5.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
}

tasks.withType<Test> {
    useJUnitPlatform()
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
