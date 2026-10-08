plugins {
    id("fuse.kmp.compose")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.model)
            api(projects.ui.fuseline)
            api(libs.coil.compose)
        }
        getByName("desktopTest") {
            dependencies {
                // Pixel tests execute real Skia; compose-ui alone supplies no platform native.
                implementation(compose.desktop.currentOs)
            }
        }
    }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "io.github.matiyaaa.fuse.ui.designsystem.res"
}
