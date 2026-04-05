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
        maven("https://raw.githubusercontent.com/nicoschmidt/wireguard-android/master/repo")
    }
}

rootProject.name = "GateControl"

include(":app")
include(":core:common")
include(":core:data")
include(":core:network")
include(":core:tunnel")
include(":core:rdp")
