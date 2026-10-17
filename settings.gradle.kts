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
        maven("https://jitpack.io") {
            content { includeGroup("com.github.termux.termux-app") }
        }
    }
}

rootProject.name = "okno"
include(":app")

// The X server and its Android view, from Termux:X11 (git submodule).
include(":lorie", ":shell-loader:stub")
project(":lorie").projectDir = file("third_party/termux-x11/lorie")
// Only its stub is needed: the shell-loader itself builds Termux:X11's own app.
project(":shell-loader").projectDir = file("third_party/termux-x11/shell-loader")
project(":shell-loader").buildFileName = "none"
project(":shell-loader:stub").projectDir = file("third_party/termux-x11/shell-loader/stub")
