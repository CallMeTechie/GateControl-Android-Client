import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    jacoco
}

android {
    namespace = "com.gatecontrol.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.gatecontrol.client"
        minSdk = 31
        targetSdk = 36
        versionCode = 17601
        versionName = "1.76.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // "github" (APK on GitHub, updates itself through the update check) or
        // "play" (AAB for Google Play: updates come from Play only, as Play
        // policy requires). release.yml builds the AAB with -Pgc.distribution=play.
        val distribution = providers.gradleProperty("gc.distribution").getOrElse("github")
        require(distribution == "github" || distribution == "play") { "gc.distribution must be github or play" }
        buildConfigField("String", "DISTRIBUTION", "\"$distribution\"")

        vectorDrawables {
            useSupportLibrary = true
        }

        // FreeRDP native libs only ship as arm64-v8a (see
        // .github/workflows/freerdp-build.yml). Restrict the APK to the
        // same ABI so it cannot accidentally package other architectures
        // from other libraries and bloat the install size.
        ndk {
            abiFilters += setOf("arm64-v8a")
            // Emulator recordings (.github/workflows/play-videos.yml) add
            // x86_64 with -Pgc.extraAbis=x86_64: the WireGuard library ships it,
            // under arm64 translation it crashes. Release builds never set this.
            (project.findProperty("gc.extraAbis") as String?)
                ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?.let { abiFilters += it }
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
        // Robolectric (Compose UI tests) needs the merged resources and manifest
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:data"))
    implementation(project(":core:network"))
    implementation(project(":core:notify"))
    implementation(project(":core:tunnel"))
    implementation(project(":core:rdp"))

    // FreeRDP embedded client AAR (runtime packaging)
    // See core/rdp/build.gradle.kts for the rationale behind splitting
    // this dependency between :core:rdp (compileOnly) and :app (runtime).
    implementation(files("../core/rdp/libs/freerdp-android.aar"))

    // AndroidX Core
    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    // Door tab: BiometricPrompt with device credential fallback (MainActivity is a FragmentActivity).
    implementation(libs.biometric)
    implementation(libs.fragment)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.material3.adaptive)

    // Home-screen widget
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
    // Glance updates widgets through WorkManager; its own pin (2.7.1) crashes
    // the release build at start (WorkDatabase stripped by R8).
    implementation(libs.work.runtime)

    // Chats (messenger plugin): Room cache of conversations, messages and the outbox.
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Services open in a Custom Tab (in-app browser)
    implementation(libs.browser)
    // Drive: the date a photo was taken (EXIF) goes with the upload
    implementation(libs.exifinterface)

    // Google Play in-app updates (Play distribution, see BuildConfig.DISTRIBUTION)
    implementation(libs.play.app.update)

    // Familie: geofences for place reminders (Play services; without them
    // places are listed but marked unavailable) and OpenStreetMap maps.
    implementation(libs.play.services.location)
    implementation(libs.osmdroid)

    // Start/Familie tab: reorder by dragging (lazy list and grid)
    implementation(libs.reorderable)

    // Gson (via the Retrofit converter, as in core): pinned quick actions
    implementation(libs.retrofit.gson)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    // Navigation
    implementation(libs.navigation.compose)

    // Lifecycle
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    // Play update: installs a downloaded update when the app goes to the background
    implementation(libs.lifecycle.process)

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
    // Drive: "Scannen" (Google's document scanner and text recognition, both through Play services: no models in the APK)
    implementation(libs.mlkit.document.scanner)
    implementation(libs.mlkit.text.recognition)
    // Drive: work photos apart — image labeling on the phone (Play services, no model in the APK, no picture leaves the phone)
    implementation(libs.mlkit.image.labeling)

    // Drive: people in the photos — faces found and described on the phone (YuNet in the assets, SFace loaded when
    // switched on; models from OpenCV Zoo), no picture leaves the phone for it
    implementation(libs.onnxruntime.android)

    // Vault: end-to-end encryption (README "Kryptoformat" of gatecontrol-vault) and the recovery key's QR
    implementation(libs.bouncycastle.prov)
    implementation(libs.zxing.core)

    // Retrofit (needed for HttpException in error handling)
    implementation(libs.retrofit)
    implementation(libs.okhttp)

    // DataStore (push settings in tests)
    implementation(libs.datastore.preferences)

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
    // Compose UI tests on the JVM (Robolectric): the Drive grid must not move when ticking starts
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testRuntimeOnly(libs.junit.vintage.engine)
    testImplementation(libs.okhttp)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
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

// Drive: SFace, the face feature model (OpenCV Zoo, unchanged, 39 MB), is not in the app: the phone loads it when the
// person switches on "Personen erkennen" (DriveFaceModel). This task fetches it, checked against its SHA-256, for the
// release workflow, which attaches it to every GitHub release (the app's first source). YuNet (0.2 MB) is in assets/faces.
// Kept in Gradle's cache; another mirror is tried when one fails.
abstract class FaceModelsTask : DefaultTask() {
    @get:Input abstract val models: MapProperty<String, String>
    @get:Input abstract val mirrors: ListProperty<String>
    @get:Internal abstract val cacheDir: DirectoryProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun fetch() {
        val out = outputDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        val cache = cacheDir.get().asFile.apply { mkdirs() }
        for ((path, sha) in models.get()) {
            val name = path.substringAfterLast('/')
            val cached = cache.resolve("$sha-$name")
            if (!cached.isFile || sha256(cached) != sha) {
                var last: Exception? = null
                val ok = mirrors.get().any { base ->
                    try {
                        val tmp = cache.resolve("$name.part")
                        val url = base.replace("{dir}", path.substringBeforeLast('/')).replace("{file}", name)
                        val conn = URI(url).toURL().openConnection() as HttpURLConnection
                        conn.instanceFollowRedirects = true
                        conn.connectTimeout = 30_000
                        conn.readTimeout = 120_000
                        conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                        if (sha256(tmp) != sha) throw GradleException("SHA-256 of $name from $base does not match")
                        tmp.renameTo(cached)
                    } catch (e: Exception) {
                        last = e
                        false
                    }
                }
                if (!ok) throw GradleException("Face model $name could not be fetched", last)
            }
            cached.copyTo(out.resolve(name), overwrite = true)
        }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

val faceModels = tasks.register<FaceModelsTask>("faceModels") {
    // directory/file in OpenCV Zoo → SHA-256 (the same as DriveFaceModel's; licences: assets/faces/NOTICE.txt)
    models.put("face_recognition_sface/face_recognition_sface_2021dec.onnx", "0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79")
    mirrors.add("https://media.githubusercontent.com/media/opencv/opencv_zoo/main/models/{dir}/{file}")
    mirrors.add("https://huggingface.co/opencv/{dir}/resolve/main/{file}")
    cacheDir.set(File(gradle.gradleUserHomeDir, "caches/gatecontrol-face-models"))
    outputDir.set(layout.buildDirectory.dir("faceModels"))
}

// Robolectric's Android jar (Compose UI tests) through Gradle and its cache, not Robolectric's own downloader
val robolectricJars: Configuration by configurations.creating
dependencies {
    robolectricJars("org.robolectric:android-all-instrumented:14-robolectric-10818077-i7")
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
