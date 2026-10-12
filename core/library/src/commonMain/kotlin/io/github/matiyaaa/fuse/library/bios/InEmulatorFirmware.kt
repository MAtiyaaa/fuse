package io.github.matiyaaa.fuse.library.bios

import io.github.matiyaaa.fuse.library.FsPath
import io.github.matiyaaa.fuse.library.FuseFileSystem
import io.github.matiyaaa.fuse.model.BiosState
import io.github.matiyaaa.fuse.model.BiosStatus
import kotlin.coroutines.cancellation.CancellationException

/** One emulator's data folder to look for installed firmware in: [family] says how it keeps it. */
data class EmulatorDataFolder(val platform: String, val family: String, val emulator: String, val folder: String)

/**
 * Firmware installed inside an emulator, which no BIOS folder holds: read only, from where each
 * keeps it once installed from its own menu. Switch emulators of the yuzu line (Eden, Citron,
 * Sudachi, yuzu): `keys/prod.keys` and the system titles in `nand/system/Contents/registered`;
 * Ryujinx: `system/prod.keys` and `bis/system/Contents/registered`; RPCS3: `dev_flash/vsh/module`
 * holding vsh.self; Vita3K: `vs0/vsh` and `vs0/sys`. A find is "present" (nothing is hashed); not
 * finding it is never called missing, since the emulator Fuse starts may keep it elsewhere.
 */
object InEmulatorFirmware {
    private class Look(val keys: String?, val firmware: String, val firmwareFile: String?)

    private val looks = mapOf(
        "yuzu" to Look("keys/prod.keys", "nand/system/Contents/registered", null),
        "ryujinx" to Look("system/prod.keys", "bis/system/Contents/registered", null),
        "rpcs3" to Look(null, "dev_flash/vsh/module", "vsh.self"),
        "vita3k" to Look(null, "vs0/vsh", null),
    )

    /** What [folders] say about [platform]'s firmware, over [checked] (the BIOS folder check); [checked] when none apply. */
    suspend fun merge(platform: String, checked: BiosStatus, folders: List<EmulatorDataFolder>, fs: FuseFileSystem): BiosStatus {
        val mine = folders.filter { it.platform == platform && it.family in looks }
        if (mine.isEmpty() || checked.state == BiosState.READY) return checked
        val looked = ArrayList<String>()
        val lacks = ArrayList<String>()
        for (f in mine) {
            val look = looks.getValue(f.family)
            val root = FsPath.normalize(f.folder)
            if (fs.stat(root)?.isDirectory != true) continue
            looked += root
            val keys = look.keys?.let { FsPath.join(root, it) }
            val keysThere = keys == null || (fs.stat(keys)?.let { !it.isDirectory && it.sizeBytes > 0 } == true)
            val firmware = FsPath.join(root, look.firmware)
            val firmwareThere = try {
                val entries = fs.list(firmware)
                if (look.firmwareFile != null) entries.any { it.name.equals(look.firmwareFile, ignoreCase = true) } else entries.isNotEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            if (keysThere && firmwareThere) {
                return checked.copy(
                    state = BiosState.READY,
                    found = listOfNotNull(keys, firmware),
                    missing = emptyList(),
                    searched = checked.searched + looked,
                    note = "Installed in ${f.emulator}. Fuse checked that it is there, not which version it is.",
                )
            }
            val lacking = listOfNotNull(if (!keysThere) "keys" else null, if (!firmwareThere) "system firmware" else null).joinToString(" and ")
            lacks += "${f.emulator} has no $lacking in $root."
        }
        if (looked.isEmpty()) return checked
        return checked.copy(
            state = BiosState.UNKNOWN,
            searched = checked.searched + looked,
            note = (lacks + "If you play with another copy, Fuse can't see that one. Install it from the emulator's menu.").joinToString(" "),
        )
    }
}
