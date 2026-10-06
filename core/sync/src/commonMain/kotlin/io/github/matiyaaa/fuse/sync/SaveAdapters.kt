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

    /** [length] bytes of the file at [path] from [offset] (game headers, a save folder's id file); null when unreadable. */
    fun readBytes(path: String, offset: Long = 0, length: Int): ByteArray? = null

    /** The folder the person chose for [emulator]'s saves (its id without a host prefix), when they did. */
    fun saveFolder(emulator: String): String? = null

    /** When [path] last changed: a file's own time, a folder's newest file inside it; null when it isn't there. */
    fun modified(path: String): Long? = null

    /**
     * The folders Fuse learned hold [game]'s saves of [format], from a play that changed them (see
     * [SaveSpot.learnIn]): used when the game's id can't be read.
     */
    fun learned(game: String, format: String): List<String> = emptyList()

    /** Where people's own files live, to look for an emulator's data folder in (Android: its storage and cards). */
    fun storageRoots(): List<String> = emptyList()

    /** [compute]'s answer for [key], kept a while by environments that can (searching storage is slow). */
    fun remember(key: String, compute: () -> List<String>): List<String> = compute()
}

/** [base] with the save folders the person chose (Settings, Save folders) and the ones Fuse learned on top. */
class WithSaveFolders(
    private val base: SaveEnvironment,
    private val learnedFolders: (String, String) -> List<String> = { _, _ -> emptyList() },
    private val folders: () -> Map<String, String>,
) : SaveEnvironment by base {
    override fun saveFolder(emulator: String): String? = folders()[emulator]?.takeIf { it.isNotBlank() }
    override fun learned(game: String, format: String): List<String> = learnedFolders(game, format)
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
    /**
     * For a save Fuse can't place yet because the game's id is unknown: the folder whose subfolders
     * are each one game's saves. The folders a play changes there are learned as this game's.
     */
    val learnIn: String? = null,
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
    val all: List<SaveAdapter> = listOf(
        RetroArch, Lemuroid, DuckStation, Pcsx2, Ppsspp, Dolphin, BesideRom, Rpcs3, Vita3k, ShadPs4, Flycast,
        DraStic, Mupen64, Redream, Epsxe, Fpse, PlayPs2, SaturnBackup, Mame, ScummVm,
        ThreeDs, SwitchNand, Ryujinx, Cemu, Xenia,
    )

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
        "xemu" -> "xemu keeps saves inside its hard disk image, which Fuse can't open safely."
        "panda3ds" -> "Panda3DS keeps its saves in a layout Fuse doesn't read yet; Azahar's saves sync."
        "skyline", "strato" -> "This emulator keeps its saves where Fuse can't reach them."
        "ax360e", "x360-mobile", "xendroid", "xenra" -> "This Xbox 360 emulator keeps its saves in its own private Android folder."
        "steam", "steam-url", "desktop", "shortcut", "open", "script" -> "PC games keep their own saves (and Steam has its own cloud)."
        "winlator", "winlator-cmod", "winlator-frost", "winlator-glibc", "winlator-proot", "winnative", "gamehub", "gamehub-lite",
        "gamehub-lite-local", "gamenative", "bannerlator" -> "Windows games keep their own saves inside this app's Windows drive, a different place for every game."
        "dosbox-staging", "dosbox-x" -> "DOS games save inside their own folders; keep those folders in step with Syncthing."
        "bachatas4", "sharpemu", "x1-box", "hakux" -> "This emulator is early, and where it keeps saves still changes between versions."
        "colem", "emucorec", "emucorev", "emucorex", "fmsx", "ines", "iratajaguar", "mastergear", "md-emu", "nesoid", "pce-emu",
        "real3doplayer", "swiff", "virtual-virtual-boy", "pico8-android", "gopher64" ->
            "This emulator keeps its saves in its own private Android folder, which no other app can open. RetroArch plays the same systems with saves Fuse keeps in step."
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

    /** The folder the person chose for this emulator's saves, if they did and it is there. */
    internal fun chosen(env: SaveEnvironment, q: SaveQuery): String? =
        env.saveFolder(baseId(q.emulatorId))?.replace('\\', '/')?.trimEnd('/')?.takeIf { env.isDirectory(it) }

    /** What to say when an Android emulator keeps its saves where Fuse can't open them. */
    internal fun androidPrivateNote(name: String, how: String = "If $name lets you choose its data folder, choose one in your storage, then choose the same folder in Fuse, Settings, Save folders."): String =
        "$name keeps its saves in its own private Android folder, which no other app can open. $how"

    /** Folders under Android's other apps' private storage, which Android 11 and later keep from other apps. */
    internal fun androidPrivate(path: String): Boolean = "/Android/data/" in path || "/Android/obb/" in path

    internal fun unavailable(kind: SaveKind, format: String, note: String, learnIn: String? = null) = SaveSpot(kind, format, available = false, note = note, learnIn = learnIn)

    /** What to say while a game's saves wait to be learned from its first play here. */
    internal const val PLAY_ONCE = "Play it once here and save: Fuse learns where its save is from that, and keeps it in step from then on."

    /** The learned folders for [q]'s saves of [format], if a play taught Fuse them. */
    internal fun learned(env: SaveEnvironment, q: SaveQuery, format: String): List<String> = env.learned(q.game.id, format)
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
    override val emulators = setOf("retroarch", "retroarch-steam")

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
            // On Android, its folder may be anywhere the person moved it.
            ?: DataFolders.find(env, "retroarch", listOf("retroarch")) { d -> "$d/retroarch.cfg".takeIf(env::exists) }.firstOrNull()
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
        "ANDROID" -> SaveAdapters.firstDir(env, "/storage/emulated/0/duckstation", "/storage/emulated/0/DuckStation", "/storage/emulated/0/Android/data/com.github.stenzek.duckstation/files")
            ?: DataFolders.find(env, "duckstation", listOf("duckstation", "psx", "ps1")) { d -> d.takeIf { env.isDirectory("$it/memcards") && (env.exists("$it/settings.ini") || env.isDirectory("$it/savestates")) } }.firstOrNull()
        else -> SaveAdapters.firstDir(env, "${SaveAdapters.xdgData(env)}/duckstation", "${SaveAdapters.xdgConfig(env)}/duckstation", "${env.home}/.var/app/org.duckstation.DuckStation/data/duckstation")
    }

    override fun locate(q: SaveQuery, env: SaveEnvironment): List<SaveSpot> {
        val dir = SaveAdapters.chosen(env, q) ?: dataDir(env, q.emulatorPath)
            ?: return listOf(SaveAdapters.unavailable(SaveKind.SAVE, "psx.mcd", if (env.host == "ANDROID") SaveAdapters.androidPrivateNote("DuckStation", "Use its Transfer data (or choose its data folder in your storage), then choose that folder in Fuse, Settings, Save folders.") else "DuckStation's folder wasn't found here. Start DuckStation once so it makes it."))
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
    override val emulators = setOf("pcsx2", "nethersx2", "nethersx2-turnip", "nethersx2-turnip-classic", "armsx1", "armsx2", "armsx3", "aethersx2")

    private fun dataDir(env: SaveEnvironment, emuPath: String?): String? = when (env.host) {
        "WINDOWS" -> SaveAdapters.firstDir(env, emuPath?.let { SaveAdapters.parent(it) }?.takeIf { env.exists("$it/portable.ini") || env.exists("$it/portable.txt") }, "${SaveAdapters.documents(env)}/PCSX2")
        "MACOS" -> SaveAdapters.firstDir(env, "${SaveAdapters.macSupport(env)}/PCSX2")
        "ANDROID" -> DataFolders.find(env, "ps2", listOf("armsx2", "nethersx2", "aethersx2", "pcsx2", "ps2")) { d -> d.takeIf { env.exists("$it/memcards/Mcd001.ps2") } }.firstOrNull()
        else -> SaveAdapters.firstDir(env, "${SaveAdapters.xdgConfig(env)}/PCSX2", "${env.home}/.var/app/net.pcsx2.PCSX2/config/PCSX2")
    }

    override fun locate(q: SaveQuery, env: SaveEnvironment): List<SaveSpot> {
        val dir = SaveAdapters.chosen(env, q) ?: dataDir(env, q.emulatorPath)
            ?: return listOf(SaveAdapters.unavailable(SaveKind.MEMORY_CARD, "ps2.card", if (env.host == "ANDROID") SaveAdapters.androidPrivateNote("This PlayStation 2 emulator", "ARMSX2 saves in the folder you chose when setting it up: choose that same folder in Fuse, Settings, Save folders. NetherSX2 needs its Transfer data first.") else "PCSX2's folder wasn't found here."))
        val cards = "$dir/memcards"
        return listOf(SaveSpot(SaveKind.MEMORY_CARD, "ps2.card", listOf(SpotFile("Mcd001.ps2", "$cards/Mcd001.ps2"), SpotFile("Mcd002.ps2", "$cards/Mcd002.ps2"))))
    }
}

/** PPSSPP: each game's save folders in SAVEDATA, named from its game id (ULUS10041...); states by id. */
internal object Ppsspp : SaveAdapter {
    override val emulators = setOf("ppsspp")

    private fun memstick(env: SaveEnvironment, emuPath: String?, id: String?): String? = when (env.host) {
        "WINDOWS" -> SaveAdapters.firstDir(env, emuPath?.let { SaveAdapters.join(SaveAdapters.parent(it), "memstick") }, "${SaveAdapters.documents(env)}/PPSSPP")
        "MACOS" -> SaveAdapters.firstDir(env, "${env.home}/.config/ppsspp", "${SaveAdapters.macSupport(env)}/PPSSPP")
        "ANDROID" -> "/storage/emulated/0".takeIf { env.isDirectory("$it/PSP") }
            ?: DataFolders.find(env, "ppsspp", listOf("ppsspp", "psp"), holds = { r -> id != null && env.list("$r/PSP/SAVEDATA").any { it.uppercase().startsWith(id) } }) { d ->
                d.takeIf { env.isDirectory("$it/PSP/SAVEDATA") || env.isDirectory("$it/PSP/SYSTEM") }
            }.firstOrNull()
        else -> SaveAdapters.firstDir(env, "${SaveAdapters.xdgConfig(env)}/ppsspp", "${env.home}/.var/app/org.ppsspp.PPSSPP/config/ppsspp")
    }

    override fun locate(q: SaveQuery, env: SaveEnvironment): List<SaveSpot> {
        val known = q.serial?.uppercase()?.filter { it.isLetterOrDigit() }
        val root = SaveAdapters.chosen(env, q) ?: memstick(env, q.emulatorPath, known)
            ?: return listOf(SaveAdapters.unavailable(SaveKind.SAVE, "psp.savedata", if (env.host == "ANDROID") "PPSSPP's memory stick folder wasn't found. Choose the folder you picked in PPSSPP (the one with PSP inside) in Fuse, Settings, Save folders." else "PPSSPP's memory stick wasn't found here."))
        val savedata = "$root/PSP/SAVEDATA"
        val id = known ?: run {
            val learned = SaveAdapters.learned(env, q, "psp.savedata")
            if (learned.isNotEmpty()) return listOf(SaveSpot(SaveKind.SAVE, "psp.savedata", root = savedata, folders = learned))
            return listOf(SaveAdapters.unavailable(SaveKind.SAVE, "psp.savedata", SaveAdapters.PLAY_ONCE, learnIn = savedata))
        }
        val folders = env.list(savedata).filter { it.uppercase().startsWith(id) }
        return listOf(SaveSpot(SaveKind.SAVE, "psp.savedata", root = savedata, folders = folders.ifEmpty { listOf(id) }))
    }
}

/**
 * Dolphin: Wii saves in its NAND by title (from the game's id); GameCube saves on its memory card
 * files per region. Paths by host.
 */
internal object Dolphin : SaveAdapter {
    override val emulators = setOf("dolphin", "dolphin-mmjr", "dolphin-mmjr2")

    private fun userDir(env: SaveEnvironment, emuPath: String?): String? = when (env.host) {
        "WINDOWS" -> SaveAdapters.firstDir(env, emuPath?.let { SaveAdapters.join(SaveAdapters.parent(it), "User") }, "${SaveAdapters.documents(env)}/Dolphin Emulator", "${SaveAdapters.appData(env)}/Dolphin Emulator")
        "MACOS" -> SaveAdapters.firstDir(env, "${SaveAdapters.macSupport(env)}/Dolphin")
        "ANDROID" -> SaveAdapters.firstDir(env, "/storage/emulated/0/dolphin-emu")
            ?: DataFolders.find(env, "dolphin", listOf("dolphin", "gamecube", "wii")) { d -> d.takeIf { (env.isDirectory("$it/GC") || env.isDirectory("$it/Wii")) && env.isDirectory("$it/Config") } }.firstOrNull()
        else -> SaveAdapters.firstDir(env, "${SaveAdapters.xdgData(env)}/dolphin-emu", "${env.home}/.dolphin-emu", "${env.home}/.var/app/org.DolphinEmu.dolphin-emu/data/dolphin-emu")
    }

    override fun locate(q: SaveQuery, env: SaveEnvironment): List<SaveSpot> {
        val user = SaveAdapters.chosen(env, q) ?: userDir(env, q.emulatorPath)
            ?: return listOf(SaveAdapters.unavailable(SaveKind.SAVE, "dolphin", if (env.host == "ANDROID") SaveAdapters.androidPrivateNote("Dolphin", "Use its Export user data, then choose that folder in Fuse, Settings, Save folders.") else "Dolphin's folder wasn't found here."))
        val id = q.serial?.uppercase()?.takeIf { it.length >= 4 }
        if (q.platform == "wii" && id == null) {
            val titles = "$user/Wii/title/00010000"
            val learned = SaveAdapters.learned(env, q, "wii.nand").firstOrNull()
                ?: return listOf(SaveAdapters.unavailable(SaveKind.SAVE, "wii.nand", SaveAdapters.PLAY_ONCE, learnIn = titles))
            return listOf(SaveSpot(SaveKind.SAVE, "wii.nand", root = "$titles/$learned/data"))
        }
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
    override val emulators = setOf(
        "melonds", "melonds-nightly", "watermelonds", "seedlessds", "noods", "mgba", "mednafen", "skyemu",
        "my-boy", "my-oldboy", "pizza-boy-gba", "pizza-boy-gbc", "pizza-boy-sc", "linkboy",
    )

    override fun locate(q: SaveQuery, env: SaveEnvironment): List<SaveSpot> {
        // Beside the game, unless the person pointed Fuse at the emulator's own save folder.
        val dir = SaveAdapters.chosen(env, q) ?: SaveAdapters.parent(q.romPath)
        val stem = SaveAdapters.stem(q.romPath)
        return listOf(SaveSpot(SaveKind.SAVE, SaveAdapters.sramFormat(q.platform), listOf(SpotFile("save.srm", "$dir/$stem.sav"))))
    }
}

/** RPCS3: each game's save folders under dev_hdd0 by its serial (BLUS30109...). */
internal object Rpcs3 : SaveAdapter {
    override val emulators = setOf("rpcs3", "rpcs3-android", "aps3e", "rpcsx")

    override fun locate(q: SaveQuery, env: SaveEnvironment): List<SaveSpot> {
        val base = SaveAdapters.chosen(env, q) ?: when (env.host) {
            "WINDOWS" -> SaveAdapters.firstDir(env, q.emulatorPath?.let { SaveAdapters.parent(it) })
            "MACOS" -> SaveAdapters.firstDir(env, "${SaveAdapters.macSupport(env)}/rpcs3")
            else -> SaveAdapters.firstDir(env, "${SaveAdapters.xdgConfig(env)}/rpcs3", "${env.home}/.var/app/net.rpcs3.RPCS3/config/rpcs3")
        } ?: return listOf(SaveAdapters.unavailable(SaveKind.SAVE, "ps3.savedata", "RPCS3's folder wasn't found here."))
        val savedata = "$base/dev_hdd0/home/00000001/savedata"
        val serial = q.serial?.uppercase()?.filter { it.isLetterOrDigit() } ?: run {
            val learned = SaveAdapters.learned(env, q, "ps3.savedata")
            if (learned.isNotEmpty()) return listOf(SaveSpot(SaveKind.SAVE, "ps3.savedata", root = savedata, folders = learned))
            return listOf(SaveAdapters.unavailable(SaveKind.SAVE, "ps3.savedata", SaveAdapters.PLAY_ONCE, learnIn = savedata))
        }
        return listOf(SaveSpot(SaveKind.SAVE, "ps3.savedata", root = savedata, folders = env.list(savedata).filter { it.uppercase().startsWith(serial) }.ifEmpty { listOf(serial) }))
    }
}

/** Vita3K: each game's saves under ux0/user/00/savedata by its title id. */
internal object Vita3k : SaveAdapter {
    override val emulators = setOf("vita3k")

    override fun locate(q: SaveQuery, env: SaveEnvironment): List<SaveSpot> {
        val base = SaveAdapters.chosen(env, q)?.let { c -> if (env.isDirectory("$c/ux0")) "$c/ux0" else c } ?: when (env.host) {
            "WINDOWS" -> SaveAdapters.firstDir(env, q.emulatorPath?.let { SaveAdapters.parent(it) + "/ux0" }, "${SaveAdapters.appData(env)}/Vita3K/Vita3K/ux0")
            "MACOS" -> SaveAdapters.firstDir(env, "${SaveAdapters.macSupport(env)}/Vita3K/Vita3K/ux0")
            "ANDROID" -> null
            else -> SaveAdapters.firstDir(env, "${SaveAdapters.xdgData(env)}/Vita3K/Vita3K/ux0")
        } ?: return listOf(SaveAdapters.unavailable(SaveKind.SAVE, "vita.savedata", "Vita3K's folder wasn't found here."))
        val savedata = "$base/user/00/savedata"
        val id = q.serial?.uppercase() ?: run {
            val learned = SaveAdapters.learned(env, q, "vita.savedata")
            if (learned.isNotEmpty()) return listOf(SaveSpot(SaveKind.SAVE, "vita.savedata", root = savedata, folders = learned))
            return listOf(SaveAdapters.unavailable(SaveKind.SAVE, "vita.savedata", SaveAdapters.PLAY_ONCE, learnIn = savedata))
        }
        return listOf(SaveSpot(SaveKind.SAVE, "vita.savedata", root = savedata, folders = listOf(id)))
    }
}

/** shadPS4: each game's saves by its CUSA id, in its user folder (beside it when portable). */
internal object ShadPs4 : SaveAdapter {
    override val emulators = setOf("shadps4")

    override fun locate(q: SaveQuery, env: SaveEnvironment): List<SaveSpot> {
        val user = SaveAdapters.chosen(env, q) ?: SaveAdapters.firstDir(
            env,
            q.emulatorPath?.let { SaveAdapters.parent(it) + "/user" },
            "${SaveAdapters.xdgData(env)}/shadPS4",
            "${env.home}/.var/app/net.shadps4.shadPS4/data/shadPS4",
            "${SaveAdapters.macSupport(env)}/shadPS4",
            "${SaveAdapters.appData(env)}/shadPS4",
        ) ?: return listOf(SaveAdapters.unavailable(SaveKind.SAVE, "ps4.savedata", "shadPS4's user folder wasn't found here."))
        val savedata = "$user/savedata/1"
        val id = q.serial?.uppercase()?.takeIf { it.startsWith("CUSA") || it.startsWith("PPSA") } ?: run {
            val learned = SaveAdapters.learned(env, q, "ps4.savedata")
            if (learned.isNotEmpty()) return listOf(SaveSpot(SaveKind.SAVE, "ps4.savedata", root = savedata, folders = learned))
            return listOf(SaveAdapters.unavailable(SaveKind.SAVE, "ps4.savedata", SaveAdapters.PLAY_ONCE, learnIn = savedata))
        }
        return listOf(SaveSpot(SaveKind.SAVE, "ps4.savedata", root = savedata, folders = listOf(id)))
    }
}

/** Flycast: the Dreamcast's VMU files, shared by every game on them. */
internal object Flycast : SaveAdapter {
    override val emulators = setOf("flycast")

    override fun locate(q: SaveQuery, env: SaveEnvironment): List<SaveSpot> {
        val dir = SaveAdapters.chosen(env, q) ?: when (env.host) {
            "WINDOWS" -> SaveAdapters.firstDir(env, q.emulatorPath?.let { SaveAdapters.parent(it) + "/data" })
            "MACOS" -> SaveAdapters.firstDir(env, "${SaveAdapters.macSupport(env)}/Flycast")
            "ANDROID" -> SaveAdapters.firstDir(env, "/storage/emulated/0/Flycast/data")
                ?: DataFolders.find(env, "flycast", listOf("flycast", "dreamcast")) { d -> "$d/data".takeIf { env.exists("$it/vmu_save_A1.bin") } }.firstOrNull()
            else -> SaveAdapters.firstDir(env, "${SaveAdapters.xdgData(env)}/flycast", "${env.home}/.var/app/org.flycast.Flycast/data/flycast")
        } ?: return listOf(SaveAdapters.unavailable(SaveKind.MEMORY_CARD, "dc.vmu", if (env.host == "ANDROID") SaveAdapters.androidPrivateNote("Flycast", "Use its save data export, then choose that folder in Fuse, Settings, Save folders.") else "Flycast's folder wasn't found here."))
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
    fun compatible(saved: String, wanted: String): Boolean = saved == wanted || SaveConversions.canConvert(saved, wanted)
}

/**
 * Saves that are the same game's save kept in another shape by another emulator, turned into the
 * shape wanted here: DraStic's `.dsv` is a raw DS save with DeSmuME's footer after it, and an N64
 * game's saves are four files to Mupen64Plus but one `.srm` to RetroArch.
 */
object SaveConversions {
    private val pairs = setOf("nds.dsv" to "sram", "sram" to "nds.dsv", "n64.split" to "retroarch.n64", "retroarch.n64" to "n64.split")

    fun canConvert(from: String, to: String): Boolean = (from to to) in pairs

    /** [files] (name to bytes, names as the save names them) in [to]'s shape; null when it can't be done. */
    fun convert(from: String, to: String, files: Map<String, ByteArray>): Map<String, ByteArray>? = when (from to to) {
        "nds.dsv" to "sram" -> files.mapValues { (_, b) -> stripDesmumeFooter(b) }
        // DraStic reads a raw save as it is.
        "sram" to "nds.dsv" -> files
        "n64.split" to "retroarch.n64" -> mapOf("save.srm" to combineN64(files))
        "retroarch.n64" to "n64.split" -> files["save.srm"]?.let(::splitN64)
        else -> null
    }

    private const val DESMUME_FOOTER = 122
    private val DESMUME_MARK = "|-DESMUME SAVE-|".encodeToByteArray()

    internal fun stripDesmumeFooter(b: ByteArray): ByteArray {
        if (b.size < DESMUME_FOOTER) return b
        val tail = b.copyOfRange(b.size - DESMUME_MARK.size, b.size)
        return if (tail.contentEquals(DESMUME_MARK)) b.copyOfRange(0, b.size - DESMUME_FOOTER) else b
    }

    // RetroArch's N64 save: EEPROM, the four controller paks, SRAM, then FlashRAM, end to end.
    private val N64_PARTS = listOf("save.eep" to 0x800, "save.mpk" to 0x20000, "save.sra" to 0x8000, "save.fla" to 0x20000)
    private const val N64_SIZE = 0x48800

    internal fun combineN64(files: Map<String, ByteArray>): ByteArray {
        val out = ByteArray(N64_SIZE)
        var at = 0
        for ((name, size) in N64_PARTS) {
            files[name]?.let { it.copyInto(out, at, 0, minOf(size, it.size)) }
            at += size
        }
        return out
    }

    /** The parts a game actually uses (any byte not blank); the rest stay unwritten. */
    internal fun splitN64(srm: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        var at = 0
        for ((name, size) in N64_PARTS) {
            if (at + size <= srm.size) {
                val part = srm.copyOfRange(at, at + size)
                if (part.any { it != 0.toByte() && it != 0xFF.toByte() }) out[name] = part
            }
            at += size
        }
        return out
    }
}

/**
 * One emulator the library plays in, and where its saves stand on this device (Settings, Save
 * folders). [emulator] is the id a chosen folder is kept under.
 */
data class EmulatorSaves(
    val emulator: String,
    val emulatorId: String,
    /** The systems in the library it plays. */
    val systems: List<String>,
    val state: State,
    /** Where its saves are here: a folder, or words such as "Beside each game". */
    val where: String? = null,
    /** The folder the person chose for it, when they did. */
    val chosen: String? = null,
    /** Why Fuse can't reach them, or what helps. */
    val note: String? = null,
    /** Whether a chosen folder means anything to it (RetroArch says where its saves are itself). */
    val canChoose: Boolean = true,
) {
    /** In the order the page lists them: what needs the person first. */
    enum class State { NOT_FOUND, FOUND, UNSUPPORTED }
}

/** Where every emulator in [samples] (one game per system is enough) keeps its saves here. */
fun SaveAdapters.survey(samples: List<SaveQuery>, env: SaveEnvironment): List<EmulatorSaves> =
    samples.groupBy { baseId(it.emulatorId) }.map { (emulator, qs) ->
        val q = qs.first()
        val systems = qs.map { it.platform }.distinct()
        val adapter = forEmulator(q.emulatorId)
            ?: return@map EmulatorSaves(emulator, q.emulatorId, systems, EmulatorSaves.State.UNSUPPORTED, note = whyNot(q.emulatorId), canChoose = false)
        val chosen = env.saveFolder(emulator)
        val spots = qs.flatMap { runCatching { adapter.locate(it, env) }.getOrDefault(emptyList()) }.filter { it.kind != SaveKind.STATE }
        val reached = spots.firstOrNull { it.available }
        val where = when {
            reached == null -> null
            adapter === BesideRom && chosen == null -> "Beside each game"
            reached.root != null -> reached.root
            else -> reached.files.firstOrNull()?.path?.let(::parent)
        }
        EmulatorSaves(
            emulator, q.emulatorId, systems,
            state = if (reached != null) EmulatorSaves.State.FOUND else EmulatorSaves.State.NOT_FOUND,
            where = where, chosen = chosen,
            note = if (reached == null) spots.firstNotNullOfOrNull { it.note } else null,
            canChoose = adapter !== RetroArch,
        )
    }.sortedWith(compareBy({ it.state.ordinal }, { it.emulator }))
