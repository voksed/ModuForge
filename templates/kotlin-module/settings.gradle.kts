pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Where `gradlew :sdk:publishToMavenLocal` in the ModuForge repository puts the SDK.
        mavenLocal()
    }
}

rootProject.name = "my-module"
