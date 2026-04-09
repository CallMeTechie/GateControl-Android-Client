plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt)
    kotlin("kapt")
}

android {
    namespace = "com.gatecontrol.android.core.rdp"
    compileSdk = 35

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

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:data"))
    implementation(project(":core:network"))

    implementation(libs.core.ktx)
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
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
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.robolectric)
}

kapt {
    correctErrorTypes = true
}

tasks.withType<Test> {
    useJUnitPlatform()
}
