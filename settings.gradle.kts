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
// GateControl TV (Fire TV, Android TV): own app, minSdk 28, 32-bit ARM. Shares core/* except core/rdp.
include(":tv")
include(":core:common")
include(":core:designsystem")
include(":core:data")
include(":core:network")
include(":core:notify")
include(":core:door")
include(":core:quickactions")
include(":core:drive-api")
include(":core:gatedrop")
include(":core:tunnel")
// Media of the own NAS (Jellyfin, SMB and WebDAV shares): shared by GateControl TV and, later, the phone app
include(":core:media")
include(":core:rdp")
// SSH terminal of „Fernzugriff“: Termux terminal-emulator + terminal-view (Apache-2.0), fixed release sources
include(":termux-terminal")
project(":termux-terminal").projectDir = file("third_party/termux-terminal")
