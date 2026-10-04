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
 * Mode can start it. The entry runs the AppImage (or the installed program) full screen. The first
 * time a user's list is changed, a copy of it is kept next to it.
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
        val shortcut = SteamShortcut(
            name = "Fuse",
            exe = quote(exe),
            startDir = quote(File(exe).parent ?: "."),
            icon = iconFor(exe),
            launchOptions = "--fullscreen",
            tags = listOf("Fuse"),
        )
        var done = 0
        for (dir in users) {
            val file = File(dir, "shortcuts.vdf")
            try {
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

    private fun iconFor(exe: String): String {
        val png = File(File(exe).parentFile, "Fuse.png")
        return if (png.isFile) png.absolutePath else ""
    }

    /** Every Steam user's config folder (`userdata/<id>/config`), on every Steam install found. */
    private fun userConfigDirs(): List<File> = folders.steamPlaces().first
        .map { File(it, "userdata") }
        .flatMap { root -> root.listFiles()?.filter { it.isDirectory && it.name.toLongOrNull()?.let { id -> id > 0 } == true }.orEmpty() }
        .map { File(it, "config").apply { mkdirs() } }
        .distinctBy { it.canonicalPath }

    private fun userLists(): List<File> = userConfigDirs().map { File(it, "shortcuts.vdf") }.filter { it.isFile }

    private fun steamRunning(): Boolean = when (DesktopOs.current) {
        DesktopOs.WINDOWS -> Processes.run(listOf("tasklist", "/FI", "IMAGENAME eq steam.exe", "/NH"), timeoutMs = 3_000)?.stdout?.contains("steam.exe", ignoreCase = true) == true
        DesktopOs.MACOS -> Processes.run(listOf("pgrep", "-x", "steam_osx"), timeoutMs = 3_000)?.exitCode == 0
        DesktopOs.LINUX -> Processes.run(listOf("pgrep", "-x", "steam"), timeoutMs = 3_000)?.exitCode == 0
    }
}
