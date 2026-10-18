// Fuse Tune's generator: reads each emulator's own settings code at its last four release tags and
// writes what it declares (section, key, type, default, per-game support, the tags it is in) as data
// for core:launch. Run by hand when emulators release: `./gradlew :tools:tune:run`. Nothing of it
// ships in Fuse; only the data it writes does.
plugins {
    id("fuse.jvm.application")
}

dependencies {
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("io.github.matiyaaa.fuse.tune.TuneMainKt")
}

tasks.named<JavaExec>("run") {
    // The data goes beside core:launch's settings, from the repository's root.
    workingDir = rootProject.projectDir
    args("core/launch/src/commonMain/kotlin/io/github/matiyaaa/fuse/launch/settings/TuneData.kt")
}

tasks.test {
    useJUnitPlatform()
}
