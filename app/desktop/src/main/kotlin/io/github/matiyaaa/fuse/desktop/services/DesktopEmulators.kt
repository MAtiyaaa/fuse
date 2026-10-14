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

    /**
     * Where Eden, Citron, Ryujinx, RPCS3 and Vita3K keep what is installed from their menus: a
     * portable folder beside the program first (`user` for the yuzu line, `portable` for Ryujinx,
     * the program's own folder for RPCS3 on Windows), then the system's data or config folder.
     */
    override fun firmwareFolders(installed: List<InstalledEmulator>): List<io.github.matiyaaa.fuse.library.bios.EmulatorDataFolder> {
        val home = env.homeDir.trimEnd('/')
        val data = System.getenv("XDG_DATA_HOME")?.takeIf { it.startsWith("/") } ?: "$home/.local/share"
        val config = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.startsWith("/") } ?: "$home/.config"
        val appData = System.getenv("APPDATA")
        val mac = "$home/Library/Application Support"
        val out = ArrayList<io.github.matiyaaa.fuse.library.bios.EmulatorDataFolder>()
        for (e in installed) {
            val name = e.id.value.substringAfter('.')
            val dir = File(e.appId).takeIf { it.isAbsolute }?.parentFile?.path
            fun add(platform: String, family: String, vararg paths: String?) =
                paths.filterNotNull().forEach { out += io.github.matiyaaa.fuse.library.bios.EmulatorDataFolder(platform, family, e.name, it) }
            when (name) {
                "eden", "citron" -> add(
                    "switch", "yuzu", dir?.let { "$it/user" },
                    when (os) { DesktopOs.LINUX -> "$data/$name"; DesktopOs.WINDOWS -> appData?.let { "$it/$name" }; DesktopOs.MACOS -> "$mac/$name" },
                    if (os == DesktopOs.LINUX && name == "eden") "$home/.var/app/dev.eden_emu.eden/data/eden" else null,
                )
                "ryujinx" -> add(
                    "switch", "ryujinx", dir?.let { "$it/portable" },
                    when (os) { DesktopOs.LINUX -> "$config/Ryujinx"; DesktopOs.WINDOWS -> appData?.let { "$it/Ryujinx" }; DesktopOs.MACOS -> "$mac/Ryujinx" },
                )
                "rpcs3" -> add(
                    "ps3", "rpcs3", if (os == DesktopOs.WINDOWS) dir else null,
                    when (os) { DesktopOs.LINUX -> "$config/rpcs3"; DesktopOs.MACOS -> "$mac/rpcs3"; DesktopOs.WINDOWS -> null },
                    if (os == DesktopOs.LINUX) "$home/.var/app/net.rpcs3.RPCS3/config/rpcs3" else null,
                )
                "vita3k" -> add(
                    "psvita", "vita3k", dir?.let { "$it/portable/fs" },
                    when (os) { DesktopOs.LINUX -> "$data/Vita3K/Vita3K"; DesktopOs.WINDOWS -> appData?.let { "$it/Vita3K/Vita3K" }; DesktopOs.MACOS -> "$mac/Vita3K/Vita3K" },
                )
            }
        }
        return out.distinctBy { it.platform to it.folder }
    }

    override fun rpcs3DevHdd0(installed: List<InstalledEmulator>): List<String> {
        val home = env.homeDir.trimEnd('/')
        val known = when (os) {
            DesktopOs.LINUX -> listOf("$home/.config/rpcs3/dev_hdd0", "$home/.var/app/net.rpcs3.RPCS3/config/rpcs3/dev_hdd0")
            DesktopOs.MACOS -> listOf("$home/Library/Application Support/rpcs3/dev_hdd0")
            DesktopOs.WINDOWS -> emptyList()
        }
        // RPCS3 on Windows keeps everything beside its program.
        val beside = installed.filter { it.id.value.endsWith(".rpcs3") }.mapNotNull { e -> File(e.appId).parentFile?.let { File(it, "dev_hdd0").path } }
        return (known + beside).filter { File(it).isDirectory }.map { File(it).absoluteFile.fusePath }.distinct()
    }

    override fun shadPs4UserDirs(installed: List<InstalledEmulator>): List<String> {
        val home = env.homeDir.trimEnd('/')
        // Portable (`user` beside the program) first, then the system's data folder (common/path_util.cpp).
        val beside = installed.filter { it.id.value.endsWith(".shadps4") }.mapNotNull { e -> File(e.appId).parentFile?.let { File(it, "user").path } }
        val known = when (os) {
            DesktopOs.LINUX -> listOf(
                (System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.local/share") + "/shadPS4",
                "$home/.var/app/net.shadps4.shadPS4/data/shadPS4",
            )
            DesktopOs.MACOS -> listOf("$home/Library/Application Support/shadPS4")
            DesktopOs.WINDOWS -> listOfNotNull(System.getenv("APPDATA")?.let { "$it/shadPS4" })
        }
        return (beside + known).filter { File(it).isDirectory }.map { File(it).absoluteFile.fusePath }.distinct()
    }

    override fun xeniaContentRoots(installed: List<InstalledEmulator>): List<String> {
        val home = env.homeDir.trimEnd('/')
        // Its storage root (xenia_main.cc): beside the program when portable (Windows builds are by
        // default, any build with portable.txt beside it), else Xenia in Documents on Windows and in
        // $XDG_DATA_HOME (or ~/.local/share) elsewhere; content is in it.
        val beside = installed.filter { it.id.value.endsWith(".xenia") }.mapNotNull { e -> File(e.appId).parentFile?.let { File(it, "content").path } }
        val known = when (os) {
            DesktopOs.LINUX -> listOf((System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.local/share") + "/Xenia/content", "$home/Xenia/content")
            DesktopOs.WINDOWS -> listOfNotNull(System.getenv("USERPROFILE"), System.getenv("OneDrive")).map { "$it/Documents/Xenia/content" }
            DesktopOs.MACOS -> emptyList()
        }
        return (beside + known).filter { File(it).isDirectory }.map { File(it).absoluteFile.fusePath }.distinct()
    }

    override fun retroArchCorePath(installed: InstalledEmulator, core: String): String? = when (os) {
        DesktopOs.LINUX -> LinuxDetector.findRetroArchCore(env, installed, core)
        DesktopOs.WINDOWS -> WindowsDetector.findRetroArchCore(files, installed, core)
        DesktopOs.MACOS -> MacDetector.findRetroArchCore(files, installed, core)
    }

    override fun retroArchCoresVisible(installed: InstalledEmulator): Boolean = when (os) {
        DesktopOs.LINUX -> LinuxDetector.retroArchCoreDirs(env, installed).any { File(it).isDirectory }
        DesktopOs.WINDOWS -> WindowsDetector.retroArchCoreDirs(files, installed).any(files::isDirectory)
        DesktopOs.MACOS -> MacDetector.retroArchCoreDirs(files, installed).any(files::isDirectory)
    }

    /** RetroArch's core folders for [installed], in the order it reads them. */
    private fun coreDirs(installed: InstalledEmulator): List<String> = when (os) {
        DesktopOs.LINUX -> LinuxDetector.retroArchCoreDirs(env, installed)
        DesktopOs.WINDOWS -> WindowsDetector.retroArchCoreDirs(files, installed)
        DesktopOs.MACOS -> MacDetector.retroArchCoreDirs(files, installed)
    }

    private val host: io.github.matiyaaa.fuse.model.Host get() = when (os) {
        DesktopOs.LINUX -> io.github.matiyaaa.fuse.model.Host.LINUX
        DesktopOs.WINDOWS -> io.github.matiyaaa.fuse.model.Host.WINDOWS
        DesktopOs.MACOS -> io.github.matiyaaa.fuse.model.Host.MACOS
    }

    /** The folder a downloaded core goes in: the person's own cores folder that RetroArch reads (see RetroArchBuildbot). */
    private fun coreInstallFolder(installed: InstalledEmulator): String? {
        if (!installed.id.value.substringAfter('.').startsWith("retroarch")) return null
        // A Steam copy keeps its cores as Steam's own downloads: Steam installs them, not Fuse.
        if (installed.detectedVia.contains("Steam", ignoreCase = true)) return null
        return io.github.matiyaaa.fuse.launch.RetroArchBuildbot.installFolder(coreDirs(installed), env.homeDir) { File(it).isDirectory }
    }

    override fun canInstallRetroArchCore(installed: InstalledEmulator): Boolean =
        coreInstallFolder(installed) != null && io.github.matiyaaa.fuse.launch.RetroArchBuildbot.folder(host, System.getProperty("os.arch").orEmpty()) != null

    override suspend fun installRetroArchCore(installed: InstalledEmulator, core: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val folder = coreInstallFolder(installed) ?: error("Fuse can't put cores where ${installed.name} reads them.")
            val url = io.github.matiyaaa.fuse.launch.RetroArchBuildbot.url(host, System.getProperty("os.arch").orEmpty(), core)
                ?: error("libretro has no build of this core for this computer.")
            val name = io.github.matiyaaa.fuse.launch.RetroArchCores.coreFile(host, core)
            val client = java.net.http.HttpClient.newBuilder()
                .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                .connectTimeout(java.time.Duration.ofSeconds(20))
                .build()
            val request = java.net.http.HttpRequest.newBuilder(java.net.URI(url)).timeout(java.time.Duration.ofMinutes(5)).GET().build()
            val response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofInputStream())
            if (response.statusCode() != 200) {
                response.body().close()
                error("libretro didn't have it (HTTP ${response.statusCode()}).")
            }
            val dir = File(folder).apply { mkdirs() }
            val target = File(dir, name)
            val tmp = File(dir, ".$name.fuse-download")
            var found = false
            java.util.zip.ZipInputStream(response.body().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    // The library itself, never a path out of the folder.
                    if (entry.isDirectory || entry.name.substringAfterLast('/') != name) continue
                    tmp.outputStream().use { out -> zip.copyTo(out) }
                    found = true
                    break
                }
            }
            if (!found) {
                tmp.delete()
                error("libretro's download held no $name.")
            }
            val head = tmp.inputStream().use { it.readNBytes(8) }
            if (!io.github.matiyaaa.fuse.launch.RetroArchBuildbot.looksLikeLibrary(host, head)) {
                tmp.delete()
                error("What libretro sent isn't a core.")
            }
            try {
                java.nio.file.Files.move(tmp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
                java.nio.file.Files.move(tmp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
            Log.info("RetroArch core $core installed into $folder")
            target.absoluteFile.fusePath
        }.onFailure { Log.warn("RetroArch core $core: not installed", it) }
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

    override suspend fun steamRoots(): io.github.matiyaaa.fuse.ui.shell.store.SteamPlaces = withContext(Dispatchers.IO) {
        val (roots, drives) = folders.steamPlaces()
        io.github.matiyaaa.fuse.ui.shell.store.SteamPlaces(roots, drives)
    }
}
