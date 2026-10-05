// Test module: attempts to break out of the sandbox and reports what happened.
// Bundled with debug builds of the host only.
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "dev.moduforge.modules.probe"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.moduforge.modules.probe"
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

configurations.matching { it.name.endsWith("RuntimeClasspath") }.configureEach {
    exclude(group = "org.jetbrains.kotlin")
    exclude(group = "org.jetbrains.kotlinx")
}

dependencies {
    compileOnly(project(":sdk"))
}
