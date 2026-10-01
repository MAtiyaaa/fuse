package io.github.matiyaaa.fuse.services

import io.github.matiyaaa.fuse.model.LibrarySourceKind
import io.github.matiyaaa.fuse.storage.StorageVolumes
import io.github.matiyaaa.fuse.ui.shell.store.DeviceLocations
import io.github.matiyaaa.fuse.ui.shell.store.LocationHint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Existing library and firmware folders on this device: `ROMs` style folders at the root of every
 * mounted volume, EmuDeck's `Emulation/roms`, ES-DE's configured ROM directory, and BIOS folders next
 * to them. Folder names are matched without case (shared storage is case-insensitive on many
 * devices) and reported with their real spelling. Cartridge's own download folder is not guessed:
 * its default location is not documented.
 */
class AndroidDeviceLocations(private val volumes: StorageVolumes) : DeviceLocations {

    override suspend fun libraryCandidates(): List<LocationHint> = withContext(Dispatchers.IO) {
        val hints = ArrayList<LocationHint>()
        for (volume in volumes.mounted()) {
            val root = File(volume.root)
            child(root, "roms")?.let { hints += LocationHint(it.path, "ROMs on ${volume.label}", LibrarySourceKind.ROMS_ROOT) }
            child(root, "emulation")?.let { child(it, "roms") }?.let {
                hints += LocationHint(it.path, "Emulation/roms on ${volume.label}", LibrarySourceKind.ROMS_ROOT)
            }
        }
        esDeRomDirectory()?.let { hints += LocationHint(it, "ES-DE ROM folder", LibrarySourceKind.ROMS_ROOT) }
        hints.distinctBy { canonical(it.path) }
    }

    override suspend fun biosRoots(): List<String> = withContext(Dispatchers.IO) {
        val roots = ArrayList<String>()
        for (volume in volumes.mounted()) {
            val root = File(volume.root)
            child(root, "bios")?.let { roots += it.path }
            child(root, "emulation")?.let { child(it, "bios") }?.let { roots += it.path }
        }
        // BIOS next to a configured ES-DE ROM folder (ROMs/../BIOS).
        esDeRomDirectory()?.let { File(it).parentFile }?.let { child(it, "bios") }?.let { roots += it.path }
        val primary = File(volumes.primaryRoot)
        child(primary, "retroarch")?.let { child(it, "system") }?.let { roots += it.path }
        roots.filter { File(it).canRead() }.distinctBy(::canonical)
    }

    override suspend fun storageRoots(): List<LocationHint> = withContext(Dispatchers.IO) {
        volumes.mounted().filter { File(it.root).canRead() }.map { LocationHint(it.root, it.label) }
    }

    /** ES-DE's `ROMDirectory` setting, when ES-DE's settings file is readable and the folder exists. */
    private fun esDeRomDirectory(): String? {
        val settings = File(volumes.primaryRoot, "ES-DE/settings/es_settings.xml")
        val text = try {
            if (!settings.isFile || settings.length() > 1_000_000) return null
            settings.readText()
        } catch (e: Exception) {
            return null
        }
        val value = ROM_DIRECTORY.find(text)?.groupValues?.get(1)?.trim()?.takeIf { it.startsWith("/") } ?: return null
        return value.trimEnd('/').takeIf { File(it).isDirectory }
    }

    /** The child directory of [parent] whose name equals [name] ignoring case, with its real spelling. */
    private fun child(parent: File, name: String): File? {
        val names = try {
            parent.list()
        } catch (e: SecurityException) {
            null
        } ?: return null
        val match = names.firstOrNull { it.equals(name, ignoreCase = true) } ?: return null
        return File(parent, match).takeIf { it.isDirectory }
    }

    private fun canonical(path: String): String = try {
        File(path).canonicalPath.lowercase()
    } catch (e: Exception) {
        path.lowercase()
    }

    private companion object {
        val ROM_DIRECTORY = Regex("""<string\s+name="ROMDirectory"\s+value="([^"]*)"""")
    }
}
