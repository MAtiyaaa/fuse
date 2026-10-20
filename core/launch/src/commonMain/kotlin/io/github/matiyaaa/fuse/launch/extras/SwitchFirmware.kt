package io.github.matiyaaa.fuse.launch.extras

/**
 * Switch system firmware put into an emulator's NAND, as the emulators of the yuzu line do it
 * themselves (Citron's src/citron/main.cpp, `OnInstallFirmware` and `OnInstallFirmwareFromZip`):
 * the `.nca` files at the top of a firmware folder or zip (not in folders below), copied by their
 * own names into `nand/system/Contents/registered` after everything already in it is cleared. That
 * folder is where every emulator of the line reads the system's titles (the registered cache of
 * core/file_sys). A firmware dump has dozens of them; fewer than a handful is not a firmware.
 *
 * Ryujinx keeps its firmware in its own form (`bis/system/Contents/registered`, each title in a
 * folder of its own) and its source can't be read here to check it, so it installs its firmware
 * itself, from its Tools menu.
 */
object SwitchFirmware {
    /** Below the emulator's user folder. */
    const val REGISTERED = "nand/system/Contents/registered"

    /** Fewer `.nca` files than this isn't a firmware (a whole one has well over a hundred). */
    const val MIN_FILES = 10

    /** The largest firmware archive Fuse unpacks. */
    const val MAX_BYTES = 2L * 1024 * 1024 * 1024

    private val NCA = Regex("^[0-9a-fA-F]{32}(\\.cnmt)?\\.nca$")

    /** The firmware's files among [entries] (paths in a zip or below a folder): `.nca` files at its top, by name. */
    fun files(entries: List<String>): List<String> {
        val top = entries.map { it.replace('\\', '/') }.filter { '/' !in it && it.endsWith(".nca", ignoreCase = true) }
        // A zip with everything in one folder (as some dumps are made) is read from inside it.
        if (top.isEmpty()) {
            val folders = entries.map { it.replace('\\', '/') }.filter { it.count { c -> c == '/' } == 1 }.groupBy { it.substringBefore('/') }
            val only = folders.entries.singleOrNull { (_, files) -> files.any { it.endsWith(".nca", ignoreCase = true) } }
            if (only != null) return only.value.filter { NCA.matches(it.substringAfter('/')) }
        }
        return top.filter { NCA.matches(it) }
    }

    /** [files] is a firmware Fuse can install. */
    fun isFirmware(files: List<String>): Boolean = files.size >= MIN_FILES
}
