plugins {
    id("fuse.kmp.compose")
    id("fuse.serialization")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.ui.designsystem)
            api(projects.core.library)
            api(projects.core.launch)
            api(projects.core.integrations)
            api(projects.core.data)
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor)
            implementation(libs.jb.lifecycle.runtime.compose)
            implementation(libs.kotlinx.datetime)
        }
    }
}
