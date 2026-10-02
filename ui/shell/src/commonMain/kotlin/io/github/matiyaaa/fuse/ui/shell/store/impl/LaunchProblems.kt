package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.github.matiyaaa.fuse.library.FsPath
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.InstalledEmulator
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.model.SourceState
import io.github.matiyaaa.fuse.ui.shell.store.Problem
import io.github.matiyaaa.fuse.ui.shell.store.ProblemAction
import io.github.matiyaaa.fuse.ui.shell.store.ProblemKind
import io.github.matiyaaa.fuse.ui.shell.store.Severity

/**
 * Launch failures as [Problem]s: what failed, why it probably did, that nothing was harmed, and what
 * to do. The system's own words go to [Problem.details], never to the headline.
 */
internal object LaunchProblems {
    fun unavailable(root: OfflineRoot, gameTitle: String, lastSeen: String?): Problem = when (root.state) {
        SourceState.NO_ACCESS -> Problem(
            title = "Fuse can't read ${root.driveLabel}",
            message = "$gameTitle is on ${root.driveLabel}, which Fuse no longer has permission to read. Allow access again and it will be back.",
            kind = ProblemKind.ACCESS,
            actions = listOf(ProblemAction.GrantAccess(), ProblemAction.OpenStorage()),
            details = "Library folder: ${root.path}",
        )
        SourceState.FOLDER_MISSING, SourceState.MOVED -> Problem(
            title = "Library folder not found",
            message = "$gameTitle is in a library folder Fuse can't find on ${root.driveLabel} any more. It may have been renamed or moved. Your games stay in Fuse until you decide.",
            kind = ProblemKind.FILE,
            actions = listOf(ProblemAction.CheckDrives("Look again"), ProblemAction.OpenSettings("library", "Library folders")),
            details = "Library folder: ${root.path}",
        )
        SourceState.OTHER_DRIVE -> Problem(
            title = "A different drive is connected",
            message = "$gameTitle is stored on ${root.driveLabel}, but another drive is connected in its place. Connect ${root.driveLabel}, or tell Fuse this drive holds the same library.",
            kind = ProblemKind.DRIVE,
            actions = listOf(ProblemAction.CheckDrives("Check drives again"), ProblemAction.OpenStorage()),
            details = "Library folder: ${root.path}",
        )
        else -> Problem(
            title = "${root.driveLabel} unavailable",
            message = "$gameTitle is stored on ${root.driveLabel}. Connect it and try again." +
                (lastSeen?.let { " Last seen $it." } ?: ""),
            kind = ProblemKind.DRIVE,
            severity = Severity.INFO,
            reassurance = "Your games, art and play time are kept. They come back as they were when the drive does.",
            actions = listOf(ProblemAction.Retry(), ProblemAction.OpenStorage()),
            details = "Library folder: ${root.path}",
        )
    }

    fun fileMissing(gameTitle: String, path: String): Problem = Problem(
        title = "$gameTitle wasn't found",
        message = "Fuse couldn't find ${FsPath.name(path)}. It may have been moved, renamed or deleted since the last scan.",
        kind = ProblemKind.FILE,
        actions = listOf(ProblemAction.Rescan(), ProblemAction.Retry()),
        details = "Expected at: $path",
    )

    fun noEmulator(platformName: String, platform: PlatformId, suggestions: List<Pair<String, String?>>): Problem = Problem(
        title = "No emulator for $platformName",
        message = if (suggestions.isEmpty()) {
            "Install an emulator for $platformName, then come back. Fuse notices new emulators by itself."
        } else {
            "Install ${suggestions.joinToString(", ", limit = 3) { it.first }}, then come back. Fuse notices new emulators by itself."
        },
        kind = ProblemKind.EMULATOR,
        reassurance = null,
        actions = suggestions.mapNotNull { (name, url) -> url?.let { ProblemAction.OpenLink(it, "Get $name") } } +
            ProblemAction.OpenSystem(platform, "$platformName settings"),
    )

    fun emulatorGone(emulator: InstalledEmulator, game: GameId): Problem = Problem(
        title = "${emulator.name} isn't installed any more",
        message = "This game is set to open in ${emulator.name}, which isn't on this device now. Choose another emulator, or reinstall it.",
        kind = ProblemKind.EMULATOR,
        actions = listOf(ProblemAction.PickEmulator(game = game)),
    )

    fun unsupported(reason: String, game: GameId): Problem = Problem(
        title = "This game can't be started from Fuse",
        message = reason,
        kind = ProblemKind.EMULATOR,
        actions = listOf(ProblemAction.PickEmulator(game = game)),
    )

    /**
     * A launch the system or emulator refused. The words the host reported are already plain, so
     * they are the explanation; a refused file on Android usually means the emulator lacks access to
     * the folder, which is said outright.
     */
    fun refused(emulator: InstalledEmulator, game: GameId, message: String, android: Boolean): Problem {
        val access = android && (message.contains("didn't let", ignoreCase = true) || message.contains("permission", ignoreCase = true))
        return Problem(
            title = "Couldn't start ${emulator.name}",
            message = if (access) {
                "Android didn't let Fuse hand this game to ${emulator.name}. This usually means the game's folder hasn't been allowed inside ${emulator.name}: open it and add the folder there."
            } else {
                message
            },
            kind = if (access) ProblemKind.ACCESS else ProblemKind.EMULATOR,
            actions = listOf(
                ProblemAction.Retry(),
                ProblemAction.OpenEmulator(emulator.id, emulator.name),
                ProblemAction.PickEmulator(game = game, label = "Try another emulator"),
            ),
            details = "${emulator.name} (${emulator.appId}): $message",
        )
    }

    fun gone(): Problem = Problem(
        title = "This game is no longer in your library",
        message = "It was removed or forgotten while this screen was open.",
        kind = ProblemKind.DATA,
        reassurance = null,
    )

    fun unknownSystem(platform: PlatformId): Problem = Problem(
        title = "Fuse doesn't know this system",
        message = "The game is filed under \"${platform.value}\", which this version of Fuse can't launch. Move it to another system from its options.",
        kind = ProblemKind.DATA,
        reassurance = null,
    )
}
