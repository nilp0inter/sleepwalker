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
        ndk {
            // Package only the ABIs LuaJava ships natives for; the AAR
            // also bundles armeabi-v7a/x86 which we exclude to keep the
            // final APK lean. This filter applies at packaging time to
            // every native library pulled in transitively via the
            // runtimeOnly AAR from :sleepwalker-core.
            abiFilters += setOf("arm64-v8a", "x86_64")
        }
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
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(project(":sleepwalker-core"))
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("io.mockk:mockk:1.13.8")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("androidx.test.ext:junit:1.1.5")
}