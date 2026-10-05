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
    }
}

rootProject.name = "ModuForge"

include(":sdk")
include(":core")
include(":runtime-sandbox")
include(":host-app")
include(":modules:hello")
include(":modules:sandbox-probe")
include(":tools:packer")
