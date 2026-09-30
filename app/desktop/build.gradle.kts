plugins {
    id("fuse.desktop.application")
}

dependencies {
    implementation(projects.ui.designsystem)
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.swing)
}

compose.desktop {
    application {
        mainClass = "io.github.matiyaaa.fuse.desktop.MainKt"
    }
}
