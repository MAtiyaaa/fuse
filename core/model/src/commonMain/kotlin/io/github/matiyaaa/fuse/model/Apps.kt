package io.github.matiyaaa.fuse.model

import kotlinx.serialization.Serializable

/**
 * A launchable application: an Android app from the launcher APIs, or a Linux .desktop entry.
 */
@Serializable
data class AppEntry(
    /** Android: package/activity. Linux: the .desktop file id. */
    val id: String,
    val label: String,
    val packageName: String,
    val isGame: Boolean,
    val customTitle: String? = null,
    val pinned: Boolean = false,
    val hidden: Boolean = false,
    val installedAt: Long? = null,
    val lastUsedAt: Long? = null,
    /** What the user says the app is ("Type" in its options); null lets Fuse decide. */
    val chosenKind: AppKind? = null,
    /** What Fuse decided by itself: an emulator it detected, else a game when the app says it is one. */
    val detectedKind: AppKind = if (isGame) AppKind.GAME else AppKind.APP,
) {
    val displayTitle: String get() = customTitle ?: label

    /** What the app is: the user's choice, else Fuse's. Games show in the Android system ([AppGames]). */
    val kind: AppKind get() = chosenKind ?: detectedKind
}

/**
 * What an installed app is to Fuse. Streaming apps (game streaming from a PC or console) and tools
 * (frontends, drivers, utilities for emulation) have lists of their own in Apps.
 */
@Serializable
enum class AppKind { GAME, APP, EMULATOR, STREAMING, TOOL }

@Serializable
enum class AppFilter { PINNED, GAMES, EMULATORS, STREAMING, TOOLS, ALL }

/**
 * Apps from the Store's catalogue (the Obtainium Emulation Pack) that aren't emulators Fuse launches
 * games with, by package: game streaming, and the frontends and tools around emulation. Emulators are
 * known from the launch catalog instead.
 */
object KnownApps {
    private val streaming = setOf("com.limelight", "com.limelight.noir")

    private val tools = setOf(
        // Frontends
        "com.nendo.argosy", "dev.cannoli.scorza", "rip.moth.cocoonshell", "com.k2.consolelauncher",
        "com.magneticchen.daijishou", "com.neogamelab.neostation", "org.pegasus_frontend.android",
        "com.retrohrai.launcher", "com.iisulauncher", "org.es_de.frontend",
        // Ports and other launchers
        "org.force9.starboard",
        // Utilities
        "com.moonbench.bifrost", "it.ottaviomiele.chd", "com.aure.clustertune", "com.quantumsoul.esde_android",
        "com.esde.companion", "com.producdevity.emureadylite", "com.emulnk", "jr.brian.home", "pup.app.mimir",
        "xyz.blacksheep.mjolnir", "local.nova.diagnostic", "de.langerhans.odintools", "app.nanostack.pixelguide",
        "com.kei.pulse", "com.raofflineproxy", "com.med.sleepmanager", "com.github.catfriend1.syncthingfork",
        "dev.imranr.obtainium", "dev.imranr.obtainium.fdroid",
    )

    /** What the app with [packageName] is, when Fuse knows it; null otherwise. */
    fun kindOf(packageName: String): AppKind? = when (packageName) {
        in streaming -> AppKind.STREAMING
        in tools -> AppKind.TOOL
        else -> null
    }
}

/**
 * Installed apps played as games: library games in the Android system, started as the app. They are
 * filed under a source and folder that are not on disk, so scans never touch them.
 */
object AppGames {
    val SOURCE = LibrarySourceId(0)
    const val FOLDER = "app:"
    val PLATFORM = PlatformId("android")

    /** The library path of the app with this id (`package/activity`). */
    fun path(appId: String): String = FOLDER + appId

    /** The app id of a library path, or null when the path is a file. */
    fun appId(path: String): String? = path.takeIf { it.startsWith(FOLDER) }?.removePrefix(FOLDER)?.takeIf { it.isNotEmpty() }
}

/**
 * Game files the user added one by one ("Add a game" in Settings), from anywhere on the device. They
 * keep their own path; a scan of a library folder that holds the same file takes it over.
 */
object AddedGames {
    val SOURCE = LibrarySourceId(0)
    const val FOLDER = "added:"
}
