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
        // What the JVM gives Android and the desktop alike: Fuse Intelligence's read-only look at
        // the file system and Fuse's own file for what it learns.
        val jvmShared by creating {
            dependsOn(commonMain.get())
        }
        androidMain.get().dependsOn(jvmShared)
        getByName("desktopMain").dependsOn(jvmShared)
    }
}
