plugins {
    id("com.android.application") version "9.3.1" apply false
    id("com.android.library") version "9.3.1" apply false
}

buildscript {
    // AGP's built-in Kotlin, at the version Termux:X11's dependencies are built with.
    dependencies { classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20") }
}

// Termux:X11's X server (third_party/termux-x11): only the ABI Okno ships.
subprojects {
    if (path == ":lorie") pluginManager.withPlugin("com.android.library") {
        extensions.configure<com.android.build.api.dsl.LibraryExtension> { defaultConfig.ndk.abiFilters += "arm64-v8a" }
    }
}
