plugins {
    id("fuse.kmp.library")
    id("fuse.serialization")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.model)
        }
        // xdelta3's LZMA sections (.xz streams): read only, bytes in and bytes out.
        val jvmShared by creating {
            dependsOn(commonMain.get())
            dependencies {
                implementation(libs.xz)
            }
        }
        androidMain.get().dependsOn(jvmShared)
        getByName("desktopMain").dependsOn(jvmShared)
    }
}
