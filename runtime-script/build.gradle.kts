import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Script runtimes (Lua, JavaScript) and the host API they expose. Plain JVM code with no
// Android dependency, so the same runtime executes modules in the sandbox and on a desktop.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":sdk"))
    implementation(libs.luaj)
    implementation(libs.rhino)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
