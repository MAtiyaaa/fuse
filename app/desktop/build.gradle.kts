import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("fuse.desktop.application")
}

dependencies {
    implementation(projects.ui.shell)
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.ktor.client.cio)
    implementation(libs.coil.compose)
    implementation(libs.coil.svg)
    // MP3 decoding for menu music (LGPL-2.1-or-later, see THIRD_PARTY_NOTICES.md).
    implementation(libs.jlayer)
    testImplementation(kotlin("test"))
}

val fuseVersion: String = providers.gradleProperty("fuse.version").getOrElse("0.0.0")

/** Writes BuildInfo.kt so the running app knows its version without reading gradle.properties. */
abstract class GenerateBuildInfo : DefaultTask() {
    @get:Input abstract val appVersion: Property<String>
    @get:Input abstract val releaseName: Property<String>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        fun literal(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$") + "\""
        val dir = outputDir.get().asFile.resolve("io/github/matiyaaa/fuse/desktop")
        dir.mkdirs()
        dir.resolve("BuildInfo.kt").writeText(
            """
            |package io.github.matiyaaa.fuse.desktop
            |
            |/** Generated from gradle.properties by the generateBuildInfo task. Do not edit. */
            |object BuildInfo {
            |    const val VERSION: String = ${literal(appVersion.get())}
            |    const val RELEASE_NAME: String = ${literal(releaseName.get())}
            |}
            |
            """.trimMargin(),
        )
    }
}

val generateBuildInfo = tasks.register<GenerateBuildInfo>("generateBuildInfo") {
    appVersion.set(providers.gradleProperty("fuse.version").orElse("0.0.0"))
    releaseName.set(providers.gradleProperty("fuse.releaseName").orElse(""))
    outputDir.set(layout.buildDirectory.dir("generated/buildinfo/kotlin"))
}

kotlin {
    sourceSets.named("main") { kotlin.srcDir(generateBuildInfo) }
}

compose.desktop {
    application {
        mainClass = "io.github.matiyaaa.fuse.desktop.MainKt"
        // Lets Main set the X11 WM_CLASS to "fuse" so desktops match the window to fuse.desktop.
        jvmArgs += listOf("--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED", "-Dfile.encoding=UTF-8")

        buildTypes.release.proguard {
            isEnabled.set(false)
        }

        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.AppImage)
            packageName = "fuse"
            packageVersion = fuseVersion
            description = "Controller-first game launcher"
            vendor = "Fuse"
            // SQLite JDBC (java.sql), coroutines and Skiko (jdk.unsupported), Ktor DNS and TLS
            // (java.naming, jdk.crypto.ec), restart and memory stats (java.management).
            modules("java.sql", "jdk.unsupported", "java.naming", "jdk.crypto.ec", "java.management")
            linux {
                // The Debian package "fuse" is the FUSE filesystem tools; never shadow it.
                packageName = "fuse-launcher"
                iconFile.set(project.file("packaging/fuse.png"))
                menuGroup = "Game"
                appCategory = "games"
                shortcut = true
            }
        }
    }
}
