package io.github.matiyaaa.fuse.sync

/** An emulator's own folder on this computer, as one of a family's members. */
data class FamilyFolder(val id: String, val name: String, val folder: String)

/**
 * What bringing one emulator's things over to another of its family would do: each file of [from]'s
 * folder that [to]'s folder doesn't have yet ([files], paths below both), how many it already has
 * ([kept]), and what stays behind on purpose ([leftOut], in words).
 */
data class FamilyMove(
    val from: FamilyFolder,
    val to: FamilyFolder,
    val files: List<String>,
    val kept: Int,
    val carried: List<String>,
    val leftOut: List<String>,
    val truncated: Boolean = false,
)

/**
 * Emulators that are forks of one another and keep their things the same way, so one's can be
 * brought over to another: the yuzu family (Eden, Citron, Sudachi, yuzu, suyu, torzu) and the
 * Citra family (Azahar, Citra, Lime3DS). Their user folders hold the same folders in the same form
 * (Eden copies a legacy one whole on its first start, migration_worker.cpp; Azahar knows Citra's
 * and Lime3DS's folders as legacy ones, file_util.cpp), so Fuse brings over the folders that hold a
 * person's things (keys, the emulated NAND and SD card, mods) when the newer one has started already
 * and won't offer its own migration any more.
 *
 * Nothing in the folder brought from is changed, nothing already in the folder brought to is
 * replaced, and these stay behind: shader caches (built for one build and graphics driver; the new
 * one builds its own), save states (made by one build, which another may not load), logs, and
 * settings files (each fork names and adds settings its own way, so the new one keeps its own; its
 * controller setup is set again there).
 */
object EmulatorFamilies {
    private val SWITCH = listOf("eden" to "Eden", "citron" to "Citron", "sudachi" to "Sudachi", "yuzu" to "yuzu", "suyu" to "suyu", "torzu" to "torzu")
    private val N3DS = listOf("azahar" to "Azahar", "citra" to "Citra", "lime3ds" to "Lime3DS")

    /** What a family's members keep of a person's, below their user folder. */
    // The folder names each fork uses (Eden's common/fs/fs_paths.h; Azahar's common/common_paths.h).
    private val SWITCH_CARRIED = listOf("keys", "nand", "sdmc", "load", "amiibo", "play_time")
    private val N3DS_CARRIED = listOf("sysdata", "nand", "sdmc", "load", "cheats")

    /** At most this many files are brought over at once, so a wrong folder stops early. */
    const val MAX_FILES = 200_000

    /** The family [emulatorId] belongs to (its members' ids), or null. */
    fun family(emulatorId: String): List<String>? {
        val id = SaveAdapters.baseId(emulatorId).let { if (it == "eden-nightly") "eden" else it }
        return listOf(SWITCH, N3DS).firstOrNull { f -> f.any { it.first == id } }?.map { it.first }
    }

    fun name(id: String): String = (SWITCH + N3DS).firstOrNull { it.first == id }?.second ?: id

    /**
     * [id]'s own user folder here, where it keeps it when nothing was chosen: beside a portable
     * program (`user`), else in the system's place for it, by the folder names each fork uses. Null
     * when it isn't there.
     */
    fun folder(env: SaveEnvironment, id: String, emulatorPath: String? = null): String? {
        val portable = emulatorPath?.let { SaveAdapters.parent(it) + "/user" }
        return when (id) {
            "eden", "citron", "sudachi", "yuzu", "suyu", "torzu" -> {
                val cap = id.replaceFirstChar { it.uppercase() }
                when (env.host) {
                    "WINDOWS" -> SaveAdapters.firstDir(env, portable, "${SaveAdapters.appData(env)}/$id", "${SaveAdapters.appData(env)}/$cap")
                    "MACOS" -> SaveAdapters.firstDir(env, "${SaveAdapters.xdgData(env)}/$id", "${SaveAdapters.macSupport(env)}/$id", "${SaveAdapters.macSupport(env)}/$cap")
                    "ANDROID" -> null
                    else -> SaveAdapters.firstDir(env, "${SaveAdapters.xdgData(env)}/$id", "${env.home}/.var/app/org.${id}_emu.$id/data/$id", "${env.home}/.var/app/dev.${id}_emu.$id/data/$id")
                }
            }
            "azahar", "citra", "lime3ds" -> {
                val (win, mac, xdg) = when (id) {
                    "azahar" -> Triple("Azahar", "Azahar", "azahar-emu")
                    "citra" -> Triple("Citra", "Citra", "citra-emu")
                    else -> Triple("Lime3DS", "Lime3DS", "lime3ds-emu")
                }
                when (env.host) {
                    "WINDOWS" -> SaveAdapters.firstDir(env, portable, "${SaveAdapters.appData(env)}/$win")
                    "MACOS" -> SaveAdapters.firstDir(env, "${SaveAdapters.macSupport(env)}/$mac")
                    "ANDROID" -> null
                    else -> SaveAdapters.firstDir(env, "${SaveAdapters.xdgData(env)}/$xdg", "${env.home}/.var/app/org.azahar_emu.Azahar/data/$xdg".takeIf { id == "azahar" }, "${env.home}/.var/app/org.citra_emu.citra/data/$xdg".takeIf { id == "citra" })
                }
            }
            else -> null
        }
    }

    /**
     * The other members of [emulatorId]'s family that have a folder here with something of a
     * person's in it, as places to bring things over from.
     */
    fun others(env: SaveEnvironment, emulatorId: String): List<FamilyFolder> {
        val members = family(emulatorId) ?: return emptyList()
        val self = SaveAdapters.baseId(emulatorId).let { if (it == "eden-nightly") "eden" else it }
        val carried = carried(self)
        return members.filter { it != self }.mapNotNull { id ->
            folder(env, id)?.takeIf { f -> carried.any { env.list("$f/$it").isNotEmpty() } }?.let { FamilyFolder(id, name(id), it) }
        }
    }

    private fun carried(id: String) = if (id in SWITCH.map { it.first }) SWITCH_CARRIED else N3DS_CARRIED

    /** Every file [from] has in the folders that are carried, that [to] doesn't have yet. */
    fun plan(env: SaveEnvironment, from: FamilyFolder, to: FamilyFolder): FamilyMove {
        val folders = carried(from.id).filter { env.isDirectory("${from.folder}/$it") }
        val files = ArrayList<String>()
        var kept = 0
        var truncated = false
        fun walk(rel: String) {
            if (files.size >= MAX_FILES) { truncated = true; return }
            for (name in env.list("${from.folder}/$rel")) {
                val r = "$rel/$name"
                if (env.isDirectory("${from.folder}/$r")) walk(r)
                else if (env.exists("${to.folder}/$r")) kept++
                else files += r
            }
        }
        folders.forEach(::walk)
        val leftOut = buildList {
            add("Shader caches: ${to.name} builds its own")
            add("Settings and controller setup: ${to.name} keeps its own")
            if (from.id in N3DS.map { it.first } && env.list("${from.folder}/states").isNotEmpty()) add("Save states: made by ${from.name}'s build, which ${to.name} may not load")
            if (kept > 0) add("$kept file${if (kept == 1) "" else "s"} ${to.name} has already, which stay as they are")
        }
        return FamilyMove(from, to, files, kept, folders, leftOut, truncated)
    }
}
