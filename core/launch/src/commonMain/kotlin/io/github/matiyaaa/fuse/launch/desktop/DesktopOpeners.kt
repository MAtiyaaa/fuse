package io.github.matiyaaa.fuse.launch.desktop

import io.github.matiyaaa.fuse.launch.Confidence
import io.github.matiyaaa.fuse.launch.EmulatorAdapter
import io.github.matiyaaa.fuse.launch.LaunchRequest
import io.github.matiyaaa.fuse.launch.Paths
import io.github.matiyaaa.fuse.launch.Platforms
import io.github.matiyaaa.fuse.launch.Sources
import io.github.matiyaaa.fuse.launch.path
import io.github.matiyaaa.fuse.model.AdapterCapabilities
import io.github.matiyaaa.fuse.model.EmulatorId
import io.github.matiyaaa.fuse.model.FolderSupport
import io.github.matiyaaa.fuse.model.Host
import io.github.matiyaaa.fuse.model.LaunchPlan
import io.github.matiyaaa.fuse.model.LaunchTarget
import io.github.matiyaaa.fuse.model.PlatformId

/**
 * PC games and shortcuts on Windows: a `.exe` runs from its own folder; `.lnk` and `.url` shortcuts,
 * `.bat` and `.cmd` scripts open the way Explorer opens them. Those go through PowerShell's
 * `[System.Diagnostics.Process]::Start`, which waits for the program a shortcut starts, so the play
 * session ends with the game. The path is passed as a PowerShell literal ('' for each quote), never
 * through cmd.exe, whose quoting breaks on names like "Game (USA) & More.lnk".
 */
object WindowsShortcutAdapter : EmulatorAdapter {
    override val id = EmulatorId("windows.shortcut")
    override val name = "Windows programs and shortcuts"
    override val host = Host.WINDOWS
    override val platforms: Set<PlatformId> = Platforms.all.toSet()
    override val capabilities = AdapterCapabilities(folders = FolderSupport.NONE)
    override val limitations = listOf("Runs the program or shortcut as it is; Fuse does not change it.")
    override val source = "${Sources.ESDE_WINDOWS}: OS-SHELL 'Shortcut or script' (%STARTDIR%=%GAMEDIR%); .NET Process.Start with UseShellExecute"
    override val confidence = Confidence.VERIFIED_ESDE
    override val homepage: String? = null
    override val builtIn = true

    val extensions = setOf("exe", "lnk", "url", "bat", "cmd")

    override fun accepts(target: LaunchTarget, platform: PlatformId): Boolean =
        target.path?.let { Paths.extension(it) in extensions } == true

    override fun plan(request: LaunchRequest): LaunchPlan {
        val path = request.target.path
        val ext = path?.let(Paths::extension)
        if (path == null || ext !in extensions) return LaunchPlan.Unsupported(id, "Only programs (.exe), shortcuts (.lnk, .url) and scripts (.bat, .cmd) run here.")
        val folder = Paths.parent(path)
        if (ext == "exe") return LaunchPlan.Command(id, listOf(path), workingDir = folder, target = request.target)
        val script = "\$i = New-Object System.Diagnostics.ProcessStartInfo ${literal(path)}; " +
            "\$i.WorkingDirectory = ${literal(folder)}; \$i.UseShellExecute = \$true; " +
            "\$p = [System.Diagnostics.Process]::Start(\$i); if (\$p) { \$p.WaitForExit() }"
        val shell = request.installed.appId.takeIf { it.endsWith("powershell.exe", ignoreCase = true) } ?: "powershell.exe"
        return LaunchPlan.Command(
            id,
            listOf(shell, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-Command", script),
            workingDir = folder,
            target = request.target,
        )
    }

    /**
     * [text] as a single-quoted PowerShell string. PowerShell also reads the typographic quotes
     * U+2018 to U+201B as single quotes, so each is doubled too. Paths are given to Windows programs
     * with backslashes.
     */
    fun literal(text: String): String {
        val sb = StringBuilder("'")
        for (c in text.replace('/', '\\')) {
            sb.append(c)
            if (c == '\'' || c in '‘'..'‛') sb.append(c)
        }
        return sb.append('\'').toString()
    }
}

/**
 * Mac apps and scripts: a `.app` opens with `open -W` (which returns when the app quits), and
 * `.command` and `.sh` scripts run with zsh from their own folder, like ES-DE's OS-SHELL.
 */
object MacOpenAdapter : EmulatorAdapter {
    override val id = EmulatorId("macos.open")
    override val name = "Mac apps and scripts"
    override val host = Host.MACOS
    override val platforms: Set<PlatformId> = Platforms.all.toSet()
    override val capabilities = AdapterCapabilities(folders = FolderSupport.NONE)
    override val limitations = listOf("Runs the app or script as it is; Fuse does not change it.")
    override val source = "${Sources.ESDE_MACOS}: OS-SHELL 'Shortcut or script' (zsh); open(1) -W"
    override val confidence = Confidence.VERIFIED_ESDE
    override val homepage: String? = null
    override val builtIn = true

    private val scripts = setOf("command", "sh")

    override fun accepts(target: LaunchTarget, platform: PlatformId): Boolean {
        val path = target.path ?: return false
        return Paths.extension(path) == "app" || Paths.extension(path) in scripts
    }

    override fun plan(request: LaunchRequest): LaunchPlan {
        val path = request.target.path ?: return LaunchPlan.Unsupported(id, "Only apps (.app) and scripts (.command, .sh) run here.")
        return when (Paths.extension(path)) {
            "app" -> LaunchPlan.Command(id, listOf("/usr/bin/open", "-W", path), target = request.target)
            in scripts -> LaunchPlan.Command(id, listOf("/bin/zsh", path), workingDir = Paths.parent(path), target = request.target)
            else -> LaunchPlan.Unsupported(id, "Only apps (.app) and scripts (.command, .sh) run here.")
        }
    }
}
