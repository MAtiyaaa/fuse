plugins {
    id("fuse.kmp.library")
    id("fuse.serialization")
}

// Provider clients (RetroAchievements, SteamGridDB, IGDB, TheGamesDB, ScreenScraper, libretro
// thumbnails, GitHub releases) and the Cartridge bridge protocol. Everything lives in commonMain:
// the apps inject the Ktor engine (OkHttp on Android, CIO or Java on desktop) through FuseHttp.
// Downloads to files (resumable release downloads) are the JVM's, Android and the desktop alike.
kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.model)
            api(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
        }
        val jvmShared by creating {
            dependsOn(commonMain.get())
            dependencies {
                implementation(libs.xz)
                implementation(libs.aircompressor)
            }
        }
        androidMain.get().dependsOn(jvmShared)
        getByName("desktopMain").dependsOn(jvmShared)
        commonTest.dependencies {
            implementation(libs.ktor.client.mock)
            // Only for checking the system art names against the whole platform catalog.
            implementation(projects.core.library)
        }
    }
}
