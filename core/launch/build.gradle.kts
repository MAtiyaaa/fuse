plugins {
    id("fuse.kmp.library")
    id("fuse.serialization")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.model)
            api(projects.core.library)
        }
    }
}
