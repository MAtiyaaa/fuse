// A shared module with Compose Multiplatform UI.
plugins {
    id("fuse.kmp.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    android {
        androidResources { enable = true }
    }
    sourceSets {
        commonMain.dependencies {
            implementation(libs.lib("compose-runtime"))
            implementation(libs.lib("compose-foundation"))
            implementation(libs.lib("compose-ui"))
            implementation(libs.lib("compose-ui-util"))
            implementation(libs.lib("compose-animation"))
            implementation(libs.lib("compose-components-resources"))
        }
    }
}
