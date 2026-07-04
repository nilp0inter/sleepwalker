// sleepwalker-app: ADB-driven companion app.
//
// Exposes an ADB-friendly command surface (broadcast receiver + shell
// command) that delegates to a service-owned BLE connection/session.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.sleepwalker.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.sleepwalker.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Use a writable debug keystore location (the default ~/.android may
    // not be writable in a Nix build environment).
    signingConfigs {
        getByName("debug") {
            storeFile = file(System.getenv("SLEEPWALKER_KEYSTORE") ?: "${System.getProperty("java.io.tmpdir")}/sleepwalker-debug.keystore")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":sleepwalker-core"))
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    testImplementation("junit:junit:4.13.2")
}