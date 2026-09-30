plugins {
    id("fuse.kmp.library")
    id("fuse.serialization")
}

// Provider clients (RetroAchievements, SteamGridDB, IGDB, TheGamesDB, ScreenScraper, libretro
// thumbnails, GitHub releases) and the Cartridge bridge protocol. Everything lives in commonMain:
// the apps inject the Ktor engine (OkHttp on Android, CIO or Java on desktop) through FuseHttp.
kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.model)
            api(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
        }
        commonTest.dependencies {
            implementation(libs.ktor.client.mock)
        }
    }
}
