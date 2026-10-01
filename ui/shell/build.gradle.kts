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
//     ./gradlew :ui:shell:desktopAudit -Pfuse.audit.dir=/tmp/fuse-audit [-Pfuse.audit.only=home,library/all] [-Pfuse.audit.sizes=M,H]
val auditTests = "io.github.matiyaaa.fuse.ui.shell.audit.*"

tasks.named<Test>("desktopTest") {
    filter {
        excludeTestsMatching(screenshotTests)
        excludeTestsMatching(auditTests)
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
    // Every screen at several sizes takes a while; the coroutine test default of one minute is too short.
    systemProperty("kotlinx.coroutines.test.default_timeout", "40m")
    maxHeapSize = "1536m"
    testLogging { showStandardStreams = true }
    outputs.upToDateWhen { false }
}
