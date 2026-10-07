// A module project is an Android application used only as a container for compiled code.
// The result is never installed into the OS: `mfrg pack` takes the classes out of the APK.
plugins {
    id("com.android.application") version "9.4.1"
    // Only puts the newer Kotlin compiler on the build classpath: the SDK is compiled with it.
    id("org.jetbrains.kotlin.jvm") version "2.4.20" apply false
}

android {
    namespace = "com.example.mymodule"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.mymodule"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// The SDK and the Kotlin runtime are provided by the sandbox; packaging them would only add dead weight.
configurations.matching { it.name.endsWith("RuntimeClasspath") }.configureEach {
    exclude(group = "org.jetbrains.kotlin")
    exclude(group = "org.jetbrains.kotlinx")
}

dependencies {
    compileOnly("dev.moduforge:moduforge-sdk:1.0.0")
}
