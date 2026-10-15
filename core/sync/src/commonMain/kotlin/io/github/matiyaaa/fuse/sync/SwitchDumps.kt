package io.github.matiyaaa.fuse.sync

/**
 * Saves copied off a Switch by its save managers, as plain files on its SD card:
 *
 *  - **JKSV** (J-D-K/JKSV): `JKSV/<game>/<backup>/`, or `<backup>.zip`. The game folder is the
 *    game's name (or its title id, by a setting); a backup is named `<user> - <date>` unless the
 *    person named it. Current versions put `.nx_save_meta.bin` in every backup ([Meta]).
 *  - **Checkpoint** (BernardoGiordano/Checkpoint): `switch/Checkpoint/saves/0x<title id> <game>/<backup>/`,
 *    a backup named `<date time> <user>`.
 *
 * A backup holds the save's own files as the game wrote them, which is what a Switch emulator keeps
 * in its NAND, so it goes in as it is (without JKSV's meta file).
 */
object SwitchDumps {
    /** JKSV's description of a backup, beside its files. */
    const val META = ".nx_save_meta.bin"

    /** Whose save a backup is, as FsSaveDataType has it. */
    enum class Kind { ACCOUNT, DEVICE, OTHER }

    enum class Tool { JKSV, CHECKPOINT }

    /** What JKSV's meta file says (fs::SaveMetaData, packed, little-endian). */
    data class Meta(val titleId: String, val kind: Kind, val account: String?, val savedAt: Long?)

    /**
     * One backup: [root] is its folder among the source's files ("" when the source is the backup
     * itself), [files] its save files there (never the meta file).
     */
    data class Backup(
        val root: String,
        val tool: Tool,
        val titleId: String?,
        val game: String?,
        val name: String,
        val user: String?,
        val kind: Kind,
        /** When it was made, in seconds since 1970, when it says. */
        val savedAt: Long?,
        val files: List<String>,
    )

    private const val MAGIC = 0x56534B4A
    private val HEX16 = Regex("[0-9A-Fa-f]{16}")
    private val CHECKPOINT_GAME = Regex("^0x([0-9A-Fa-f]{16}) (.*)$")

    /** Reads JKSV's meta file, or null when [bytes] isn't one. */
    fun meta(bytes: ByteArray): Meta? {
        if (bytes.size < 49 || u32(bytes, 0) != MAGIC.toLong()) return null
        val app = u64(bytes, 5)
        if (app == 0L) return null
        val account = bytes.copyOfRange(13, 29).takeIf { a -> a.any { it != 0.toByte() } }
            ?.joinToString("") { ((it.toInt() and 0xFF) + 0x100).toString(16).substring(1) }
        val kind = when (bytes[37].toInt()) {
            1 -> Kind.ACCOUNT
            3 -> Kind.DEVICE
            else -> Kind.OTHER
        }
        val at = if (bytes.size >= 57) u64(bytes, 49).takeIf { it in 1..4_102_444_800L } else null
        return Meta(hex16(app), kind, account, at)
    }

    /**
     * The backups among a source's files. [paths] are the staged files' names, `/`-separated;
     * [above] the names of the folders the source sits in, nearest last, ending with the source's
     * own name (a ZIP's without `.zip`), so a backup or game folder chosen on its own is recognised
     * by where it was. [read] gives a meta file's bytes.
     */
    fun backups(paths: Collection<String>, above: List<String>, read: (String) -> ByteArray?): List<Backup> {
        val lead = above.size
        // Every folder that is a backup, by the whole path from above the source.
        val found = LinkedHashMap<List<String>, Backup>()
        val all = paths.map { above + it.split('/') }
        val seen = HashSet<List<String>>()
        for (parts in all) for (end in maxOf(lead, 1) until parts.size) {
            val folder = parts.subList(0, end).toList()
            if (!seen.add(folder)) continue
            backupOf(folder, lead, paths, read)?.let { found[folder] = it }
        }
        // A backup folder inside another's is not two saves: the outer one holds it.
        return found.filterKeys { k -> found.keys.none { o -> o.size < k.size && k.subList(0, o.size) == o } }.values.toList()
    }

    private fun backupOf(folder: List<String>, lead: Int, paths: Collection<String>, read: (String) -> ByteArray?): Backup? {
        // The folder must be inside the source or be the source itself.
        if (folder.size < lead) return null
        val root = folder.drop(lead).joinToString("/")
        val prefix = if (root.isEmpty()) "" else "$root/"
        val inside = paths.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }
        val metaBytes = if (META in inside) read(prefix + META) else null
        val meta = metaBytes?.let(::meta)
        val name = folder.last()
        val game = folder.getOrNull(folder.size - 2)
        val checkpoint = game?.let { CHECKPOINT_GAME.find(it) }
        val underJksv = folder.size >= 3 && folder.dropLast(2).lastOrNull()?.let { it.equals("JKSV", true) || it.equals("Saves", true) && folder.getOrNull(folder.size - 4)?.equals("JKSV", true) == true } == true
        val files = inside.filter { it != META && !it.startsWith(".") && !it.contains("/.") }
        if (files.isEmpty()) return null
        return when {
            meta != null -> Backup(root, Tool.JKSV, meta.titleId, game?.takeUnless { HEX16.matches(it) }, name, userOf(name, Tool.JKSV), meta.kind, meta.savedAt, files)
            checkpoint != null -> Backup(root, Tool.CHECKPOINT, checkpoint.groupValues[1].uppercase(), checkpoint.groupValues[2].trim().ifEmpty { null }, name,
                userOf(name, Tool.CHECKPOINT), Kind.ACCOUNT, null, files)
            underJksv && game != null -> Backup(root, Tool.JKSV, game.takeIf { HEX16.matches(it) }?.uppercase(), game.takeUnless { HEX16.matches(it) }, name,
                userOf(name, Tool.JKSV), Kind.ACCOUNT, null, files)
            else -> null
        }
    }

    /** The Switch user a backup's default name carries. */
    fun userOf(name: String, tool: Tool): String? = when (tool) {
        // "<nickname> - YYYY.MM.DD @ HH.MM.SS"
        Tool.JKSV -> name.substringBeforeLast(" - ", "").trim().ifEmpty { null }
        // "YYYY-MM-DD HH-MM-SS <user>"
        Tool.CHECKPOINT -> Regex("^\\d{4}-?\\d{2}-?\\d{2}[ _]\\d{2}-?\\d{2}-?\\d{2} (.+)$").find(name)?.groupValues?.get(1)?.trim()
    }

    /**
     * A sortable time for a backup, newest highest: its meta file's, else the date and time its name
     * carries (both tools name a backup by when it was made), else 0.
     */
    fun order(b: Backup): Long {
        b.savedAt?.let { return it }
        val m = Regex("(\\d{4})[.\\-]?(\\d{2})[.\\-]?(\\d{2})\\D{1,4}(\\d{2})[.\\-:]?(\\d{2})[.\\-:]?(\\d{2})").find(b.name) ?: return 0
        val (y, mo, d, h, mi, s) = m.destructured
        return days(y.toInt(), mo.toInt(), d.toInt()) * 86_400L + h.toInt() * 3600 + mi.toInt() * 60 + s.toInt()
    }

    /** Days from 1970-01-01 to a civil date (Howard Hinnant's days_from_civil). */
    private fun days(y0: Int, m: Int, d: Int): Long {
        val y = if (m <= 2) y0 - 1 else y0
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (m + (if (m > 2) -3 else 9)) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097L + doe - 719_468L
    }

    private fun u32(b: ByteArray, at: Int): Long = (0 until 4).fold(0L) { acc, i -> acc or ((b[at + i].toLong() and 0xFF) shl (8 * i)) }
    private fun u64(b: ByteArray, at: Int): Long = (0 until 8).fold(0L) { acc, i -> acc or ((b[at + i].toLong() and 0xFF) shl (8 * i)) }
    private fun hex16(v: Long): String = (0 until 16).joinToString("") { i -> ((v ushr (60 - 4 * i)) and 0xF).toString(16) }.uppercase()
}
