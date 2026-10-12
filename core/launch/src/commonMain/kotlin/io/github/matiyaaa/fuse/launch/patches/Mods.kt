package io.github.matiyaaa.fuse.launch.patches

/** The emulators Fuse puts a person's mods in place for, by where each reads them. */
enum class ModEmulator(val title: String) {
    DOLPHIN("Dolphin"),
    CEMU("Cemu"),
    EDEN("Eden"),
    RYUJINX("Ryujinx"),
    RPCS3("RPCS3"),
    SHADPS4("shadPS4"),
}

/** What a mod is for its emulator: a texture pack, a graphics mod, or files the game reads instead of its own. */
enum class ModForm { TEXTURES, GRAPHICS_MOD, GRAPHIC_PACK, GAME_FILES }

/**
 * A mod's files laid out for its emulator: each entry of the source with its path below the folder
 * the mod goes in, the folder's own name when the emulator keeps each mod in one, and what was left
 * out and why.
 */
data class ModLayout(
    val form: ModForm,
    val files: List<Pair<String, String>>,
    val rejected: List<String>,
    /** The mod's own folder name (Switch mods, Cemu graphic packs, Dolphin graphics mods); null when its files go straight in. */
    val folder: String? = null,
)

/**
 * Where each emulator reads a game's mods, checked against its source:
 *
 * - Dolphin (VideoCommon/HiresTextures.cpp; GraphicsModSystem/Config/GraphicsModGroup.cpp):
 *   texture packs in `Load/Textures/<game id>` at any depth, `.png` or `.dds` pictures whose names
 *   start with `tex1_`, read while `HiresTextures` is on; graphics mods (a folder with a
 *   `metadata.json`) in `Load/GraphicMods/<game id>`, each off until ticked in the game's
 *   properties, and read only while `EnableMods` is on. A game's own settings file
 *   (`GameSettings/<id>.ini`) keeps both switches under `[Video_Settings]`.
 * - Cemu (Cafe/GraphicPack/GraphicPack2.cpp): graphic packs in `graphicPacks`, each a folder with a
 *   `rules.txt`, its `content`, `aoc` and `code` folders standing in for the game's own files.
 *   Packs are turned on in Cemu's Graphic Packs window.
 * - Eden and the emulators of its family (core/file_sys/patch_manager.cpp, bis_factory.cpp): each
 *   mod a folder in `load/<TITLE ID>` (sixteen capital hex digits) holding `exefs`, `romfs`,
 *   `romfs_ext`, `romfslite` or `cheats`, matched without minding case; every one is used unless
 *   turned off by its folder's name.
 * - Ryujinx: each mod a folder in `mods/contents/<title id>` holding `romfs` or `exefs`.
 * - RPCS3: no mod folder. A mod's files stand in for the game's own in its `USRDIR`; Fuse keeps
 *   every file it replaces to put back.
 * - shadPS4 (core/file_sys/fs.cpp): a game's mods in a `-mods` folder beside the game's own
 *   (`CUSA00001-mods`), laid out like the game folder, read over the game's files without
 *   changing them.
 */
object ModRules {
    /** The folders a Switch mod is made of (Eden reads every one; Ryujinx `romfs` and `exefs`). */
    val SWITCH_PARTS = setOf("exefs", "romfs", "romfs_ext", "romfslite", "cheats")
    private val DOLPHIN_PICTURES = setOf("png", "dds")
    private const val TEX_PREFIX = "tex1_"

    /** A Switch title id as Eden names its folder: sixteen hex digits, in capitals. */
    private val TITLE_ID = Regex("^[0-9A-Fa-f]{16}$")

    /**
     * A GameCube or Wii game id as Dolphin names its folders (`GALE01`): six capitals and digits,
     * starting with a letter and with a digit in it, so a folder called `PLAYER` isn't taken for one.
     */
    private val DOLPHIN_ID = Regex("^[A-Z][A-Z0-9]{5}$")

    fun isTitleId(id: String): Boolean = TITLE_ID.matches(id)
    fun isDolphinId(id: String): Boolean = DOLPHIN_ID.matches(id) && id.any { it.isDigit() }

    /**
     * A Switch mod in [entries] (paths below what was chosen, folders ending in `/`): the shallowest
     * folder holding one of [SWITCH_PARTS] is the mod, and everything below it goes in its own folder,
     * named [name] when the mod is what was chosen. Null when nothing in it is a Switch mod.
     */
    fun switchMod(entries: List<String>, name: String, parts: Set<String> = SWITCH_PARTS): ModLayout? {
        val (source, rejected) = safe(entries)
        // The folder each part is in, for every file below a part.
        val roots = source.keys.mapNotNull { path ->
            val segs = path.split('/')
            val i = segs.dropLast(1).indexOfFirst { it.lowercase() in parts }
            if (i < 0) null else segs.take(i).joinToString("/")
        }
        val root = roots.minByOrNull { depth(it) } ?: return null
        val files = within(source, root)
        val folder = folderName(root.substringAfterLast('/').ifEmpty { name })
        return ModLayout(ModForm.GAME_FILES, files, rejected + outside(source, root), folder)
    }

    /** A Cemu graphic pack: the folder its `rules.txt` is in (the shallowest), all of it. */
    fun cemuPack(entries: List<String>, name: String): ModLayout? {
        val (source, rejected) = safe(entries)
        val rules = source.keys.filter { it.substringAfterLast('/').equals("rules.txt", ignoreCase = true) }.minByOrNull { depth(it) } ?: return null
        val root = rules.substringBeforeLast('/', "")
        return ModLayout(ModForm.GRAPHIC_PACK, within(source, root), rejected + outside(source, root), folderName(root.substringAfterLast('/').ifEmpty { name }))
    }

    /** A Dolphin graphics mod: the folder its `metadata.json` is in (the shallowest), all of it. */
    fun dolphinGraphicsMod(entries: List<String>, name: String): ModLayout? {
        val (source, rejected) = safe(entries)
        val meta = source.keys.filter { it.substringAfterLast('/').equals("metadata.json", ignoreCase = true) }.minByOrNull { depth(it) } ?: return null
        val root = meta.substringBeforeLast('/', "")
        return ModLayout(ModForm.GRAPHICS_MOD, within(source, root), rejected + outside(source, root), folderName(root.substringAfterLast('/').ifEmpty { name }))
    }

    /**
     * A Dolphin texture pack: its `tex1_` pictures, below the folder named for a game when the pack
     * has one (so a pack made as `GALE01/...` isn't nested twice), else below what they share. Null
     * when there are none. [madeFor] is the game id the pack's folder names, when it names one.
     */
    fun dolphinTextures(entries: List<String>): Pair<ModLayout, String?>? {
        val (source, rejected0) = safe(entries)
        val rejected = rejected0.toMutableList()
        val pictures = source.keys.filter { p ->
            val n = p.substringAfterLast('/')
            n.lowercase().startsWith(TEX_PREFIX) && EnhancementRules.ext(n) in DOLPHIN_PICTURES
        }
        if (pictures.isEmpty()) return null
        // The shallowest folder named like a game id, when every picture is below it.
        val idFolder = pictures.mapNotNull { p ->
            val segs = p.split('/').dropLast(1)
            segs.indexOfFirst { isDolphinId(it) }.takeIf { it >= 0 }?.let { segs.take(it + 1).joinToString("/") }
        }.distinct()
        val root = if (idFolder.size == 1 && pictures.all { it.startsWith(idFolder[0] + "/") }) idFolder[0] else commonFolder(pictures)
        val files = pictures.map { source.getValue(it) to it.removePrefix(root).trimStart('/') }
        val other = source.keys.count { it !in pictures }
        if (other > 0) rejected += "$other other file${if (other == 1) "" else "s"} (Dolphin reads only .png and .dds pictures named tex1_...)"
        val madeFor = idFolder.singleOrNull()?.takeIf { it == root }?.substringAfterLast('/')
        return ModLayout(ModForm.TEXTURES, files, rejected) to madeFor
    }

    /**
     * Files that stand in for an RPCS3 game's own: everything below the shallowest `USRDIR` folder,
     * at the same place below the game's. Null when the mod has no `USRDIR` (so where its files
     * belong isn't known).
     */
    fun rpcs3Files(entries: List<String>): ModLayout? {
        val (source, rejected) = safe(entries)
        val roots = source.keys.mapNotNull { p ->
            val segs = p.split('/')
            val i = segs.dropLast(1).indexOfFirst { it.equals("USRDIR", ignoreCase = true) }
            if (i < 0) null else segs.take(i + 1).joinToString("/")
        }
        val root = roots.minByOrNull { depth(it) } ?: return null
        return ModLayout(ModForm.GAME_FILES, within(source, root), rejected + outside(source, root))
    }

    /**
     * A shadPS4 mod laid out like the game's folder, whose top level holds [gameTop] (its files and
     * folders): the shallowest folder of the mod whose own entries are among the game's. Null when
     * none is, since then where its files belong isn't known.
     */
    fun shadPs4Files(entries: List<String>, gameTop: Set<String>): ModLayout? {
        val (source, rejected) = safe(entries)
        val top = gameTop.map { it.lowercase() }.toSet()
        if (top.isEmpty()) return null
        val roots = source.keys.mapNotNull { p ->
            val segs = p.split('/')
            segs.indices.firstOrNull { i -> segs[i].lowercase() in top }?.let { segs.take(it).joinToString("/") }
        }
        val root = roots.minByOrNull { depth(it) } ?: return null
        val files = within(source, root).filter { (_, rel) -> rel.substringBefore('/').lowercase() in top }
        val left = within(source, root).size - files.size
        val extra = if (left > 0) listOf("$left file${if (left == 1) "" else "s"} beside the game's own folders (shadPS4 reads only what lies where the game's files are)") else emptyList()
        return ModLayout(ModForm.GAME_FILES, files, rejected + outside(source, root) + extra)
    }

    /** The title ids a Cemu graphic pack's rules.txt says it is for (`titleIds = ...` in `[Definition]`), lowercase. */
    fun cemuTitleIds(rules: String): Set<String> = rules.lineSequence().map { it.trim() }
        .firstOrNull { it.substringBefore('=').trim().equals("titleIds", ignoreCase = true) }
        ?.substringAfter('=')?.split(',')?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }?.toSet().orEmpty()

    /** The folder name a mod is kept under: its own, without characters no file system takes. */
    fun folderName(name: String): String {
        val n = name.substringBeforeLast(".zip").substringBeforeLast(".ZIP").replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), " ").trim().trim('.')
        return n.ifEmpty { "Mod" }.take(80)
    }

    // ---------------------------------------------------------------------------------------------

    /** The source's files by cleaned path, and the entries that would land outside the mod's folder. */
    private fun safe(entries: List<String>): Pair<Map<String, String>, List<String>> {
        val rejected = ArrayList<String>()
        val source = LinkedHashMap<String, String>()
        for (raw in entries) {
            val e = raw.replace('\\', '/')
            if (e.endsWith("/")) continue
            if (EnhancementRules.unsafe(e)) rejected += "$raw (it would land outside the mod's folder)" else {
                val c = EnhancementRules.clean(e)
                if (c.isNotEmpty() && !junk(c)) source.putIfAbsent(c, raw)
            }
        }
        return source to rejected
    }

    /** Files archives carry that are no part of any mod (macOS's resource forks and Finder notes, Windows' thumbnails). */
    private fun junk(path: String): Boolean {
        val n = path.substringAfterLast('/')
        return path.split('/').any { it == "__MACOSX" } || n == ".DS_Store" || n.equals("Thumbs.db", ignoreCase = true) || n.equals("desktop.ini", ignoreCase = true)
    }

    private fun within(source: Map<String, String>, root: String): List<Pair<String, String>> =
        source.filterKeys { root.isEmpty() || it.startsWith("$root/") }.map { (clean, raw) -> raw to clean.removePrefix(root).trimStart('/') }

    private fun outside(source: Map<String, String>, root: String): List<String> {
        if (root.isEmpty()) return emptyList()
        val n = source.keys.count { !it.startsWith("$root/") }
        return if (n == 0) emptyList() else listOf("$n file${if (n == 1) "" else "s"} outside the mod's own folder ($root)")
    }

    private fun depth(path: String) = if (path.isEmpty()) 0 else path.count { it == '/' } + 1

    private fun commonFolder(paths: List<String>): String {
        val parts = paths.map { it.split('/').dropLast(1) }
        val first = parts.firstOrNull() ?: return ""
        var n = 0
        while (n < first.size && parts.all { it.size > n && it[n] == first[n] }) n++
        return first.take(n).joinToString("/")
    }
}
