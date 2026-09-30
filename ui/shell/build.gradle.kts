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
            implementation(libs.jb.lifecycle.runtime.compose)
            implementation(libs.kotlinx.datetime)
        }
        getByName("desktopTest") {
            dependencies {
                implementation(libs.ktor.client.mock)
                implementation(libs.compose.ui.test)
                implementation(compose.desktop.currentOs)
            }
        }
    }
}

// README screenshots: the real interface over a sample library of invented games, rendered headless
// into docs/assets/screenshots. Never part of desktopTest, check or CI; run it on purpose:
//     ./gradlew :ui:shell:desktopScreenshots
val screenshotTests = "io.github.matiyaaa.fuse.ui.shell.screenshots.*"

tasks.named<Test>("desktopTest") {
    filter { excludeTestsMatching(screenshotTests) }
}

tasks.register<Test>("desktopScreenshots") {
    description = "Renders the README screenshots into docs/assets/screenshots."
    group = "documentation"
    val desktopTest = tasks.named<Test>("desktopTest").get()
    testClassesDirs = desktopTest.testClassesDirs
    classpath = desktopTest.classpath
    filter { includeTestsMatching(screenshotTests) }
    systemProperty(
        "fuse.screenshots.dir",
        providers.gradleProperty("fuse.screenshots.dir")
            .getOrElse(rootProject.layout.projectDirectory.dir("docs/assets/screenshots").asFile.absolutePath),
    )
    // Rendering every screen takes a few minutes; the coroutine test default of one minute is too short.
    systemProperty("kotlinx.coroutines.test.default_timeout", "20m")
    maxHeapSize = "1g"
    testLogging { showStandardStreams = true }
    outputs.upToDateWhen { false }
}
