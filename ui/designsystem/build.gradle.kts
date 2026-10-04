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
    }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "io.github.matiyaaa.fuse.ui.designsystem.res"
}
