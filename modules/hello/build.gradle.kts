// Module package: an APK-format archive with the module code and its manifest.
// It is loaded by the host sandbox and never installed into the OS.
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "dev.moduforge.modules.hello"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.moduforge.modules.hello"
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
    compileOnly(project(":sdk"))
}
