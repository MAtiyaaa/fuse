plugins {
    id("fuse.kmp.library")
    id("fuse.serialization")
}

// Fuse RomM, the Fuse RomM native integration: a RomM server's library mirrored into Fuse's own
// database, its games downloaded through Fuse's transfers into Fuse's library, and Fuse's games
// uploaded back. RomM is its own project (https://github.com/rommapp/romm); this is Fuse's client.
kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.data)
            api(projects.core.integrations)
            api(projects.core.transfer)
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
        getByName("desktopTest") {
            dependencies {
                implementation(libs.sqldelight.sqlite.driver)
            }
        }
    }
}
