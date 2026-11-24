plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.gatecontrol.android.core.chat"
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

// Messenger (gatecontrol-messenger): the app API's routes through the client plugin bridge (ChatApi, always with
// `?bridged=1`), its answers and models (ChatJson, ChatJson14, ChatModels), the realtime events' parser (ChatEvents) and
// the rules of chats over a bridge (ChatBridgeChat, ChatBridges) — shared by the phone app and GateControl TV. The
// phone's Room store, outbox, push and all screens stay in :app.
dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:data"))
    // ApiClient, ApiClientProvider and the media scan (MediaScan, MediaScanJson) are in the API of the calls and models
    api(project(":core:network"))
    // The image path rule of plugin cards (PushEventParser.IMAGE_PATH_RE)
    implementation(project(":core:notify"))
    // QR code of a bridge login (ChatBridges.qr)
    implementation(libs.zxing.core)

    implementation(libs.core.ktx)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.okhttp)
    // Response of the plugin bridge (ApiClient) is in the API of the calls
    api(libs.retrofit)
    // JsonObject is in the API of the calls and parsers
    api(libs.retrofit.gson)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.timber)

    testImplementation(libs.junit5.api)
    testRuntimeOnly(libs.junit5.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.mockk)
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
