import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.evowria.printerconnector"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.evowria.printerconnector"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0"
        // Real tablets are ARM; x86 builds of ML Kit's native scanner (~12 MB)
        // are only for emulators.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    // Two separate apps, so the event-day app can never point at a laptop:
    // - prod: www.evowria.com over HTTPS only (what goes on event tablets).
    // - dev: a local server over HTTP, its own package and an orange "DEV"
    //   icon, so it installs next to prod and is easy to tell apart.
    flavorDimensions += "server"
    productFlavors {
        create("prod") {
            dimension = "server"
            buildConfigField("boolean", "LOCAL_SERVER_ALLOWED", "false")
            buildConfigField("String", "DEFAULT_SERVER_URL", "\"https://www.evowria.com\"")
            resValue("string", "app_name", "Evowria Check-in")
        }
        create("dev") {
            dimension = "server"
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            buildConfigField("boolean", "LOCAL_SERVER_ALLOWED", "true")
            // Pair with `adb reverse tcp:3021 tcp:3021`: localhost counts as a
            // secure origin, so the web QR camera works over plain HTTP.
            buildConfigField("String", "DEFAULT_SERVER_URL", "\"http://localhost:3021\"")
            resValue("string", "app_name", "Evowria Check-in DEV")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.10.0")
    // Event QR scanner: bundled ML Kit model (inside the APK, works offline,
    // no Play services download) + CameraX for our own camera screen.
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
}
