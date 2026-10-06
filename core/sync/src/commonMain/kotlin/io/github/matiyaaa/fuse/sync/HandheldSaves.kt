package io.github.matiyaaa.fuse.sync

/**
 * The handheld emulators that keep a battery save as one file named after the game. They used to
 * share one rule ("beside the game"), which is only true for some of them, some of the time. Each
 * now says where its save really is, read from its own source code where that is public:
 *
 * - melonDS (computer): beside the game, unless `SaveFilePath` is set for the instance in
 *   `melonDS.toml` (EmuInstance::getAssetPath). The config lives in a `portable` folder beside the
 *   program when there is one, else in the system's config folder (main.cpp pathInit).
 * - melonDS on Android, its Nightly and WatermelonDS: beside the game only when the game is in the
 *   app's own ROM list. A game opened from another app that isn't in that list gets its save in the
 *   app's private folder, `Android/data/<package>/files/saves`, which no other app can open
 *   (SharedPreferencesSettingsRepository.getSaveFileDirectory, FileSystemRomsRepository.getRomAtUri).
 * - NooDS: beside the game, or in `saves` in its settings folder when "Separate Saves Folder" is on
 *   (cartridge.cpp setRom, settings.cpp `savesFolder`).
 * - mGBA (computer): beside the game, unless `savegamePath` is set in its `config.ini`
 *   (directories.c mDirectorySetMapOptions), relative paths meaning its config folder.
 * - SkyEmu: beside the game, or in its chosen save folder when the save is already there or
 *   "save to path" is on (main.c se_load_rom).
 * - Mednafen: never beside the game. `sav/<name>.<md5 of the game>.sav` in its home folder, and for
 *   the Game Boy Advance an `.eep` (EEPROM) and `.rtc` beside it. Mednafen's own layout, so these
 *   only ever sync between Mednafen installs.
 * - My Boy! and My OldBoy!: their own `MyBoy/save` and `MyOldBoy/save` folders on the device's
 *   storage by default, or beside the game when the person turned that on.
 * - Pizza Boy, Linkboy, SeedlessDS: closed source, no published layout. Fuse uses a save it finds
 *   beside the game, and otherwise says plainly that it is guessing.
 *
 * Every adapter first looks for a save that is already there ([SaveConfidence.FOUND]); only when
 * there is none does it name where the emulator would make one, and how sure that is.
 */
internal object HandheldSaves {
    fun spot(q: SaveQuery, path: String, confidence: SaveConfidence, note: String? = null, format: String = SaveAdapters.sramFormat(q.platform)) =
        SaveSpot(SaveKind.SAVE, format, listOf(SpotFile("save.srm", path)), confidence = confidence, note = note)

    /** The first of [dirs] that already holds [name], found; else the first, as [otherwise] says. */
    fun pick(env: SaveEnvironment, dirs: List<String>, name: String, otherwise: SaveConfidence): Pair<String, SaveConfidence> {
        dirs.firstOrNull { env.exists("$it/$name") }?.let { return "$it/$name" to SaveConfidence.FOUND }
        return "${dirs.first()}/$name" to otherwise
    }

    /** `key = "value"` from [section] of a small TOML or INI file (melonDS, mGBA); [section] null means before any. */
    fun setting(text: String?, section: String?, key: String): String? {
        if (text == null) return null
        var current: String? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("#") || line.startsWith(";") || line.isEmpty()) continue
            if (line.startsWith("[") && line.endsWith("]")) {
                current = line.substring(1, line.length - 1).trim()
                continue
            }
            if (current != section) continue
            val i = line.indexOf('=')
            if (i <= 0 || line.substring(0, i).trim() != key) continue
            return line.substring(i + 1).trim().trim('"').replace("\\\\", "\\").replace('\\', '/')
        }
        return null
    }

    fun isArchive(path: String) = path.substringAfterLast('.').lowercase() in setOf("zip", "7z", "rar")

    /** What to say about a game kept zipped: some emulators name the save after the file inside. */
    const val ZIPPED = "This game is zipped: melonDS names its save after the game inside the zip. Fuse uses a save it finds; if none is found, play it once and save."
}

/** How sure Fuse is that a save belongs where an adapter says. */
enum class SaveConfidence {
    /** A save is there already. */
    FOUND,

    /** Where the emulator puts it, from its own code and settings. */
    KNOWN,

    /** Where it most likely goes; the emulator doesn't publish its layout. */
    GUESS,
}

/** melonDS on a computer: beside the game, or in the folder its config names. */
internal object MelonDsDesktop {
    fun configs(env: SaveEnvironment, emuPath: String?): List<String> {
        val beside = emuPath?.let { SaveAdapters.parent(it) }
        // A Mac app's portable folder sits beside the .app, three levels up from the program.
        val macBundle = emuPath?.substringBefore(".app/", "")?.takeIf { it.isNotEmpty() }?.let { SaveAdapters.parent(it) }
        return listOfNotNull(
            beside?.let { "$it/portable/melonDS.toml" },
            macBundle?.let { "$it/portable/melonDS.toml" },
        ) + when (env.host) {
            "WINDOWS" -> listOfNotNull(beside?.let { "$it/melonDS.toml" }, "${env.env("LOCALAPPDATA")?.replace('\\', '/') ?: "${env.home}/AppData/Local"}/melonDS/melonDS.toml")
            "MACOS" -> listOf("${env.home}/Library/Preferences/melonDS/melonDS.toml")
            else -> listOf("${SaveAdapters.xdgConfig(env)}/melonDS/melonDS.toml", "${env.home}/.var/app/net.kuribo64.melonDS/config/melonDS/melonDS.toml")
        }
    }

    fun locate(q: SaveQuery, env: SaveEnvironment): List<SaveSpot> {
        val name = SaveAdapters.stem(q.romPath) + ".sav"
        SaveAdapters.chosen(env, q)?.let { return listOf(HandheldSaves.spot(q, "$it/$name", SaveConfidence.KNOWN)) }
        val config = configs(env, q.emulatorPath).firstOrNull(env::exists)
        val set = HandheldSaves.setting(config?.let { env.readText(it) }, "Instance0", "SaveFilePath")?.takeIf { it.isNotBlank() }?.trimEnd('/')
            ?.let { if (it.startsWith("/") || (it.length > 1 && it[1] == ':')) it else config?.let { c -> "${SaveAdapters.parent(c)}/$it" } ?: it }
        val game = SaveAdapters.parent(q.romPath)
        val dirs = listOfNotNull(set, game).distinct()
        val (path, confidence) = HandheldSaves.pick(env, dirs, name, SaveConfidence.KNOWN)
        return listOf(HandheldSaves.spot(q, path, confidence, HandheldSaves.ZIPPED.takeIf { HandheldSaves.isArchive(q.romPath) && confidence != SaveConfidence.FOUND }))
    }
}

/** melonDS for Android (and its Nightly and WatermelonDS builds): beside the game only when it is in the app's ROM list. */
internal object MelonDsAndroid {
    private val packages = mapOf("melonds" to "me.magnum.melonds", "melonds-nightly" to "me.magnum.melonds.nightly", "watermelonds" to "me.magnum.melondualds")

    fun locate(q: SaveQuery, env: SaveEnvironment): List<SaveSpot> {
        val name = SaveAdapters.stem(q.romPath) + ".sav"
        SaveAdapters.chosen(env, q)?.let { return listOf(HandheldSaves.spot(q, "$it/$name", SaveConfidence.KNOWN)) }
        val pkg = packages[SaveAdapters.baseId(q.emulatorId)] ?: "me.magnum.melonds"
        val private = "/storage/emulated/0/Android/data/$pkg/files/saves"
        val game = SaveAdapters.parent(q.romPath)
        if (env.exists("$game/$name")) return listOf(HandheldSaves.spot(q, "$game/$name", SaveConfidence.FOUND))
        // Readable only on older Android or with special access; when it holds the save, it is the one.
        if (env.exists("$private/$name")) return listOf(HandheldSaves.spot(q, "$private/$name", SaveConfidence.FOUND))
        val name0 = packageName(pkg)
        return listOf(
            HandheldSaves.spot(
                q, "$game/$name", SaveConfidence.KNOWN,
                note = "$name0 saves beside the game only when the game's folder is in $name0's own ROM folders. " +
                    "Add your DS folder there (in $name0, Settings, ROM search folders); otherwise it keeps the save in its private folder, which Fuse can't open.",
            ),
        )
    }

    private fun packageName(pkg: String) = when (pkg) {
        "me.magnum.melondualds" -> "WatermelonDS"
        "me.magnum.melonds.nightly" -> "melonDS Nightly"
        else -> "melonDS"
    }
}

/** NooDS: beside the game, or in `saves` in its settings folder when "Separate Saves Folder" is on. */
internal object NooDs : SaveAdapter {
    override val emulators = setOf("noods")

    override fun locate(q: SaveQuery, env: SaveEnvironment): List<SaveSpot> {
        val name = SaveAdapters.stem(q.romPath) + ".sav"
        SaveAdapters.chosen(env, q)?.let { return listOf(HandheldSaves.spot(q, "$it/$name", SaveConfidence.KNOWN)) }
        val settings = when (env.host) {
            "ANDROID" -> listOf("/storage/emulated/0/Android/data/com.hydra.noods/files")
            "WINDOWS" -> listOfNotNull(q.emulatorPath?.let { SaveAdapters.parent(it) }, "${SaveAdapters.appData(env)}/NooDS")
            "MACOS" -> listOf("${SaveAdapters.macSupport(env)}/NooDS")
            else -> listOf("${SaveAdapters.xdgConfig(env)}/noods", "${env.home}/.var/app/io.github.hydr8gon.NooDS/config/noods")
        }
        val ini = settings.map { "$it/noods.ini" }.firstOrNull(env::exists)
        val separate = HandheldSaves.setting(ini?.let { env.readText(it) }, null, "savesFolder") == "1"
        val game = SaveAdapters.parent(q.romPath)
        val dirs = if (separate && ini != null) listOf("${SaveAdapters.parent(ini)}/saves", game) else listOf(game)
        val (path, confidence) = HandheldSaves.pick(env, dirs, name, SaveConfidence.KNOWN)
        if (env.host == "ANDROID" && SaveAdapters.androidPrivate(path) && confidence != SaveConfidence.FOUND) {
            return listOf(SaveAdapters.unavailable(SaveKind.SAVE, SaveAdapters.sramFormat(q.platform), SaveAdapters.androidPrivateNote("NooDS", "Turn off Separate Saves Folder in NooDS so it saves beside the game.")))
        }
        return listOf(HandheldSaves.spot(q, path, confidence))
    }
}

/** mGBA on a computer: beside the game, or the folder its config names. */
internal object MgbaDesktop {
    fun locate(q: SaveQuery, env: SaveEnvironment): List<SaveSpot> {
        val name = SaveAdapters.stem(q.romPath) + ".sav"
        SaveAdapters.chosen(env, q)?.let { return listOf(HandheldSaves.spot(q, "$it/$name", SaveConfidence.KNOWN)) }
        val beside = q.emulatorPath?.let { SaveAdapters.parent(it) }
        val configDirs = listOfNotNull(beside?.takeIf { env.exists("$it/portable.ini") }) + when (env.host) {
            "WINDOWS" -> listOf("${SaveAdapters.appData(env)}/mGBA")
            "MACOS" -> listOf("${SaveAdapters.xdgConfig(env)}/mgba", "${SaveAdapters.macSupport(env)}/mGBA")
            else -> listOf("${SaveAdapters.xdgConfig(env)}/mgba", "${env.home}/.var/app/io.mgba.mGBA/config/mgba")
        }
        val configDir = configDirs.firstOrNull { env.exists("$it/config.ini") }
        val text = configDir?.let { env.readText("$it/config.ini") }
        val set = (HandheldSaves.setting(text, "ports.qt", "savegamePath") ?: HandheldSaves.setting(text, "default", "savegamePath"))
            ?.takeIf { it.isNotBlank() }?.trimEnd('/')
            ?.let { if (it.startsWith("/") || (it.length > 1 && it[1] == ':')) it else "$configDir/$it" }
        val dirs = listOfNotNull(set, SaveAdapters.parent(q.romPath))
        val (path, confidence) = HandheldSaves.pick(env, dirs, name, SaveConfidence.KNOWN)
        return listOf(HandheldSaves.spot(q, path, confidence))
    }
}

/** SkyEmu: beside the game; its chosen save folder when the save is already there. */
internal object SkyEmu : SaveAdapter {
    override val emulators = setOf("skyemu")

    override fun locate(q: SaveQuery, env: SaveEnvironment): List<SaveSpot> {
        val name = SaveAdapters.stem(q.romPath) + ".sav"
        SaveAdapters.chosen(env, q)?.let { return listOf(HandheldSaves.spot(q, "$it/$name", SaveConfidence.KNOWN)) }
        val game = SaveAdapters.parent(q.romPath)
        if (env.exists("$game/$name")) return listOf(HandheldSaves.spot(q, "$game/$name", SaveConfidence.FOUND))
        // On Android it opens games through a content link, so where it writes isn't certain.
        val sure = if (env.host == "ANDROID") SaveConfidence.GUESS else SaveConfidence.KNOWN
        return listOf(HandheldSaves.spot(q, "$game/$name", sure, note = "If SkyEmu saves to its own save folder, choose that folder in Fuse, Settings, Save folders.".takeIf { sure == SaveConfidence.GUESS }))
    }
}

/** Mednafen: `sav/<name>.<md5>.sav` in its home folder, found by the game's name. */
internal object Mednafen : SaveAdapter {
    override val emulators = setOf("mednafen")
    private val md5 = Regex("[0-9a-f]{32}")

    override fun locate(q: SaveQuery, env: SaveEnvironment): List<SaveSpot> {
        val format = "mednafen:${q.platform}"
        val home = env.env("MEDNAFEN_HOME")?.replace('\\', '/')?.trimEnd('/')
        val bases = listOfNotNull(home) + when (env.host) {
            "WINDOWS" -> listOfNotNull(q.emulatorPath?.let { SaveAdapters.parent(it) })
            else -> listOf("${env.home}/.mednafen", "${env.home}/.var/app/com.github.AmatCoder.mednaffe/.mednafen")
        }
        val base = bases.firstOrNull { env.isDirectory(it) } ?: return listOf(SaveAdapters.unavailable(SaveKind.SAVE, format, "Mednafen's folder wasn't found here."))
        // Its config is "name value" lines, no equals sign.
        val custom = env.readText("$base/mednafen.cfg")?.lineSequence()?.map { it.trim() }
            ?.firstOrNull { it.startsWith("filesys.path_sav ") }?.removePrefix("filesys.path_sav ")?.trim()?.replace('\\', '/')?.ifBlank { null }
        val savDir = SaveAdapters.chosen(env, q) ?: custom?.let { if (it.startsWith("/") || (it.length > 1 && it[1] == ':')) it else "$base/$it" } ?: "$base/sav"
        val stem = SaveAdapters.stem(q.romPath)
        // Its file names carry the game's MD5, which only Mednafen computes: the save it made is found by name.
        val hashes = env.list(savDir).mapNotNull { f ->
            if (!f.startsWith("$stem.")) return@mapNotNull null
            f.removePrefix("$stem.").substringBefore('.').takeIf { md5.matches(it) }
        }.distinct()
        val hash = hashes.singleOrNull()
            ?: return listOf(SaveAdapters.unavailable(SaveKind.SAVE, format, if (hashes.isEmpty()) "Mednafen names a save after the game's own fingerprint. Play it once in Mednafen and save; from then on Fuse keeps it in step." else "Mednafen has saves of more than one version of this game, so Fuse leaves them alone."))
        val names = buildList {
            add("save.sav" to "$stem.$hash.sav")
            if (q.platform == "gba") {
                add("save.eep" to "$stem.$hash.eep")
                add("save.rtc" to "$stem.$hash.rtc")
            }
        }
        return listOf(SaveSpot(SaveKind.SAVE, format, names.map { (n, f) -> SpotFile(n, "$savDir/$f") }, confidence = SaveConfidence.FOUND))
    }
}

/** My Boy! and My OldBoy!: their own save folders on the device, or beside the game when chosen. */
internal object MyBoy : SaveAdapter {
    override val emulators = setOf("my-boy", "my-oldboy")

    override fun locate(q: SaveQuery, env: SaveEnvironment): List<SaveSpot> {
        val name = SaveAdapters.stem(q.romPath) + ".sav"
        SaveAdapters.chosen(env, q)?.let { return listOf(HandheldSaves.spot(q, "$it/$name", SaveConfidence.KNOWN)) }
        val own = if (SaveAdapters.baseId(q.emulatorId) == "my-oldboy") "MyOldBoy" else "MyBoy"
        val folders = (listOf("/storage/emulated/0/$own/save") + env.storageRoots().map { "$it/$own/save" }).distinct()
        val dirs = folders + SaveAdapters.parent(q.romPath)
        val (path, confidence) = HandheldSaves.pick(env, dirs, name, SaveConfidence.KNOWN)
        return listOf(HandheldSaves.spot(q, path, confidence))
    }
}

/**
 * Emulators that don't publish where they save (Pizza Boy, Linkboy, SeedlessDS): a save found
 * beside the game is used; otherwise Fuse says it is guessing, and the person can point it at the
 * right folder in Settings, Save folders.
 */
internal object UnpublishedHandheld : SaveAdapter {
    override val emulators = setOf("pizza-boy-gba", "pizza-boy-gbc", "pizza-boy-sc", "linkboy", "seedlessds")

    override fun locate(q: SaveQuery, env: SaveEnvironment): List<SaveSpot> {
        val name = SaveAdapters.stem(q.romPath) + ".sav"
        SaveAdapters.chosen(env, q)?.let { return listOf(HandheldSaves.spot(q, "$it/$name", SaveConfidence.KNOWN)) }
        val game = SaveAdapters.parent(q.romPath)
        if (env.exists("$game/$name")) return listOf(HandheldSaves.spot(q, "$game/$name", SaveConfidence.FOUND))
        return listOf(HandheldSaves.spot(q, "$game/$name", SaveConfidence.GUESS, note = "This emulator doesn't say where it saves. If its saves aren't beside your games, choose its save folder in Fuse, Settings, Save folders."))
    }
}

/** The computer and Android sides of melonDS and mGBA, which keep saves differently. */
internal object MelonDs : SaveAdapter {
    override val emulators = setOf("melonds", "melonds-nightly", "watermelonds")
    override fun locate(q: SaveQuery, env: SaveEnvironment): List<SaveSpot> =
        if (env.host == "ANDROID") MelonDsAndroid.locate(q, env) else MelonDsDesktop.locate(q, env)
}

internal object Mgba : SaveAdapter {
    override val emulators = setOf("mgba")
    override fun locate(q: SaveQuery, env: SaveEnvironment): List<SaveSpot> = MgbaDesktop.locate(q, env)
}
