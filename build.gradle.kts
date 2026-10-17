plugins {
    id("com.android.application") version "9.3.1" apply false
    id("com.android.library") version "9.3.1" apply false
}

buildscript {
    // AGP's built-in Kotlin, at the version Termux:X11's dependencies are built with.
    dependencies { classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20") }
}

// Okno's fixes to Termux:X11 (patches/termux-x11), applied to the pinned submodule before it is
// built. Already applied patches are skipped; one that no longer fits fails the build.
val patchTermuxX11 by tasks.registering {
    val patches = fileTree("patches/termux-x11") { include("*.patch") }
    inputs.files(patches)
    val module = file("third_party/termux-x11")
    doLast {
        patches.sortedBy { it.name }.forEach { patch ->
            fun git(vararg args: String) = ProcessBuilder("git", "apply", *args, patch.absolutePath)
                .directory(module).inheritIO().start().waitFor()
            if (git("--reverse", "--check") != 0 && git() != 0) throw GradleException("${patch.name} does not apply to Termux:X11")
        }
    }
}

// Termux:X11's X server (third_party/termux-x11): only the ABI Okno ships.
subprojects {
    if (path == ":lorie") tasks.configureEach { if (name == "preBuild") dependsOn(patchTermuxX11) }
    if (path == ":lorie") pluginManager.withPlugin("com.android.library") {
        extensions.configure<com.android.build.api.dsl.LibraryExtension> { defaultConfig.ndk.abiFilters += "arm64-v8a" }
    }
}
