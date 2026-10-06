// Jellyfin as a server behind Fuse's own media screens: Fuse's own REST client (no SDK, so no
// clash with the app's Ktor), the connection (local, remote, automatic), discovery on the network,
// device profiles from what the player can play, caches for working offline, and the resolver
// that turns an item into a stream for Fuse Player. See docs/jellyfin.md.
plugins {
    id("fuse.kmp.library")
    id("fuse.serialization")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.playback)
            api(projects.core.data)
            api(projects.core.transfer)
            api(libs.ktor.client.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.ktor.client.mock)
        }
        getByName("desktopTest").dependencies {
            implementation(libs.ktor.client.mock)
        }
        // Android and the desktop are both the JVM: discovery's UDP lives here once.
        val jvmShared by creating {
            dependsOn(commonMain.get())
        }
        androidMain.get().dependsOn(jvmShared)
        getByName("desktopMain").dependsOn(jvmShared)
    }
}
