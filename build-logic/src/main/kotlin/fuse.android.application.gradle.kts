// The Android app. AGP 9 compiles Kotlin itself ("built-in Kotlin"), so only the Compose compiler is added.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.matiyaaa.fuse"
    compileSdk = libs.int("android-compileSdk")
    defaultConfig {
        applicationId = "io.github.matiyaaa.fuse"
        minSdk = libs.int("android-minSdk")
        targetSdk = libs.int("android-targetSdk")
        versionName = project.fuseVersion
        versionCode = (findProperty("fuse.versionCode") as String?)?.toInt() ?: 1
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

composeCompiler {
    // No per-composable source records or trace checks in the code that ships: less work on every
    // recomposition, and nothing that changes what is drawn.
    includeSourceInformation.set(false)
    includeTraceMarkers.set(false)
}
