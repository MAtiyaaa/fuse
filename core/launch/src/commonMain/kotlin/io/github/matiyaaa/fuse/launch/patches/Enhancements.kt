package io.github.matiyaaa.fuse.launch.patches

/** What a person adds to a game from a file, folder or archive of their own. */
enum class EnhancementKind { CHEATS, PATCHES, TEXTURES }

/**
 * One file to put in place: [from] is where it is in what was chosen (a path below the folder, an
 * entry in the archive, or empty for the chosen file itself) and [to] where it goes.
 */
data class PlannedFile(val from: String, val to: String)

/** A texture pack's files, each as an entry of the source and its path below the pack's own root, and what was left out. */
data class TexturePack(val files: List<Pair<String, String>>, val rejected: List<String>)

/**
 * Where each emulator reads a game's own cheats, patches and texture replacements, checked against
 * its source:
 *
 * - PCSX2 (pcsx2/Patch.cpp; GS/Renderers/HW/GSTextureReplacements.cpp): `.pnach` files named
 *   `SERIAL_CRC*.pnach` in its cheats or patches folder, so one of a person's own sits beside the
 *   others under a name of its own; textures in `textures/SERIAL/replacements` at any depth, as
 *   `.png` or `.dds` (or zips of them), read while `[EmuCore/GS] LoadTextureReplacements` is on.
 * - DuckStation (src/core/cheats.cpp; gpu_hw_texture_cache.cpp): `SERIAL.cht` in its cheats or
 *   patches folder, a later code replacing one of the same name, so a person's own are added to the
 *   end of that file; textures in `textures/SERIAL/replacements` at any depth, as `.png`, `.jpg` or
 *   `.webp`: `vram-write-*` ones while `[TextureReplacements] EnableVRAMWriteReplacements` is on,
 *   `texupload-*` and `texpage-*` ones while `EnableTextureReplacements` and its texture cache
 *   (`[GPU] EnableTextureCache`) are.
 * - PPSSPP (Core/CwCheat.cpp; GPU/Common/TextureReplacer.cpp): cheats in `PSP/Cheats/GAMEID.ini`,
 *   a person's own added to its end; textures in `PSP/TEXTURES/GAMEID`, a pack being the folder its
 *   `textures.ini` (or `textures.zip`) is in, read while `[Graphics] ReplaceTextures` is on (it is
 *   unless turned off).
 * - RetroArch: a game's cheats are its own `.cht` file (`<cheats>/<core>/<content>.cht`); a file
 *   chosen for it takes the place of the one there, which is kept to put back.
 */
object EnhancementRules {
    private val PCSX2_TEXTURES = setOf("png", "dds", "zip")
    private val DUCKSTATION_TEXTURES = setOf("png", "jpg", "webp")

    /** At most this many files from one folder or archive, so a wrong choice (a whole drive) stops early. */
    const val MAX_ENTRIES = 200_000

    /** Entries that could leave the folder they are put in: absolute, with a drive, with `..`, or with characters no file system takes. */
    fun unsafe(entry: String): Boolean {
        val e = entry.replace('\\', '/')
        if (e.isEmpty() || e.startsWith("/") || DRIVE.containsMatchIn(e) || '\u0000' in e) return true
        return e.split('/').any { it == ".." }
    }

    /** [entry] with `\\` as `/` and its `.` and empty parts dropped (`./a//b.png` is `a/b.png`). */
    fun clean(entry: String): String = entry.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }.joinToString("/")

    fun ext(name: String): String = name.substringAfterLast('/').let { if ('.' in it) it.substringAfterLast('.').lowercase() else "" }

    /**
     * A texture pack's files, from the [entries] of a folder or archive (paths below it, folders
     * ending in `/`), for [emulator] (`pcsx2`, `duckstation`, `ppsspp`), with what was left out and
     * why. Null when nothing in it is a texture pack for that emulator.
     */
    fun texturePack(emulator: String, entries: List<String>): TexturePack? {
        val rejected = ArrayList<String>()
        // Each entry as it is in the source, by its cleaned path.
        val source = LinkedHashMap<String, String>()
        for (raw in entries) {
            val e = raw.replace('\\', '/')
            if (e.endsWith("/")) continue
            if (unsafe(e)) rejected += "$raw (it would land outside the pack's folder)" else source.putIfAbsent(clean(e), raw)
        }
        val safe = source.keys.filter { it.isNotEmpty() }
        val root: String = when (emulator) {
            "ppsspp" -> {
                // The pack is the folder its textures.ini (or textures.zip) is in: the shallowest one.
                val marker = safe.filter { it.substringAfterLast('/').lowercase() in PPSSPP_MARKERS }.minByOrNull { depth(it) } ?: return null
                marker.substringBeforeLast('/', "")
            }
            "pcsx2", "duckstation" -> {
                // A `replacements` folder when the pack has one (the shallowest), else the pictures where they are.
                val wanted = if (emulator == "pcsx2") PCSX2_TEXTURES else DUCKSTATION_TEXTURES
                val marker = safe.filter { e -> e.split('/').dropLast(1).any { it.equals(REPLACEMENTS, ignoreCase = true) } }
                    .minByOrNull { e -> e.split('/').indexOfFirst { it.equals(REPLACEMENTS, ignoreCase = true) } }
                if (marker != null) {
                    val parts = marker.split('/')
                    parts.take(parts.indexOfFirst { it.equals(REPLACEMENTS, ignoreCase = true) } + 1).joinToString("/")
                } else {
                    if (safe.none { ext(it) in wanted }) return null
                    commonFolder(safe.filter { ext(it) in wanted })
                }
            }
            else -> return null
        }
        val prefix = if (root.isEmpty()) "" else "$root/"
        val files = ArrayList<Pair<String, String>>()
        for (e in safe) {
            if (!e.startsWith(prefix)) {
                rejected += "$e (not part of the pack)"
                continue
            }
            val rel = e.removePrefix(prefix)
            val ok = when (emulator) {
                "pcsx2" -> ext(rel) in PCSX2_TEXTURES
                "duckstation" -> ext(rel) in DUCKSTATION_TEXTURES
                else -> !rel.substringAfterLast('/').startsWith(".")
            }
            if (!ok) {
                rejected += "$e (not a kind of file ${NAMES[emulator]} reads for textures)"
                continue
            }
            files += source.getValue(e) to rel
        }
        return if (files.isEmpty()) null else TexturePack(files, rejected)
    }

    /** The DuckStation switches a pack's files need: VRAM write replacements, texture replacements (with the texture cache). */
    fun duckStationNeeds(paths: List<String>): Pair<Boolean, Boolean> {
        val names = paths.map { it.substringAfterLast('/').lowercase() }
        return names.any { it.startsWith("vram-write-") } to names.any { it.startsWith("texupload-") || it.startsWith("texpage-") }
    }

    /**
     * A name for a person's own `.pnach` that PCSX2 reads for the game (`SERIAL_CRC*.pnach`) and that
     * doesn't take the place of one there: `SERIAL_CRC_<name>.pnach`, the name cut to letters,
     * digits, spaces, dashes and underscores.
     */
    fun pnachName(serial: String, crc: Long, name: String): String {
        val hex = crc.toString(16).uppercase().padStart(8, '0')
        val label = name.substringBeforeLast('.').filter { it.isLetterOrDigit() || it in " -_" }.trim().take(40).ifEmpty { "own" }
        val base = if (serial.isEmpty()) hex else "${serial.uppercase()}_$hex"
        return "${base}_$label.pnach"
    }

    /** Whether [text] holds codes [emulator] reads as cheats or patches: at least one, parsed as the emulator parses them. */
    fun codesIn(emulator: String, text: String, gameId: String = ""): Int = when (emulator) {
        "pcsx2" -> Pnach.parse(text).size + (if (Pnach.hasUnlabelled(text)) 1 else 0)
        "duckstation" -> Cht.parse(text).size
        "ppsspp" -> CwCheats.parse(text, gameId).size
        "retroarch" -> RetroArchCheats.parse(text).size
        else -> 0
    }

    /** [existing] with [block] added at its end, on lines of its own and with the file's own line endings. */
    fun append(existing: String?, block: String): String {
        val base = existing ?: ""
        val crlf = base.contains("\r\n")
        val body = block.replace("\r\n", "\n").trimEnd('\n').let { if (crlf) it.replace("\n", "\r\n") else it }
        val sep = if (crlf) "\r\n" else "\n"
        return when {
            base.isEmpty() -> body + sep
            base.endsWith("\n") -> base + body + sep
            else -> base + sep + body + sep
        }
    }

    /**
     * [text] without the lines [append] added for [block]; null when they are no longer there as
     * they were. For PPSSPP a cheat turned on or off since (`_C0` and `_C1`) still counts as the same.
     */
    fun removeAppended(text: String, block: String, ppsspp: Boolean = false): String? {
        val crlf = text.contains("\r\n")
        val lines = text.replace("\r\n", "\n").split('\n').let { if (text.endsWith("\n")) it.dropLast(1) else it }
        val want = block.replace("\r\n", "\n").trimEnd('\n').split('\n')
        fun same(a: String, b: String): Boolean {
            if (!ppsspp) return a.trimEnd() == b.trimEnd()
            fun norm(s: String) = s.trim().replace(SWITCH, "_C ")
            return norm(a) == norm(b)
        }
        // The last place it is: it was added to the end, and anything later was added after it.
        for (start in lines.size - want.size downTo 0) {
            if (want.indices.all { same(lines[start + it], want[it]) }) {
                val rest = lines.subList(0, start) + lines.subList(start + want.size, lines.size)
                val sep = if (crlf) "\r\n" else "\n"
                return if (rest.isEmpty() || rest.all { it.isBlank() }) "" else rest.joinToString(sep) + sep
            }
        }
        return null
    }

    private fun depth(path: String) = path.count { it == '/' }

    /** The deepest folder every one of [paths] is in ("" when they are not all in one). */
    private fun commonFolder(paths: List<String>): String {
        if (paths.isEmpty()) return ""
        var parts = paths.first().split('/').dropLast(1)
        for (p in paths.drop(1)) {
            val other = p.split('/').dropLast(1)
            val n = parts.zip(other).takeWhile { (a, b) -> a == b }.size
            parts = parts.take(n)
        }
        return parts.joinToString("/")
    }

    private const val REPLACEMENTS = "replacements"
    private val PPSSPP_MARKERS = setOf("textures.ini", "textures.zip")
    private val NAMES = mapOf("pcsx2" to "PCSX2", "duckstation" to "DuckStation", "ppsspp" to "PPSSPP")
    private val DRIVE = Regex("^[A-Za-z]:")
    private val SWITCH = Regex("^_C[0-9] ")
}
