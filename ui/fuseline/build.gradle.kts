// Fuseline by Fuse: Fuse's animation engine. Its curves, springs, animated values, transitions, loops,
// scrolling and timelines are its own; from Compose it takes only the frame tick, layout and the
// graphics layer it draws through (see docs/fuseline.md).
plugins {
    id("fuse.kmp.compose")
}

kotlin {
    sourceSets {
        getByName("desktopTest") {
            dependencies {
                implementation(libs.compose.ui.test)
                implementation(compose.desktop.currentOs)
            }
        }
    }
}

// The benchmarks run only when asked: -Pfuse.bench=true (-Pfuse.bench.only=<case>, -Pfuse.bench.rounds=<n>,
// -Pfuse.bench.lenient=true to write the tables without failing on a row Fuseline 4 doesn't win).
// The equivalence fuzzer runs 400 histories by default: -Pfuse.fuzz.seeds=<n> -Pfuse.fuzz.from=<seed>.
tasks.withType<Test>().configureEach {
    systemProperty("fuse.bench", providers.gradleProperty("fuse.bench").getOrElse("false"))
    systemProperty("fuse.bench.only", providers.gradleProperty("fuse.bench.only").getOrElse(""))
    for (name in listOf("fuse.bench.rounds", "fuse.bench.lenient", "fuse.fuzz.seeds", "fuse.fuzz.from", "fuse.fuzz.trail", "fuse.fuzz.debug")) {
        providers.gradleProperty(name).orNull?.let { systemProperty(name, it) }
    }
    // The benchmark keeps thousands of values per engine alive at once.
    maxHeapSize = "2g"
    outputs.upToDateWhen { false }
    providers.gradleProperty("fuse.jfr").orNull?.let { jvmArgs("-XX:StartFlightRecording=filename=$it,settings=profile") }
}
