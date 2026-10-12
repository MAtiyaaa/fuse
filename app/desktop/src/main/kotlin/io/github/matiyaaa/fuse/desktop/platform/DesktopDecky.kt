package io.github.matiyaaa.fuse.desktop.platform

import io.github.matiyaaa.fuse.desktop.BuildInfo
import io.github.matiyaaa.fuse.desktop.Log
import io.github.matiyaaa.fuse.desktop.system.Processes
import io.github.matiyaaa.fuse.ui.shell.platform.DeckyIntegration
import io.github.matiyaaa.fuse.ui.shell.platform.DeckyState
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Decky Loader and the Fuse Decky plugin on Linux. Decky lives in `~/homebrew` and keeps its
 * plugins in `~/homebrew/plugins`, which belongs to the system account; its service is
 * `plugin_loader`. Fuse carries the plugin (built from tools/decky/fuse), stages it in its own data
 * folder, and puts it in place only through `pkexec`, which shows the system's own password prompt:
 * nothing privileged ever runs without the person approving it there. The previous plugin is kept
 * beside it until the new one is in place, so an interrupted install never leaves Decky without one.
 */
internal class DesktopDecky(
    private val home: File = File(System.getProperty("user.home")),
    private val staging: File,
    /** A Steam game is running: Decky isn't restarted under it (its overlay and plugins would blink out mid-game). */
    private val gameRunning: () -> Boolean = { SystemSteamProcessOps(fuseProgram = { null }, fuseGameId = { null }).gameRunning() },
    /** The data folder the bridge's discovery file is in (XDG_DATA_HOME, else ~/.local/share). */
    private val dataHome: File = System.getenv("XDG_DATA_HOME")?.takeIf { it.startsWith("/") }?.let(::File) ?: File(home, ".local/share"),
) : DeckyIntegration {
    private val lock = Mutex()
    private val homebrew get() = File(home, "homebrew")
    private val plugins get() = File(homebrew, "plugins")
    private val target get() = File(plugins, PLUGIN)

    /** Beside the bridge's discovery file, where the plugin looks (main.py, _menu_off). */
    private val menuOff get() = File(dataHome, "fuse/decky-menu-off")

    override suspend fun setMenuEntries(on: Boolean): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            if (on) menuOff.delete() || !menuOff.exists()
            else menuOff.parentFile.mkdirs().let { menuOff.writeText("Fuse's entries in Steam's game menu are off.\n"); true }
        }.getOrDefault(false)
    }

    override suspend fun state(): DeckyState = withContext(Dispatchers.IO) {
        val loader = File(homebrew, "services/PluginLoader").exists() || File("/etc/systemd/system/plugin_loader.service").exists()
        val installed = File(target, "plugin.json").isFile
        DeckyState(
            loader = loader,
            plugin = installed,
            pluginVersion = if (installed) versionOf(File(target, "package.json")) else null,
            bundledVersion = BuildInfo.VERSION,
            canInstall = loader && Processes.which("pkexec") != null,
            menuEntries = !menuOff.exists(),
        )
    }

    override suspend fun install(): Result<String> = lock.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                val st = state()
                if (!st.loader) error("Decky Loader isn't installed. Install it first from decky.xyz, then try again.")
                val source = stagePlugin() ?: error("This copy of Fuse doesn't carry the Decky plugin.")
                val pkexec = Processes.which("pkexec") ?: error("This system can't ask for your password. Install it from Decky's settings: Developer, Install Plugin from ZIP.")
                // Decky's own folder is Decky's: Fuse never creates it (as root it would be the wrong owner).
                if (!plugins.isDirectory) error("Decky's plugins folder isn't there, so Decky may not be installed properly. Reinstall Decky from decky.xyz, then try again. Nothing was changed.")
                val restart = !gameRunning()
                val script = installScript(restart)
                val out = Processes.run(listOf(pkexec, "sh", "-c", script, "fuse-decky", plugins.absolutePath, source.absolutePath), timeoutMs = 120_000, withErrors = true)
                    ?: error("The password prompt didn't open. Install it from Decky's settings: Developer, Install Plugin from ZIP.")
                when (out.exitCode) {
                    0 -> if (restart) "The Fuse plugin is in Decky. Decky restarted to load it." else "The Fuse plugin is in Decky. It loads the next time Decky starts; Fuse didn't restart Decky while a game is running."
                    126, 127 -> error("Installing was cancelled. Nothing was changed.")
                    else -> error("Decky's plugins folder couldn't be updated (${out.stdout.trim().lines().lastOrNull().orEmpty()}). The plugin that was there is back in place.")
                }
            }.onFailure { Log.warn("Decky plugin install", it) }
        }
    }

    override suspend fun remove(): Result<String> = lock.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                if (!File(target, "plugin.json").isFile) return@runCatching "The Fuse plugin isn't in Decky."
                val pkexec = Processes.which("pkexec") ?: error("This system can't ask for your password. Remove it from Decky's settings.")
                val restart = if (gameRunning()) "" else "systemctl restart plugin_loader 2>/dev/null || true"
                val script = "set -e\nrm -rf \"\$1/$PLUGIN\"\n$restart"
                val out = Processes.run(listOf(pkexec, "sh", "-c", script, "fuse-decky", plugins.absolutePath), timeoutMs = 120_000, withErrors = true)
                    ?: error("The password prompt didn't open.")
                if (out.exitCode != 0) error("It wasn't removed. Nothing was changed.")
                "The Fuse plugin is out of Decky. Your games, play time and saves in Fuse stay as they are."
            }
        }
    }

    override suspend fun stage(): String? = withContext(Dispatchers.IO) {
        val folder = stagePlugin() ?: return@withContext null
        val zip = File(staging, "$PLUGIN.zip")
        runCatching {
            val tmp = File(staging, ".$PLUGIN.zip.tmp")
            ZipOutputStream(tmp.outputStream()).use { out ->
                folder.walkTopDown().filter { it.isFile }.forEach { f ->
                    out.putNextEntry(ZipEntry("$PLUGIN/" + f.relativeTo(folder).invariantSeparatorsPath))
                    f.inputStream().use { it.copyTo(out) }
                    out.closeEntry()
                }
            }
            if (!tmp.renameTo(zip)) { zip.delete(); tmp.renameTo(zip) }
            zip.absolutePath
        }.getOrNull()
    }

    /** The plugin this Fuse carries, copied out of its resources into Fuse's own folder. */
    private fun stagePlugin(): File? {
        val folder = File(staging, PLUGIN)
        val files = listOf("plugin.json", "package.json", "main.py", "README.md", "dist/index.js")
        if (javaClass.getResource("/decky/$PLUGIN/plugin.json") == null) return null
        folder.deleteRecursively()
        for (name in files) {
            val bytes = javaClass.getResourceAsStream("/decky/$PLUGIN/$name")?.use { it.readBytes() } ?: continue
            File(folder, name).apply { parentFile.mkdirs() }.writeBytes(bytes)
        }
        return folder.takeIf { File(it, "plugin.json").isFile && File(it, "dist/index.js").isFile }
    }

    private fun versionOf(packageJson: File): String? = runCatching {
        Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(packageJson.readText())?.groupValues?.get(1)
    }.getOrNull()

    companion object {
        const val PLUGIN = "Fuse"

        /**
         * The privileged install, as one script ($1 Decky's plugins folder, $2 the staged plugin):
         * copy beside the old plugin, give it the folder's own owner (as Decky's other plugins have),
         * swap, and put the old one back if the swap fails. Decky restarts only when [restart].
         */
        internal fun installScript(restart: Boolean): String = """
            set -e
            [ -d "${'$'}1" ] || exit 3
            rm -rf "${'$'}1/$PLUGIN.new"
            cp -r "${'$'}2" "${'$'}1/$PLUGIN.new"
            chown -R --reference="${'$'}1" "${'$'}1/$PLUGIN.new" 2>/dev/null || true
            if [ -d "${'$'}1/$PLUGIN" ]; then rm -rf "${'$'}1/$PLUGIN.previous"; mv "${'$'}1/$PLUGIN" "${'$'}1/$PLUGIN.previous"; fi
            if ! mv "${'$'}1/$PLUGIN.new" "${'$'}1/$PLUGIN"; then
                [ -d "${'$'}1/$PLUGIN.previous" ] && mv "${'$'}1/$PLUGIN.previous" "${'$'}1/$PLUGIN"
                exit 4
            fi
            rm -rf "${'$'}1/$PLUGIN.previous"
        """.trimIndent() + if (restart) "\nsystemctl restart plugin_loader 2>/dev/null || true" else ""
    }
}
