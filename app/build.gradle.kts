import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.smriti.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.smriti.app"
        minSdk = 31
        targetSdk = 35
        // One source of truth: ../version.properties. scripts/package-apk.ps1 bumps BUILD for every shared APK,
        // so every APK has a higher versionCode (installs over the old one) and a unique name.
        val ver = Properties().apply { file("../version.properties").inputStream().use { load(it) } }
        versionCode = ver.getProperty("BUILD").toInt()
        versionName = "${ver.getProperty("VERSION")}.${ver.getProperty("BUILD")}"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Shared builds are signed with the debug key so friends can install them without a keystore.
            // Use a real keystore before any store release.
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // LiteRT-LM is compiled with a newer Kotlin than this project (2.0.21); we only call its plain classes, so skip the metadata version gate
    kotlinOptions { jvmTarget = "17"; freeCompilerArgs += "-Xskip-metadata-version-check" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Model weights must never be compressed in the APK (memory-mapped by NPU runtimes).
    androidResources { noCompress += listOf("tflite", "litert", "pte", "bin", "gguf") }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
        // the NPU runtime is loaded by file path from nativeLibraryDir, so the libraries must be extracted on install
        jniLibs { useLegacyPackaging = true }
    }

    testOptions { unitTests.isReturnDefaultValues = true }

    // Release lint needs artefacts that are not cached for offline builds on this machine.
    lint { checkReleaseBuilds = false }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    // Compose
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Settings
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Dashboard server: small embedded HTTP + WebSocket server (BSD licence), serves the bundled website
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("org.nanohttpd:nanohttpd-websocket:2.3.1")

    // CameraX
    val camerax = "1.4.1"
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    // Lifecycle / coroutines
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    // Room (text-only memory store)
    val room = "2.6.1"
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    ksp("androidx.room:room-compiler:$room")
    // SQLCipher (AES-256) under Room: the memory database is encrypted with a key kept in the phone's secure hardware (see security/DatabaseGuard.kt).
    // 4.13.0 is the newest release that still works with the androidx.sqlite version Room 2.6.1 brings.
    implementation("net.zetetic:sqlcipher-android:4.13.0")

    // ML Kit, BUNDLED variants only: models ship inside the APK, nothing is downloaded (offline-safe).
    implementation("com.google.mlkit:face-detection:16.1.7")
    implementation("com.google.mlkit:image-labeling:17.0.9")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:object-detection:17.0.2")
    // EfficientDet-Lite2 (COCO, 80 everyday objects: bottle, cup, phone, book, remote...) reused from the AssembleFix app
    implementation("com.google.mediapipe:tasks-vision:0.10.20")

    // EmbeddingGemma-2 (the model Google AI Edge Gallery uses for Smart Album / Video Moment Finder) through LiteRT-LM,
    // plus the Qualcomm Hexagon runtime so the SM8850 build of the model can run on the NPU. The model file itself is not in the APK.
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.0")
    implementation("com.google.ai.edge.litert:litert-npu-runtime-qualcomm:2.3.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
}
