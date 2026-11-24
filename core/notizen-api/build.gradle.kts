plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.gatecontrol.android.core.notizen"
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

// „Notizen & Aufgaben“ (gatecontrol-notizen): the app API's routes through the client plugin bridge, its answers and
// models, the repeat rules and the note's Markdown reader — shared by the phone app and GateControl TV. Places with
// geofences, the Tresor session, the import, the offline copy and outbox and all screens stay in the phone app (:app).
dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:network"))
    // Tresor notebooks use Drive's vault routes and their answers (DVaultKeys, DriveJson)
    api(project(":core:drive-api"))
    // The shopping lists' answers (GET shopping, POST shopping/ops) are Familie's
    api(project(":core:familie-api"))

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
}

tasks.withType<Test> {
    useJUnitPlatform()
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
