plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.gatecontrol.android.core.rdp"
    compileSdk = 37

    defaultConfig {
        minSdk = 31

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

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:data"))
    implementation(project(":core:network"))

    implementation(libs.core.ktx)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.timber)

    // FreeRDP embedded client AAR (built from freerdp/ submodule by
    // .github/workflows/freerdp-build.yml).
    //
    // AGP forbids direct local .aar dependencies inside a library module
    // because the classes+resources would not be re-packaged into the
    // downstream AAR. We therefore split the dependency:
    //
    //   :core:rdp  → compileOnly + testImplementation (compile-time symbols,
    //                test-time Class.forName checks)
    //   :app       → implementation(files("../core/rdp/libs/freerdp-android.aar"))
    //                (runtime packaging — classes.dex + jni/arm64-v8a/*.so)
    //
    // This is the AGP-recommended pattern for library modules that need to
    // reference classes from a local AAR without re-bundling them.
    compileOnly(files("libs/freerdp-android.aar"))
    testImplementation(files("libs/freerdp-android.aar"))

    testImplementation(libs.junit5.api)
    testRuntimeOnly(libs.junit5.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.org.json)
}


tasks.withType<Test> {
    useJUnitPlatform()
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
