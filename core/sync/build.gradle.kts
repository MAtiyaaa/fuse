// Fuse Sync by Fuse: Fuse's own, self-hosted synchronisation of a person's Fuse world (profiles,
// saves, states, play time, favourites, collections, settings) between their devices through one
// Fuse Sync Host they run themselves. Game-aware, not folder-aware: it knows which game, which
// emulator and which person a save belongs to. The model and merge rules are plain Kotlin
// (commonMain); the content store, the host's server, the client and discovery are the JVM's
// (jvmShared, Android and the desktop alike). See docs/sync.md.
plugins {
    id("fuse.kmp.library")
    id("fuse.serialization")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // Its settings (which host, which profile, what syncs) are part of Fuse's own.
            api(projects.core.data)
            implementation(libs.kotlinx.serialization.json)
        }
        val jvmShared by creating {
            dependsOn(commonMain.get())
            dependencies {
                implementation(libs.ktor.server.core)
                implementation(libs.ktor.server.cio)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.cio)
            }
        }
        androidMain.get().dependsOn(jvmShared)
        getByName("desktopMain").dependsOn(jvmShared)
    }
}
