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
        versionCode = 1
        versionName = "0.1.0"
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
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
}
