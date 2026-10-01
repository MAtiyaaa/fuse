package io.github.matiyaaa.fuse.desktop.services

import io.github.matiyaaa.fuse.desktop.Log
import io.github.matiyaaa.fuse.desktop.system.DesktopOs
import io.github.matiyaaa.fuse.desktop.system.fusePath
import io.github.matiyaaa.fuse.launch.desktop.HostFiles
import io.github.matiyaaa.fuse.launch.desktop.MacDetector
import io.github.matiyaaa.fuse.launch.desktop.WindowsDetector
import io.github.matiyaaa.fuse.launch.linux.LinuxDetector
import io.github.matiyaaa.fuse.model.EmulatorId
import io.github.matiyaaa.fuse.model.InstalledEmulator
import io.github.matiyaaa.fuse.ui.shell.store.DeviceLocations
import io.github.matiyaaa.fuse.ui.shell.store.EmulatorDetector
import io.github.matiyaaa.fuse.ui.shell.store.LocationHint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Finds emulators with the shared detectors over the real system: [LinuxDetector] on Linux,
 * [WindowsDetector] and [MacDetector] elsewhere. On Windows and macOS the user can also point Fuse at
 * an emulator it missed, and add folders to search; both are kept in [EmulatorLocations].
 */
internal class DesktopEmulatorDetector(
    private val env: SystemLinuxEnvironment,
    private val folders: KnownFolders,
    configDir: String,
    private val os: DesktopOs = DesktopOs.current,
) : EmulatorDetector {
    private val files = SystemHostFiles(env.homeDir, os)
    private val locations = EmulatorLocations(File(configDir, "emulators.json"))

    /** The folder of the AppImage Fuse runs from is searched too (emulators often sit next to it). */
    private val extraDirs: List<String> =
        listOfNotNull(System.getenv("APPIMAGE")?.let { File(it).parent }).filter { File(it).isDirectory }

    override suspend fun detect(): List<InstalledEmulator> = withContext(Dispatchers.IO) {
        when (os) {
            DesktopOs.LINUX -> LinuxDetector.detect(env, extraDirs = extraDirs)
            DesktopOs.WINDOWS -> WindowsDetector.detect(files, extraDirs = locations.folders(), located = locations.located())
            DesktopOs.MACOS -> MacDetector.detect(files, extraDirs = locations.folders(), located = locations.located())
        }
    }

    override fun biosFolders(installed: List<InstalledEmulator>): List<String> =
        (folders.retroArchSystemDirs(installed) + folders.biosRoots()).distinct()

    override fun retroArchCorePath(installed: InstalledEmulator, core: String): String? = when (os) {
        DesktopOs.LINUX -> LinuxDetector.findRetroArchCore(env, installed, core)
        DesktopOs.WINDOWS -> WindowsDetector.findRetroArchCore(files, installed, core)
        DesktopOs.MACOS -> MacDetector.findRetroArchCore(files, installed, core)
    }

    override val homeDir: String get() = env.homeDir

    override val canLocate: Boolean get() = os != DesktopOs.LINUX

    /** A Windows program (.exe), or on macOS an app or a program file. */
    override suspend fun locate(emulator: EmulatorId, path: String): Boolean = withContext(Dispatchers.IO) {
        val p = File(path).absoluteFile.fusePath.trimEnd('/')
        val ok = when (os) {
            DesktopOs.WINDOWS -> File(p).isFile && p.endsWith(".exe", ignoreCase = true)
            DesktopOs.MACOS -> MacDetector.resolvePicked(files, p) != null
            DesktopOs.LINUX -> false
        }
        if (ok) locations.locate(emulator.value, p)
        ok
    }

    override suspend fun forget(emulator: EmulatorId) = withContext(Dispatchers.IO) { locations.forget(emulator.value) }

    override fun searchFolders(): List<String> = locations.folders()

    override suspend fun setSearchFolders(folders: List<String>) = withContext(Dispatchers.IO) {
        locations.setFolders(folders.map { File(it).absoluteFile.fusePath.trimEnd('/') }.distinct())
    }

    override fun located(): Map<EmulatorId, String> = locations.located().mapKeys { EmulatorId(it.key) }
}

/**
 * Emulators the user located and extra folders to search, in `emulators.json` in Fuse's config
 * folder. Read once and kept in memory; every change is written at once.
 */
internal class EmulatorLocations(private val file: File) {
    private val json = Json { prettyPrint = true }
    private var located: Map<String, String> = emptyMap()
    private var folders: List<String> = emptyList()

    init {
        try {
            if (file.isFile) {
                val root = json.parseToJsonElement(file.readText()).jsonObject
                located = root["located"]?.jsonObject?.mapNotNull { (k, v) -> v.jsonPrimitive.contentOrNull?.let { k to it } }?.toMap().orEmpty()
                folders = root["folders"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
            }
        } catch (e: Exception) {
            Log.warn("emulators.json could not be read; located emulators are forgotten", e)
        }
    }

    @Synchronized fun located(): Map<String, String> = located

    @Synchronized fun folders(): List<String> = folders

    @Synchronized fun locate(id: String, path: String) {
        located = located + (id to path)
        save()
    }

    @Synchronized fun forget(id: String) {
        located = located - id
        save()
    }

    @Synchronized fun setFolders(list: List<String>) {
        folders = list
        save()
    }

    private fun save() {
        val root = JsonObject(
            mapOf(
                "located" to JsonObject(located.mapValues { JsonPrimitive(it.value) }),
                "folders" to JsonArray(folders.map(::JsonPrimitive)),
            ),
        )
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json.encodeToString(JsonObject.serializer(), root))
            java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            Log.warn("emulators.json could not be written", e)
        }
    }
}

/** [HostFiles] over the real file system, with forward-slash paths. Blocking; call from an IO thread. */
internal class SystemHostFiles(override val homeDir: String, private val os: DesktopOs = DesktopOs.current) : HostFiles {
    override fun env(name: String): String? = System.getenv(name)

    override fun pathDirectories(): List<String> =
        (System.getenv("PATH") ?: "").split(File.pathSeparatorChar).filter { it.isNotBlank() }.map { File(it).fusePath.trimEnd('/') }.distinct()

    override fun isFile(path: String): Boolean = File(path).isFile

    override fun isDirectory(path: String): Boolean = File(path).isDirectory

    override fun list(dir: String): List<String> = try {
        File(dir).list()?.toList() ?: emptyList()
    } catch (e: SecurityException) {
        emptyList()
    }

    override fun readText(path: String): String? = SystemLinuxEnvironment.readSmallText(path)

    override fun drives(): List<String> =
        if (os == DesktopOs.WINDOWS) File.listRoots().orEmpty().filter { it.isDirectory }.map { it.fusePath.trimEnd('/') + "/" } else emptyList()
}

/** Library and firmware suggestions for onboarding and Settings. */
internal class DesktopLocations(private val folders: KnownFolders) : DeviceLocations {
    override suspend fun libraryCandidates(): List<LocationHint> = withContext(Dispatchers.IO) {
        folders.romRoots().map { LocationHint(it.path, it.label) }
    }

    override suspend fun biosRoots(): List<String> = withContext(Dispatchers.IO) {
        (folders.biosRoots() + folders.retroArchSystemDirs()).distinct()
    }

    override suspend fun storageRoots(): List<LocationHint> = withContext(Dispatchers.IO) {
        folders.storageRoots().map { LocationHint(it.path, it.label) }
    }
}
