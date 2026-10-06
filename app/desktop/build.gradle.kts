import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("fuse.desktop.application")
}

/**
 * The system this build packages for. Each package is built on its own system (jpackage can't cross
 * build), so Windows and macOS only change what their own builds carry; the Linux AppImage and deb
 * stay exactly as they were.
 */
val packagingOs: String = System.getProperty("os.name").lowercase().let {
    when {
        it.startsWith("windows") -> "windows"
        it.startsWith("mac") -> "macos"
        else -> "linux"
    }
}

dependencies {
    implementation(projects.ui.shell)
    implementation(projects.ui.link)
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.ktor.client.cio)
    implementation(libs.coil.compose)
    implementation(libs.coil.svg)
    // MP3 decoding for menu music (LGPL-2.1-or-later, see THIRD_PARTY_NOTICES.md).
    implementation(libs.jlayer)
    // Recordings: the FFmpeg program Fuse Player already carries encodes them (see DesktopScreenCapture).
    implementation(libs.ffmpeg)
    implementation(libs.javacpp)
    // Controllers on Windows and macOS through SDL2 (Apache-2.0; SDL2 is zlib). Linux reads
    // /dev/input itself, so only the Windows and macOS builds carry it.
    compileOnly(libs.jamepad)
    if (packagingOs != "linux") implementation(libs.jamepad)
    testImplementation(kotlin("test"))
    testRuntimeOnly(libs.jamepad)
}

val fuseVersion: String = providers.gradleProperty("fuse.version").getOrElse("0.0.0")

/**
 * The version Windows Installer and macOS read. Both refuse a leading 0 (macOS) or compare only
 * numbers (MSI upgrades), so 0.1.0 is packaged as 1.1.0, and 1.0.0 later as 2.0.0: always rising.
 * Fuse itself still shows [fuseVersion].
 */
val installerVersion: String = fuseVersion.substringBefore('-').split('.').let { parts ->
    val n = parts.map { it.toIntOrNull() ?: 0 } + listOf(0, 0, 0)
    "${n[0] + 1}.${n[1]}.${n[2]}"
}

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
        jvmArgs += "-Dfile.encoding=UTF-8"
        // Lets Main set the X11 WM_CLASS to "fuse" so desktops match the window to fuse.desktop.
        if (packagingOs == "linux") jvmArgs += "--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED"

        buildTypes.release.proguard {
            isEnabled.set(false)
        }

        nativeDistributions {
            // Each system builds its own formats: Deb and AppImage on Linux, Msi on Windows, Dmg on macOS.
            targetFormats(TargetFormat.Deb, TargetFormat.AppImage, TargetFormat.Msi, TargetFormat.Dmg)
            // "fuse" on Linux (bin/fuse in the AppImage), "Fuse" for Fuse.exe and Fuse.app.
            packageName = if (packagingOs == "linux") "fuse" else "Fuse"
            packageVersion = if (packagingOs == "linux") fuseVersion else installerVersion
            description = "Controller-first game launcher"
            vendor = "Fuse"
            // SQLite JDBC (java.sql), coroutines and Skiko (jdk.unsupported), Ktor DNS and TLS
            // (java.naming, jdk.crypto.ec), restart and memory stats (java.management).
            modules("java.sql", "jdk.unsupported", "java.naming", "jdk.crypto.ec", "java.management")
            // CPU and memory numbers on Windows and macOS (com.sun.management).
            if (packagingOs != "linux") modules("jdk.management")
            linux {
                // The Debian package "fuse" is the FUSE filesystem tools; never shadow it.
                packageName = "fuse-launcher"
                iconFile.set(project.file("packaging/fuse.png"))
                menuGroup = "Game"
                appCategory = "games"
                shortcut = true
            }
            windows {
                iconFile.set(project.file("packaging/fuse.ico"))
                // Installs for the signed-in user only, under %LOCALAPPDATA%\Programs\Fuse: no
                // administrator prompt, and Fuse's own data (%LOCALAPPDATA%\Fuse) stays separate.
                perUserInstall = true
                installationPath = "Programs\\Fuse"
                dirChooser = true
                menu = true
                menuGroup = "Fuse"
                shortcut = true
                // Never change: Windows Installer finds and replaces older Fuse installs by it.
                upgradeUuid = "6f2c9a4e-1b7d-4d83-9e55-0c7a3f8b2d61"
                msiPackageVersion = installerVersion
            }
            macOS {
                iconFile.set(project.file("packaging/fuse.icns"))
                bundleID = "io.github.matiyaaa.fuse"
                appCategory = "public.app-category.games"
                dockName = "Fuse"
                // Big Sur was the first macOS on Apple silicon; Intel Macs go back to Catalina, which the
                // bundled Java runtime and Compose still support.
                minimumSystemVersion = if (System.getProperty("os.arch") == "aarch64") "11.0" else "10.15"
                packageVersion = installerVersion
                packageBuildVersion = installerVersion
                dmgPackageVersion = installerVersion
                dmgPackageBuildVersion = installerVersion
                infoPlist {
                    extraKeysRawXml = """
                        |    <key>NSLocalNetworkUsageDescription</key>
                        |    <string>Phone Link lets your phone open your library on the same Wi-Fi.</string>
                        |    <key>NSDocumentsFolderUsageDescription</key>
                        |    <string>Fuse reads your games and their art from folders you choose.</string>
                        |    <key>NSDownloadsFolderUsageDescription</key>
                        |    <string>Fuse reads your games and their art from folders you choose.</string>
                        |    <key>NSDesktopFolderUsageDescription</key>
                        |    <string>Fuse reads your games and their art from folders you choose.</string>
                        |    <key>NSRemovableVolumesUsageDescription</key>
                        |    <string>Fuse reads games from drives and SD cards you choose.</string>
                        |    <key>NSNetworkVolumesUsageDescription</key>
                        |    <string>Fuse reads games from network folders you choose.</string>
                        |    <key>NSSupportsAutomaticGraphicsSwitching</key>
                        |    <true/>
                    """.trimMargin()
                }
            }
        }
    }
}
