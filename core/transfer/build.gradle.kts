plugins {
    id("fuse.kmp.library")
    id("fuse.serialization")
}

// The transfers Fuse makes for the person (games, BIOS, films, apps, uploads): one queue with its own
// limits, kept across restarts, resumed where the source allows, and placed only once checked.
kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.library)
            api(libs.ktor.client.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.ktor.client.mock)
        }
        val jvmShared by creating {
            dependsOn(commonMain.get())
        }
        androidMain.get().dependsOn(jvmShared)
        getByName("desktopMain").dependsOn(jvmShared)
    }
}
