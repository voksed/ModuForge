import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "dev.moduforge.host"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.moduforge.host"
        minSdk = 26
        targetSdk = 37
        versionCode = 2
        versionName = "0.1.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release signing is configured by `.keys/keystore.properties` (storeFile, storePassword,
    // keyAlias, keyPassword), which is not part of the repository. Without it the release APK
    // is built unsigned.
    val keystoreFile = rootProject.file(".keys/keystore.properties")
    val keystore = Properties().apply { if (keystoreFile.isFile) keystoreFile.inputStream().use(::load) }

    signingConfigs {
        if (keystoreFile.isFile) {
            create("release") {
                storeFile = rootProject.file(keystore.getProperty("storeFile"))
                storePassword = keystore.getProperty("storePassword")
                keyAlias = keystore.getProperty("keyAlias")
                keyPassword = keystore.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Lets a development build live next to an installed release, whose signature differs.
            applicationIdSuffix = ".debug"
            manifestPlaceholders["appLabel"] = "ModuForge Dev"
        }
        release {
            manifestPlaceholders["appLabel"] = "@string/app_name"
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

/** Copies module packages built by sibling projects into `assets/modules/`. */
abstract class BundleModulesTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val packages: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun bundle() {
        val target = outputDir.get().asFile.resolve("modules").apply {
            deleteRecursively()
            mkdirs()
        }
        packages.files.forEach { apk ->
            apk.copyTo(target.resolve(apk.name.replace(Regex("-(debug|release-unsigned)\\.apk$"), ".apk")))
        }
    }
}

// The host ships without modules. These are packed into the instrumentation tests only:
// the probe proves that an isolated module cannot reach anything, the compiled example
// that a Kotlin module runs from a signed package.
val testModules = listOf("sandbox-probe", "hello")

androidComponents {
    onVariants { variant ->
        val tests = variant.androidTest ?: return@onVariants
        val buildType = variant.buildType ?: return@onVariants
        val variantName = variant.name.replaceFirstChar { it.uppercase() }
        val task = tasks.register<BundleModulesTask>("bundle${variantName}TestModules") {
            testModules.forEach { name ->
                dependsOn(":modules:$name:assemble${buildType.replaceFirstChar { it.uppercase() }}")
                packages.from(
                    rootProject.layout.projectDirectory.file("modules/$name/build/outputs/apk/$buildType/$name-$buildType.apk"),
                )
            }
        }
        tests.sources.assets?.addGeneratedSourceDirectory(task, BundleModulesTask::outputDir)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":runtime-sandbox"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.material.kolor)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
