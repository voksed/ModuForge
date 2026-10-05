plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.moduforge.sandbox"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        aidl = true
    }
}

dependencies {
    api(project(":core"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.luaj)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
