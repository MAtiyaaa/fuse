package io.github.matiyaaa.fuse.desktop.services

import io.github.matiyaaa.fuse.desktop.system.DesktopOs
import io.github.matiyaaa.fuse.desktop.system.Processes
import io.github.matiyaaa.fuse.desktop.system.fusePath
import io.github.matiyaaa.fuse.launch.ports.Recomp
import io.github.matiyaaa.fuse.launch.ports.Recomps
import io.github.matiyaaa.fuse.ui.shell.store.FoundRecomp
import io.github.matiyaaa.fuse.ui.shell.store.RecompFiles
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Recomps on a computer: the ones the person installed found where their own builds go (a Flatpak,
 * the program on the PATH, or in the folders people keep programs and AppImages in), and their
 * files read and written only in a recomp's own folder, Fuse's folder of ports ([ports], where its
 * own copies keep theirs) and Fuse's folder of controls profiles ([profiles]).
 */
internal class DesktopRecompFiles(
    private val system: DesktopOs,
    private val ports: File,
    profilesDir: File,
    private val home: String = System.getProperty("user.home").orEmpty(),
    private val env: Map<String, String> = System.getenv(),
    private val which: (String) -> String? = { Processes.which(it) },
    private val run: (List<String>, Long) -> Processes.Output? = { argv, timeout -> Processes.run(argv, timeoutMs = timeout) },
) : RecompFiles {
    override val profiles: String = profilesDir.absoluteFile.fusePath

    private val os = when (system) {
        DesktopOs.LINUX -> "linux"
        DesktopOs.WINDOWS -> "windows"
        DesktopOs.MACOS -> "macos"
    }

    /** Folders found holding a recomp's files beside its program (portable mode), allowed once found. */
    private val portable = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    override suspend fun find(): List<FoundRecomp> = withContext(Dispatchers.IO) {
        val out = ArrayList<FoundRecomp>()
        for (r in Recomps.all) {
            if (system == DesktopOs.LINUX) flatpak(r)?.let { out += it }
            for (program in programs(r)) {
                val dir = program.parentFile ?: continue
                val inBundle = system == DesktopOs.MACOS && program.path.contains(".app/Contents/MacOS/")
                // A portable.txt beside the program (beside the app on a Mac) keeps its files there.
                val beside = if (inBundle) program.parentFile.parentFile.parentFile.parentFile else dir
                val isPortable = beside != null && File(beside, Recomps.PORTABLE).isFile
                val folder = Recomps.folder(r, os, home, env, programDir = beside?.absoluteFile?.fusePath, portable = isPortable) ?: continue
                if (isPortable) portable += folder
                out += FoundRecomp(
                    id = r.id,
                    how = if (program.name.lowercase().endsWith(".appimage")) "AppImage" else "Program",
                    path = program.absoluteFile.fusePath,
                    argv = listOf(program.absoluteFile.fusePath),
                    // Its assets are read from the folder it starts in (support.cpp get_program_path is empty there).
                    cwd = dir.absoluteFile.fusePath,
                    folder = folder,
                    version = versionIn(program.name) ?: versionIn(dir.name),
                )
            }
        }
        out.distinctBy { it.id to it.path }
    }

    /** [r]'s Flatpak, the person's own or the system's, started through flatpak itself. */
    private fun flatpak(r: Recomp): FoundRecomp? {
        val id = r.flatpak ?: return null
        val installed = listOf(File(home, ".local/share/flatpak/app/$id/current"), File("/var/lib/flatpak/app/$id/current")).any { it.exists() }
        if (!installed) return null
        // The Flatpak is given the whole home folder: it keeps its files in ~/.config as the program does.
        val folder = Recomps.folder(r, "linux", home, env - "APP_FOLDER_PATH") ?: return null
        val version = run(listOf("flatpak", "info", id), 5_000)?.stdout?.lineSequence()
            ?.map { it.trim() }?.firstOrNull { it.startsWith("Version:") }?.substringAfter(':')?.trim()?.takeIf { it.isNotEmpty() }
        return FoundRecomp(r.id, "Flatpak", id, listOf("flatpak", "run", id), home, folder, version)
    }

    /** [r]'s program wherever people keep one: on the PATH, and a level or two into their programs and games folders. */
    private fun programs(r: Recomp): List<File> {
        val names = when (system) {
            DesktopOs.WINDOWS -> setOf("${r.program}.exe".lowercase())
            DesktopOs.MACOS -> setOf("${r.program}.app".lowercase())
            DesktopOs.LINUX -> setOf(r.program.lowercase())
        }
        fun matches(f: File): Boolean {
            val n = f.name.lowercase()
            return n in names || system == DesktopOs.LINUX && f.isFile && n.startsWith(r.program.lowercase()) && n.endsWith(".appimage")
        }
        val found = LinkedHashSet<File>()
        if (system == DesktopOs.LINUX) which(r.program)?.let { found += File(it) }
        for (root in roots()) {
            val top = root.listFiles() ?: continue
            for (f in top) {
                if (matches(f)) found += f
                else if (f.isDirectory && !f.name.lowercase().endsWith(".app")) {
                    f.listFiles()?.forEach { g -> if (matches(g)) found += g }
                }
            }
        }
        return found.mapNotNull { f ->
            when {
                system == DesktopOs.MACOS && f.isDirectory -> File(f, "Contents/MacOS/${r.program}").takeIf { it.isFile }
                f.isFile -> f
                else -> null
            }
        }
    }

    private fun roots(): List<File> {
        val h = File(home)
        return when (system) {
            DesktopOs.LINUX -> listOf(File(h, "Applications"), File(h, "Games"), File(h, "Apps"), File(h, ".local/bin"), File(h, "Desktop"), File(h, "Downloads"), File("/opt"))
            DesktopOs.WINDOWS -> listOfNotNull(
                env["LOCALAPPDATA"]?.let { File(it, "Programs") }, env["ProgramFiles"]?.let(::File), env["ProgramFiles(x86)"]?.let(::File),
                File(h, "Desktop"), File(h, "Downloads"), File(h, "Games"), File("C:/Games"),
            )
            DesktopOs.MACOS -> listOf(File("/Applications"), File(h, "Applications"), File(h, "Games"), File(h, "Downloads"), File(h, "Desktop"))
        }
    }

    /** The folders Fuse may read and write in: every recomp's own, its own copies', and its profiles. */
    private fun allowed(path: String): Boolean {
        val p = File(path).absoluteFile.toPath().normalize()
        val bases = buildList {
            add(ports.absoluteFile.fusePath)
            add(profiles)
            Recomps.all.forEach { r -> Recomps.folder(r, os, home, env)?.let(::add); Recomps.folder(r, os, home, env - "APP_FOLDER_PATH")?.let(::add) }
            addAll(portable)
        }
        return bases.any { b -> val base = File(b).absoluteFile.toPath().normalize(); p.startsWith(base) && p != base }
    }

    override suspend fun readText(path: String): String? = withContext(Dispatchers.IO) {
        val f = File(path)
        if (!allowed(path) || !f.isFile || f.length() > MAX_TEXT) null else runCatching { f.readText() }.getOrNull()
    }

    override suspend fun writeText(path: String, text: String): Boolean = withContext(Dispatchers.IO) {
        if (!allowed(path)) return@withContext false
        runCatching {
            val target = File(path)
            target.parentFile?.mkdirs()
            val part = File(target.parentFile, ".${target.name}.part")
            part.writeText(text)
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.isSuccess
    }

    override suspend fun list(folder: String): List<Pair<String, Long>>? = withContext(Dispatchers.IO) {
        if (!allowed(folder)) return@withContext null
        File(folder).listFiles()?.filter { it.isFile }?.map { it.name to it.length() } ?: emptyList()
    }

    override suspend fun addMods(file: String, mods: String): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            if (!allowed(mods)) error("Fuse only adds mods to the recomp's own mods folder.")
            val src = File(file)
            if (!src.isFile) error("That file isn't there any more.")
            val into = File(mods).apply { mkdirs() }
            if (Recomps.isMod(src.name)) {
                place(into, src.name) { out -> src.inputStream().use { it.copyTo(out) } }
                listOf(src.name)
            } else {
                // A package: every mod and texture pack inside, each by its own file name (as the recomp's installer takes them).
                val added = ArrayList<String>()
                ZipFile(src).use { zip ->
                    for (e in zip.entries()) {
                        if (e.isDirectory) continue
                        val name = Recomps.modName(e.name) ?: continue
                        place(into, name) { out -> zip.getInputStream(e).use { it.copyTo(out) } }
                        added += name
                    }
                }
                added
            }
        }
    }

    /** [name] written into [into] whole, under a temporary name first, replacing an older copy. */
    private fun place(into: File, name: String, write: (java.io.OutputStream) -> Unit) {
        val target = File(into, name)
        if (target.parentFile.absoluteFile != into.absoluteFile) error("That mod's name isn't one Fuse can use.")
        val part = File(into, ".$name.part")
        try {
            part.outputStream().use(write)
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            part.delete()
        }
    }

    override suspend fun remove(path: String): Boolean = withContext(Dispatchers.IO) {
        val f = File(path)
        allowed(path) && f.isFile && f.delete()
    }

    override suspend fun copy(from: String, to: String): Boolean = withContext(Dispatchers.IO) {
        if (!allowed(from) || !allowed(to)) return@withContext false
        runCatching {
            File(to).parentFile?.mkdirs()
            Files.copy(File(from).toPath(), File(to).toPath(), StandardCopyOption.REPLACE_EXISTING)
        }.isSuccess
    }

    override suspend fun updateFlatpak(id: String): String? = withContext(Dispatchers.IO) {
        if (system != DesktopOs.LINUX || which("flatpak") == null) return@withContext "Flatpak isn't on this computer."
        if (Recomps.all.none { it.flatpak == id }) return@withContext "Fuse only updates the recomps it knows."
        val user = File(home, ".local/share/flatpak/app/$id").exists()
        val result = run(listOf("flatpak", "update", "-y", "--noninteractive", if (user) "--user" else "--system", id), UPDATE_TIMEOUT_MS)
        when {
            result == null -> "Flatpak didn't finish updating it. Try again, or update it from your software centre."
            result.exitCode != 0 -> if (user) "Flatpak couldn't update it. Try again, or update it from your software centre." else "It's installed for every account, so updating it may need your password: update it from your software centre."
            else -> null
        }
    }

    companion object {
        private const val MAX_TEXT = 4L * 1024 * 1024
        private const val UPDATE_TIMEOUT_MS = 10L * 60 * 1000

        /** A version in a file or folder's name ("Zelda64Recompiled-v1.2.2-Linux-X64"), or null. */
        fun versionIn(name: String): String? = Regex("""(?<![\d.])v?(\d+\.\d+(?:\.\d+)?)""").find(name)?.groupValues?.get(1)
    }
}
