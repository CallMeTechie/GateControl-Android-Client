plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

// GateControl TV: the app for Fire TV (Fire TV Stick 4K Max: Fire OS 7 = Android 9, a 32-bit system) and
// Android TV. Its own applicationId, the same signing key and the same version as the phone app.
//
// Version: read from the phone app's build file, never set here. release.yml bumps versionName/versionCode in
// app/build.gradle.kts before it builds, so both APKs of a release carry the same version.
val phoneBuildFile = providers.fileContents(rootProject.layout.projectDirectory.file("app/build.gradle.kts")).asText
val phoneVersionName: String = phoneBuildFile.map {
    Regex("""versionName = "([0-9]+\.[0-9]+\.[0-9]+)"""").find(it)?.groupValues?.get(1)
        ?: throw GradleException("versionName not found in app/build.gradle.kts")
}.get()
val phoneVersionCode: Int = phoneBuildFile.map {
    Regex("""versionCode = ([0-9]+)""").find(it)?.groupValues?.get(1)?.toInt()
        ?: throw GradleException("versionCode not found in app/build.gradle.kts")
}.get()

android {
    namespace = "com.gatecontrol.android.tv"
    compileSdk = 37

    defaultConfig {
        // The phone app's applicationId + ".tv": both can sit on one account, the server tells them apart.
        applicationId = "com.gatecontrol.client.tv"
        // Fire OS 7 (Fire TV Stick 4K Max, 1st gen.) is Android 9.
        minSdk = 28
        targetSdk = 36
        versionCode = phoneVersionCode
        versionName = phoneVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // The Fire TV Stick 4K Max has a 64-bit CPU but a 32-bit system: without armeabi-v7a it refuses the APK
        // (INSTALL_FAILED_NO_MATCHING_ABIS). arm64-v8a for 64-bit Android TV devices.
        ndk {
            abiFilters += setOf("armeabi-v7a", "arm64-v8a")
        }
    }

    // Release signing as in :app: only from the environment (CI secrets), the same key as the phone app.
    val releaseKeystorePath = System.getenv("KEYSTORE_PATH")?.takeIf { it.isNotEmpty() }
    val releaseStorePassword = System.getenv("KEYSTORE_PASSWORD")?.takeIf { it.isNotEmpty() }
    val releaseKeyAlias = System.getenv("KEY_ALIAS")?.takeIf { it.isNotEmpty() }
    val releaseKeyPassword = System.getenv("KEY_PASSWORD")?.takeIf { it.isNotEmpty() }
    val hasReleaseSigning = releaseKeystorePath != null && releaseStorePassword != null &&
        releaseKeyAlias != null && releaseKeyPassword != null

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // Robolectric (Compose UI tests, launcher entry) needs the merged resources and manifest
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    // Shared with the phone app. Not core/rdp (FreeRDP only ships arm64-v8a) and not the Termux terminal.
    implementation(project(":core:common"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:data"))
    implementation(project(":core:network"))
    implementation(project(":core:notify"))
    implementation(project(":core:door"))
    implementation(project(":core:quickactions"))
    implementation(project(":core:drive-api"))
    implementation(project(":core:gatedrop"))
    implementation(project(":core:tunnel"))
    // Medien: Jellyfin (later SMB, WebDAV), shared with the phone app later
    implementation(project(":core:media"))
    // the photo frame reads Drive's `GET frame` itself (JsonObject of the plugin bridge)
    implementation(libs.retrofit.gson)

    implementation(libs.core.ktx)
    implementation(libs.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    // Compose for TV: focusable cards and surfaces (scale, border, glow on focus), TV color scheme and type
    implementation(libs.tv.material)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    // Hilt, as in :app: the core modules' bindings (setup, network, tunnel) plus the TV's own (TvModule)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    // The pairing and update calls of core/network (Retrofit answers, OkHttp errors)
    implementation(libs.retrofit)
    // Gson: GateDrop's pushes and answers (core/gatedrop hands its JsonObject over)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp)
    implementation(libs.timber)
    // Home cameras live (HLS fMP4 over the client API's OkHttp), as in the phone app
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.ui)
    // The pairing QR code (pure Java, no native code)
    implementation(libs.zxing.core)

    testImplementation(libs.junit5.api)
    testRuntimeOnly(libs.junit5.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.robolectric)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testRuntimeOnly(libs.junit.vintage.engine)
}

// Fail release packaging when the signing secrets are missing (as in :app): never an unsigned or
// throwaway-signed TV APK. Lint/tests on the release variant do not package and stay unaffected.
gradle.taskGraph.whenReady {
    val packagesRelease = allTasks.any { task ->
        task.project == project && (task.name == "packageRelease" || task.name == "assembleRelease")
    }
    val signingEnvComplete = listOf("KEYSTORE_PATH", "KEYSTORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD")
        .all { !System.getenv(it).isNullOrEmpty() }
    if (packagesRelease && !signingEnvComplete) {
        throw GradleException(
            "Release signing requires KEYSTORE_PATH, KEYSTORE_PASSWORD, KEY_ALIAS and KEY_PASSWORD",
        )
    }
}

// Robolectric's Android 9 jar (the Fire TV Stick's API level) through Gradle and its cache, as in :app
val robolectricJars: Configuration by configurations.creating
dependencies {
    robolectricJars("org.robolectric:android-all-instrumented:9-robolectric-4913185-2-i7")
}
val robolectricDeps = tasks.register<Sync>("robolectricDeps") {
    from(robolectricJars)
    into(layout.buildDirectory.dir("robolectric"))
}

tasks.withType<Test> {
    useJUnitPlatform()
    dependsOn(robolectricDeps)
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", layout.buildDirectory.dir("robolectric").get().asFile.absolutePath)
    // TvVersionTest compares BuildConfig with the phone app's build file
    systemProperty("gc.phoneBuildFile", rootProject.file("app/build.gradle.kts").absolutePath)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
