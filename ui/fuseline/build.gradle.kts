// Fuseline: Fuse's animation engine. Its curves, springs, animated values, transitions, loops,
// scrolling and timelines are its own; from Compose it takes only the frame tick, layout and the
// graphics layer it draws through (see docs/fuseline.md).
plugins {
    id("fuse.kmp.compose")
}

kotlin {
    sourceSets {
        getByName("desktopTest") {
            dependencies {
                implementation(libs.compose.ui.test)
                implementation(compose.desktop.currentOs)
            }
        }
    }
}
