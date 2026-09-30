package io.github.matiyaaa.fuse.launch

import io.github.matiyaaa.fuse.model.GameLocation
import io.github.matiyaaa.fuse.model.LocationKind

/**
 * Small text files whose content, not path, starts the game (ES-DE `%INJECT%`): Steam/store ids for
 * GameNative and GameHub Lite, title ids for Vita3K/aPS3e/ARMSX3/BachataS4, `package[/activity]` for
 * native Android apps, ScummVM game ids, and freedesktop `.desktop` shortcuts on Linux.
 *
 * The caller reads the file (at most [MAX_BYTES], like ES-DE) and passes it as
 * [LaunchOptions.injectedText] / [ScopedLaunchChoice.injectedText].
 */
object IdFiles {
    /** ES-DE injects at most 4096 bytes. */
    const val MAX_BYTES = 4096

    /** GameNative / ES-DE store id files and the `game_source` each stands for. */
    val storeSources: Map<String, String> = mapOf(
        "steam" to "STEAM",
        "epic" to "EPIC",
        "gog" to "GOG",
        "amazon" to "AMAZON",
        "pcgame" to "CUSTOM_GAME",
    )

    /** Every id-file extension any adapter reads. */
    val extensions: Set<String> = storeSources.keys + setOf("psvita", "ps3", "ps4", "app", "scummvm", "desktop")

    /** True when [location] is a file whose content the caller should read before resolving. */
    fun needsContent(location: GameLocation): Boolean =
        location.kind == LocationKind.FILE && Paths.extension(location.launchPath) in extensions
}
