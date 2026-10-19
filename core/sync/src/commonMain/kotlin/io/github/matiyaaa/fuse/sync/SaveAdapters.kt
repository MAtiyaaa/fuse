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

    /**
     * A folder in Fuse's own storage for a part of [game]'s save the emulator has no place for yet
     * ([part], such as a device save Ryujinx hasn't made a record for): kept there, never dropped,
     * and carried on with the rest of the save. Null where there is no such storage (tests).
     */
    fun held(game: String, part: String): String? = null

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
@kotlinx.serialization.Serializable
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
    /**
     * The game's own folders in the folder a [learnIn] names (a 3DS game's title id folder), when
     * this device knows them. They travel with the save, so a device that can't read the game's id
     * (a compressed image) puts it in place without a play to learn from.
     */
    val learnedAs: List<String> = emptyList(),
    /** How sure Fuse is that the save is (or goes) here; see [SaveConfidence]. */
    val confidence: SaveConfidence = SaveConfidence.KNOWN,
    /**
     * More parts of the same save kept in other folders, by the name their files carry in the save
     * (`@device` for a Switch game's device save beside its account save): a file named
     * `@device/x` is `x` in that folder. The parts are one save, captured and put back together.
     */
    val mounts: Map<String, String> = emptyMap(),
    /**
     * For a [root] shared by many games' files (a GameCube GCI folder): only files whose names
     * start with this are this game's. The others are never read, written or removed.
     */
    val only: String? = null,
) {
    /** Where the file named [name] goes here. */
    fun pathFor(name: String): String? = files.firstOrNull { it.name == name }?.path
        ?: mounts.entries.firstOrNull { name.startsWith(it.key + "/") }?.let { (p, dir) -> "$dir/${name.removePrefix("$p/")}" }
        ?: root?.let { "$it/$name" }
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
        RetroArch, Lemuroid, DuckStation, Pcsx2, Ppsspp, Dolphin, MelonDs, NooDs, Mgba, SkyEmu, Mednafen, MyBoy, UnpublishedHandheld, Rpcs3, Vita3k, ShadPs4, Flycast,
        DraStic, ExAlpha, Mupen64, Redream, Epsxe, Fpse, PlayPs2, SaturnBackup, Mame, ScummVm,
        ThreeDs, SwitchNand, Ryujinx, Cemu, Xenia, AnyPs5, PortSaves,
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
        "2600-emu" -> "Atari 2600 games have no saves of their own; the emulator's save states stay on this device."
        "c64-emu", "msx-emu" -> "Games on this computer save to their own disk or tape images, which Fuse doesn't change. Keep those files in step with Syncthing."
        "neo-emu", "ngp-emu", "swan-emu", "lynx-emu" -> "This EX+Alpha app keeps saves in a form Fuse doesn't read yet; RetroArch plays the same systems with saves Fuse keeps in step."
        "android-app" -> "An Android game keeps its own saves inside its app."
        "pico8" -> "PICO-8 keeps cartridge data in its own folder by cartridge; Fuse doesn't keep it in step yet."
        "ruffle" -> "Flash games keep their saves in Ruffle's own storage by site; Fuse doesn't keep it in step yet."
        "kytyps5" -> "This emulator is early, and where it keeps saves still changes between versions."
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

    /** [path] from an emulator's settings: as it is when absolute, else inside [base]. */
    internal fun resolve(base: String, path: String): String {
        val p = path.replace('\\', '/').trimEnd('/')
        return if (p.startsWith("/") || (p.length > 1 && p[1] == ':')) p else "$base/$p"
    }

    internal fun appData(env: SaveEnvironment): String = env.env("APPDATA")?.replace('\\', '/') ?: "${env.home}/AppData/Roaming"
    internal fun localAppData(env: SaveEnvironment): String = env.env("LOCALAPPDATA")?.replace('\\', '/') ?: "${env.home}/AppData/Local"
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

    /**
     * Where "default" saves and states go, as RetroArch's frontend drivers set them: on Android
     * `RetroArch` in the device's storage (platform_unix.c, storage writable), on a Mac
     * `~/Documents/RetroArch` unless it is portable or Steam's, which keep them in Application
     * Support (platform_darwin.m), and elsewhere beside its config.
     */
    private fun defaults(env: SaveEnvironment, config: String?, emuPath: String?, emulatorId: String): Pair<String, String> {
        val macData = "${SaveAdapters.macSupport(env)}/RetroArch"
        val base = when {
            env.host == "ANDROID" -> "/storage/emulated/0/RetroArch"
            env.host == "MACOS" -> if (emulatorId.endsWith("retroarch-steam") || env.exists("$macData/portable.txt")) macData else "${SaveAdapters.documents(env)}/RetroArch"
            config != null -> SaveAdapters.parent(config)
            env.host == "WINDOWS" && emuPath != null -> SaveAdapters.parent(emuPath)
            else -> "${SaveAdapters.xdgConfig(env)}/retroarch"
        }
        return "$base/saves" to "$base/states"
    }

    override fun locate(q: SaveQuery, env: SaveEnvironment): List<SaveSpot> {
        val config = configs(env, q.emulatorPath).firstOrNull(env::exists)
            // On Android, its folder may be anywhere the person moved it.
            ?: DataFolders.find(env, "retroarch", listOf("retroarch")) { d -> "$d/retroarch.cfg".takeIf(env::exists) }.firstOrNull()
        val base = config?.let { env.readText(it) }?.let(::parseCfg).orEmpty()
        val (defSaves, defStates) = defaults(env, config, q.emulatorPath, q.emulatorId)
        val content = SaveAdapters.parent(q.romPath)
        val coreName = q.core?.let { CoreNames.libraryName(it) }
        val stem = SaveAdapters.stem(q.romPath)
        // The configuration in effect for this game (retroarch.cfg, then the core's, the content
        // folder's and the game's own overrides, each over the one before, as RetroArch loads them).
        val configDir = base["rgui_config_directory"]?.replace('\\', '/')?.takeIf { it.isNotBlank() && it != "default" }
            ?.let { if (it.startsWith(":")) SaveAdapters.join(config?.let(SaveAdapters::parent) ?: "", it.drop(1).trimStart('/')) else it }
            ?: config?.let { "${SaveAdapters.parent(it)}/config" }
        val overrides = if (configDir != null && coreName != null) {
            listOf("$coreName/$coreName.cfg", "$coreName/${content.substringAfterLast('/')}.cfg", "$coreName/$stem.cfg")
                .mapNotNull { env.readText("$configDir/$it")?.let(::parseCfg) }
        } else emptyList()
        val cfg = overrides.fold(base) { acc, o -> acc + o }
        fun dir(key: String, fallback: String): String {
            val v = cfg[key]?.replace('\\', '/')?.let { if (it.startsWith("~")) env.home + it.drop(1) else it }
            return when {
                // Set but empty: RetroArch saves beside the game (runloop_path_set_redirect).
                v != null && v.isEmpty() -> content
                v == null || v == "default" -> fallback
                v.startsWith(":") -> SaveAdapters.join(config?.let(SaveAdapters::parent) ?: "", v.drop(1).trimStart('/'))
                else -> v
            }
        }
        val inContent = cfg["savefiles_in_content_dir"] == "true"
        val statesInContent = cfg["savestates_in_content_dir"] == "true"
        val coreFolder = coreName
        val contentFolder = SaveAdapters.parent(q.romPath).substringAfterLast('/')
        // RetroArch's own order (runloop_path_set_redirect): the content's folder, then the core's.
        fun sorted(dir: String, byCore: Boolean, byContent: Boolean): String = buildString {
            append(dir)
            if (byContent) append('/').append(contentFolder)
            if (byCore && coreFolder != null) append('/').append(coreFolder)
        }
        val savesDir = sorted(if (inContent) content else dir("savefile_directory", defSaves), cfg["sort_savefiles_enable"] == "true", cfg["sort_savefiles_by_content_enable"] == "true")
        val statesDir = sorted(if (statesInContent) content else dir("savestate_directory", defStates), cfg["sort_savestates_enable"] == "true", cfg["sort_savestates_by_content_enable"] == "true")
        val format = SaveAdapters.sramFormat(q.platform)
        val system = cfg["system_directory"]?.replace('\\', '/')?.takeIf { it.isNotBlank() && it != "default" }
            ?.let { if (it.startsWith(":")) SaveAdapters.join(config?.let(SaveAdapters::parent) ?: "", it.drop(1).trimStart('/')) else it }
            ?: "${SaveAdapters.parent(defSaves)}/system"
        val save = when (val layout = RetroArchCoreSaves.layout(q.core)) {
            // The frontend's save RAM (RETRO_MEMORY_SAVE_RAM) and the clock beside it (RETRO_MEMORY_RTC).
            RetroArchCoreSaves.Layout.SRAM -> spot(env, SaveKind.SAVE, format, savesDir, listOf("save.srm" to "$stem.srm", "save.rtc" to "$stem.rtc") + RetroArchCoreSaves.extras(q.core, stem))
            RetroArchCoreSaves.Layout.FLYCAST -> flycast(env, q, savesDir, system, configDir, cfg, stem)
            RetroArchCoreSaves.Layout.DOSBOX_PURE -> spot(env, SaveKind.SAVE, "dosbox.pure", savesDir, listOf("save.pure.zip" to "$stem.pure.zip", "save.save.zip" to "$stem.save.zip"))
            RetroArchCoreSaves.Layout.PPSSPP -> {
                val savedata = "$system/PPSSPP/PSP/SAVEDATA"
                val id = q.serial?.uppercase()?.replace("-", "")
                val folders = id?.let { i -> env.list(savedata).filter { it.startsWith(i) } }.orEmpty()
                if (id == null) SaveAdapters.unavailable(SaveKind.SAVE, "psp.savedata", "Fuse needs the game's serial (ULUS10041 and the like) to find its saves.")
                else SaveSpot(SaveKind.SAVE, "psp.savedata", root = savedata, folders = folders.ifEmpty { listOf(id) })
            }
            is RetroArchCoreSaves.Layout.Unsupported -> SaveAdapters.unavailable(SaveKind.SAVE, format, layout.why)
        }
        // States: slots 0 to 9 and the auto state, tied to the core.
        val stateNames = listOf("state.auto" to "$stem.state.auto", "state" to "$stem.state") + (1..9).map { "state$it" to "$stem.state$it" }
        val states = spot(env, SaveKind.STATE, "retroarch.state:${q.core ?: "core"}", statesDir, stateNames)
        return listOf(save, states)
    }

    /**
     * Flycast's VMUs (shell/libretro/oslib.cpp getVmuPath): one shared VMU per port in the system
     * folder's dc folder, unless the flycast_per_content_vmus option gives games their own in the
     * save folder, named after the disc's product id (older versions: the content's name).
     */
    private fun flycast(env: SaveEnvironment, q: SaveQuery, saves: String, system: String, configDir: String?, cfg: Map<String, String>, stem: String): SaveSpot {
        val options = listOfNotNull(
            cfg["global_core_options"]?.takeIf { it == "true" }?.let { configDir?.let { d -> "${SaveAdapters.parent(d)}/retroarch-core-options.cfg" } },
            configDir?.let { "$it/retroarch-core-options.cfg" }, configDir?.let { "$it/Flycast/Flycast.opt" }, configDir?.let { "$it/Flycast/$stem.opt" },
        ).mapNotNull { env.readText(it)?.let(::parseCfg) }.fold(emptyMap<String, String>()) { acc, o -> acc + o }
        val perGame = options["flycast_per_content_vmus"]?.let { it == "VMU A1" || it == "All VMUs" } == true
        if (!perGame) return SaveSpot(SaveKind.MEMORY_CARD, "dc.vmu", listOf(SpotFile("vmu_save_A1.bin", "$system/dc/vmu_save_A1.bin")))
        val id = q.serial?.trim()?.takeIf { it.isNotEmpty() }?.map { c -> if (c in " /\\:*?|<>") '_' else c }?.joinToString("")
        val byId = id?.let { "$saves/$it.A1.bin" }
        val legacy = "$saves/$stem.A1.bin"
        val path = byId?.takeIf { env.exists(it) || !env.exists(legacy) } ?: legacy
        return SaveSpot(SaveKind.SAVE, "dc.vmu.game", listOf(SpotFile("vmu_save_A1.bin", path)))
    }

    private fun spot(env: SaveEnvironment, kind: SaveKind, format: String, dir: String, names: List<Pair<String, String>>): SaveSpot {
        if (env.host == "ANDROID" && SaveAdapters.androidPrivate(dir)) {
            return SaveAdapters.unavailable(kind, format, "RetroArch keeps these in its own Android folder, which Android doesn't let other apps open. Set Saves and States to a folder in Settings, Directory.")
        }
        // A folder not made yet is fine (RetroArch makes it); a folder whose drive is gone isn't.
        return SaveSpot(kind, format, names.map { (n, f) -> SpotFile(n, "$dir/$f") }, available = env.isDirectory(dir) || env.isDirectory(SaveAdapters.parent(dir)))
    }
}

/** The library name of the libretro [core] (`mgba` to `mGBA`), which RetroArch names its per-core folders after; null when unknown. */
fun retroArchLibraryName(core: String): String? = CoreNames.libraryName(core)

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

    // Where DuckStation keeps its data, as its Core::SetDataRoot decides: beside the program when
    // portable.txt or settings.ini is there; on Windows Documents\DuckStation for older installs,
    // else %LOCALAPPDATA%\DuckStation; on Linux $XDG_CONFIG_HOME/duckstation when that is set, else
    // ~/.local/share/duckstation. Flatpak always sets XDG_CONFIG_HOME inside its sandbox, so its
    // Flatpak keeps everything in ~/.var/app/org.duckstation.DuckStation/config/duckstation.
    private fun dataDir(env: SaveEnvironment, emuPath: String?): String? = when (env.host) {
        "WINDOWS" -> SaveAdapters.firstDir(
            env,
            emuPath?.let(SaveAdapters::parent)?.takeIf { env.exists("$it/portable.txt") || env.exists("$it/settings.ini") },
            "${SaveAdapters.documents(env)}/DuckStation",
            "${SaveAdapters.localAppData(env)}/DuckStation",
        )
        "MACOS" -> SaveAdapters.firstDir(env, "${SaveAdapters.macSupport(env)}/DuckStation")
        "ANDROID" -> SaveAdapters.firstDir(env, "/storage/emulated/0/duckstation", "/storage/emulated/0/DuckStation", "/storage/emulated/0/Android/data/com.github.stenzek.duckstation/files")
            ?: DataFolders.find(env, "duckstation", listOf("duckstation", "psx", "ps1")) { d -> d.takeIf { env.isDirectory("$it/memcards") && (env.exists("$it/settings.ini") || env.isDirectory("$it/savestates")) } }.firstOrNull()
        else -> SaveAdapters.firstDir(
            env,
            emuPath?.let(SaveAdapters::parent)?.takeIf { env.exists("$it/portable.txt") || env.exists("$it/settings.ini") },
            env.env("XDG_CONFIG_HOME")?.takeIf { it.startsWith("/") }?.let { "$it/duckstation" },
            "${env.home}/.local/share/duckstation",
            "${SaveAdapters.xdgData(env)}/duckstation",
            "${env.home}/.var/app/org.duckstation.DuckStation/config/duckstation",
            "${env.home}/.var/app/org.duckstation.DuckStation/data/duckstation",
        )
    }

    override fun locate(q: SaveQuery, env: SaveEnvironment): List<SaveSpot> {
        val dir = SaveAdapters.chosen(env, q) ?: dataDir(env, q.emulatorPath)
            ?: return listOf(SaveAdapters.unavailable(SaveKind.SAVE, "psx.mcd", if (env.host == "ANDROID") SaveAdapters.androidPrivateNote("DuckStation", "Use its Transfer data (or choose its data folder in your storage), then choose that folder in Fuse, Settings, Save folders.") else "DuckStation's folder wasn't found here. Start DuckStation once so it makes it."))
        val serial = q.serial?.uppercase()
        // Its settings (settings.ini), and the game's own (gamesettings/<serial>.ini), which win.
        val global = IniText.parse(env.readText("$dir/settings.ini"))
        val game = serial?.let { IniText.parse(env.readText("$dir/gamesettings/$it.ini")) } ?: emptyMap()
        fun setting(key: String): String? = game["MemoryCards/$key"] ?: global["MemoryCards/$key"]
        // Its cards' folder: [MemoryCards] Directory, from its data folder when not absolute (EmuFolders).
        val cards = global["MemoryCards/Directory"]?.takeIf { it.isNotBlank() }?.let { SaveAdapters.resolve(dir, it) } ?: "$dir/memcards"
        val names = env.list(cards)
        val perGame = ArrayList<SpotFile>()
        val shared = ArrayList<SpotFile>()
        for (slot in 1..2) {
            // As settings.cpp: card 1 is per game by title unless set, card 2 is off unless set.
            val type = setting("Card${slot}Type") ?: if (slot == 1) "PerGameTitle" else "None"
            val custom = setting("Card${slot}Path")?.takeIf { it.isNotBlank() }
            when (type) {
                "Shared" -> shared += SpotFile("shared$slot.mcd", custom?.let { SaveAdapters.resolve(cards, it) } ?: "$cards/shared_card_$slot.mcd")
                // By serial (Settings::GetGameMemoryCardPath).
                "PerGame" -> serial?.let { perGame += SpotFile("card$slot.mcd", "$cards/${it}_$slot.mcd") }
                // By the game's file name.
                "PerGameFileTitle" -> perGame += SpotFile("card$slot.mcd", "$cards/${SaveAdapters.stem(q.romPath)}_$slot.mcd")
                // By its title (DuckStation's own, from its game list, or a multi-disc set's): the card
                // DuckStation made for this game is found by name; the serial one is its older default.
                "PerGameTitle" -> {
                    val byTitle = names.firstOrNull { n -> n.endsWith("_$slot.mcd") && TitleMatch.same(n.removeSuffix("_$slot.mcd"), q.title) }
                    val bySerial = serial?.let { s -> names.firstOrNull { it.equals("${s}_$slot.mcd", ignoreCase = true) } }
                    perGame += SpotFile("card$slot.mcd", "$cards/${byTitle ?: bySerial ?: "${q.title.ifBlank { SaveAdapters.stem(q.romPath) }}_$slot.mcd"}")
                }
                // None and NonPersistent keep nothing.
                else -> {}
            }
        }
        val states = serial?.let { s ->
            SaveSpot(SaveKind.STATE, "duckstation.state", (1..10).map { i -> SpotFile("state$i.sav", "$dir/savestates/${s}_$i.sav") } + SpotFile("resume.sav", "$dir/savestates/${s}_resume.sav"))
        }
        return listOfNotNull(
            perGame.takeIf { it.isNotEmpty() }?.let { SaveSpot(SaveKind.SAVE, "psx.mcd", it) },
            // A card every game shares is the person's, not this game's.
            shared.takeIf { it.isNotEmpty() }?.let { SaveSpot(SaveKind.MEMORY_CARD, "psx.sharedcard", it) },
            states,
        )
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
        // Its settings (inis/PCSX2.ini): the cards' folder, and each slot's card, on or off.
        val ini = IniText.parse(listOf("$dir/inis/PCSX2.ini", "$dir/inis/PCSX2_ui.ini", "$dir/PCSX2.ini").firstNotNullOfOrNull { env.readText(it) })
        val cards = ini["Folders/MemoryCards"]?.takeIf { it.isNotBlank() }?.let { SaveAdapters.resolve(dir, it) } ?: "$dir/memcards"
        val files = ArrayList<SpotFile>()
        val folders = LinkedHashMap<String, String>()
        for (slot in 1..2) {
            if (ini["MemoryCards/Slot${slot}_Enable"]?.equals("false", ignoreCase = true) == true) continue
            val name = ini["MemoryCards/Slot${slot}_Filename"]?.takeIf { it.isNotBlank() } ?: "Mcd00$slot.ps2"
            val path = SaveAdapters.resolve(cards, name)
            // The name each card goes by in the save is its slot's, whatever it is called here.
            val logical = "Mcd00$slot.ps2"
            // A folder card is a folder of its games' saves (it has PCSX2's superblock file).
            if (env.isDirectory(path)) folders[logical] = path else files += SpotFile(logical, path)
        }
        if (folders.isEmpty()) return listOf(SaveSpot(SaveKind.MEMORY_CARD, "ps2.card", files))
        // Folder cards aren't file cards: their own format, so neither is ever put where the other is read.
        return listOf(SaveSpot(SaveKind.MEMORY_CARD, "ps2.foldercard", files, root = "$cards/.fuse-cards", mounts = folders))
    }
}

/** PPSSPP: each game's save folders in SAVEDATA, named from its game id (ULUS10041...); states by id. */
internal object Ppsspp : SaveAdapter {
    override val emulators = setOf("ppsspp")

    /**
     * PPSSPP's memory stick (Windows/main.cpp, Core/Config.cpp): on Windows, installed.txt beside
     * the program means an installed copy, whose memory stick is the folder that file names or
     * Documents\PPSSPP; without it the copy is portable, with memstick beside it. A memstick_dir.txt
     * in its folder names a memory stick chosen elsewhere.
     */
    private fun windows(env: SaveEnvironment, emuPath: String?): String? {
        val dir = emuPath?.let { SaveAdapters.parent(it) }
        val installed = dir?.let { env.readText("$it/installed.txt") }
        if (dir != null && installed == null && env.isDirectory("$dir/memstick")) return "$dir/memstick"
        installed?.trim()?.lineSequence()?.firstOrNull()?.trim()?.replace('\\', '/')?.trimEnd('/')?.takeIf { it.isNotEmpty() && env.isDirectory(it) }?.let { return it }
        return SaveAdapters.firstDir(env, "${SaveAdapters.documents(env)}/PPSSPP")
    }

    private fun memstick(env: SaveEnvironment, emuPath: String?, id: String?): String? = memstickChosen(env, emuPath) ?: when (env.host) {
        "WINDOWS" -> windows(env, emuPath)
        "MACOS" -> SaveAdapters.firstDir(env, "${env.home}/.config/ppsspp", "${SaveAdapters.macSupport(env)}/PPSSPP")
        "ANDROID" -> "/storage/emulated/0".takeIf { env.isDirectory("$it/PSP") }
            ?: DataFolders.find(env, "ppsspp", listOf("ppsspp", "psp"), holds = { r -> id != null && env.list("$r/PSP/SAVEDATA").any { it.uppercase().startsWith(id) } }) { d ->
                d.takeIf { env.isDirectory("$it/PSP/SAVEDATA") || env.isDirectory("$it/PSP/SYSTEM") }
            }.firstOrNull()
        else -> SaveAdapters.firstDir(env, "${SaveAdapters.xdgConfig(env)}/ppsspp", "${env.home}/.var/app/org.ppsspp.PPSSPP/config/ppsspp")
    }

    /** A memory stick chosen in PPSSPP itself, named by memstick_dir.txt in its own folder. */
    private fun memstickChosen(env: SaveEnvironment, emuPath: String?): String? {
        val homes = listOfNotNull(emuPath?.let { SaveAdapters.parent(it) }, "${SaveAdapters.xdgConfig(env)}/ppsspp", "${env.home}/.var/app/org.ppsspp.PPSSPP/config/ppsspp")
        return homes.firstNotNullOfOrNull { env.readText("$it/memstick_dir.txt") }?.trim()?.replace('\\', '/')?.trimEnd('/')?.takeIf { it.isNotEmpty() && env.isDirectory(it) }
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

    /**
     * Dolphin's user folder (UICommon::SetUserDirectory): DOLPHIN_EMU_USERPATH when set; on Windows
     * a portable User beside the program, else Documents; on Linux ~/.dolphin-emu when it exists,
     * else its XDG data folder (the Flatpak's own); its own folder on macOS and Android.
     */
    internal fun userDir(env: SaveEnvironment, emuPath: String?): String? {
        env.env("DOLPHIN_EMU_USERPATH")?.replace('\\', '/')?.trimEnd('/')?.takeIf { env.isDirectory(it) }?.let { return it }
        return when (env.host) {
            "WINDOWS" -> SaveAdapters.firstDir(env, emuPath?.let { SaveAdapters.join(SaveAdapters.parent(it), "User") }?.takeIf { emuPath.let { p -> env.exists(SaveAdapters.parent(p) + "/portable.txt") } || env.isDirectory(it) }, "${SaveAdapters.documents(env)}/Dolphin Emulator", "${SaveAdapters.appData(env)}/Dolphin Emulator")
            "MACOS" -> SaveAdapters.firstDir(env, "${SaveAdapters.macSupport(env)}/Dolphin")
            "ANDROID" -> SaveAdapters.firstDir(env, "/storage/emulated/0/dolphin-emu")
                ?: DataFolders.find(env, "dolphin", listOf("dolphin", "gamecube", "wii")) { d -> d.takeIf { (env.isDirectory("$it/GC") || env.isDirectory("$it/Wii")) && env.isDirectory("$it/Config") } }.firstOrNull()
            else -> SaveAdapters.firstDir(env, "${env.home}/.dolphin-emu", "${SaveAdapters.xdgData(env)}/dolphin-emu", "${env.home}/.var/app/org.DolphinEmu.dolphin-emu/data/dolphin-emu")
        }
    }

    /** Dolphin.ini: in Config of the user folder, or on Linux in the XDG config folder beside an XDG data folder. */
    private fun config(env: SaveEnvironment, user: String): Map<String, String> {
        val xdg = when {
            user.endsWith("/.var/app/org.DolphinEmu.dolphin-emu/data/dolphin-emu") -> user.replace("/data/dolphin-emu", "/config/dolphin-emu")
            env.host == "LINUX" && user == "${SaveAdapters.xdgData(env)}/dolphin-emu" -> "${SaveAdapters.xdgConfig(env)}/dolphin-emu"
            else -> null
        }
        return IniText.parse(listOfNotNull(xdg?.let { "$it/Dolphin.ini" }, "$user/Config/Dolphin.ini").firstNotNullOfOrNull { env.readText(it) })
    }

    override fun locate(q: SaveQuery, env: SaveEnvironment): List<SaveSpot> {
        val user = SaveAdapters.chosen(env, q) ?: userDir(env, q.emulatorPath)
            ?: return listOf(SaveAdapters.unavailable(SaveKind.SAVE, "dolphin", if (env.host == "ANDROID") SaveAdapters.androidPrivateNote("Dolphin", "Use its Export user data, then choose that folder in Fuse, Settings, Save folders.") else "Dolphin's folder wasn't found here."))
        val ini = config(env, user)
        val id = q.serial?.uppercase()?.takeIf { it.length >= 4 }
        if (q.platform == "wii") {
            // The NAND: [General] NANDRootPath when set, else Wii in the user folder.
            val nand = ini["General/NANDRootPath"]?.takeIf { it.isNotBlank() }?.replace('\\', '/')?.trimEnd('/') ?: "$user/Wii"
            if (id == null) {
                val titles = "$nand/title/00010000"
                val learned = SaveAdapters.learned(env, q, "wii.nand").firstOrNull()
                    ?: return listOf(SaveAdapters.unavailable(SaveKind.SAVE, "wii.nand", SaveAdapters.PLAY_ONCE, learnIn = titles))
                return listOf(SaveSpot(SaveKind.SAVE, "wii.nand", root = "$titles/$learned/data"))
            }
            val title = id.take(4).map { c -> "%02x".format(c.code) }.joinToString("")
            // Disc games are 00010000; a title found elsewhere (00010004, games that install data) is used where it is.
            val where = listOf("00010000", "00010004").map { "$nand/title/$it/$title/data" }.firstOrNull(env::isDirectory) ?: "$nand/title/00010000/$title/data"
            return listOf(SaveSpot(SaveKind.SAVE, "wii.nand", root = where))
        }
        // GameCube. Its region's folders (Config::GetDirectoryForRegion), from the game id's fourth letter.
        val region = when (id?.getOrNull(3)) { 'E' -> "USA"; 'J' -> "JAP"; else -> "EUR" }
        val gci = ArrayList<String>()
        val raw = ArrayList<SpotFile>()
        for (slot in listOf('A', 'B')) {
            // Slot A is a GCI folder unless set (MainSettings.cpp MAIN_SLOT_A), slot B is empty.
            val type = ini["Core/Slot$slot"]?.toIntOrNull() ?: if (slot == 'A') FOLDER else NONE
            when (type) {
                FOLDER -> {
                    val custom = ini["Core/GCIFolder${slot}Path"]?.takeIf { it.isNotBlank() }?.replace('\\', '/')?.trimEnd('/')
                    gci += custom?.let { c -> listOf("/USA", "/JAP", "/EUR", "/DEV").firstOrNull { c.endsWith(it) }?.let { c.dropLast(it.length) } ?: c }?.let { "$it/$region" }
                        ?: "$user/GC/$region/Card $slot"
                }
                CARD -> {
                    val custom = ini["Core/Memcard${slot}Path"]?.takeIf { it.isNotBlank() }?.replace('\\', '/')
                    val path = custom?.let { c ->
                        val dir = SaveAdapters.parent(c)
                        val name = c.substringAfterLast('/').substringBeforeLast('.').let { n -> listOf(".USA", ".JAP", ".EUR", ".DEV").firstOrNull { n.endsWith(it) }?.let { n.dropLast(it.length) } ?: n }
                        "$dir/$name.$region.raw"
                    } ?: "$user/GC/MemoryCard$slot.$region.raw"
                    raw += SpotFile("MemoryCard$slot.$region.raw", path)
                }
            }
        }
        val spots = ArrayList<SaveSpot>()
        // A GCI folder keeps each game's saves as its own files ("<maker>-<game code>-<name>.gci"):
        // this game's alone, from slot A's folder, with slot B's as another part.
        if (gci.isNotEmpty() && id != null && id.length >= 6) {
            val prefix = "${id.substring(4, 6)}-${id.substring(0, 4)}-"
            spots += SaveSpot(SaveKind.SAVE, "gc.gci", root = gci[0], only = prefix,
                mounts = gci.drop(1).associate { "@cardB" to it })
        } else if (gci.isNotEmpty()) {
            spots += SaveAdapters.unavailable(SaveKind.SAVE, "gc.gci", "Fuse needs the game's id (GALE01 and the like) to tell its saves apart in Dolphin's GCI folder.")
        }
        // A memory card file holds every game on it: the person's, not one game's.
        if (raw.isNotEmpty()) spots += SaveSpot(SaveKind.MEMORY_CARD, "gc.card.$region", raw)
        return spots
    }

    private const val CARD = 1
    private const val FOLDER = 8
    private const val NONE = 0xFF
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
        val home = "${hdd0(env, base)}/home"
        val serial = q.serial?.uppercase()?.filter { it.isLetterOrDigit() }
        // The user RPCS3 plays as (its GUI settings' active_user), else the only one with this game's
        // save, else the only one there is: never another user's saves by accident.
        val users = env.list(home).filter { it.length == 8 && it.all(Char::isDigit) }
        val active = listOf("$base/GuiConfigs/CurrentSettings.ini", "$base/GuiConfigs/persistent_settings.dat").firstNotNullOfOrNull { f ->
            env.readText(f)?.let { Regex("active_user\\s*[=:]\\s*\"?(\\d{8})").find(it)?.groupValues?.get(1) }
        }
        val withGame = serial?.let { s -> users.filter { u -> env.list("$home/$u/savedata").any { it.uppercase().startsWith(s) } } }.orEmpty()
        val user = active?.takeIf { it in users || users.isEmpty() } ?: withGame.singleOrNull() ?: users.singleOrNull() ?: if (users.isEmpty()) "00000001" else null
            ?: return listOf(SaveAdapters.unavailable(SaveKind.SAVE, "ps3.savedata", "RPCS3 has more than one user, and Fuse couldn't tell which one you play as. Log in as yours in RPCS3's User Manager once, then Fuse keeps that user's saves in step."))
        val savedata = "$home/$user/savedata"
        if (serial == null) run {
            val learned = SaveAdapters.learned(env, q, "ps3.savedata")
            if (learned.isNotEmpty()) return listOf(SaveSpot(SaveKind.SAVE, "ps3.savedata", root = savedata, folders = learned))
            return listOf(SaveAdapters.unavailable(SaveKind.SAVE, "ps3.savedata", SaveAdapters.PLAY_ONCE, learnIn = savedata))
        }
        return listOf(SaveSpot(SaveKind.SAVE, "ps3.savedata", root = savedata, folders = env.list(savedata).filter { it.uppercase().startsWith(serial) }.ifEmpty { listOf(serial) }))
    }
}

/**
 * Where RPCS3's dev_hdd0 is: its vfs.yml (`config/vfs.yml` in its folder, `vfs.yml` in older
 * versions) may move it, as `/dev_hdd0/: $(EmulatorDir)dev_hdd0/` with `$(EmulatorDir)` its own
 * folder unless set (RPCS3's Emu/vfs_config.h). Unset or not there: beside its config.
 */
internal fun Rpcs3.hdd0(env: SaveEnvironment, base: String): String {
    val text = env.readText("$base/config/vfs.yml") ?: env.readText("$base/vfs.yml")
    if (text != null) {
        val values = text.lineSequence().mapNotNull { line ->
            val i = line.indexOf(": ")
            if (i <= 0) null else line.substring(0, i).trim().trim('"', '\'') to line.substring(i + 2).trim().trim('"', '\'')
        }.toMap()
        val emulatorDir = values["\$(EmulatorDir)"]?.takeIf { it.isNotEmpty() }?.replace('\\', '/')?.trimEnd('/') ?: base.trimEnd('/')
        val hdd = values["/dev_hdd0/"]?.takeIf { it.isNotEmpty() }?.replace("\$(EmulatorDir)", "$emulatorDir/")?.replace('\\', '/')?.replace("//", "/")?.trimEnd('/')
        if (hdd != null && env.isDirectory(hdd)) return hdd
    }
    return "$base/dev_hdd0"
}

/**
 * Vita3K: each game's saves under `ux0/user/00/savedata` by its title id. Where ux0 is, as its
 * init_paths decides: `portable/fs` beside the program when that folder is there; else the
 * `pref-path` its config.yml names; else SDL's preference folder (`Vita3K/Vita3K`, with ux0 in an
 * `fs` folder on a Mac unless an older ux0 is already there), or `Vita3K/Vita3K` in the XDG data
 * folder on Linux. On Android it is the app's own folder, which other apps can't open.
 */
internal object Vita3k : SaveAdapter {
    override val emulators = setOf("vita3k")

    private val PREF = Regex("^pref-path:\\s*['\"]?(.*?)['\"]?\\s*$")
    private val USER = Regex("^user-id:\\s*['\"]?(\\d+)['\"]?\\s*$")

    /** The folder holding the program, or a Mac app's folder, where a `portable` folder would be. */
    private fun programDir(emuPath: String?): String? = emuPath?.let { p ->
        p.substringBefore(".app/", "").takeIf { it.isNotEmpty() }?.let(SaveAdapters::parent) ?: SaveAdapters.parent(p)
    }

    private fun prefPath(env: SaveEnvironment, configDirs: List<String>): String? =
        configDirs.firstNotNullOfOrNull { env.readText("$it/config.yml") }?.lineSequence()
            ?.firstNotNullOfOrNull { PREF.find(it.trim())?.groupValues?.get(1) }
            ?.replace('\\', '/')?.trimEnd('/')?.takeIf { it.isNotEmpty() }

    internal fun ux0(env: SaveEnvironment, emuPath: String?): String? {
        val dir = programDir(emuPath)
        dir?.let { "$it/portable/fs/ux0" }?.takeIf(env::isDirectory)?.let { return it }
        val prefs = when (env.host) {
            "WINDOWS" -> "${SaveAdapters.appData(env)}/Vita3K/Vita3K"
            "MACOS" -> "${SaveAdapters.macSupport(env)}/Vita3K/Vita3K"
            else -> "${SaveAdapters.xdgData(env)}/Vita3K/Vita3K"
        }
        val configDirs = when (env.host) {
            // The config sits beside the program on Windows, in the preference folder on a Mac.
            "WINDOWS" -> listOfNotNull(dir)
            "MACOS" -> listOfNotNull(dir, prefs)
            else -> listOf("${SaveAdapters.xdgConfig(env)}/Vita3K")
        }
        prefPath(env, configDirs)?.let { "$it/ux0" }?.takeIf(env::isDirectory)?.let { return it }
        return SaveAdapters.firstDir(env, "$prefs/ux0", "$prefs/fs/ux0")
    }

    override fun locate(q: SaveQuery, env: SaveEnvironment): List<SaveSpot> {
        val base = SaveAdapters.chosen(env, q)?.let { c -> if (env.isDirectory("$c/ux0")) "$c/ux0" else c }
            ?: (if (env.host == "ANDROID") null else ux0(env, q.emulatorPath))
            ?: return listOf(SaveAdapters.unavailable(SaveKind.SAVE, "vita.savedata", if (env.host == "ANDROID") SaveAdapters.androidPrivateNote("Vita3K") else "Vita3K's folder wasn't found here."))
        // The user Vita3K plays as (config.yml user-id), else the only one there is, else its first, 00.
        val configured = listOfNotNull(programDir(q.emulatorPath), "${SaveAdapters.xdgConfig(env)}/Vita3K", "${SaveAdapters.macSupport(env)}/Vita3K/Vita3K")
            .firstNotNullOfOrNull { env.readText("$it/config.yml") }?.lineSequence()
            ?.firstNotNullOfOrNull { USER.find(it.trim())?.groupValues?.get(1) }
        val users = env.list("$base/user").filter { it.all(Char::isDigit) }
        val user = configured?.takeIf { it in users || users.isEmpty() } ?: users.singleOrNull() ?: "00"
        val savedata = "$base/user/$user/savedata"
        val id = q.serial?.uppercase()?.filter { it.isLetterOrDigit() } ?: run {
            val learned = SaveAdapters.learned(env, q, "vita.savedata")
            if (learned.isNotEmpty()) return listOf(SaveSpot(SaveKind.SAVE, "vita.savedata", root = savedata, folders = learned))
            return listOf(SaveAdapters.unavailable(SaveKind.SAVE, "vita.savedata", SaveAdapters.PLAY_ONCE, learnIn = savedata))
        }
        return listOf(SaveSpot(SaveKind.SAVE, "vita.savedata", root = savedata, folders = listOf(id)))
    }
}

/**
 * shadPS4: each game's saves by its CUSA id, in its user folder (`user` beside it when portable,
 * else the system's data folder; common/path_util.cpp). Newer builds keep them per console user,
 * `home/1000/savedata/<id>` for the first, and move the older `savedata/1/<id>` there on their
 * first start (core/user_manager.cpp CheckAndMigrateSaves); Fuse reads whichever holds the game.
 */
internal object ShadPs4 : SaveAdapter {
    override val emulators = setOf("shadps4")

    /** The newer per-user folder first, then the older one. */
    internal fun savedata(env: SaveEnvironment, user: String, id: String?): String {
        val newer = "$user/home/$FIRST_USER/savedata"
        val older = "$user/savedata/1"
        if (id != null) listOf(newer, older).firstOrNull { env.isDirectory("$it/$id") }?.let { return it }
        return if (env.isDirectory("$user/home") || !env.isDirectory(older)) newer else older
    }

    private const val FIRST_USER = "1000"

    override fun locate(q: SaveQuery, env: SaveEnvironment): List<SaveSpot> {
        val user = SaveAdapters.chosen(env, q) ?: SaveAdapters.firstDir(
            env,
            q.emulatorPath?.let { SaveAdapters.parent(it) + "/user" },
            "${SaveAdapters.xdgData(env)}/shadPS4",
            "${env.home}/.var/app/net.shadps4.shadPS4/data/shadPS4",
            "${SaveAdapters.macSupport(env)}/shadPS4",
            "${SaveAdapters.appData(env)}/shadPS4",
        ) ?: return listOf(SaveAdapters.unavailable(SaveKind.SAVE, "ps4.savedata", "shadPS4's user folder wasn't found here."))
        val id = q.serial?.uppercase()?.filter { it.isLetterOrDigit() }?.takeIf { it.startsWith("CUSA") || it.startsWith("PPSA") }
        val savedata = savedata(env, user, id)
        if (id == null) {
            val learned = SaveAdapters.learned(env, q, "ps4.savedata")
            if (learned.isNotEmpty()) return listOf(SaveSpot(SaveKind.SAVE, "ps4.savedata", root = savedata, folders = learned))
            return listOf(SaveAdapters.unavailable(SaveKind.SAVE, "ps4.savedata", SaveAdapters.PLAY_ONCE, learnIn = savedata))
        }
        return listOf(SaveSpot(SaveKind.SAVE, "ps4.savedata", root = savedata, folders = listOf(id)))
    }
}

/**
 * Flycast: the Dreamcast's VMUs, as its hostfs::getVmuPath names them. With "Per-game VMU" on (its
 * default, `PerGameVmu` in emu.cfg) the first VMU is the game's own, `<product number>_vmu_save_A1.bin`
 * with the disc's product number from its boot header; with it off, one `vmu_save_A1.bin` that every
 * game shares. A game's own VMU and a shared one are different things, so each only ever syncs with
 * its own kind. They are in the folder `Dreamcast.VMUPath` names, else Flycast's data folder:
 * `data` beside the program on Windows, `data` in its config folder on a Mac, Flycast in the XDG data
 * folder on Linux (core/windows/winmain.cpp, emulator-osx/osx-main.mm, linux-dist/main.cpp).
 */
internal object Flycast : SaveAdapter {
    override val emulators = setOf("flycast")

    private fun configDirs(env: SaveEnvironment, emuPath: String?): List<String> = when (env.host) {
        "WINDOWS" -> listOfNotNull(emuPath?.let(SaveAdapters::parent))
        "MACOS" -> listOf("${env.home}/.flycast", "${SaveAdapters.macSupport(env)}/Flycast")
        "ANDROID" -> listOf("/storage/emulated/0/Flycast")
        else -> listOf("${SaveAdapters.xdgConfig(env)}/flycast", "${env.home}/.var/app/org.flycast.Flycast/config/flycast")
    }

    private fun dataDir(env: SaveEnvironment, emuPath: String?): String? = when (env.host) {
        "WINDOWS" -> SaveAdapters.firstDir(env, emuPath?.let { SaveAdapters.parent(it) + "/data" })
        "MACOS" -> SaveAdapters.firstDir(env, "${env.home}/.flycast/data", "${SaveAdapters.macSupport(env)}/Flycast/data")
        "ANDROID" -> SaveAdapters.firstDir(env, "/storage/emulated/0/Flycast/data")
            ?: DataFolders.find(env, "flycast", listOf("flycast", "dreamcast")) { d -> "$d/data".takeIf { env.isDirectory(it) && env.exists("$d/emu.cfg") } }.firstOrNull()
        else -> SaveAdapters.firstDir(env, "${SaveAdapters.xdgData(env)}/flycast", "${env.home}/.var/app/org.flycast.Flycast/data/flycast")
    }

    override fun locate(q: SaveQuery, env: SaveEnvironment): List<SaveSpot> {
        val config = configDirs(env, q.emulatorPath).map { "$it/emu.cfg" }.firstOrNull(env::exists)?.let { env.readText(it) }
        val vmuPath = HandheldSaves.setting(config, "config", "Dreamcast.VMUPath")?.trimEnd('/')?.takeIf { it.isNotEmpty() && env.isDirectory(it) }
        val dir = SaveAdapters.chosen(env, q) ?: vmuPath ?: dataDir(env, q.emulatorPath)
            ?: return listOf(SaveAdapters.unavailable(SaveKind.MEMORY_CARD, "dc.vmu", if (env.host == "ANDROID") SaveAdapters.androidPrivateNote("Flycast", "Use its save data export, then choose that folder in Fuse, Settings, Save folders.") else "Flycast's folder wasn't found here."))
        val perGame = HandheldSaves.setting(config, "config", "PerGameVmu")?.lowercase()?.let { it == "yes" || it == "true" || it == "1" } ?: true
        if (!perGame) return listOf(SaveSpot(SaveKind.MEMORY_CARD, "dc.vmu", listOf(SpotFile("vmu_save_A1.bin", "$dir/vmu_save_A1.bin"))))
        val id = DreamcastDisc.productNumber(env, q.romPath) ?: q.serial?.trim()?.takeIf { it.isNotEmpty() }
            ?: return listOf(SaveAdapters.unavailable(SaveKind.SAVE, "dc.vmu.game", "Flycast names this game's own VMU after its product number, which Fuse reads from GDI games. For CHD and CDI games, turn off Per-game VMU A1 in Flycast to keep one shared VMU in step."))
        val name = id.map { if (it in VMU_INVALID) '_' else it }.joinToString("") + "_vmu_save_A1.bin"
        return listOf(SaveSpot(SaveKind.SAVE, "dc.vmu.game", listOf(SpotFile("vmu_save_A1.bin", "$dir/$name"))))
    }

    /** The characters Flycast swaps for `_` in a VMU's name. */
    private const val VMU_INVALID = " /\\:*?|<>\""
}

/**
 * A Dreamcast disc's product number, from its boot header (IP.BIN: "SEGA SEGAKATANA" at the start,
 * the product number at 0x40, ten characters), the way Flycast reads it to name a game's own VMU.
 * Read from a GDI's first high density track (track 3), or a plain image that starts with it.
 */
internal object DreamcastDisc {
    private const val MAGIC = "SEGA SEGAKATANA"

    fun productNumber(env: SaveEnvironment, path: String): String? {
        val track = when (path.substringAfterLast('.').lowercase()) {
            "gdi" -> gdiTrack3(env, path) ?: return null
            "iso", "bin", "img" -> path to 0L
            else -> return null
        }
        // Raw 2352-byte sectors carry 16 bytes of sync and header before the data.
        for (at in listOf(track.second, track.second + 16)) {
            val b = env.readBytes(track.first, at, 0x50) ?: continue
            if (b.size < 0x4A || b.decodeToString(0, MAGIC.length) != MAGIC) continue
            return b.decodeToString(0x40, 0x4A).substringBefore('\u0000').trimEnd().takeIf { it.isNotEmpty() }
        }
        return null
    }

    /** Track 3's file (beside the .gdi) and where its data begins, from the .gdi's track list. */
    private fun gdiTrack3(env: SaveEnvironment, gdi: String): Pair<String, Long>? {
        val text = env.readText(gdi) ?: return null
        for (line in text.lineSequence().drop(1)) {
            val parts = Regex("\"[^\"]*\"|\\S+").findAll(line.trim()).map { it.value.trim('"') }.toList()
            if (parts.size < 5 || parts[0] != "3") continue
            return "${SaveAdapters.parent(gdi)}/${parts[4]}" to 0L
        }
        return null
    }
}

/** Loose title matching for files emulators name after a game's title (DuckStation's cards). */
internal object TitleMatch {
    private fun norm(s: String) = s.lowercase().replace(Regex("\\(.*?\\)|\\[.*?]"), "").filter { it.isLetterOrDigit() }

    /** Letters and digits of English only: a save manager on the Switch may drop the rest ("Pokmon"). */
    private fun ascii(s: String) = norm(s).filter { it in 'a'..'z' || it in '0'..'9' }

    fun same(a: String, b: String): Boolean = norm(a).isNotEmpty() && (norm(a) == norm(b) || ascii(a).length >= 4 && ascii(a) == ascii(b))

    /** [title] begins with [cut], a name an emulator shortened (Mupen64Plus keeps 32 characters). */
    fun startsWith(title: String, cut: String): Boolean = norm(cut).length >= 8 && norm(title).startsWith(norm(cut))
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
        "nds.dsv" to "sram" -> files.mapValues { (_, b) -> stripDesmumeFooter(b) }.takeIf { m -> m.values.all { it.size in DS_SIZES } }
        // DraStic reads a raw save as it is.
        "sram" to "nds.dsv" -> files
        "n64.split" to "retroarch.n64" -> combineN64(files)?.let { mapOf("save.srm" to it) }
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

    // The sizes each part really comes in: a 4 or 16 kbit EEPROM, one to four controller paks.
    private val N64_ALLOWED = mapOf("save.eep" to setOf(0x200, 0x800), "save.mpk" to setOf(0x8000, 0x10000, 0x18000, 0x20000), "save.sra" to setOf(0x8000), "save.fla" to setOf(0x20000))

    // A DS save is one of the chips' sizes (512 bytes to 8 MiB), nothing in between.
    private val DS_SIZES = setOf(512, 8 * 1024, 32 * 1024, 64 * 1024, 128 * 1024, 256 * 1024, 512 * 1024, 1024 * 1024, 2 * 1024 * 1024, 8 * 1024 * 1024)

    /** Mupen64Plus's parts as RetroArch's one save; null for a part of a size no N64 save has (never cut or padded blindly). */
    internal fun combineN64(files: Map<String, ByteArray>): ByteArray? {
        val out = ByteArray(N64_SIZE)
        var at = 0
        for ((name, size) in N64_PARTS) {
            files[name]?.let { part ->
                if (part.size !in N64_ALLOWED.getValue(name)) return null
                part.copyInto(out, at)
            }
            at += size
        }
        return out
    }

    /** The parts a game actually uses (any byte not blank); the rest stay unwritten. */
    internal fun splitN64(srm: ByteArray): Map<String, ByteArray>? {
        // RetroArch's N64 save is always exactly this size: anything else isn't one.
        if (srm.size != N64_SIZE) return null
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
            chosen == null && qs.all { qq -> reached.files.firstOrNull()?.path?.let(::parent) == parent(qq.romPath) } -> "Beside each game"
            reached.root != null -> reached.root
            else -> reached.files.firstOrNull()?.path?.let(::parent)
        }
        EmulatorSaves(
            emulator, q.emulatorId, systems,
            state = if (reached != null) EmulatorSaves.State.FOUND else EmulatorSaves.State.NOT_FOUND,
            where = where, chosen = chosen,
            // Unreachable: why. Reached, but not certain or with a catch (melonDS's ROM list): that too.
            note = if (reached == null) spots.firstNotNullOfOrNull { it.note } else reached.note,
            canChoose = adapter !== RetroArch,
        )
    }.sortedWith(compareBy({ it.state.ordinal }, { it.emulator }))

/** An emulator's `.ini` settings as `Section/key` to value (the last one wins, as Qt and SimpleIni read them). */
internal object IniText {
    fun parse(text: String?): Map<String, String> {
        if (text == null) return emptyMap()
        val out = HashMap<String, String>()
        var section = ""
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith(";") || line.startsWith("#")) continue
            if (line.startsWith("[") && line.endsWith("]")) { section = line.substring(1, line.length - 1).trim().replace("%20", " "); continue }
            val i = line.indexOf('=')
            if (i <= 0) continue
            out["$section/${line.substring(0, i).trim()}"] = line.substring(i + 1).trim().trim('"')
        }
        return out
    }
}

/**
 * How each RetroArch core keeps its saves, from each core's own code, so Fuse never claims a save
 * is in step because it built an `.srm` path a core never writes:
 *
 * - [Layout.SRAM]: the core hands its save RAM to RetroArch (RETRO_MEMORY_SAVE_RAM), which writes
 *   `<content>.srm` (and `<content>.rtc` for a clock, RETRO_MEMORY_RTC). Almost every core.
 * - [Layout.FLYCAST], [Layout.DOSBOX_PURE], [Layout.PPSSPP]: cores that write their own files,
 *   placed as each does.
 * - [Layout.Unsupported]: cores whose saves live inside their own emulated storage, which Fuse
 *   doesn't read; it says so, and points to the standalone emulator Fuse does sync.
 */
object RetroArchCoreSaves {
    sealed interface Layout {
        data object SRAM : Layout
        data object FLYCAST : Layout
        data object DOSBOX_PURE : Layout
        data object PPSSPP : Layout
        data class Unsupported(val why: String) : Layout
    }

    private val own = mapOf(
        "flycast" to Layout.FLYCAST,
        "dosbox_pure" to Layout.DOSBOX_PURE,
        "ppsspp" to Layout.PPSSPP,
        "dolphin" to Layout.Unsupported("Dolphin's core keeps GameCube and Wii saves inside its own user folder. Dolphin itself syncs: play these with it."),
        "citra" to Layout.Unsupported("Citra's core keeps 3DS saves on its own emulated SD card. Azahar syncs 3DS saves: play these with it."),
        "citra_canary" to Layout.Unsupported("Citra's core keeps 3DS saves on its own emulated SD card. Azahar syncs 3DS saves: play these with it."),
        "panda3ds" to Layout.Unsupported("Panda3DS keeps 3DS saves on its own emulated SD card. Azahar syncs 3DS saves: play these with it."),
        "pcsx2" to Layout.Unsupported("LRPS2 keeps PlayStation 2 memory cards in its own folder. PCSX2 itself syncs its cards: play these with it."),
        "pcee2" to Layout.Unsupported("This PlayStation 2 core keeps memory cards in its own folder. PCSX2 itself syncs its cards."),
        "play" to Layout.Unsupported("Play! keeps PlayStation 2 memory cards in its own folder. PCSX2 itself syncs its cards."),
        "scummvm" to Layout.Unsupported("ScummVM's core keeps each game's saves in its own saves folder, by engine. Fuse doesn't keep those in step yet."),
        "dosbox_core" to Layout.Unsupported("This DOSBox core saves inside the game's own folder. DOSBox Pure keeps saves Fuse syncs."),
        "dosbox_svn" to Layout.Unsupported("This DOSBox core saves inside the game's own folder. DOSBox Pure keeps saves Fuse syncs."),
        "cemu" to Layout.Unsupported("Cemu's core keeps Wii U saves in its own storage. Cemu itself syncs: play these with it."),
        "mame" to Layout.Unsupported("MAME keeps each game's NVRAM and high scores in its own folders, not as a save RetroArch writes."),
    )

    fun layout(core: String?): Layout = own[core?.removeSuffix("_libretro")?.removeSuffix("_libretro_android")] ?: Layout.SRAM

    /**
     * Files a frontend-saved core keeps beside its `.srm`: Beetle PSX's second memory card,
     * `<content>.1.mcr`, when its option for it is on.
     */
    fun extras(core: String?, stem: String): List<Pair<String, String>> = when (core?.removeSuffix("_libretro")) {
        "mednafen_psx", "mednafen_psx_hw" -> listOf("save.1.mcr" to "$stem.1.mcr")
        else -> emptyList()
    }
}
