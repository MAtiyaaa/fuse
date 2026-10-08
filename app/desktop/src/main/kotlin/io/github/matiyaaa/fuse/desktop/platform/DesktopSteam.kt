package io.github.matiyaaa.fuse.desktop.platform

import io.github.matiyaaa.fuse.desktop.Log
import io.github.matiyaaa.fuse.desktop.services.KnownFolders
import io.github.matiyaaa.fuse.desktop.system.DesktopOs
import io.github.matiyaaa.fuse.desktop.system.GameMode
import io.github.matiyaaa.fuse.desktop.system.Processes
import io.github.matiyaaa.fuse.library.steam.SteamShortcut
import io.github.matiyaaa.fuse.library.steam.SteamShortcuts
import io.github.matiyaaa.fuse.ui.shell.platform.SteamIntegration
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Puts Fuse in Steam's library as a non-Steam game, for each Steam user on this computer, so Game
 * Mode can start it. The entry runs the AppImage (or the installed program) full screen, with
 * Fuse's own art in every place Steam shows a game: the library capsule, the wide capsule, the
 * hero, the logo over it and the icon (in the user's `config/grid`, by the entry's id; art already
 * there, such as the person's own, is left as it is). The first time a user's list is changed, a
 * copy of it is kept next to it.
 */
internal class DesktopSteam(private val folders: KnownFolders) : SteamIntegration {
    override val gameMode: Boolean get() = GameMode.active

    override suspend fun added(): Boolean = withContext(Dispatchers.IO) {
        val exe = program() ?: return@withContext false
        userLists().any { f -> SteamShortcuts.contains(runCatching { f.readBytes() }.getOrNull(), quote(exe)) }
    }

    override suspend fun addFuse(): Result<String> = withContext(Dispatchers.IO) {
        val exe = program() ?: return@withContext Result.failure(IllegalStateException("Fuse isn't running from its AppImage or an installed copy, so Steam would have nothing to start."))
        if (steamRunning()) return@withContext Result.failure(IllegalStateException("Close Steam first: it rewrites its library when it quits. Then add Fuse again."))
        val users = userConfigDirs()
        if (users.isEmpty()) return@withContext Result.failure(IllegalStateException("No Steam user here yet. Sign in to Steam once, close it, then try again."))
        val base = SteamShortcut(
            name = "Fuse",
            exe = quote(exe),
            startDir = quote(File(exe).parent ?: "."),
            launchOptions = "--fullscreen",
            tags = listOf("Fuse"),
        )
        var done = 0
        for (dir in users) {
            val file = File(dir, "shortcuts.vdf")
            try {
                // Steam's art for the entry, then the entry pointing at its icon.
                val icon = runCatching { placeArt(File(dir, "grid"), base) }.onFailure { Log.warn("could not give Steam Fuse's art in $dir", it) }.getOrNull()
                val shortcut = base.copy(icon = icon ?: "")
                val before = if (file.isFile) file.readBytes() else null
                val after = SteamShortcuts.add(before, shortcut) ?: continue
                if (before != null) {
                    val backup = File(dir, "shortcuts.vdf.before-fuse")
                    if (!backup.exists()) backup.writeBytes(before)
                }
                val tmp = File(dir, "shortcuts.vdf.fuse-tmp")
                tmp.writeBytes(after)
                if (!tmp.renameTo(file)) {
                    file.delete()
                    tmp.renameTo(file)
                }
                done++
            } catch (e: Exception) {
                Log.warn("could not add Fuse to Steam in $dir", e)
            }
        }
        if (done == 0) Result.failure(IllegalStateException("Steam's library couldn't be written."))
        else Result.success(if (done == 1) "Fuse is in Steam's library. Start Steam and find it under Non-Steam." else "Fuse is in Steam's library for $done users. Start Steam and find it under Non-Steam.")
    }

    /**
     * Gives every Steam entry that starts Fuse its art, whoever made it: Add Fuse to Steam, or
     * Steam's own Add a Non-Steam Game (which names it after the AppImage, often with a version).
     * Only art files are written, never Steam's list, so this is safe while Steam runs; Steam shows
     * them the next time it draws its library. Art already there stays. Returns how many entries.
     */
    fun dressEntries(): Int {
        val exe = program()
        var n = 0
        for (dir in userConfigDirs(create = false)) {
            val bytes = File(dir, "shortcuts.vdf").takeIf { it.isFile }?.let { runCatching { it.readBytes() }.getOrNull() } ?: continue
            for (id in SteamShortcuts.idsOf(bytes) { path, name -> isFuse(path, name, exe) }) {
                runCatching { placeArt(File(dir, "grid"), id) }.onSuccess { n++ }.onFailure { Log.warn("could not give Steam Fuse's art in $dir", it) }
            }
        }
        return n
    }

    /** What Steam should start: the AppImage Fuse runs from, or the installed program. */
    private fun program(): String? {
        System.getenv("APPIMAGE")?.takeIf { File(it).isFile }?.let { return it }
        val command = ProcessHandle.current().info().command().orElse(null) ?: return null
        // A packaged app runs its own launcher; a bare java (a development run) has nothing to offer.
        if (File(command).name.lowercase().startsWith("java")) return null
        if (DesktopOs.isMac) {
            val app = generateSequence(File(command)) { it.parentFile }.firstOrNull { it.name.endsWith(".app") }
            if (app != null) return app.absolutePath
        }
        return command
    }

    private fun quote(path: String): String = "\"$path\""

    companion object {
        private val FUSE_PROGRAM = Regex("(?i)^fuse([-_. ][^/\\\\]*)?\\.(appimage|exe)$|^fuse$")

        /**
         * Whether a Steam entry running [path] (named [name]) is Fuse: the program Fuse runs as now
         * ([running]), or a Fuse AppImage or program by its file name (`Fuse.AppImage`,
         * `Fuse-0.3.6.4-x86_64.AppImage`, `Fuse.exe`).
         */
        internal fun isFuse(path: String, name: String, running: String?): Boolean {
            if (running != null && File(path).absolutePath == File(running).absolutePath) return true
            return FUSE_PROGRAM.matches(File(path).name) || (name.equals("Fuse", ignoreCase = true) && path.contains("fuse", ignoreCase = true))
        }
    }

    /**
     * Writes Fuse's art for [shortcut] into [grid], named as Steam looks for a non-Steam game's:
     * `<id>p.png` (library capsule), `<id>.png` (wide capsule), `<id>_hero.png`, `<id>_logo.png` and
     * `<id>_icon.png`. Returns the icon's path for the entry.
     */
    internal fun placeArt(grid: File, shortcut: SteamShortcut): String =
        placeArt(grid, SteamShortcuts.appId(shortcut.exe, shortcut.name).toLong() and 0xFFFFFFFFL)

    /** [placeArt] for the entry Steam knows as [id]. */
    internal fun placeArt(grid: File, id: Long): String {
        grid.mkdirs()
        for ((resource, name) in listOf("portrait" to "${id}p", "capsule" to "$id", "hero" to "${id}_hero", "logo" to "${id}_logo", "icon" to "${id}_icon")) {
            // Art already there for this entry (the person's own, from SteamGridDB or Steam) stays.
            if (listOf("png", "jpg", "jpeg", "webp").any { File(grid, "$name.$it").exists() }) continue
            val bytes = javaClass.getResourceAsStream("/steam/$resource.png")?.use { it.readBytes() } ?: continue
            val target = File(grid, "$name.png")
            val tmp = File(grid, ".$name.png.fuse-tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(target)) {
                tmp.delete()
                target.writeBytes(bytes)
            }
        }
        return listOf("png", "jpg", "jpeg").map { File(grid, "${id}_icon.$it") }.firstOrNull { it.isFile }?.absolutePath.orEmpty()
    }

    /** Every Steam user's config folder (`userdata/<id>/config`), on every Steam install found. */
    private fun userConfigDirs(create: Boolean = true): List<File> = folders.steamPlaces().first
        .map { File(it, "userdata") }
        .flatMap { root -> root.listFiles()?.filter { it.isDirectory && it.name.toLongOrNull()?.let { id -> id > 0 } == true }.orEmpty() }
        .map { File(it, "config").apply { if (create) mkdirs() } }
        .filter { it.isDirectory }
        .distinctBy { it.canonicalPath }

    private fun userLists(): List<File> = userConfigDirs().map { File(it, "shortcuts.vdf") }.filter { it.isFile }

    private fun steamRunning(): Boolean = when (DesktopOs.current) {
        DesktopOs.WINDOWS -> Processes.run(listOf("tasklist", "/FI", "IMAGENAME eq steam.exe", "/NH"), timeoutMs = 3_000)?.stdout?.contains("steam.exe", ignoreCase = true) == true
        DesktopOs.MACOS -> Processes.run(listOf("pgrep", "-x", "steam_osx"), timeoutMs = 3_000)?.exitCode == 0
        DesktopOs.LINUX -> Processes.run(listOf("pgrep", "-x", "steam"), timeoutMs = 3_000)?.exitCode == 0
    }
}
