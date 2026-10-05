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

/** DraStic: each game's save in `backup` as a `.dsv` (a raw DS save with DeSmuME's footer). */
internal object DraStic : SaveAdapter {
    override val emulators = setOf("drastic")

    override fun locate(q: SaveQuery, env: SaveEnvironment): List<SaveSpot> {
        val dir = SaveAdapters.chosen(env, q)
            ?: SaveAdapters.firstDir(env, "/storage/emulated/0/DraStic", "/storage/emulated/0/Drastic", "/storage/emulated/0/drastic", "${env.home}/.drastic", "/mnt/vendor/deep/drastic")
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
            ext in parts && (TitleMatch.same(base.substringBeforeLast('-'), q.title) || TitleMatch.same(base, q.title) || TitleMatch.same(base, SaveAdapters.stem(q.romPath)))
        }
        if (found.isEmpty()) return listOf(SaveAdapters.unavailable(SaveKind.SAVE, "n64.split", "Play it once here so Mupen64Plus names its save; from then on Fuse keeps it in step."))
        // Every part under the name Mupen64Plus gave this game, so a part it hasn't made yet (or one
        // that arrives from another device) has its place too.
        val base = found.first().substringBeforeLast('.')
        return listOf(SaveSpot(SaveKind.SAVE, "n64.split", parts.map { ext -> SpotFile("save.$ext", "$dir/$base.$ext") }))
    }
}

/** Redream: the Dreamcast's VMU, one file shared by every game on it (the same bytes as Flycast's). */
internal object Redream : SaveAdapter {
    override val emulators = setOf("redream")

    override fun locate(q: SaveQuery, env: SaveEnvironment): List<SaveSpot> {
        val dir = SaveAdapters.chosen(env, q)
            ?: q.emulatorPath?.let(SaveAdapters::parent)?.takeIf { env.exists("$it/vmu0.bin") || env.exists("$it/redream.cfg") }
            ?: SaveAdapters.firstDir(env, "${SaveAdapters.xdgData(env)}/redream", "${SaveAdapters.appData(env)}/redream")
            ?: return listOf(SaveAdapters.unavailable(SaveKind.MEMORY_CARD, "dc.vmu", if (env.host == "ANDROID") SaveAdapters.androidPrivateNote("Redream") else "Redream's folder wasn't found here."))
        return listOf(SaveSpot(SaveKind.MEMORY_CARD, "dc.vmu", listOf(SpotFile("vmu_save_A1.bin", "$dir/vmu0.bin"))))
    }
}

/** ePSXe: two memory cards shared by every game, in its folder in your storage. */
internal object Epsxe : SaveAdapter {
    override val emulators = setOf("epsxe")

    override fun locate(q: SaveQuery, env: SaveEnvironment): List<SaveSpot> {
        val dir = SaveAdapters.chosen(env, q)?.let { c -> if (env.isDirectory("$c/memcards")) "$c/memcards" else c }
            ?: SaveAdapters.firstDir(env, "/storage/emulated/0/epsxe/memcards", "${env.home}/.epsxe/memcards")
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

/** Play!: its PlayStation 2 memory card, kept as a folder. */
internal object PlayPs2 : SaveAdapter {
    override val emulators = setOf("play")

    override fun locate(q: SaveQuery, env: SaveEnvironment): List<SaveSpot> {
        val data = SaveAdapters.chosen(env, q) ?: SaveAdapters.firstDir(
            env,
            "${SaveAdapters.xdgData(env)}/Play Data Files", "${env.home}/Play Data Files", "${SaveAdapters.documents(env)}/Play Data Files",
            "${SaveAdapters.macSupport(env)}/Play Data Files", "/storage/emulated/0/Play Data Files",
        ) ?: return listOf(SaveAdapters.unavailable(SaveKind.MEMORY_CARD, "play.mcr", "Play!'s data folder wasn't found here."))
        return listOf(SaveSpot(SaveKind.MEMORY_CARD, "play.mcr", root = "$data/mcr0"))
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

/** MAME (and MAME4droid): a machine's non-volatile memory (scores, settings) in `nvram/<set>`. */
internal object Mame : SaveAdapter {
    override val emulators = setOf("mame", "mame4droid", "mame4droid-current")

    override fun locate(q: SaveQuery, env: SaveEnvironment): List<SaveSpot> {
        val base = SaveAdapters.chosen(env, q) ?: when (env.host) {
            "WINDOWS" -> SaveAdapters.firstDir(env, q.emulatorPath?.let(SaveAdapters::parent))
            "ANDROID" -> SaveAdapters.firstDir(env, "/storage/emulated/0/MAME4droid", "/storage/emulated/0/mame4droid")
            else -> SaveAdapters.firstDir(env, "${env.home}/.mame", "${env.home}/.var/app/org.mamedev.MAME/.mame")
        } ?: return listOf(SaveAdapters.unavailable(SaveKind.SAVE, "mame.nvram", "MAME's folder wasn't found here."))
        return listOf(SaveSpot(SaveKind.SAVE, "mame.nvram", root = "$base/nvram", folders = listOf(SaveAdapters.stem(q.romPath))))
    }
}

/** ScummVM: every game's saves in one folder, kept as one (each person's own). */
internal object ScummVm : SaveAdapter {
    override val emulators = setOf("scummvm")

    override fun locate(q: SaveQuery, env: SaveEnvironment): List<SaveSpot> {
        val dir = SaveAdapters.chosen(env, q) ?: when (env.host) {
            "WINDOWS" -> SaveAdapters.firstDir(env, "${SaveAdapters.appData(env)}/ScummVM/Saved games")
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
 */
internal object ThreeDs : SaveAdapter {
    override val emulators = setOf("azahar", "azaharplus", "citra", "citra-canary", "citra-mmj", "lime3ds", "mandarine")

    private fun base(env: SaveEnvironment, q: SaveQuery): String? = SaveAdapters.chosen(env, q) ?: when (env.host) {
        "WINDOWS" -> SaveAdapters.firstDir(
            env, q.emulatorPath?.let { SaveAdapters.parent(it) + "/user" },
            "${SaveAdapters.appData(env)}/Azahar", "${SaveAdapters.appData(env)}/Citra", "${SaveAdapters.appData(env)}/Lime3DS", "${SaveAdapters.appData(env)}/Mandarine",
        )
        "MACOS" -> SaveAdapters.firstDir(env, "${SaveAdapters.macSupport(env)}/Azahar", "${SaveAdapters.macSupport(env)}/Citra", "${SaveAdapters.macSupport(env)}/Lime3DS")
        "ANDROID" -> SaveAdapters.firstDir(env, "/storage/emulated/0/Azahar", "/storage/emulated/0/azahar-emu", "/storage/emulated/0/citra-emu", "/storage/emulated/0/lime3ds-emu", "/storage/emulated/0/Citra")
        else -> SaveAdapters.firstDir(
            env, "${SaveAdapters.xdgData(env)}/azahar-emu", "${env.home}/.var/app/org.azahar_emu.Azahar/data/azahar-emu",
            "${SaveAdapters.xdgData(env)}/citra-emu", "${env.home}/.var/app/org.citra_emu.citra/data/citra-emu",
            "${SaveAdapters.xdgData(env)}/lime3ds-emu", "${SaveAdapters.xdgData(env)}/mandarine-emu",
        )
    }

    /** The game's title id: its serial when Fuse has one, else read from the cartridge header (NCSD). */
    internal fun titleId(q: SaveQuery, env: SaveEnvironment): String? {
        q.serial?.uppercase()?.takeIf { HEX16.matches(it) && it.startsWith("00040000") }?.let { return it }
        val b = env.readBytes(q.romPath, 0x100, 0x10) ?: return null
        if (b.size < 0x10 || b.decodeToString(0, 4) != "NCSD") return null
        return leHex(b, 8, 8).takeIf { it.startsWith("00040000") }
    }

    override fun locate(q: SaveQuery, env: SaveEnvironment): List<SaveSpot> {
        val base = base(env, q) ?: return listOf(SaveAdapters.unavailable(SaveKind.SAVE, "3ds.savedata", if (env.host == "ANDROID") "Choose the user folder you picked in Azahar (the one with sdmc inside) in Fuse, Settings, Save folders." else "The 3DS emulator's folder wasn't found here."))
        val id = titleId(q, env) ?: return listOf(SaveAdapters.unavailable(SaveKind.SAVE, "3ds.savedata", "Fuse couldn't read this game's title id (installed .cia games keep theirs inside)."))
        val n3ds = "$base/sdmc/Nintendo 3DS"
        val id0 = env.list(n3ds).firstOrNull { HEX32.matches(it) } ?: ZERO
        val id1 = env.list("$n3ds/$id0").firstOrNull { HEX32.matches(it) } ?: ZERO
        val save = SaveSpot(SaveKind.SAVE, "3ds.savedata", root = "$n3ds/$id0/$id1/title/${id.take(8).lowercase()}/${id.drop(8).lowercase()}/data")
        // Save states: `states/<TITLE ID>.<slot>.cst` in the same user folder, slots 0 to 10.
        val states = SaveSpot(SaveKind.STATE, "3ds.state", (0..STATE_SLOTS).map { n ->
            val slot = n.toString().padStart(2, '0')
            SpotFile("state$slot", "$base/states/$id.$slot.cst")
        })
        return listOf(save, states)
    }

    private const val STATE_SLOTS = 10

    private const val ZERO = "00000000000000000000000000000000"
}

/**
 * Switch emulators of the yuzu family (Eden, Citron, Sudachi, yuzu and its forks): a game's save in
 * the emulated NAND, `nand/user/save/0000000000000000/<user>/<title id>`.
 */
internal object SwitchNand : SaveAdapter {
    override val emulators = setOf("eden", "eden-nightly", "citron", "sudachi", "yuzu", "suyu", "torzu", "kenji-nx")

    private fun folderName(id: String) = when (id) {
        "eden-nightly" -> "eden"
        "kenji-nx" -> "kenji"
        else -> id
    }

    private fun base(env: SaveEnvironment, q: SaveQuery): String? {
        SaveAdapters.chosen(env, q)?.let { return it }
        val name = folderName(SaveAdapters.baseId(q.emulatorId))
        val cap = name.replaceFirstChar { it.uppercase() }
        return when (env.host) {
            "WINDOWS" -> SaveAdapters.firstDir(env, q.emulatorPath?.let { SaveAdapters.parent(it) + "/user" }, "${SaveAdapters.appData(env)}/$name", "${SaveAdapters.appData(env)}/$cap")
            "MACOS" -> SaveAdapters.firstDir(env, "${SaveAdapters.macSupport(env)}/$name", "${SaveAdapters.macSupport(env)}/$cap")
            "ANDROID" -> null
            else -> SaveAdapters.firstDir(env, "${SaveAdapters.xdgData(env)}/$name", "${env.home}/.var/app/org.${name}_emu.$name/data/$name", "${env.home}/.var/app/dev.${name}_emu.$name/data/$name")
        }
    }

    override fun locate(q: SaveQuery, env: SaveEnvironment): List<SaveSpot> {
        val base = base(env, q) ?: return listOf(SaveAdapters.unavailable(SaveKind.SAVE, "switch.savedata", if (env.host == "ANDROID") SaveAdapters.androidPrivateNote("This Switch emulator", "Use its Manage save data export, then choose that folder in Fuse, Settings, Save folders.") else "The Switch emulator's folder wasn't found here."))
        val id = SwitchIds.of(q, env) ?: return listOf(SaveAdapters.unavailable(SaveKind.SAVE, "switch.savedata", "Fuse doesn't know this game's title id yet (a [0100…] tag in its file name tells it)."))
        val users = "$base/nand/user/save/0000000000000000"
        val people = env.list(users).filter { HEX32.matches(it) }
        val user = people.firstOrNull { env.isDirectory("$users/$it/$id") } ?: people.firstOrNull()
            ?: return listOf(SaveAdapters.unavailable(SaveKind.SAVE, "switch.savedata", "Play a game once in the emulator so it makes its user, then Fuse keeps saves in step."))
        return listOf(SaveSpot(SaveKind.SAVE, "switch.savedata", root = "$users/$user/$id"))
    }
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
        if (!path.lowercase().endsWith(".nsp")) return null
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
 * Ryujinx: saves in numbered folders (`bis/user/save/<n>/0`), each with an `ExtraData0` beside it
 * whose first eight bytes are the game's program id. A game is synced once it has played here.
 */
internal object Ryujinx : SaveAdapter {
    override val emulators = setOf("ryujinx")

    override fun locate(q: SaveQuery, env: SaveEnvironment): List<SaveSpot> {
        val base = SaveAdapters.chosen(env, q) ?: when (env.host) {
            "WINDOWS" -> SaveAdapters.firstDir(env, q.emulatorPath?.let { SaveAdapters.parent(it) + "/portable" }, "${SaveAdapters.appData(env)}/Ryujinx")
            "MACOS" -> SaveAdapters.firstDir(env, "${SaveAdapters.macSupport(env)}/Ryujinx")
            "ANDROID" -> null
            else -> SaveAdapters.firstDir(env, "${SaveAdapters.xdgConfig(env)}/Ryujinx", "${env.home}/.var/app/org.ryujinx.Ryujinx/config/Ryujinx")
        } ?: return listOf(SaveAdapters.unavailable(SaveKind.SAVE, "switch.savedata", "Ryujinx's folder wasn't found here."))
        val id = SwitchIds.of(q, env) ?: return listOf(SaveAdapters.unavailable(SaveKind.SAVE, "switch.savedata", "Fuse doesn't know this game's title id yet (a [0100…] tag in its file name tells it)."))
        val saves = "$base/bis/user/save"
        val match = env.list(saves).firstOrNull { n ->
            val extra = env.readBytes("$saves/$n/ExtraData0", 0, 8) ?: return@firstOrNull false
            extra.size == 8 && leHex(extra, 0, 8) == id
        } ?: return listOf(SaveAdapters.unavailable(SaveKind.SAVE, "switch.savedata", "Play it once in Ryujinx so it makes its save; from then on Fuse keeps it in step."))
        return listOf(SaveSpot(SaveKind.SAVE, "switch.savedata", root = "$saves/$match/0"))
    }
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

    override fun locate(q: SaveQuery, env: SaveEnvironment): List<SaveSpot> {
        val base = SaveAdapters.chosen(env, q) ?: when (env.host) {
            "WINDOWS" -> SaveAdapters.firstDir(env, q.emulatorPath?.let(SaveAdapters::parent), "${SaveAdapters.appData(env)}/Cemu")
            "MACOS" -> SaveAdapters.firstDir(env, "${SaveAdapters.macSupport(env)}/Cemu")
            "ANDROID" -> null
            else -> SaveAdapters.firstDir(env, "${SaveAdapters.xdgData(env)}/Cemu", "${env.home}/.var/app/info.cemu.Cemu/data/Cemu")
        } ?: return listOf(SaveAdapters.unavailable(SaveKind.SAVE, "wiiu.savedata", if (env.host == "ANDROID") SaveAdapters.androidPrivateNote("Cemu", "Choose the custom folder you set in Cemu in Fuse, Settings, Save folders.") else "Cemu's folder wasn't found here."))
        val mlc = if (env.isDirectory("$base/mlc01")) "$base/mlc01" else base
        val id = titleId(q, env) ?: return listOf(SaveAdapters.unavailable(SaveKind.SAVE, "wiiu.savedata", "Fuse couldn't read this game's title id (from meta/meta.xml in its folder)."))
        return listOf(SaveSpot(SaveKind.SAVE, "wiiu.savedata", root = "$mlc/usr/save/${id.take(8).lowercase()}/${id.drop(8).lowercase()}/user"))
    }
}

/** Xenia: an Xbox 360 game's saves in `content/<profile>/<title id>/00000001`. */
internal object Xenia : SaveAdapter {
    override val emulators = setOf("xenia", "xenia-canary")
    private val HEX8 = Regex("^[0-9A-Fa-f]{8}$")
    private val TAG8 = Regex("\\[([0-9A-Fa-f]{8})]")

    override fun locate(q: SaveQuery, env: SaveEnvironment): List<SaveSpot> {
        val content = SaveAdapters.chosen(env, q) ?: SaveAdapters.firstDir(
            env, q.emulatorPath?.let { SaveAdapters.parent(it) + "/content" }, "${SaveAdapters.documents(env)}/Xenia/content", "${SaveAdapters.xdgData(env)}/Xenia/content",
        ) ?: return listOf(SaveAdapters.unavailable(SaveKind.SAVE, "x360.savedata", "Xenia's content folder wasn't found here."))
        val id = q.serial?.uppercase()?.takeIf { HEX8.matches(it) } ?: TAG8.find(q.romPath)?.groupValues?.get(1)?.uppercase()
            ?: return listOf(SaveAdapters.unavailable(SaveKind.SAVE, "x360.savedata", "Fuse doesn't know this game's title id (an [XXXXXXXX] tag in its name tells it)."))
        val profiles = env.list(content).filter { HEX16.matches(it) }
        val profile = profiles.firstOrNull { env.isDirectory("$content/$it/$id") } ?: profiles.firstOrNull()
            ?: return listOf(SaveAdapters.unavailable(SaveKind.SAVE, "x360.savedata", "Play it once in Xenia so it makes its profile and save."))
        return listOf(SaveSpot(SaveKind.SAVE, "x360.savedata", root = "$content/$profile/$id/00000001"))
    }
}
