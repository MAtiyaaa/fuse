package io.github.matiyaaa.fuse.sync

/**
 * Where one game's mods go for the emulator it starts in, found the way the save adapters find that
 * emulator's own folder (so a portable install, a Flatpak or a moved folder are found alike).
 *
 * [folder] is where the mod's files (or its own folder) go; [settingsFile] the game's own settings
 * file when the emulator needs a switch turned on for it (Dolphin); [gameId] the id the emulator
 * files the game by.
 */
data class ModHome(
    val emulator: String,
    val gameId: String,
    val folder: String,
    val settingsFile: String? = null,
    /** For Dolphin, its graphics mods' folder beside [folder] (its texture packs'). */
    val graphicsMods: String? = null,
)

/** Finds [ModHome]s (see [ModHome]); null when the emulator's folder or the game's id isn't known here. */
object ModFolders {
    private val HEX16 = Regex("^[0-9A-Fa-f]{16}$")

    /** Where [q]'s game's mods go for the emulator it starts in, or why that isn't known here (in a sentence). */
    fun locate(q: SaveQuery, env: SaveEnvironment): Result<ModHome> {
        val id = SaveAdapters.baseId(q.emulatorId)
        return when {
            id in Dolphin.emulators -> dolphin(q, env)
            id == "cemu" -> cemu(q, env)
            id in SwitchNand.emulators -> eden(q, env)
            id in Ryujinx.emulators -> ryujinx(q, env)
            else -> Result.failure(IllegalStateException("Fuse doesn't put mods in place for this emulator."))
        }
    }

    private fun fail(message: String) = Result.failure<ModHome>(IllegalStateException(message))

    private fun dolphin(q: SaveQuery, env: SaveEnvironment): Result<ModHome> {
        if (env.host == "ANDROID") return fail("Dolphin on Android keeps its folders where other apps can't add to them.")
        val user = Dolphin.userDir(env, q.emulatorPath) ?: return fail("Dolphin's folder wasn't found here. Start Dolphin once so it makes it.")
        val gameId = q.serial?.uppercase()?.takeIf { it.length == 6 && it.all(Char::isLetterOrDigit) }
            ?: return fail("Fuse doesn't know this game's id yet. Dolphin files texture packs and mods under it (like GALE01).")
        return Result.success(ModHome("dolphin", gameId, "$user/Load/Textures", "$user/GameSettings/$gameId.ini", "$user/Load/GraphicMods"))
    }

    private fun cemu(q: SaveQuery, env: SaveEnvironment): Result<ModHome> {
        if (env.host == "ANDROID") return fail("Cemu on Android keeps its folders where other apps can't add to them.")
        val user = Cemu.folders(env, q.emulatorPath)?.first ?: return fail("Cemu's folder wasn't found here. Start Cemu once so it makes it.")
        val gameId = Cemu.titleId(q, env) ?: ""
        return Result.success(ModHome("cemu", gameId, "$user/graphicPacks"))
    }

    private fun eden(q: SaveQuery, env: SaveEnvironment): Result<ModHome> {
        if (env.host == "ANDROID") return fail("Switch emulators on Android keep their mod folders where other apps can't add to them. Add mods from the emulator's own Add-ons screen.")
        val base = SwitchNand.home(env, q) ?: return fail("The Switch emulator's folder wasn't found here. Start it once so it makes it.")
        val id = SwitchIds.of(q, env) ?: return fail("Fuse doesn't know this game's title id yet (a [0100…] tag in its file name tells it).")
        val load = SwitchNand.loadFolder(env, q, base)
        return Result.success(ModHome(SaveAdapters.baseId(q.emulatorId), id.uppercase(), "$load/${id.uppercase()}"))
    }

    private fun ryujinx(q: SaveQuery, env: SaveEnvironment): Result<ModHome> {
        if (env.host == "ANDROID") return fail("Ryujinx's Android builds keep their mod folders where other apps can't add to them.")
        val base = Ryujinx.home(env, q) ?: return fail("Ryujinx's folder wasn't found here. Start it once so it makes it.")
        val id = SwitchIds.of(q, env) ?: return fail("Fuse doesn't know this game's title id yet (a [0100…] tag in its file name tells it).")
        // Ryujinx finds a game's folder by a name starting with its id; one already there is used as it is named.
        val contents = "$base/mods/contents"
        val existing = env.list(contents).firstOrNull { it.length >= 16 && it.take(16).equals(id, ignoreCase = true) && HEX16.matches(it.take(16)) }
        return Result.success(ModHome("ryujinx", id.uppercase(), "$contents/${existing ?: id.lowercase()}"))
    }
}
