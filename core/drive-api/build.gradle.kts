plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.gatecontrol.android.core.driveapi"
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

// Drive (gatecontrol-drive): the app API, its models and JSON, the rules of lists, photos and the media viewer, the
// preview pictures (with the home cache) — shared by the phone app and GateControl TV. The screens, the transfers,
// offline copies, the vault and backups stay in the phone app.
dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:data"))
    implementation(project(":core:network"))

    implementation(libs.core.ktx)
    implementation(libs.annotation)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.okhttp)
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.timber)
    // The viewer's player: the error codes (DriveMediaLogic.trouble) and the stream's data source (OkHttp of the API)
    implementation(libs.media3.datasource.okhttp)

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
