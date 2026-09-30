plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    jacoco
}

android {
    namespace = "com.gatecontrol.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.gatecontrol.client"
        minSdk = 31
        targetSdk = 36
        versionCode = 11300
        versionName = "1.13.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        vectorDrawables {
            useSupportLibrary = true
        }

        // FreeRDP native libs only ship as arm64-v8a (see
        // .github/workflows/freerdp-build.yml). Restrict the APK to the
        // same ABI so it cannot accidentally package other architectures
        // from other libraries and bloat the install size.
        ndk {
            abiFilters += setOf("arm64-v8a")
        }
    }

    // Release signing comes exclusively from the environment (CI secrets).
    // There is no fallback keystore/password: a release package task without
    // the secrets fails (see the taskGraph check below). Debug is unaffected.
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
            ndk {
                debugSymbolLevel = "FULL"
            }
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
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


    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        // startActivityAndCollapse(Intent) is deprecated but needed for API < 34
        disable += "StartActivityAndCollapseDeprecated"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:data"))
    implementation(project(":core:network"))
    implementation(project(":core:tunnel"))
    implementation(project(":core:rdp"))

    // FreeRDP embedded client AAR (runtime packaging)
    // See core/rdp/build.gradle.kts for the rationale behind splitting
    // this dependency between :core:rdp (compileOnly) and :app (runtime).
    implementation(files("../core/rdp/libs/freerdp-android.aar"))

    // AndroidX Core
    implementation(libs.core.ktx)
    implementation(libs.activity.compose)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    // Navigation
    implementation(libs.navigation.compose)

    // Lifecycle
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // CameraX
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)

    // ML Kit
    implementation(libs.mlkit.barcode)

    // Retrofit (needed for HttpException in error handling)
    implementation(libs.retrofit)

    // Logging
    implementation(libs.timber)

    // Coroutines
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    // Testing
    testImplementation(libs.junit5.api)
    testRuntimeOnly(libs.junit5.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.okhttp)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
}


// Fail release packaging when the signing secrets are missing instead of
// producing an unsigned or throwaway-signed artifact. Lint/tests on the
// release variant do not package and stay unaffected.
gradle.taskGraph.whenReady {
    val packagesRelease = allTasks.any { task ->
        task.project == project && (
            task.name == "packageRelease" || task.name == "signReleaseBundle" ||
                task.name == "assembleRelease" || task.name == "bundleRelease"
            )
    }
    val signingEnvComplete = listOf("KEYSTORE_PATH", "KEYSTORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD")
        .all { !System.getenv(it).isNullOrEmpty() }
    if (packagesRelease && !signingEnvComplete) {
        throw GradleException(
            "Release signing requires KEYSTORE_PATH, KEYSTORE_PASSWORD, KEY_ALIAS and KEY_PASSWORD",
        )
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    finalizedBy(tasks.named("jacocoTestReport"))
}

tasks.register<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.withType<Test>())
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
    sourceDirectories.setFrom(files("src/main/java"))
    classDirectories.setFrom(
        fileTree("build/tmp/kotlin-classes/debug") {
            exclude("**/hilt_aggregated_deps/**", "**/Hilt_*", "**/*_Factory*", "**/*_MembersInjector*")
        }
    )
    executionData.setFrom(fileTree("build") { include("jacoco/*.exec") })
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
