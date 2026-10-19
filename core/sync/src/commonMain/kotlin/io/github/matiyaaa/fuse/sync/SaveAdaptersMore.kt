package io.github.matiyaaa.fuse.sync

/*
 * The rest of the emulators Fuse plays games in: handhelds' and computers' own emulators, and the
 * consoles whose saves are kept by title id in an emulator's own storage (3DS, Switch, Wii U, Xbox
 * 360). Each finds a game's save the way that emulator keeps it; where it can't (an Android app's
 * private folder, a game never played here) it says why, and what the person can do.
 */

/** Hex of [len] bytes of [b] from [at], read as a little-endian number (title ids), upper case. */
internal fun leHex(b: ByteArray, at: Int, len: Int): String = buildString {
    for (i in len - 1 downTo 0) append(((b[at + i].toInt() and 0xFF) + 0x100).toString(16).substring(1))
}.uppercase()

private val HEX16 = Regex("^[0-9A-Fa-f]{16}$")
private val HEX32 = Regex("^[0-9A-Fa-f]{32}$")

/** Lemuroid: RetroArch's cores, but its saves are in its own private Android folder. */
internal object Lemuroid : SaveAdapter {
    override val emulators = setOf("lemuroid")

    override fun locate(q: SaveQuery, env: SaveEnvironment): List<SaveSpot> {
        val dir = SaveAdapters.chosen(env, q)
            ?: SaveAdapters.firstDir(env, "/storage/emulated/0/Android/data/com.swordfish.lemuroid/files/saves")
            ?: return listOf(SaveAdapters.unavailable(SaveKind.SAVE, SaveAdapters.sramFormat(q.platform), SaveAdapters.androidPrivateNote("Lemuroid", "RetroArch, with its saves in your storage, syncs fully.")))
        return listOf(SaveSpot(SaveKind.SAVE, SaveAdapters.sramFormat(q.platform), listOf(SpotFile("save.srm", "$dir/${SaveAdapters.stem(q.romPath)}.srm"))))
    }
}

/**
 * EX+Alpha's GBA.emu, GBC.emu, NES.emu and Snes9x EX+ (EmuFramework/src/EmuSystem.cc,
 * contentSaveFilePath): with the Main autosave slot, the battery save is `<game name>.sav`
 * (`.fds.sav` for a Disk System disk, `.srm` for Snes9x EX+), in the save folder chosen in its
 * options, else beside the game. A save folder chosen there is in its private settings, so the
 * person chooses the same one in Fuse. A named autosave slot keeps its own copy, which Fuse leaves.
 */
internal object ExAlpha : SaveAdapter {
    override val emulators = setOf("gba-emu", "gbc-emu", "nes-emu", "snes9x-explus")

    override fun locate(q: SaveQuery, env: SaveEnvironment): List<SaveSpot> {
        val dir = SaveAdapters.chosen(env, q) ?: SaveAdapters.parent(q.romPath)
        val stem = SaveAdapters.stem(q.romPath)
        val fds = q.platform == "fds" || q.romPath.endsWith(".fds", ignoreCase = true)
        val (ext, format) = when {
            SaveAdapters.baseId(q.emulatorId) == "snes9x-explus" -> ".srm" to SaveAdapters.sramFormat(q.platform)
            // FCEUX keeps a disk's changes in its own form, not as save RAM another emulator reads.
            fds -> ".fds.sav" to "fds.fceux"
            else -> ".sav" to SaveAdapters.sramFormat(q.platform)
        }
        return listOf(SaveSpot(SaveKind.SAVE, format, listOf(SpotFile("save.srm", "$dir/$stem$ext"))))
    }
}

/** DraStic: each game's save in `backup` as a `.dsv` (a raw DS save with DeSmuME's footer). */
internal object DraStic : SaveAdapter {
    override val emulators = setOf("drastic")

    override fun locate(q: SaveQuery, env: SaveEnvironment): List<SaveSpot> {
        val dir = SaveAdapters.chosen(env, q)
            ?: SaveAdapters.firstDir(env, *(listOf("/storage/emulated/0") + env.storageRoots()).distinct()
                .flatMap { r -> listOf("$r/DraStic", "$r/Drastic", "$r/drastic") }.plus(listOf("${env.home}/.drastic", "/mnt/vendor/deep/drastic")).toTypedArray())
            ?: return listOf(SaveAdapters.unavailable(SaveKind.SAVE, "nds.dsv", SaveAdapters.androidPrivateNote("DraStic", "In DraStic, Change Options, General, System Directory, choose Scoped Storage Folder and a folder named DraStic in your storage; Fuse finds it there.")))
        return listOf(SaveSpot(SaveKind.SAVE, "nds.dsv", listOf(SpotFile("save.srm", "$dir/backup/${SaveAdapters.stem(q.romPath)}.dsv"))))
    }
}

/**
 * Mupen64Plus (and M64Plus FZ, Mupen64Plus AE on Android): a game's saves as separate files named
 * after the game (EEPROM, SRAM, FlashRAM and the controller paks). Their names carry a hash Fuse
 * can't work out, so a game is synced once it has played here and made them.
 */
internal object Mupen64 : SaveAdapter {
    override val emulators = setOf("mupen64plus", "m64plus-fz", "mupen64plus-ae")
    private val parts = listOf("eep", "sra", "fla", "mpk")
    private const val GOOD_NAME_MAX = 32

    override fun locate(q: SaveQuery, env: SaveEnvironment): List<SaveSpot> {
        val dir = SaveAdapters.chosen(env, q) ?: when (env.host) {
            "WINDOWS" -> SaveAdapters.firstDir(env, q.emulatorPath?.let { SaveAdapters.parent(it) + "/save" }, "${SaveAdapters.appData(env)}/Mupen64Plus/save")
            "MACOS" -> SaveAdapters.firstDir(env, "${SaveAdapters.macSupport(env)}/Mupen64Plus/save")
            "ANDROID" -> null
            else -> SaveAdapters.firstDir(env, "${SaveAdapters.xdgData(env)}/mupen64plus/save", "${env.home}/.var/app/io.github.m64p.m64p/data/mupen64plus/save")
        } ?: return listOf(SaveAdapters.unavailable(SaveKind.SAVE, "n64.split", if (env.host == "ANDROID") SaveAdapters.androidPrivateNote("This N64 emulator", "In M64Plus FZ, Settings, Data, set Game Data Storage Location to External and pick a folder, then choose that folder in Fuse, Settings, Save folders.") else "Mupen64Plus's save folder wasn't found here."))
        val found = env.list(dir).filter { name ->
            val ext = name.substringAfterLast('.', "").lowercase()
            val base = name.substringBeforeLast('.')
            // `<good name, at most 32 characters>-<first 8 of the game's MD5>` (main.c get_save_filename),
            // or the good name alone (older versions).
            val named = base.substringBeforeLast('-')
            ext in parts && (TitleMatch.same(named, q.title) || TitleMatch.same(base, q.title) || TitleMatch.same(base, SaveAdapters.stem(q.romPath)) ||
                (named.length == GOOD_NAME_MAX && TitleMatch.startsWith(q.title, named)))
        }
        if (found.isEmpty()) return listOf(SaveAdapters.unavailable(SaveKind.SAVE, "n64.split", "Play it once here so Mupen64Plus names its save; from then on Fuse keeps it in step."))
        // Every part under the name Mupen64Plus gave this game, so a part it hasn't made yet (or one
        // that arrives from another device) has its place too.
        val base = found.first().substringBeforeLast('.')
        return listOf(SaveSpot(SaveKind.SAVE, "n64.split", parts.map { ext -> SpotFile("save.$ext", "$dir/$base.$ext") }))
    }
}

/**
 * Redream: the Dreamcast's first VMU, `vmu0.bin`, one file shared by every game on it (the same
 * bytes as Flycast's shared VMU). Redream keeps its files beside its program; RetroPie's install
 * keeps them in its configs folder.
 */
internal object Redream : SaveAdapter {
    override val emulators = setOf("redream")

    override fun locate(q: SaveQuery, env: SaveEnvironment): List<SaveSpot> {
        val dir = SaveAdapters.chosen(env, q)
            ?: q.emulatorPath?.let(SaveAdapters::parent)?.takeIf { env.exists("$it/vmu0.bin") || env.exists("$it/redream.cfg") }
            ?: SaveAdapters.firstDir(env, "/opt/retropie/configs/dreamcast/redream", "${SaveAdapters.xdgData(env)}/redream", "${SaveAdapters.appData(env)}/redream")
            ?: return listOf(SaveAdapters.unavailable(SaveKind.MEMORY_CARD, "dc.vmu", if (env.host == "ANDROID") SaveAdapters.androidPrivateNote("Redream") else "Redream's folder wasn't found here. It keeps its files beside its program."))
        return listOf(SaveSpot(SaveKind.MEMORY_CARD, "dc.vmu", listOf(SpotFile("vmu_save_A1.bin", "$dir/vmu0.bin"))))
    }
}

/** ePSXe: two memory cards shared by every game, `memcards/epsxe000.mcr` and `epsxe001.mcr` in its folder. */
internal object Epsxe : SaveAdapter {
    override val emulators = setOf("epsxe")

    override fun locate(q: SaveQuery, env: SaveEnvironment): List<SaveSpot> {
        val dir = SaveAdapters.chosen(env, q)?.let { c -> if (env.isDirectory("$c/memcards")) "$c/memcards" else c }
            ?: SaveAdapters.firstDir(env, q.emulatorPath?.let { SaveAdapters.parent(it) + "/memcards" }, "/storage/emulated/0/epsxe/memcards", "${env.home}/.epsxe/memcards")
            ?: return listOf(SaveAdapters.unavailable(SaveKind.MEMORY_CARD, "psx.card", "ePSXe's memory cards weren't found. Choose its memcards folder in Fuse, Settings, Save folders."))
        return listOf(SaveSpot(SaveKind.MEMORY_CARD, "psx.card", listOf(SpotFile("card1.mcr", "$dir/epsxe000.mcr"), SpotFile("card2.mcr", "$dir/epsxe001.mcr"))))
    }
}

/** FPse: its memory cards, in the folder the person points Fuse at. */
internal object Fpse : SaveAdapter {
    override val emulators = setOf("fpse", "fpse-ng")

    override fun locate(q: SaveQuery, env: SaveEnvironment): List<SaveSpot> {
        val dir = SaveAdapters.chosen(env, q)
            ?: return listOf(SaveAdapters.unavailable(SaveKind.MEMORY_CARD, "psx.card", "Choose FPse's memory card folder in Fuse, Settings, Save folders."))
        val cards = env.list(dir).filter { it.lowercase().endsWith(".mcd") || it.lowercase().endsWith(".mcr") }.take(2)
        if (cards.isEmpty()) return listOf(SaveAdapters.unavailable(SaveKind.MEMORY_CARD, "psx.card", "No memory cards in the folder chosen for FPse yet."))
        return listOf(SaveSpot(SaveKind.MEMORY_CARD, "psx.card", cards.mapIndexed { i, n -> SpotFile("card${i + 1}.mcr", "$dir/$n") }))
    }
}

/**
 * Play!: its first PlayStation 2 memory card, kept as a folder of saves, `vfs/mc0` in its data
 * folder unless its config.xml moves it (`ps2.mc0.directory`; Source/PS2VM.cpp). The data folder is
 * "Play Data Files" in the person's data folder, which Play!'s framework (PathUtils.cpp) takes as
 * Documents on Windows and a Mac, and on Linux XDG_CONFIG_HOME when that is set, else ~/.local/share.
 */
internal object PlayPs2 : SaveAdapter {
    override val emulators = setOf("play")
    private val MC0 = Regex("<Preference[^>]*Name=\"ps2\\.mc0\\.directory\"[^>]*Value=\"([^\"]*)\"")

    override fun locate(q: SaveQuery, env: SaveEnvironment): List<SaveSpot> {
        val data = SaveAdapters.firstDir(
            env,
            env.env("XDG_CONFIG_HOME")?.takeIf { env.host == "LINUX" }?.let { "$it/Play Data Files" },
            "${env.home}/.local/share/Play Data Files", "${SaveAdapters.documents(env)}/Play Data Files",
            "${env.home}/.var/app/org.purei.Play/config/Play Data Files", "${env.home}/.var/app/org.purei.Play/data/Play Data Files",
        )
        val set = data?.let { env.readText("$it/config.xml") }?.let { MC0.find(it)?.groupValues?.get(1) }
            ?.replace("&amp;", "&")?.replace('\\', '/')?.trimEnd('/')?.takeIf { it.isNotEmpty() && env.isDirectory(it) }
        val card = SaveAdapters.chosen(env, q)?.let { c -> if (env.isDirectory("$c/vfs/mc0")) "$c/vfs/mc0" else c }
            ?: set ?: data?.let { "$it/vfs/mc0" }
            ?: return listOf(SaveAdapters.unavailable(SaveKind.MEMORY_CARD, "play.mcr", if (env.host == "ANDROID") SaveAdapters.androidPrivateNote("Play!") else "Play!'s data folder wasn't found here."))
        return listOf(SaveSpot(SaveKind.MEMORY_CARD, "play.mcr", root = card))
    }
}

/** Saturn emulators on Android: their backup memory, in the folder the person points Fuse at. */
internal object SaturnBackup : SaveAdapter {
    override val emulators = setOf("yabasanshiro-2", "saturn-emu")

    override fun locate(q: SaveQuery, env: SaveEnvironment): List<SaveSpot> {
        val dir = SaveAdapters.chosen(env, q)
            ?: return listOf(SaveAdapters.unavailable(SaveKind.MEMORY_CARD, "saturn.bkr", "Choose this emulator's backup memory folder in Fuse, Settings, Save folders."))
        return listOf(SaveSpot(SaveKind.MEMORY_CARD, "saturn.bkr.${SaveAdapters.baseId(q.emulatorId)}", root = dir))
    }
}

/**
 * MAME (and MAME4droid): a machine's non-volatile memory (scores, settings) in `nvram/<set>`. The
 * folder is `nvram_directory` from mame.ini, found where MAME looks for it (`$HOME/.mame` on Linux,
 * also Application Support on a Mac; src/osd/sdl/sdlopts.cpp INI_PATH), else `nvram` in its folder.
 */
internal object Mame : SaveAdapter {
    override val emulators = setOf("mame", "mame4droid", "mame4droid-current")

    override fun locate(q: SaveQuery, env: SaveEnvironment): List<SaveSpot> {
        val base = SaveAdapters.chosen(env, q) ?: when (env.host) {
            "WINDOWS" -> SaveAdapters.firstDir(env, q.emulatorPath?.let(SaveAdapters::parent))
            "ANDROID" -> SaveAdapters.firstDir(env, "/storage/emulated/0/MAME4droid", "/storage/emulated/0/mame4droid")
            "MACOS" -> SaveAdapters.firstDir(env, "${SaveAdapters.macSupport(env)}/mame", "${env.home}/.mame")
            else -> SaveAdapters.firstDir(env, "${env.home}/.mame", "${env.home}/.var/app/org.mamedev.MAME/.mame")
        } ?: return listOf(SaveAdapters.unavailable(SaveKind.SAVE, "mame.nvram", "MAME's folder wasn't found here."))
        val nvram = nvramDir(env, base) ?: "$base/nvram"
        return listOf(SaveSpot(SaveKind.SAVE, "mame.nvram", root = nvram, folders = listOf(SaveAdapters.stem(q.romPath))))
    }

    /** `nvram_directory` from [base]'s mame.ini (or `ini/mame.ini`), with `$HOME` and `~` filled in. */
    internal fun nvramDir(env: SaveEnvironment, base: String): String? {
        val text = env.readText("$base/mame.ini") ?: env.readText("$base/ini/mame.ini") ?: return null
        val value = text.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("nvram_directory") && it.getOrNull(15)?.isWhitespace() == true }
            ?.substring(15)?.trim()?.trim('"')?.substringBefore(';')?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val path = value.replace("\$HOME", env.home).replace('\\', '/').let { if (it.startsWith("~")) env.home + it.drop(1) else it }.trimEnd('/')
        return if (path.startsWith("/") || (path.length > 1 && path[1] == ':')) path else "$base/$path"
    }
}

/**
 * ScummVM: every game's saves in one folder, kept as one (each person's own). The folder is
 * `savepath` in scummvm.ini when set; else, as its save managers register it, `Saved games` in its
 * Application Data folder (beside the program when portable) on Windows, `Savegames` in
 * Application Support on a Mac (`~/Documents/ScummVM Savegames` for older installs), and
 * `$XDG_DATA_HOME/scummvm/saves` on Linux.
 */
internal object ScummVm : SaveAdapter {
    override val emulators = setOf("scummvm")

    private fun configs(env: SaveEnvironment, emuPath: String?): List<String> = when (env.host) {
        "WINDOWS" -> listOfNotNull(emuPath?.let { SaveAdapters.parent(it) + "/scummvm.ini" }, "${SaveAdapters.appData(env)}/ScummVM/scummvm.ini")
        "MACOS" -> listOf("${env.home}/Library/Preferences/ScummVM Preferences")
        "ANDROID" -> emptyList()
        else -> listOf("${SaveAdapters.xdgConfig(env)}/scummvm/scummvm.ini", "${env.home}/.var/app/org.scummvm.ScummVM/config/scummvm/scummvm.ini")
    }

    override fun locate(q: SaveQuery, env: SaveEnvironment): List<SaveSpot> {
        val config = configs(env, q.emulatorPath).firstOrNull(env::exists)
        val set = HandheldSaves.setting(config?.let { env.readText(it) }, "scummvm", "savepath")?.trimEnd('/')?.takeIf { it.isNotEmpty() && env.isDirectory(it) }
        val dir = SaveAdapters.chosen(env, q) ?: set ?: when (env.host) {
            "WINDOWS" -> SaveAdapters.firstDir(env, q.emulatorPath?.let { SaveAdapters.parent(it) + "/Saved games" }, "${SaveAdapters.appData(env)}/ScummVM/Saved games")
            "MACOS" -> SaveAdapters.firstDir(env, "${SaveAdapters.macSupport(env)}/ScummVM/Savegames", "${env.home}/Documents/ScummVM Savegames")
            "ANDROID" -> null
            else -> SaveAdapters.firstDir(env, "${SaveAdapters.xdgData(env)}/scummvm/saves", "${env.home}/.var/app/org.scummvm.ScummVM/data/scummvm/saves")
        } ?: return listOf(SaveAdapters.unavailable(SaveKind.MEMORY_CARD, "scummvm.saves", if (env.host == "ANDROID") "Choose ScummVM's save path (Options, Paths) in Fuse, Settings, Save folders." else "ScummVM's save folder wasn't found here."))
        return listOf(SaveSpot(SaveKind.MEMORY_CARD, "scummvm.saves", root = dir))
    }
}

/**
 * Nintendo 3DS emulators (Azahar, Citra and its builds, Lime3DS, Mandarine): a game's save in the
 * emulated SD card under its title id, `sdmc/Nintendo 3DS/<id0>/<id1>/title/00040000/<id>/data`.
 * On Android the user folder is wherever the person picked it, so Fuse looks for it there too.
 */
internal object ThreeDs : SaveAdapter {
    override val emulators = setOf("azahar", "azaharplus", "citra", "citra-canary", "citra-mmj", "lime3ds", "mandarine")

    private const val FORMAT = "3ds.savedata"

    private fun base(env: SaveEnvironment, q: SaveQuery, id: String?): String? = SaveAdapters.chosen(env, q) ?: when (env.host) {
        "WINDOWS" -> SaveAdapters.firstDir(
            env, q.emulatorPath?.let { SaveAdapters.parent(it) + "/user" },
            "${SaveAdapters.appData(env)}/Azahar", "${SaveAdapters.appData(env)}/Citra", "${SaveAdapters.appData(env)}/Lime3DS", "${SaveAdapters.appData(env)}/Mandarine",
        )
        "MACOS" -> SaveAdapters.firstDir(env, "${SaveAdapters.macSupport(env)}/Azahar", "${SaveAdapters.macSupport(env)}/Citra", "${SaveAdapters.macSupport(env)}/Lime3DS")
        "ANDROID" -> SaveAdapters.firstDir(env, "/storage/emulated/0/Azahar", "/storage/emulated/0/azahar-emu", "/storage/emulated/0/citra-emu", "/storage/emulated/0/lime3ds-emu", "/storage/emulated/0/Citra")
            ?: DataFolders.find(env, "3ds", listOf("azahar", "citra", "lime3ds", "mandarine", "3ds"), holds = { b -> id != null && env.isDirectory(titles(env, b) + "/" + id.drop(8).lowercase()) }) { d ->
                d.takeIf { env.isDirectory("$it/sdmc/Nintendo 3DS") && (env.isDirectory("$it/nand") || env.isDirectory("$it/config")) }
            }.firstOrNull()
        else -> SaveAdapters.firstDir(
            env, "${SaveAdapters.xdgData(env)}/azahar-emu", "${env.home}/.var/app/org.azahar_emu.Azahar/data/azahar-emu",
            "${SaveAdapters.xdgData(env)}/citra-emu", "${env.home}/.var/app/org.citra_emu.citra/data/citra-emu",
            "${SaveAdapters.xdgData(env)}/lime3ds-emu", "${SaveAdapters.xdgData(env)}/mandarine-emu",
        )
    }

    /** Where the user folder [base] keeps games' saves: `sdmc/Nintendo 3DS/<id0>/<id1>/title/00040000`. */
    private fun titles(env: SaveEnvironment, base: String): String {
        val n3ds = "$base/sdmc/Nintendo 3DS"
        val id0 = env.list(n3ds).firstOrNull { HEX32.matches(it) } ?: ZERO
        val id1 = env.list("$n3ds/$id0").firstOrNull { HEX32.matches(it) } ?: ZERO
        return "$n3ds/$id0/$id1/title/00040000"
    }

    /**
     * The game's title id: its serial when Fuse has one, else what the file itself says (a cartridge
     * image's NCSD header, an NCCH's program id, a CIA's title metadata). Null for files whose header
     * is compressed (`.zcci`, `.zcxi`): their id is learned from the first play instead.
     */
    internal fun titleId(q: SaveQuery, env: SaveEnvironment): String? = titleId(q.serial, q.romPath, env)

    internal fun titleId(serial: String?, romPath: String, env: SaveEnvironment): String? {
        serial?.uppercase()?.takeIf { HEX16.matches(it) && it.startsWith("00040000") }?.let { return it }
        val b = env.readBytes(romPath, 0x100, 0x20)
        if (b != null && b.size >= 0x20) {
            when (b.decodeToString(0, 4)) {
                // Cartridge image: the media id at 0x108.
                "NCSD" -> return leHex(b, 8, 8).takeIf { it.startsWith("00040000") }
                // One NCCH (.cxi, .app): the program id at 0x118.
                "NCCH" -> return leHex(b, 0x18, 8).takeIf { it.startsWith("00040000") }
            }
        }
        return ciaTitle(env, romPath)?.let { "00040000" + it.takeLast(8) }
    }

    /**
     * A CIA's title id from its title metadata (TMD), laid out as 3dbrew documents it and as
     * `core/library`'s Cia reads it: a little-endian header of section sizes, each section aligned to
     * 64 bytes, then the TMD whose signature type says where its big-endian title id is.
     */
    internal fun ciaTitle(env: SaveEnvironment, path: String): String? {
        val head = env.readBytes(path, 0, 0x20) ?: return null
        if (head.size < 0x20 || le32(head, 0) != CIA_HEADER) return null
        fun align(v: Long) = (v + 63) / 64 * 64
        val ticketAt = align(align(CIA_HEADER) + le32(head, 0x08))
        val tmdAt = align(ticketAt + le32(head, 0x0C))
        val sig = env.readBytes(path, tmdAt, 4) ?: return null
        if (sig.size < 4) return null
        val header = when (((sig[0].toLong() and 0xFF) shl 24) or ((sig[1].toLong() and 0xFF) shl 16) or ((sig[2].toLong() and 0xFF) shl 8) or (sig[3].toLong() and 0xFF)) {
            0x10003L -> 0x240
            0x10004L -> 0x140
            0x10005L -> 0x80
            else -> return null
        }
        val id = env.readBytes(path, tmdAt + header + 0x4C, 8) ?: return null
        if (id.size < 8) return null
        return id.joinToString("") { ((it.toInt() and 0xFF) + 0x100).toString(16).substring(1) }.uppercase().takeIf { HEX16.matches(it) }
    }

    private fun le32(b: ByteArray, at: Int): Long =
        (b[at].toLong() and 0xFF) or ((b[at + 1].toLong() and 0xFF) shl 8) or ((b[at + 2].toLong() and 0xFF) shl 16) or ((b[at + 3].toLong() and 0xFF) shl 24)

    override fun locate(q: SaveQuery, env: SaveEnvironment): List<SaveSpot> {
        val read = titleId(q, env)
        val base = base(env, q, read) ?: return listOf(SaveAdapters.unavailable(SaveKind.SAVE, FORMAT, if (env.host == "ANDROID") "Fuse didn't find the user folder you picked in Azahar (the one with sdmc inside). Choose it in Fuse, Settings, Save folders." else "The 3DS emulator's folder wasn't found here."))
        val titles = titles(env, base)
        val id = read ?: SaveAdapters.learned(env, q, FORMAT).firstOrNull()?.let { "00040000" + it.uppercase() }
            ?: return listOf(SaveAdapters.unavailable(SaveKind.SAVE, FORMAT, SaveAdapters.PLAY_ONCE, learnIn = titles))
        val save = SaveSpot(SaveKind.SAVE, FORMAT, root = "$titles/${id.drop(8).lowercase()}/data", learnIn = titles, learnedAs = listOf(id.drop(8).lowercase()))
        // Save states: `states/<TITLE ID>.<slot>.cst` in the same user folder, slots 0 to 10.
        val states = SaveSpot(SaveKind.STATE, "3ds.state", (0..STATE_SLOTS).map { n ->
            val slot = n.toString().padStart(2, '0')
            SpotFile("state$slot", "$base/states/$id.$slot.cst")
        })
        return listOf(save, states)
    }

    private const val STATE_SLOTS = 10

    private const val CIA_HEADER = 0x2020L

    private const val ZERO = "00000000000000000000000000000000"
}

/**
 * Switch emulators of the yuzu family (Eden, Citron, Sudachi, yuzu and its forks): a game's save in
 * the emulated NAND, `nand/user/save/0000000000000000/<user>/<title id>`. Eden can keep saves (or the
 * whole NAND) in a folder of the person's choosing: its config's `Data Storage` group names it
 * (`save_directory`, `nand_directory`, as Eden's frontend_common/config.cpp writes them), and on
 * Android it is the folder chosen in Eden's Save Data Directory or NAND Directory setting. Whichever
 * folder the person picks in Fuse (Eden's own, its NAND or its save data folder) is read the same way.
 */
internal object SwitchNand : SaveAdapter {
    override val emulators = setOf("eden", "eden-nightly", "citron", "sudachi", "yuzu", "suyu", "torzu")

    private fun folderName(id: String) = when (id) {
        "eden-nightly" -> "eden"
        else -> id
    }

    private fun base(env: SaveEnvironment, q: SaveQuery): String? = SaveAdapters.chosen(env, q) ?: defaultBase(env, q)

    /**
     * The emulator's own folder, for its mods: the one the person chose when it is the emulator's
     * (it has a `load` or `config` folder), else where the emulator keeps it.
     */
    internal fun home(env: SaveEnvironment, q: SaveQuery): String? {
        SaveAdapters.chosen(env, q)?.takeIf { env.isDirectory("$it/load") || env.isDirectory("$it/config") }?.let { return it }
        return defaultBase(env, q)
    }

    /** Where the emulator keeps its folder when none was chosen. */
    private fun defaultBase(env: SaveEnvironment, q: SaveQuery): String? {
        val name = folderName(SaveAdapters.baseId(q.emulatorId))
        val cap = name.replaceFirstChar { it.uppercase() }
        return when (env.host) {
            "WINDOWS" -> SaveAdapters.firstDir(env, q.emulatorPath?.let { SaveAdapters.parent(it) + "/user" }, "${SaveAdapters.appData(env)}/$name", "${SaveAdapters.appData(env)}/$cap")
            // On macOS too they keep it in the XDG folders (common/fs/path_util.cpp has no branch of its own for it).
            "MACOS" -> SaveAdapters.firstDir(env, "${SaveAdapters.xdgData(env)}/$name", "${SaveAdapters.macSupport(env)}/$name", "${SaveAdapters.macSupport(env)}/$cap")
            "ANDROID" -> null
            else -> SaveAdapters.firstDir(env, "${SaveAdapters.xdgData(env)}/$name", "${env.home}/.var/app/org.${name}_emu.$name/data/$name", "${env.home}/.var/app/dev.${name}_emu.$name/data/$name")
        }
    }

    /** Its mods' folder: the one its config names (`load_directory`, as frontend_common/config.cpp writes it), else `load` in [base]. */
    internal fun loadFolder(env: SaveEnvironment, q: SaveQuery, base: String): String =
        dataStorage(env, q, base)["load_directory"]?.takeIf { it.isNotEmpty() && env.isDirectory(it) } ?: "$base/load"

    /**
     * The folder holding a NAND's `user/save` contents under [base] (the emulator's folder, or one
     * the person chose): the folder its config names first, then the layouts a chosen folder may
     * have (Eden's folder, its NAND, its save data folder, or the save folder itself). It is the one
     * with the legacy `0000000000000000` folder or the newer `account` and `device` folders.
     */
    internal fun saveSpace(env: SaveEnvironment, q: SaveQuery, base: String): String? {
        val roots = listOfNotNull(configured(env, q, base), base)
        return roots.flatMap { r -> listOf("$r/user/save", "$r/nand/user/save", "$r/save", r) }
            .firstOrNull { s -> env.isDirectory("$s/$ZERO_ID") || env.isDirectory("$s/account") || env.isDirectory("$s/device") }
    }

    /** The legacy users folder ([saveSpace]'s `0000000000000000`), kept for callers that read it. */
    internal fun usersFolder(env: SaveEnvironment, q: SaveQuery, base: String): String? =
        saveSpace(env, q, base)?.let { "$it/$ZERO_ID" }?.takeIf { env.isDirectory(it) }

    /** The save (or NAND) folder the emulator's config names, when it names one of its own. */
    internal fun configured(env: SaveEnvironment, q: SaveQuery, base: String): String? {
        val values = dataStorage(env, q, base)
        return listOf("save_directory", "nand_directory").firstNotNullOfOrNull { k -> values[k]?.takeIf { it.isNotEmpty() && env.isDirectory(it) } }
    }

    /**
     * Where the emulator's config (`qt-config.ini`) is: in `config` of its folder on Windows, when it
     * is portable and on Android; else in the XDG config folder, a Flatpak's own or the system's
     * (common/fs/path_util.cpp keeps data, config and cache in separate XDG folders there).
     */
    internal fun configFiles(env: SaveEnvironment, q: SaveQuery, base: String): List<String> {
        val name = folderName(SaveAdapters.baseId(q.emulatorId))
        val flatpak = Regex("^(.*/\\.var/app/[^/]+)/data/([^/]+)$").find(base)?.let { "${it.groupValues[1]}/config/${it.groupValues[2]}/qt-config.ini" }
        val xdg = "${SaveAdapters.xdgConfig(env)}/$name/qt-config.ini".takeIf { env.host != "WINDOWS" && env.host != "ANDROID" }
        return listOfNotNull("$base/config/qt-config.ini", flatpak, xdg).distinct()
    }

    /** The `Data Storage` group of the emulator's config (`qt-config.ini`), its folders with `/` separators. */
    private fun dataStorage(env: SaveEnvironment, q: SaveQuery, base: String): Map<String, String> {
        val text = configFiles(env, q, base).firstNotNullOfOrNull { env.readText(it) } ?: return emptyMap()
        val values = HashMap<String, String>()
        var inGroup = false
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("[")) { inGroup = line == "[Data%20Storage]" || line == "[Data Storage]"; continue }
            if (!inGroup) continue
            val i = line.indexOf('=')
            if (i <= 0) continue
            val key = line.substring(0, i).trim()
            // `key\default=false` lines say whether the value is the default, not where it is.
            if ('\\' in key) continue
            values[key] = line.substring(i + 1).trim().trim('"').replace("\\\\", "/").replace('\\', '/').trimEnd('/')
        }
        return values
    }

    private const val ZERO_ID = "0000000000000000"

    override fun locate(q: SaveQuery, env: SaveEnvironment): List<SaveSpot> {
        val base = base(env, q) ?: return listOf(SaveAdapters.unavailable(SaveKind.SAVE, FORMAT, if (env.host == "ANDROID") SaveAdapters.androidPrivateNote("This Switch emulator", "In Eden, Settings, choose a Save Data Directory (or NAND Directory) in your storage, then choose the same folder in Fuse, Settings, Save folders. Other Switch emulators can export saves from Manage save data.") else "The Switch emulator's folder wasn't found here."))
        val space = saveSpace(env, q, base) ?: "$base/nand/user/save"
        val read = SwitchIds.of(q, env)
        val legacy = "$space/$ZERO_ID"
        // Everyone the emulator has a save folder for, by their account id in Eden's newer spelling.
        val users = (env.list(legacy).filter { HEX32.matches(it) && it != ZERO_USER }.map(EdenUser::fromLegacy) +
            env.list("$space/account").filter { HEX32.matches(it) }.map { it.lowercase() }).distinct()
        if (read == null) {
            val user = users.singleOrNull()
                ?: return listOf(SaveAdapters.unavailable(SaveKind.SAVE, FORMAT, if (users.isEmpty()) NO_USER else MANY_USERS))
            val id = SaveAdapters.learned(env, q, FORMAT).firstOrNull()
                ?: return listOf(SaveAdapters.unavailable(SaveKind.SAVE, FORMAT, SaveAdapters.PLAY_ONCE, learnIn = "$legacy/${EdenUser.toLegacy(user)}"))
            return listOf(SaveSpot(SaveKind.SAVE, FORMAT, root = "$legacy/${EdenUser.toLegacy(user)}/$id"))
        }
        val masked = EdenUser.masked(read)
        fun accountPaths(user: String) = listOf("$space/account/$user/$masked/0", "$legacy/${EdenUser.toLegacy(user)}/$read")
        val devicePaths = listOf("$space/device/$masked/0", "$legacy/$ZERO_USER/$read")
        // The person the emulator plays as: the one its config selects, else the only one with this
        // game's save, else the only one there is. Never someone else's save by accident.
        val selected = EdenUser.current(env, configFiles(env, q, base), space)?.takeIf { it in users }
        val withGame = users.filter { u -> accountPaths(u).any(env::isDirectory) }
        val user = selected ?: withGame.singleOrNull() ?: users.singleOrNull().takeIf { withGame.isEmpty() }
        // Where the game's device save is (a save every user shares), or where it would go: the newer
        // place when the emulator already uses that layout. Carried with the account save as one.
        val device = devicePaths.firstOrNull(env::isDirectory)
            ?: if (env.isDirectory("$space/device") || env.isDirectory("$space/account")) devicePaths.first() else devicePaths.last()
        val deviceHere = devicePaths.any(env::isDirectory)
        if (user == null) {
            // Only a device save and no user to tell apart: the device save alone is enough to keep.
            if (deviceHere && withGame.isEmpty()) return listOf(SaveSpot(SaveKind.SAVE, FORMAT, root = "$space/$DEVICE_ONLY", confidence = SaveConfidence.FOUND, mounts = mapOf(DEVICE to device)))
            return listOf(SaveAdapters.unavailable(SaveKind.SAVE, FORMAT, if (users.isEmpty()) NO_USER else MANY_USERS))
        }
        // Eden prefers the newer place whenever it exists (savedata_factory.cpp GetFullPath).
        val account = accountPaths(user).firstOrNull(env::isDirectory)
        if (account != null || deviceHere) {
            val root = account ?: if (env.isDirectory("$space/account")) accountPaths(user).first() else accountPaths(user).last()
            return listOf(SaveSpot(SaveKind.SAVE, FORMAT, root = root, confidence = SaveConfidence.FOUND, mounts = mapOf(DEVICE to device)))
        }
        // Not saved yet: where the emulator will put it, the newer place when it already uses that layout.
        val next = if (env.isDirectory("$space/account")) accountPaths(user).first() else accountPaths(user).last()
        return listOf(SaveSpot(SaveKind.SAVE, FORMAT, root = next, mounts = mapOf(DEVICE to device), note = "Fuse looked in ${(accountPaths(user) + devicePaths).joinToString(", ")}. The game may not have saved yet."))
    }

    /** The name a game's device save carries inside its save, beside the account save's own files. */
    internal const val DEVICE = "@device"

    /** A folder that never exists, for a game with only a device save and no user: its account part stays empty. */
    private const val DEVICE_ONLY = ".fuse-no-account"

    private const val FORMAT = "switch.savedata"
    private const val ZERO_USER = "00000000000000000000000000000000"
    private const val NO_USER = "Play a game once in the emulator so it makes its user, then Fuse keeps saves in step."
    private const val MANY_USERS = "The Switch emulator has more than one user, and Fuse couldn't tell which one you play as. Choose that user's save folder in Fuse, Settings, Save folders."
}

/**
 * Eden's (and yuzu's) emulated users. A user id is 16 bytes. The legacy save folder spells it as
 * its high then low 64-bit halves in upper case hex (`{:016X}{:016X}` of user_id[1], user_id[0]),
 * the newer `account` folder as its bytes in order in lower case (Common::UUID::RawString), so the
 * same person reads the same in both. The profile in use is the `current_user` index of the
 * config's System group into the profile list (`system/save/8000000000000010/su/avators/profiles.dat`:
 * 0x10 bytes of header, then 0xC8 bytes per user, its id first).
 */
internal object EdenUser {
    /** The newer spelling of a legacy folder name. */
    fun fromLegacy(legacy: String): String {
        val hi = legacy.substring(0, 16)
        val lo = legacy.substring(16, 32)
        return (bytesLe(lo) + bytesLe(hi)).lowercase()
    }

    /** The legacy folder name of a newer spelling. */
    fun toLegacy(raw: String): String {
        val lo = raw.substring(0, 16)
        val hi = raw.substring(16, 32)
        return (bytesLe(hi) + bytesLe(lo)).uppercase()
    }

    /** A 64-bit number's hex as its bytes in little-endian order, and back: the byte pairs reversed. */
    private fun bytesLe(hex: String): String = hex.chunked(2).reversed().joinToString("")

    /** A title id with its low byte cleared, as the newer layout keeps it. */
    fun masked(titleId: String): String = titleId.uppercase().dropLast(2) + "00"

    /** The user the emulator is set to play as, when its config and profile list say so. */
    fun current(env: SaveEnvironment, configs: List<String>, space: String): String? {
        val index = configs.firstNotNullOfOrNull { env.readText(it) }?.let(::currentIndex) ?: return null
        // The save space is `<nand>/user/save`; the profile list is in `<nand>/system`.
        val nand = space.removeSuffix("/user/save").removeSuffix("/save")
        val profiles = listOf("$nand/system/save/8000000000000010/su/avators/profiles.dat")
        val bytes = profiles.firstNotNullOfOrNull { env.readBytes(it, HEADER + index * ENTRY.toLong(), 16) } ?: return null
        if (bytes.size < 16 || bytes.all { it == 0.toByte() }) return null
        return bytes.joinToString("") { ((it.toInt() and 0xFF) + 0x100).toString(16).substring(1) }
    }

    private fun currentIndex(text: String): Int? {
        var inSystem = false
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("[")) { inSystem = line == "[System]"; continue }
            if (inSystem && line.startsWith("current_user=")) return line.substringAfter('=').trim().toIntOrNull()?.takeIf { it in 0..7 }
        }
        return null
    }

    private const val HEADER = 0x10L
    private const val ENTRY = 0xC8
}

/** A Switch game's title id: from its serial (a `[0100…]` tag), else from the ticket in its NSP. */
internal object SwitchIds {
    fun of(q: SaveQuery, env: SaveEnvironment): String? {
        val id = q.serial?.uppercase()?.takeIf { HEX16.matches(it) && it.startsWith("01") }
            ?: TAG.findAll(q.romPath.substringAfterLast('/')).map { it.groupValues[1].uppercase() }.firstOrNull()
            ?: fromNsp(env, q.romPath)
        return id?.let(::program)
    }

    private val TAG = Regex("\\[(01[0-9A-Fa-f]{14})]")

    /** The game's own id for an update's (which ends in 800): saves are kept under the game's. */
    internal fun program(id: String): String = if (id.endsWith("800")) id.dropLast(3) + "000" else id

    /** An NSP is a PFS0: a plain list of names, where a ticket is named by its rights id (title id first). */
    internal fun fromNsp(env: SaveEnvironment, path: String): String? {
        // An NSZ is an NSP with its contents compressed: the same PFS0 list of names.
        if (!path.lowercase().let { it.endsWith(".nsp") || it.endsWith(".nsz") }) return null
        val head = env.readBytes(path, 0, 0x10) ?: return null
        if (head.size < 0x10 || head.decodeToString(0, 4) != "PFS0") return null
        val count = le32(head, 4)
        val tableSize = le32(head, 8)
        if (count !in 1..4096 || tableSize !in 1..(1 shl 20)) return null
        val names = env.readBytes(path, 0x10L + count * 0x18L, tableSize)?.decodeToString() ?: return null
        val tik = names.split('\u0000').firstOrNull { it.lowercase().endsWith(".tik") && it.length >= 36 } ?: return null
        return tik.take(16).uppercase().takeIf { HEX16.matches(it) }
    }

    private fun le32(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)
}

/**
 * Ryujinx and the emulators built from it (Kenji-NX, from Ryujinx's own Android branch): saves in
 * numbered folders (`bis/user/save/<n>/0`), each with an `ExtraData0` beside it whose first eight
 * bytes are the game's program id. A game is synced once it has played here.
 */
internal object Ryujinx : SaveAdapter {
    override val emulators = setOf("ryujinx", "kenji-nx")

    override fun locate(q: SaveQuery, env: SaveEnvironment): List<SaveSpot> {
        val base = SaveAdapters.chosen(env, q)?.let { c -> if (env.isDirectory("$c/bis")) c else SaveAdapters.parent(SaveAdapters.parent(SaveAdapters.parent(c))).takeIf { c.trimEnd('/').endsWith("bis/user/save") } ?: c } ?: defaultHome(env, q) ?: return listOf(SaveAdapters.unavailable(SaveKind.SAVE, "switch.savedata", if (env.host == "ANDROID") SaveAdapters.androidPrivateNote("Kenji-NX") else "Ryujinx's folder wasn't found here."))
        val id = SwitchIds.of(q, env) ?: return listOf(SaveAdapters.unavailable(SaveKind.SAVE, "switch.savedata", "Fuse doesn't know this game's title id yet (a [0100…] tag in its file name tells it)."))
        val saves = "$base/bis/user/save"
        // A game can have several saves (its account saves, one per user, a device save, BCAT and
        // cache data). Each numbered folder's ExtraData0 starts with the save's attribute
        // (switchbrew SaveDataAttribute): the program id at 0, the user at 8 (high then low half),
        // its type at 0x20 (1 account, 3 device).
        class Record(val folder: String, val user: String, val type: Int)
        val records = env.list(saves).mapNotNull { n ->
            val extra = env.readBytes("$saves/$n/ExtraData0", 0, 0x21) ?: return@mapNotNull null
            if (extra.size < 0x21 || leHex(extra, 0, 8) != id) return@mapNotNull null
            Record(n, (leHex(extra, 8, 8) + leHex(extra, 0x10, 8)).lowercase(), extra[0x20].toInt())
        }
        val accounts = records.filter { it.type == ACCOUNT }
        val device = records.firstOrNull { it.type == DEVICE }
        // The user Ryujinx plays as (system/Profiles.json, last_opened); else the only one with a save.
        val selected = Regex("\"last_opened\"\\s*:\\s*\"([0-9a-fA-F]{32})\"").find(env.readText("$base/system/Profiles.json").orEmpty())?.groupValues?.get(1)?.lowercase()
        val account = if (selected != null) accounts.firstOrNull { it.user == selected } else accounts.singleOrNull()
        if (account == null && accounts.isNotEmpty()) {
            // Never another user's save: say why instead.
            return listOf(SaveAdapters.unavailable(SaveKind.SAVE, "switch.savedata",
                if (selected != null) "Ryujinx hasn't made a save of this game for the user it plays as yet. Play it once in Ryujinx, then Fuse keeps it in step."
                else "Ryujinx has saves of this game for more than one user. Open Ryujinx with your user once, then Fuse keeps that one in step."))
        }
        if (account == null && device == null) {
            return listOf(SaveAdapters.unavailable(SaveKind.SAVE, "switch.savedata", "Play it once in Ryujinx so it makes its save; from then on Fuse keeps it in step."))
        }
        // A part Ryujinx has no record for yet is held by Fuse, never dropped and never put anywhere Ryujinx would misread.
        val devicePlace = device?.let { "$saves/${it.folder}/0" } ?: env.held(q.game.id, "switch-device")
        val root = account?.let { "$saves/${it.folder}/0" } ?: env.held(q.game.id, "switch-account")
            ?: return listOf(SaveAdapters.unavailable(SaveKind.SAVE, "switch.savedata", "Play it once in Ryujinx so it makes its save; from then on Fuse keeps it in step."))
        return listOf(SaveSpot(SaveKind.SAVE, "switch.savedata", root = root, confidence = SaveConfidence.FOUND,
            mounts = devicePlace?.let { mapOf(SwitchNand.DEVICE to it) }.orEmpty()))
    }

    /** Ryujinx's own folder (a `portable` one beside the program first), where its `bis` and `mods` are. */
    private fun defaultHome(env: SaveEnvironment, q: SaveQuery): String? = when (env.host) {
        "WINDOWS" -> SaveAdapters.firstDir(env, q.emulatorPath?.let { SaveAdapters.parent(it) + "/portable" }, "${SaveAdapters.appData(env)}/Ryujinx")
        "MACOS" -> SaveAdapters.firstDir(env, "${SaveAdapters.macSupport(env)}/Ryujinx")
        "ANDROID" -> null
        else -> SaveAdapters.firstDir(env, "${SaveAdapters.xdgConfig(env)}/Ryujinx", "${env.home}/.var/app/org.ryujinx.Ryujinx/config/Ryujinx")
    }

    /** Ryujinx's own folder, for its mods: the one the person chose when it is Ryujinx's, else where Ryujinx keeps it. */
    internal fun home(env: SaveEnvironment, q: SaveQuery): String? =
        SaveAdapters.chosen(env, q)?.takeIf { env.isDirectory("$it/bis") || env.isDirectory("$it/mods") } ?: defaultHome(env, q)

    private const val ACCOUNT = 1
    private const val DEVICE = 3
}

/** Cemu: a Wii U game's save in `mlc01/usr/save/00050000/<id>/user`, by the title id in its meta.xml. */
internal object Cemu : SaveAdapter {
    override val emulators = setOf("cemu")
    private val TITLE = Regex("<title_id[^>]*>([0-9A-Fa-f]{16})</title_id>")

    internal fun titleId(q: SaveQuery, env: SaveEnvironment): String? {
        q.serial?.uppercase()?.takeIf { HEX16.matches(it) && it.startsWith("00050000") }?.let { return it }
        // A game folder: code/<name>.rpx, with meta/meta.xml beside code.
        val game = SaveAdapters.parent(SaveAdapters.parent(q.romPath))
        return env.readText("$game/meta/meta.xml")?.let { TITLE.find(it)?.groupValues?.get(1)?.uppercase() }
    }

    private val MLC = Regex("<mlc_path>([^<]*)</mlc_path>")
    private val ACCOUNT = Regex("<AccountId>([0-9A-Fa-f]{8})</AccountId>")
    private val ACCOUNT_ID = Regex("^[0-9A-Fa-f]{8}$")

    /**
     * Cemu's user data folder and the folder its settings.xml is in, as CemuApp.cpp sets them: a
     * `portable` folder beside the program when there is one; on Windows the program's own folder
     * when an older settings.xml is there, else Cemu in Application Data; on Linux Cemu in the XDG
     * data folder (settings in the XDG config folder); on a Mac Cemu in Application Support.
     */
    internal fun folders(env: SaveEnvironment, emuPath: String?): Pair<String, String>? {
        val program = emuPath?.let { p -> p.substringBefore(".app/", "").takeIf { it.isNotEmpty() }?.let(SaveAdapters::parent) ?: SaveAdapters.parent(p) }
        program?.let { "$it/portable" }?.takeIf(env::isDirectory)?.let { return it to it }
        return when (env.host) {
            "WINDOWS" -> (program?.takeIf { env.exists("$it/settings.xml") } ?: SaveAdapters.firstDir(env, "${SaveAdapters.appData(env)}/Cemu"))?.let { it to it }
            "MACOS" -> SaveAdapters.firstDir(env, "${SaveAdapters.macSupport(env)}/Cemu")?.let { it to it }
            "ANDROID" -> null
            else -> SaveAdapters.firstDir(env, "${SaveAdapters.xdgData(env)}/Cemu")?.let { it to "${SaveAdapters.xdgConfig(env)}/Cemu" }
                ?: SaveAdapters.firstDir(env, "${env.home}/.var/app/info.cemu.Cemu/data/Cemu")?.let { it to "${env.home}/.var/app/info.cemu.Cemu/config/Cemu" }
        }
    }

    override fun locate(q: SaveQuery, env: SaveEnvironment): List<SaveSpot> {
        val chosen = SaveAdapters.chosen(env, q)
        val found = if (chosen == null) folders(env, q.emulatorPath) else null
        // The mlc folder its settings name (ActiveSettings::GetMlcPath), else mlc01 in its data folder.
        val set = found?.let { env.readText("${it.second}/settings.xml") }?.let { MLC.find(it)?.groupValues?.get(1) }
            ?.replace('\\', '/')?.trimEnd('/')?.takeIf { it.isNotEmpty() && env.isDirectory(it) }
        val base = chosen ?: set ?: found?.first
            ?: return listOf(SaveAdapters.unavailable(SaveKind.SAVE, "wiiu.savedata", if (env.host == "ANDROID") SaveAdapters.androidPrivateNote("Cemu", "Choose the custom folder you set in Cemu in Fuse, Settings, Save folders.") else "Cemu's folder wasn't found here."))
        val mlc = if (env.isDirectory("$base/mlc01")) "$base/mlc01" else base
        val games = "$mlc/usr/save/00050000"
        val id = titleId(q, env) ?: SaveAdapters.learned(env, q, "wiiu.savedata").firstOrNull()?.let { "00050000" + it.uppercase() }
            ?: return listOf(SaveAdapters.unavailable(SaveKind.SAVE, "wiiu.savedata", SaveAdapters.PLAY_ONCE, learnIn = games))
        val user = "$mlc/usr/save/${id.take(8).lowercase()}/${id.drop(8).lowercase()}/user"
        // The account Cemu plays as (settings.xml AccountId, its persistent id) and the data its
        // accounts share (common): never every account's saves at once.
        val account = found?.let { env.readText("${it.second}/settings.xml") }?.let { ACCOUNT.find(it)?.groupValues?.get(1)?.lowercase() }
            ?: env.list(user).filter { ACCOUNT_ID.matches(it) }.singleOrNull()
        val folders = account?.let { listOf(it, "common") }.orEmpty()
        return listOf(SaveSpot(SaveKind.SAVE, "wiiu.savedata", root = user, folders = folders))
    }
}

/** Xenia: an Xbox 360 game's saves in `content/<profile XUID>/<title id>/00000001` (content_manager.cc ResolvePackageRoot). */
internal object Xenia : SaveAdapter {
    override val emulators = setOf("xenia", "xenia-canary")
    private val HEX8 = Regex("^[0-9A-Fa-f]{8}$")
    private val TAG8 = Regex("\\[([0-9A-Fa-f]{8})]")
    private val SIGNED_IN = Regex("logged_profile_slot_0_xuid\\s*=\\s*\"([0-9A-Fa-f]{16})\"")

    override fun locate(q: SaveQuery, env: SaveEnvironment): List<SaveSpot> {
        // Its storage root (xenia_main.cc): beside the program when portable (Windows builds are,
        // and any with portable.txt beside them), else Xenia in Documents on Windows and in
        // XDG_DATA_HOME, or ~/.local/share when that isn't set, elsewhere (filesystem_posix.cc
        // GetUserFolder). Its comment there says the home folder, which builds may have used, so
        // that is looked at last.
        val userFolder = if (env.host == "WINDOWS") SaveAdapters.documents(env) else SaveAdapters.xdgData(env)
        val content = SaveAdapters.chosen(env, q) ?: SaveAdapters.firstDir(
            env, q.emulatorPath?.let { SaveAdapters.parent(it) + "/content" }, "$userFolder/Xenia/content",
            "${env.home}/Xenia/content".takeIf { env.host != "WINDOWS" },
        ) ?: return listOf(SaveAdapters.unavailable(SaveKind.SAVE, "x360.savedata", "Xenia's content folder wasn't found here."))
        val read = q.serial?.uppercase()?.takeIf { HEX8.matches(it) } ?: TAG8.find(q.romPath)?.groupValues?.get(1)?.uppercase()
        // Profiles by their XUID; all zeros holds what every profile shares (DLC), never a save.
        val profiles = env.list(content).filter { HEX16.matches(it) && it != "0000000000000000" }
        // The profile Xenia signs in (its config's logged_profile_slot_0_xuid), else the only one
        // with this game's save, else the only one: never another profile's save by accident.
        val configDir = q.emulatorPath?.let { SaveAdapters.parent(it) }
        val signedIn = listOfNotNull(configDir?.let { "$it/xenia-canary.config.toml" }, configDir?.let { "$it/xenia.config.toml" },
            "$userFolder/Xenia/xenia-canary.config.toml", "$userFolder/Xenia/xenia.config.toml")
            .firstNotNullOfOrNull { env.readText(it) }?.let { SIGNED_IN.find(it)?.groupValues?.get(1)?.uppercase() }
        val withGame = profiles.filter { read != null && env.isDirectory("$content/$it/$read") }
        val profile = signedIn?.let { s -> profiles.firstOrNull { it.equals(s, ignoreCase = true) } } ?: withGame.singleOrNull() ?: profiles.singleOrNull()
            ?: return listOf(SaveAdapters.unavailable(SaveKind.SAVE, "x360.savedata", if (profiles.isEmpty()) "Play it once in Xenia so it makes its profile and save." else "Xenia has more than one profile, and Fuse couldn't tell which one you play as. Sign in with yours in Xenia once, then Fuse keeps its saves in step."))
        val id = read ?: SaveAdapters.learned(env, q, "x360.savedata").firstOrNull()
            ?: return listOf(SaveAdapters.unavailable(SaveKind.SAVE, "x360.savedata", SaveAdapters.PLAY_ONCE, learnIn = "$content/$profile"))
        return listOf(SaveSpot(SaveKind.SAVE, "x360.savedata", root = "$content/$profile/$id/00000001"))
    }
}

/**
 * PlayStation 5 games ported with AnyPS5: each port is its own folder, and its saves stay in it.
 * AnyPS5's libc roots a game's paths at the folder it starts in (General.cpp), which is the port's
 * own when Fuse starts it, and its save data libraries keep saves there: libSceSaveData in `_sd`
 * (`/_sd/<save folder>`, with its `sce_sys/param.bin`) and libSceSaveData.native's save memory in
 * `_sd_mem`. Both folders are the game's whole save, so they travel as they are to the same game's
 * port on another device, whichever system it was relinked for.
 */
internal object AnyPs5 : SaveAdapter {
    override val emulators = setOf("anyps5")

    override fun locate(q: SaveQuery, env: SaveEnvironment): List<SaveSpot> {
        val port = SaveAdapters.parent(q.romPath)
        return listOf(SaveSpot(SaveKind.SAVE, "anyps5.savedata", root = port, folders = listOf("_sd", "_sd_mem")))
    }
}
