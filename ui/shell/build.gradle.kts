plugins {
    id("fuse.kmp.compose")
    id("fuse.serialization")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.ui.designsystem)
            api(projects.core.library)
            api(projects.core.launch)
            api(projects.core.integrations)
            api(projects.core.data)
            api(projects.core.jellyfin)
            api(projects.core.sync)
            api(projects.ui.player)
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor)
            implementation(libs.coil.svg)
            implementation(libs.jb.lifecycle.runtime.compose)
            implementation(libs.kotlinx.datetime)
        }
        getByName("desktopTest") {
            dependencies {
                implementation(libs.ktor.client.mock)
                // The README screenshots fetch real art (see ReadmeScreenshots).
                implementation(libs.ktor.client.okhttp)
                implementation(libs.compose.ui.test)
                implementation(compose.desktop.currentOs)
            }
        }
    }
}

// Screenshot renders: the real interface over a library of well-known games whose art Fuse fetches
// (libretro thumbnails and Art Book Next; SteamGridDB too when STEAMGRIDDB_API_KEY is set), rendered
// headless into build/readme-screenshots. The README and website use captures from a device
// (docs/assets/screenshots), so these never overwrite them unless asked with -Pfuse.screenshots.dir.
// Never part of desktopTest, check or CI; run it on purpose:
//     ./gradlew :ui:shell:desktopScreenshots [-Pfuse.screenshots.sources=STEAMGRIDDB,LIBRETRO]
val screenshotTests = "io.github.matiyaaa.fuse.ui.shell.screenshots.*"

// UI audit: every screen, overlay and state at the sizes Fuse runs at, rendered headless into a
// folder with a manifest.json. Never part of desktopTest, check or CI; run it on purpose:
//     ./gradlew :ui:shell:desktopAudit -Pfuse.audit.dir=/tmp/fuse-audit [-Pfuse.audit.only=home,library/all] [-Pfuse.audit.sizes=M,H] [-Pfuse.audit.theme=daylight]
val auditTests = "io.github.matiyaaa.fuse.ui.shell.audit.*"

// Frame cost of the interactions that must stay smooth (scrolling, tab and system runs), with a JFR
// profile. Never part of desktopTest, check or CI; run it on purpose and compare runs:
//     ./gradlew :ui:shell:desktopPerf -Pfuse.perf.dir=/tmp/fuse-perf [-Pfuse.perf.only=settings,storage]
val perfTests = "io.github.matiyaaa.fuse.ui.shell.perf.*"

tasks.named<Test>("desktopTest") {
    filter {
        excludeTestsMatching(screenshotTests)
        excludeTestsMatching(auditTests)
        excludeTestsMatching(perfTests)
    }
}

tasks.register<Test>("desktopScreenshots") {
    description = "Renders screenshots of the interface into build/readme-screenshots."
    group = "documentation"
    val desktopTest = tasks.named<Test>("desktopTest").get()
    testClassesDirs = desktopTest.testClassesDirs
    classpath = desktopTest.classpath
    filter { includeTestsMatching(screenshotTests) }
    systemProperty(
        "fuse.screenshots.dir",
        providers.gradleProperty("fuse.screenshots.dir")
            .getOrElse(layout.buildDirectory.dir("readme-screenshots").get().asFile.absolutePath),
    )
    systemProperty("fuse.screenshots.sources", providers.gradleProperty("fuse.screenshots.sources").getOrElse(""))
    // Filling the art and rendering every screen takes several minutes; the coroutine test default of one minute is too short.
    systemProperty("kotlinx.coroutines.test.default_timeout", "40m")
    maxHeapSize = "1g"
    testLogging { showStandardStreams = true }
    outputs.upToDateWhen { false }
}

tasks.register<Test>("desktopAudit") {
    description = "Renders every screen and state for a UI audit into -Pfuse.audit.dir, with a manifest.json."
    group = "verification"
    val desktopTest = tasks.named<Test>("desktopTest").get()
    testClassesDirs = desktopTest.testClassesDirs
    classpath = desktopTest.classpath
    filter { includeTestsMatching(auditTests) }
    // A relative folder is taken from the repository root.
    val dir: String? = providers.gradleProperty("fuse.audit.dir").orNull?.let { rootProject.file(it).absolutePath }
    doFirst { check(dir != null) { "Pass the output folder: -Pfuse.audit.dir=<folder>" } }
    systemProperty("fuse.audit.dir", dir.orEmpty())
    systemProperty("fuse.audit.only", providers.gradleProperty("fuse.audit.only").getOrElse(""))
    systemProperty("fuse.audit.sizes", providers.gradleProperty("fuse.audit.sizes").getOrElse(""))
    systemProperty("fuse.audit.theme", providers.gradleProperty("fuse.audit.theme").getOrElse(""))
    // Every screen at several sizes takes a while; the coroutine test default of one minute is too short.
    systemProperty("kotlinx.coroutines.test.default_timeout", "40m")
    maxHeapSize = "1536m"
    testLogging { showStandardStreams = true }
    outputs.upToDateWhen { false }
}

tasks.register<Test>("desktopPerf") {
    description = "Measures frame times of laggy-prone interactions into -Pfuse.perf.dir, with a JFR profile."
    group = "verification"
    val desktopTest = tasks.named<Test>("desktopTest").get()
    testClassesDirs = desktopTest.testClassesDirs
    classpath = desktopTest.classpath
    filter { includeTestsMatching(perfTests) }
    val dir: String? = providers.gradleProperty("fuse.perf.dir").orNull?.let { rootProject.file(it).absolutePath }
    doFirst { check(dir != null) { "Pass the output folder: -Pfuse.perf.dir=<folder>" } }
    systemProperty("fuse.perf.dir", dir.orEmpty())
    systemProperty("fuse.perf.only", providers.gradleProperty("fuse.perf.only").getOrElse(""))
    systemProperty("fuse.perf.lowPower", providers.gradleProperty("fuse.perf.lowPower").getOrElse(""))
    systemProperty("kotlinx.coroutines.test.default_timeout", "40m")
    maxHeapSize = "1536m"
    testLogging { showStandardStreams = true }
    outputs.upToDateWhen { false }
}
