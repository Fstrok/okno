plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.okno"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.okno"
        minSdk = 26
        // Kept at 28 deliberately, like Termux: higher targets forbid exec()
        // of files in the app's data dir, which Linux userland relies on.
        targetSdk = 28
        versionCode = 1
        versionName = "0.1.0"

        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        // Committed debug key: every CI build gets the same signature, so new
        // APKs install over old ones without losing the Linux system.
        getByName("debug") {
            storeFile = file("okno-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        // Native binaries (proot) must be extracted to nativeLibraryDir to be executable.
        jniLibs.useLegacyPackaging = true
    }

    androidResources {
        // Read in place with openFd(); also stops aapt from un-gzipping ".gz" assets.
        noCompress += "tgz"
    }

    lint {
        disable += setOf("ExpiredTargetSdkVersion", "OldTargetApi")
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation("com.github.termux.termux-app:terminal-view:v0.118.1")
    implementation("org.apache.commons:commons-compress:1.28.0")
}
