package io.github.matiyaaa.fuse.sync

/**
 * What a save adapter may ask of the device: its files, read only. Paths use `/`; [host] is
 * LINUX, WINDOWS, MACOS or ANDROID.
 */
interface SaveEnvironment {
    val host: String
    val home: String
    fun exists(path: String): Boolean
    fun isDirectory(path: String): Boolean
    fun list(path: String): List<String>
    fun readText(path: String, limit: Int = 256 * 1024): String?
    fun env(name: String): String? = null
}

/** The game whose saves are wanted, and what it is played with here. */
data class SaveQuery(
    val game: GameKey,
    val platform: String,
    val romPath: String,
    /** Fuse's emulator id (`linux.retroarch`, `duckstation`...). */
    val emulatorId: String,
    /** Where the emulator's program is, when known (portable installs keep data beside it). */
    val emulatorPath: String? = null,
    /** The libretro core, for RetroArch (`snes9x_libretro`). */
    val core: String? = null,
    val serial: String? = null,
    val title: String = "",
)

/** One file of a save: its name in the save (the same on every device) and its path here. */
data class SpotFile(val name: String, val path: String)

/**
 * Where one kind of save lives for a game here. [files] are the named files, there or where they
 * would go; [root] is the folder for saves made of folders (a PSP's SAVEDATA), where names are paths
 * inside it. [available] is false when the folder can't be reached (a removed card, or an Android
 * folder other apps can't open), with [note] saying why.
 */
data class SaveSpot(
    val kind: SaveKind,
    val format: String,
    val files: List<SpotFile> = emptyList(),
    val root: String? = null,
    /** For a [root], the names of the folders in it that are this game's (PSP and PS3 save folders). */
    val folders: List<String> = emptyList(),
    val available: Boolean = true,
    val note: String? = null,
) {
    /** Where the file named [name] goes here. */
    fun pathFor(name: String): String? = files.firstOrNull { it.name == name }?.path ?: root?.let { "$it/$name" }
}

/** Finds a game's saves for one family of emulators. */
interface SaveAdapter {
    /** Emulator ids it knows, without a host prefix (`retroarch`, `duckstation`). */
    val emulators: Set<String>
    fun locate(q: SaveQuery, env: SaveEnvironment): List<SaveSpot>
}

/**
 * Every emulator Fuse knows how to sync, and the honest answer for the rest. Formats name what a
 * save is, so it is only ever put where it can be read: a battery save is the same bytes in
 * RetroArch and in mGBA (`sram`), a PlayStation memory card is the same in RetroArch's cores and in
 * DuckStation (`psx.mcd`), but a save state belongs to one emulator and one core.
 */
object SaveAdapters {
    val all: List<SaveAdapter> = listOf(RetroArch, DuckStation, Pcsx2, Ppsspp, Dolphin, BesideRom, Rpcs3, Vita3k, ShadPs4, Flycast)

    /** The adapter for [emulatorId] (any host's id), or null when Fuse can't sync this one yet. */
    fun forEmulator(emulatorId: String): SaveAdapter? {
        val base = baseId(emulatorId)
        return all.firstOrNull { base in it.emulators }
    }

    fun baseId(emulatorId: String): String {
        val prefixes = listOf("linux.", "windows.", "macos.")
        val p = prefixes.firstOrNull { emulatorId.startsWith(it) }
        return (if (p != null) emulatorId.removePrefix(p) else emulatorId).removeSuffix("-steam").removeSuffix("-flatpak")
    }

    /** Why an emulator's saves can't be synced, for the ones with no adapter. */
    fun whyNot(emulatorId: String): String = when (baseId(emulatorId)) {
        "xemu", "xenia" -> "This emulator keeps saves inside a disk image, which Fuse can't open safely yet."
        "eden", "ryujinx", "citron", "sudachi", "yuzu" -> "Switch saves are kept per console user and title in the emulator's NAND; Fuse Sync doesn't move them yet."
        "cemu", "azahar", "azaharplus", "lime3ds", "citra", "mandarine", "panda3ds" -> "This system's saves are kept by title id in the emulator's own storage; Fuse Sync doesn't move them yet."
        "steam", "steam-url", "desktop", "shortcut", "open", "script" -> "PC games keep their own saves (and Steam has its own cloud)."
        else -> "Fuse doesn't know where this emulator keeps its saves yet."
    }

    internal fun stem(path: String): String = path.substringAfterLast('/').substringAfterLast('\\').let { n -> n.substringBeforeLast('.', n) }

    internal fun join(vararg parts: String): String = parts.filter { it.isNotEmpty() }.joinToString("/") { it.trimEnd('/') }

    internal fun parent(path: String): String = path.replace('\\', '/').substringBeforeLast('/', "")

    /** Battery saves of cartridge systems are raw bytes every emulator reads alike. */
    internal fun sramFormat(platform: String): String = when (platform) {
        // RetroArch's N64 cores keep all of a game's saves in one .srm; standalone emulators split them.
        "n64" -> "retroarch.n64"
        "psx" -> "psx.mcd"
        else -> "sram"
    }

    /** The data folder of a desktop emulator, by host, first that exists. */
    internal fun firstDir(env: SaveEnvironment, vararg candidates: String?): String? = candidates.filterNotNull().firstOrNull { env.isDirectory(it) }

    internal fun appData(env: SaveEnvironment): String = env.env("APPDATA")?.replace('\\', '/') ?: "${env.home}/AppData/Roaming"
    internal fun documents(env: SaveEnvironment): String = "${env.home}/Documents"
    internal fun xdgData(env: SaveEnvironment): String = env.env("XDG_DATA_HOME") ?: "${env.home}/.local/share"
    internal fun xdgConfig(env: SaveEnvironment): String = env.env("XDG_CONFIG_HOME") ?: "${env.home}/.config"
    internal fun macSupport(env: SaveEnvironment): String = "${env.home}/Library/Application Support"

    /** Folders under Android's other apps' private storage, which Android 11 and later keep from other apps. */
    internal fun androidPrivate(path: String): Boolean = "/Android/data/" in path || "/Android/obb/" in path

    internal fun unavailable(kind: SaveKind, format: String, note: String) = SaveSpot(kind, format, available = false, note = note)
}

/** Reads `key = "value"` lines of a RetroArch-style config. */
internal fun parseCfg(text: String): Map<String, String> = text.lineSequence().mapNotNull { line ->
    val i = line.indexOf('=')
    if (i <= 0 || line.trimStart().startsWith("#")) null else line.substring(0, i).trim() to line.substring(i + 1).trim().trim('"')
}.toMap()

/**
 * RetroArch on every host: saves (`.srm`) and states (`.state`, `.state1`...) in the folders its
 * config names, or beside the game when set to the content folder, sorted into core folders when
 * those options are on. The core's folder name is its library name (Snes9x, mGBA...).
 */
internal object RetroArch : SaveAdapter {
    override val emulators = setOf("retroarch", "retroarch-steam", "lemuroid")

    private fun configs(env: SaveEnvironment, emuPath: String?): List<String> = when (env.host) {
        "WINDOWS" -> listOfNotNull(emuPath?.let { SaveAdapters.join(SaveAdapters.parent(it), "retroarch.cfg") }, "${SaveAdapters.appData(env)}/RetroArch/retroarch.cfg")
        "MACOS" -> listOf("${SaveAdapters.macSupport(env)}/RetroArch/config/retroarch.cfg")
        "ANDROID" -> listOf("/storage/emulated/0/RetroArch/retroarch.cfg", "/storage/emulated/0/Android/data/com.retroarch/files/retroarch.cfg")
        else -> listOf(
            "${SaveAdapters.xdgConfig(env)}/retroarch/retroarch.cfg",
            "${env.home}/.var/app/org.libretro.RetroArch/config/retroarch/retroarch.cfg",
            "${env.home}/snap/retroarch/current/.config/retroarch/retroarch.cfg",
        )
    }

    private fun defaults(env: SaveEnvironment, config: String?, emuPath: String?): Pair<String, String> {
        val base = when {
            config != null -> SaveAdapters.parent(config)
            env.host == "ANDROID" -> "/storage/emulated/0/RetroArch"
            env.host == "WINDOWS" && emuPath != null -> SaveAdapters.parent(emuPath)
            else -> "${SaveAdapters.xdgConfig(env)}/retroarch"
        }
        return "$base/saves" to "$base/states"
    }

    override fun locate(q: SaveQuery, env: SaveEnvironment): List<SaveSpot> {
        val config = configs(env, q.emulatorPath).firstOrNull(env::exists)
        val cfg = config?.let { env.readText(it) }?.let(::parseCfg).orEmpty()
        val (defSaves, defStates) = defaults(env, config, q.emulatorPath)
        fun dir(key: String, fallback: String): String {
            val v = cfg[key]?.replace('\\', '/')?.let { if (it.startsWith("~")) env.home + it.drop(1) else it }
            return when {
                v.isNullOrEmpty() || v == "default" -> fallback
                v.startsWith(":") -> SaveAdapters.join(config?.let(SaveAdapters::parent) ?: "", v.drop(1).trimStart('/'))
                else -> v
            }
        }
        val content = SaveAdapters.parent(q.romPath)
        val inContent = cfg["savefiles_in_content_dir"] == "true"
        val statesInContent = cfg["savestates_in_content_dir"] == "true"
        val sortSaves = cfg["sort_savefiles_enable"] == "true" || cfg["sort_savefiles_by_content_enable"] == "true"
        val sortStates = cfg["sort_savestates_enable"] == "true" || cfg["sort_savestates_by_content_enable"] == "true"
        val coreFolder = q.core?.let { CoreNames.libraryName(it) }
        val contentFolder = SaveAdapters.parent(q.romPath).substringAfterLast('/')
        fun sorted(dir: String, byCore: Boolean, byContent: Boolean): String = buildString {
            append(dir)
            if (byContent) append('/').append(contentFolder)
            if (byCore && coreFolder != null) append('/').append(coreFolder)
        }
        val savesDir = if (inContent) content else sorted(dir("savefile_directory", defSaves), sortSaves, cfg["sort_savefiles_by_content_enable"] == "true")
        val statesDir = if (statesInContent) content else sorted(dir("savestate_directory", defStates), sortStates, cfg["sort_savestates_by_content_enable"] == "true")
        val stem = SaveAdapters.stem(q.romPath)
        val format = SaveAdapters.sramFormat(q.platform)
        val save = spot(env, SaveKind.SAVE, format, savesDir, listOf("save.srm" to "$stem.srm"))
        // States: slots 0 to 9 and the auto state, tied to the core.
        val stateNames = listOf("state.auto" to "$stem.state.auto", "state" to "$stem.state") + (1..9).map { "state$it" to "$stem.state$it" }
        val states = spot(env, SaveKind.STATE, "retroarch.state:${q.core ?: "core"}", statesDir, stateNames)
        return listOf(save, states)
    }

    private fun spot(env: SaveEnvironment, kind: SaveKind, format: String, dir: String, names: List<Pair<String, String>>): SaveSpot {
        if (env.host == "ANDROID" && SaveAdapters.androidPrivate(dir)) {
            return SaveAdapters.unavailable(kind, format, "RetroArch keeps these in its own Android folder, which Android doesn't let other apps open. Set Saves and States to a folder in Settings, Directory.")
        }
        // A folder not made yet is fine (RetroArch makes it); a folder whose drive is gone isn't.
        return SaveSpot(kind, format, names.map { (n, f) -> SpotFile(n, "$dir/$f") }, available = env.isDirectory(dir) || env.isDirectory(SaveAdapters.parent(dir)))
    }
}

/** libretro cores' library names, which RetroArch uses for its per-core folders. */
internal object CoreNames {
    private val names = mapOf(
        "snes9x" to "Snes9x", "bsnes" to "bsnes", "bsnes_hd_beta" to "bsnes-hd beta", "mesen" to "Mesen", "mesen-s" to "Mesen-S",
        "nestopia" to "Nestopia", "fceumm" to "FCEUmm", "quicknes" to "QuickNES", "mgba" to "mGBA", "gambatte" to "Gambatte",
        "sameboy" to "SameBoy", "gearboy" to "Gearboy", "vba_next" to "VBA Next", "vbam" to "VBA-M", "gpsp" to "gpSP",
        "genesis_plus_gx" to "Genesis Plus GX", "picodrive" to "PicoDrive", "blastem" to "BlastEm",
        "mednafen_psx_hw" to "Beetle PSX HW", "mednafen_psx" to "Beetle PSX", "swanstation" to "SwanStation", "pcsx_rearmed" to "PCSX-ReARMed",
        "mupen64plus_next" to "Mupen64Plus-Next", "mupen64plus_next_gles3" to "Mupen64Plus-Next", "parallel_n64" to "ParaLLEl N64",
        "melonds" to "melonDS", "melondsds" to "melonDS DS", "desmume" to "DeSmuME", "ppsspp" to "PPSSPP", "flycast" to "Flycast",
        "mednafen_saturn" to "Beetle Saturn", "yabasanshiro" to "YabaSanshiro", "mednafen_pce" to "Beetle PCE", "mednafen_pce_fast" to "Beetle PCE Fast",
        "mednafen_supergrafx" to "Beetle SuperGrafx", "mednafen_ngp" to "Beetle NeoPop", "mednafen_wswan" to "Beetle WonderSwan",
        "mednafen_vb" to "Beetle VB", "mednafen_lynx" to "Beetle Lynx", "handy" to "Handy", "stella" to "Stella", "prosystem" to "ProSystem",
        "a5200" to "a5200", "virtualjaguar" to "Virtual Jaguar", "fbneo" to "FinalBurn Neo", "mame" to "MAME", "dosbox_pure" to "DOSBox-pure",
        "scummvm" to "ScummVM", "opera" to "Opera", "bluemsx" to "blueMSX", "fmsx" to "fMSX", "vice_x64" to "VICE x64",
        "puae" to "PUAE", "dolphin" to "Dolphin", "citra" to "Citra", "pcsx2" to "LRPS2", "play" to "Play!", "beetle_psx_hw" to "Beetle PSX HW",
    )

    fun libraryName(core: String): String? = names[core.removeSuffix("_libretro").removeSuffix("_android").lowercase()]
}

/**
 * DuckStation: a memory card per game in its memcards folder (by title, or by serial when set so),
 * the same raw card RetroArch's PlayStation cores keep as `.srm`; states by serial.
 */
internal object DuckStation : SaveAdapter {
    override val emulators = setOf("duckstation")

    private fun dataDir(env: SaveEnvironment, emuPath: String?): String? = when (env.host) {
        // Portable when portable.txt sits beside the program.
        "WINDOWS" -> SaveAdapters.firstDir(env, emuPath?.let(SaveAdapters::parent)?.takeIf { env.exists("$it/portable.txt") }, "${SaveAdapters.documents(env)}/DuckStation")
        "MACOS" -> SaveAdapters.firstDir(env, "${SaveAdapters.macSupport(env)}/DuckStation")
        "ANDROID" -> SaveAdapters.firstDir(env, "/storage/emulated/0/duckstation", "/storage/emulated/0/DuckStation")
        else -> SaveAdapters.firstDir(env, "${SaveAdapters.xdgData(env)}/duckstation", "${SaveAdapters.xdgConfig(env)}/duckstation", "${env.home}/.var/app/org.duckstation.DuckStation/data/duckstation")
    }

    override fun locate(q: SaveQuery, env: SaveEnvironment): List<SaveSpot> {
        val dir = dataDir(env, q.emulatorPath) ?: return listOf(SaveAdapters.unavailable(SaveKind.SAVE, "psx.mcd", "DuckStation's folder wasn't found here. Start DuckStation once so it makes it."))
        val cards = "$dir/memcards"
        // Its own card for this game: by title (the default) or by serial, whichever is there.
        val names = env.list(cards)
        val serial = q.serial?.uppercase()
        val byTitle = names.firstOrNull { n -> n.endsWith("_1.mcd") && TitleMatch.same(n.removeSuffix("_1.mcd"), q.title) }
        val bySerial = serial?.let { s -> names.firstOrNull { it.equals("${s}_1.mcd", ignoreCase = true) } }
        val card = byTitle ?: bySerial ?: "${q.title.ifBlank { SaveAdapters.stem(q.romPath) }}_1.mcd"
        val save = SaveSpot(SaveKind.SAVE, "psx.mcd", listOf(SpotFile("card1.mcd", "$cards/$card")))
        val states = serial?.let { s ->
            SaveSpot(SaveKind.STATE, "duckstation.state", (1..10).map { i -> SpotFile("state$i.sav", "$dir/savestates/${s}_$i.sav") } + SpotFile("resume.sav", "$dir/savestates/${s}_resume.sav"))
        }
        return listOfNotNull(save, states)
    }
}

/**
 * PCSX2: memory cards are shared by every game on them (Mcd001.ps2 and Mcd002.ps2, or folder
 * cards), so they sync as the profile's cards, not one game's; states are per game.
 */
internal object Pcsx2 : SaveAdapter {
    override val emulators = setOf("pcsx2", "nethersx2", "nethersx2-turnip", "nethersx2-turnip-classic", "armsx2")

    private fun dataDir(env: SaveEnvironment, emuPath: String?): String? = when (env.host) {
        "WINDOWS" -> SaveAdapters.firstDir(env, emuPath?.let { SaveAdapters.parent(it) }?.takeIf { env.exists("$it/portable.ini") || env.exists("$it/portable.txt") }, "${SaveAdapters.documents(env)}/PCSX2")
        "MACOS" -> SaveAdapters.firstDir(env, "${SaveAdapters.macSupport(env)}/PCSX2")
        "ANDROID" -> null
        else -> SaveAdapters.firstDir(env, "${SaveAdapters.xdgConfig(env)}/PCSX2", "${env.home}/.var/app/net.pcsx2.PCSX2/config/PCSX2")
    }

    override fun locate(q: SaveQuery, env: SaveEnvironment): List<SaveSpot> {
        val dir = dataDir(env, q.emulatorPath)
            ?: return listOf(SaveAdapters.unavailable(SaveKind.MEMORY_CARD, "ps2.card", if (env.host == "ANDROID") "PlayStation 2 emulators on Android keep cards where other apps can't reach them." else "PCSX2's folder wasn't found here."))
        val cards = "$dir/memcards"
        return listOf(SaveSpot(SaveKind.MEMORY_CARD, "ps2.card", listOf(SpotFile("Mcd001.ps2", "$cards/Mcd001.ps2"), SpotFile("Mcd002.ps2", "$cards/Mcd002.ps2"))))
    }
}

/** PPSSPP: each game's save folders in SAVEDATA, named from its game id (ULUS10041...); states by id. */
internal object Ppsspp : SaveAdapter {
    override val emulators = setOf("ppsspp")

    private fun memstick(env: SaveEnvironment, emuPath: String?): String? = when (env.host) {
        "WINDOWS" -> SaveAdapters.firstDir(env, emuPath?.let { SaveAdapters.join(SaveAdapters.parent(it), "memstick") }, "${SaveAdapters.documents(env)}/PPSSPP")
        "MACOS" -> SaveAdapters.firstDir(env, "${env.home}/.config/ppsspp", "${SaveAdapters.macSupport(env)}/PPSSPP")
        "ANDROID" -> "/storage/emulated/0".takeIf { env.isDirectory("$it/PSP") }
        else -> SaveAdapters.firstDir(env, "${SaveAdapters.xdgConfig(env)}/ppsspp", "${env.home}/.var/app/org.ppsspp.PPSSPP/config/ppsspp")
    }

    override fun locate(q: SaveQuery, env: SaveEnvironment): List<SaveSpot> {
        val root = memstick(env, q.emulatorPath) ?: return listOf(SaveAdapters.unavailable(SaveKind.SAVE, "psp.savedata", "PPSSPP's memory stick wasn't found here."))
        val savedata = "$root/PSP/SAVEDATA"
        val id = q.serial?.uppercase()?.filter { it.isLetterOrDigit() }
            ?: return listOf(SaveAdapters.unavailable(SaveKind.SAVE, "psp.savedata", "Fuse doesn't know this game's id yet, which PPSSPP names its saves by."))
        val folders = env.list(savedata).filter { it.uppercase().startsWith(id) }
        return listOf(SaveSpot(SaveKind.SAVE, "psp.savedata", root = savedata, folders = folders.ifEmpty { listOf(id) }))
    }
}

/**
 * Dolphin: Wii saves in its NAND by title (from the game's id); GameCube saves on its memory card
 * files per region. Paths by host.
 */
internal object Dolphin : SaveAdapter {
    override val emulators = setOf("dolphin")

    private fun userDir(env: SaveEnvironment, emuPath: String?): String? = when (env.host) {
        "WINDOWS" -> SaveAdapters.firstDir(env, emuPath?.let { SaveAdapters.join(SaveAdapters.parent(it), "User") }, "${SaveAdapters.documents(env)}/Dolphin Emulator", "${SaveAdapters.appData(env)}/Dolphin Emulator")
        "MACOS" -> SaveAdapters.firstDir(env, "${SaveAdapters.macSupport(env)}/Dolphin")
        "ANDROID" -> SaveAdapters.firstDir(env, "/storage/emulated/0/dolphin-emu")
        else -> SaveAdapters.firstDir(env, "${SaveAdapters.xdgData(env)}/dolphin-emu", "${env.home}/.dolphin-emu", "${env.home}/.var/app/org.DolphinEmu.dolphin-emu/data/dolphin-emu")
    }

    override fun locate(q: SaveQuery, env: SaveEnvironment): List<SaveSpot> {
        val user = userDir(env, q.emulatorPath) ?: return listOf(SaveAdapters.unavailable(SaveKind.SAVE, "dolphin", if (env.host == "ANDROID") "Dolphin keeps its folder where other apps can't reach it on this Android." else "Dolphin's folder wasn't found here."))
        val id = q.serial?.uppercase()?.takeIf { it.length >= 4 }
        return if (q.platform == "wii" && id != null) {
            val title = id.take(4).map { c -> "%02x".format(c.code) }.joinToString("")
            listOf(SaveSpot(SaveKind.SAVE, "wii.nand", root = "$user/Wii/title/00010000/$title/data", folders = emptyList()))
        } else {
            // GameCube: the region's memory card files, shared by the games on them.
            val region = when (id?.getOrNull(3)) { 'E' -> "USA"; 'J' -> "JAP"; else -> "EUR" }
            listOf(SaveSpot(SaveKind.MEMORY_CARD, "gc.card.$region", listOf(SpotFile("MemoryCardA.$region.raw", "$user/GC/MemoryCardA.$region.raw"))))
        }
    }
}

/** Emulators that keep the save beside the game, named after it: melonDS, mGBA, Mednafen. */
internal object BesideRom : SaveAdapter {
    override val emulators = setOf("melonds", "mgba", "mednafen", "skyemu")

    override fun locate(q: SaveQuery, env: SaveEnvironment): List<SaveSpot> {
        val dir = SaveAdapters.parent(q.romPath)
        val stem = SaveAdapters.stem(q.romPath)
        return listOf(SaveSpot(SaveKind.SAVE, SaveAdapters.sramFormat(q.platform), listOf(SpotFile("save.srm", "$dir/$stem.sav"))))
    }
}

/** RPCS3: each game's save folders under dev_hdd0 by its serial (BLUS30109...). */
internal object Rpcs3 : SaveAdapter {
    override val emulators = setOf("rpcs3")

    override fun locate(q: SaveQuery, env: SaveEnvironment): List<SaveSpot> {
        val base = when (env.host) {
            "WINDOWS" -> SaveAdapters.firstDir(env, q.emulatorPath?.let { SaveAdapters.parent(it) })
            "MACOS" -> SaveAdapters.firstDir(env, "${SaveAdapters.macSupport(env)}/rpcs3")
            else -> SaveAdapters.firstDir(env, "${SaveAdapters.xdgConfig(env)}/rpcs3", "${env.home}/.var/app/net.rpcs3.RPCS3/config/rpcs3")
        } ?: return listOf(SaveAdapters.unavailable(SaveKind.SAVE, "ps3.savedata", "RPCS3's folder wasn't found here."))
        val serial = q.serial?.uppercase()?.filter { it.isLetterOrDigit() } ?: return listOf(SaveAdapters.unavailable(SaveKind.SAVE, "ps3.savedata", "Fuse doesn't know this game's serial yet, which RPCS3 names its saves by."))
        val savedata = "$base/dev_hdd0/home/00000001/savedata"
        return listOf(SaveSpot(SaveKind.SAVE, "ps3.savedata", root = savedata, folders = env.list(savedata).filter { it.uppercase().startsWith(serial) }.ifEmpty { listOf(serial) }))
    }
}

/** Vita3K: each game's saves under ux0/user/00/savedata by its title id. */
internal object Vita3k : SaveAdapter {
    override val emulators = setOf("vita3k")

    override fun locate(q: SaveQuery, env: SaveEnvironment): List<SaveSpot> {
        val base = when (env.host) {
            "WINDOWS" -> SaveAdapters.firstDir(env, q.emulatorPath?.let { SaveAdapters.parent(it) + "/ux0" }, "${SaveAdapters.appData(env)}/Vita3K/Vita3K/ux0")
            "MACOS" -> SaveAdapters.firstDir(env, "${SaveAdapters.macSupport(env)}/Vita3K/Vita3K/ux0")
            "ANDROID" -> null
            else -> SaveAdapters.firstDir(env, "${SaveAdapters.xdgData(env)}/Vita3K/Vita3K/ux0")
        } ?: return listOf(SaveAdapters.unavailable(SaveKind.SAVE, "vita.savedata", "Vita3K's folder wasn't found here."))
        val id = q.serial?.uppercase() ?: return listOf(SaveAdapters.unavailable(SaveKind.SAVE, "vita.savedata", "Fuse doesn't know this game's title id yet."))
        return listOf(SaveSpot(SaveKind.SAVE, "vita.savedata", root = "$base/user/00/savedata", folders = listOf(id)))
    }
}

/** shadPS4: each game's saves by its CUSA id, in its user folder (beside it when portable). */
internal object ShadPs4 : SaveAdapter {
    override val emulators = setOf("shadps4")

    override fun locate(q: SaveQuery, env: SaveEnvironment): List<SaveSpot> {
        val user = SaveAdapters.firstDir(
            env,
            q.emulatorPath?.let { SaveAdapters.parent(it) + "/user" },
            "${SaveAdapters.xdgData(env)}/shadPS4",
            "${env.home}/.var/app/net.shadps4.shadPS4/data/shadPS4",
            "${SaveAdapters.macSupport(env)}/shadPS4",
            "${SaveAdapters.appData(env)}/shadPS4",
        ) ?: return listOf(SaveAdapters.unavailable(SaveKind.SAVE, "ps4.savedata", "shadPS4's user folder wasn't found here."))
        val id = q.serial?.uppercase()?.takeIf { it.startsWith("CUSA") || it.startsWith("PPSA") }
            ?: return listOf(SaveAdapters.unavailable(SaveKind.SAVE, "ps4.savedata", "Fuse doesn't know this game's id yet."))
        return listOf(SaveSpot(SaveKind.SAVE, "ps4.savedata", root = "$user/savedata/1", folders = listOf(id)))
    }
}

/** Flycast: the Dreamcast's VMU files, shared by every game on them. */
internal object Flycast : SaveAdapter {
    override val emulators = setOf("flycast")

    override fun locate(q: SaveQuery, env: SaveEnvironment): List<SaveSpot> {
        val dir = when (env.host) {
            "WINDOWS" -> SaveAdapters.firstDir(env, q.emulatorPath?.let { SaveAdapters.parent(it) + "/data" })
            "MACOS" -> SaveAdapters.firstDir(env, "${SaveAdapters.macSupport(env)}/Flycast")
            "ANDROID" -> SaveAdapters.firstDir(env, "/storage/emulated/0/Flycast/data")
            else -> SaveAdapters.firstDir(env, "${SaveAdapters.xdgData(env)}/flycast", "${env.home}/.var/app/org.flycast.Flycast/data/flycast")
        } ?: return listOf(SaveAdapters.unavailable(SaveKind.MEMORY_CARD, "dc.vmu", "Flycast's folder wasn't found here."))
        return listOf(SaveSpot(SaveKind.MEMORY_CARD, "dc.vmu", listOf(SpotFile("vmu_save_A1.bin", "$dir/vmu_save_A1.bin"))))
    }
}

/** Loose title matching for files emulators name after a game's title (DuckStation's cards). */
internal object TitleMatch {
    private fun norm(s: String) = s.lowercase().replace(Regex("\\(.*?\\)|\\[.*?]"), "").filter { it.isLetterOrDigit() }

    fun same(a: String, b: String): Boolean = norm(a).isNotEmpty() && norm(a) == norm(b)
}

/**
 * Whether a save in one format can be put where another is read. Only the same format: Fuse never
 * converts a save, so a save state never lands in an emulator or core that can't load it.
 */
object SaveSlotFormats {
    fun compatible(saved: String, wanted: String): Boolean = saved == wanted
}
