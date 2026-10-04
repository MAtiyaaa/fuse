// Fuse Player: Fuse's own video and music player, for any provider (Jellyfin first). The controls,
// subtitles and session are shared; each platform brings an engine: Media3 on Android, FFmpeg
// (through JavaCPP) on the desktop. See docs/player.md.
plugins {
    id("fuse.kmp.compose")
}

/** The FFmpeg and JavaCPP natives for the system this build runs on (the desktop app packages one). */
val nativeClassifier: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val cpu = if (arch == "aarch64" || arch == "arm64") "arm64" else "x86_64"
    when {
        "win" in os -> "windows-$cpu"
        "mac" in os -> "macosx-$cpu"
        else -> "linux-$cpu"
    }
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.playback)
            api(projects.ui.designsystem)
        }
        androidMain.dependencies {
            implementation(libs.media3.exoplayer)
            implementation(libs.media3.exoplayer.hls)
        }
        getByName("desktopMain").dependencies {
            implementation(libs.ffmpeg)
            implementation(libs.javacpp)
            runtimeOnly("org.bytedeco:ffmpeg:${libs.versions.ffmpeg.get()}:$nativeClassifier-gpl")
            runtimeOnly("org.bytedeco:javacpp:${libs.versions.javacpp.get()}:$nativeClassifier")
        }
        getByName("desktopTest").dependencies {
            implementation(libs.compose.ui.test)
            implementation(compose.desktop.currentOs)
            // The live Jellyfin check (LiveJellyfinCheck): only when its environment is set.
            implementation(projects.core.jellyfin)
            implementation(libs.ktor.client.cio)
        }
    }
}

// Renders of the player for looking at (PlayerRenders): -Pfuse.player.renders=<dir>.
// The live Jellyfin check reads FUSE_JF_URL, FUSE_JF_USER and FUSE_JF_PASS from the environment.
tasks.withType<Test>().configureEach {
    providers.gradleProperty("fuse.player.renders").orNull?.let { systemProperty("fuse.player.renders", it) }
    outputs.upToDateWhen { false }
}
