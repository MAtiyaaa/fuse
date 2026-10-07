plugins {
    id("fuse.kmp.library")
    id("fuse.serialization")
}

// Fuse's reach: a game brought to a device from wherever is best (another of the household's
// devices on the home network, RomM at home, another device through the Fuse Sync host, RomM from
// outside), through Fuse's one queue of transfers, checked by hash and put in place whole. It joins
// Fuse Sync's household (core:sync) and Fuse RomM (core:romm) without either knowing the other.
kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.sync)
            api(projects.core.romm)
            api(projects.core.transfer)
            implementation(libs.kotlinx.serialization.json)
        }
        val jvmShared by creating {
            dependsOn(commonMain.get())
            dependencies {
                implementation(libs.ktor.client.core)
            }
        }
        androidMain.get().dependsOn(jvmShared)
        getByName("desktopMain").dependsOn(jvmShared)
        getByName("desktopTest") {
            dependencies {
                implementation(libs.ktor.client.cio)
                implementation(libs.ktor.server.core)
                implementation(libs.ktor.server.cio)
                implementation(libs.sqldelight.sqlite.driver)
            }
        }
    }
}
