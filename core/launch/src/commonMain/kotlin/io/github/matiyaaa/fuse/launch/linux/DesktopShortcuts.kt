package io.github.matiyaaa.fuse.launch.linux

import io.github.matiyaaa.fuse.launch.Confidence
import io.github.matiyaaa.fuse.launch.EmulatorAdapter
import io.github.matiyaaa.fuse.launch.LaunchRequest
import io.github.matiyaaa.fuse.launch.Paths
import io.github.matiyaaa.fuse.launch.Platforms
import io.github.matiyaaa.fuse.launch.Sources
import io.github.matiyaaa.fuse.launch.path
import io.github.matiyaaa.fuse.launch.pc.ShortcutParser
import io.github.matiyaaa.fuse.model.AdapterCapabilities
import io.github.matiyaaa.fuse.model.EmulatorId
import io.github.matiyaaa.fuse.model.FolderSupport
import io.github.matiyaaa.fuse.model.Host
import io.github.matiyaaa.fuse.model.LaunchPlan
import io.github.matiyaaa.fuse.model.LaunchTarget
import io.github.matiyaaa.fuse.model.PlatformId

/**
 * Turns a `.desktop` `Exec=` line into argv the way ES-DE's `%ENABLESHORTCUTS%` does (FileData.cpp):
 * field codes `%f %F %u %U %d %D %n %N %i %c %k %v %m` are removed, `%%` becomes `%`, and the result is
 * trimmed. Quoting follows the Desktop Entry spec. Lines that need a shell (pipes, `;`, `$VAR`) run as
 * `sh -c <line>`, since ES-DE also hands them to the shell.
 */
object DesktopExec {
    private const val FIELD_CODES = "fFuUdDnNickvm"
    private const val SHELL_CHARS = ";&|<>$`()"

    fun stripFieldCodes(exec: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < exec.length) {
            val c = exec[i]
            if (c == '%' && i + 1 < exec.length) {
                val n = exec[i + 1]
                if (n == '%') {
                    sb.append('%'); i += 2; continue
                }
                if (n in FIELD_CODES) {
                    i += 2; continue
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString().trim()
    }

    /** argv for [exec], or null when nothing is left to run. */
    fun toArgv(exec: String): List<String>? {
        val line = stripFieldCodes(exec)
        if (line.isEmpty()) return null
        if (line.any { it in SHELL_CHARS }) return listOf("sh", "-c", line)
        return tokenize(line).ifEmpty { null }
    }

    /** Splits on unquoted whitespace; double and single quotes group, backslash escapes. */
    fun tokenize(line: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var inDouble = false
        var inSingle = false
        var has = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inSingle -> if (c == '\'') inSingle = false else cur.append(c)
                inDouble -> when {
                    c == '"' -> inDouble = false
                    c == '\\' && i + 1 < line.length && line[i + 1] in "\"`$\\" -> { cur.append(line[i + 1]); i++ }
                    else -> cur.append(c)
                }
                c == '"' -> { inDouble = true; has = true }
                c == '\'' -> { inSingle = true; has = true }
                c == '\\' && i + 1 < line.length -> { cur.append(line[i + 1]); i++; has = true }
                c.isWhitespace() -> if (has || cur.isNotEmpty()) {
                    out += cur.toString(); cur.clear(); has = false
                }
                else -> { cur.append(c); has = true }
            }
            i++
        }
        if (has || cur.isNotEmpty()) out += cur.toString()
        return out
    }
}

/**
 * Generic freedesktop `.desktop` shortcuts on Linux (Steam, Heroic, Lutris, RPCS3 and Azahar shortcuts).
 * Uses `gio launch <file>` when gio was found ([InstalledEmulator.appId] ends with `/gio`); otherwise
 * parses the injected file content and runs its `Exec` line with [DesktopExec].
 */
object DesktopShortcutAdapter : EmulatorAdapter {
    override val id = EmulatorId("linux.desktop")
    override val name = "Desktop shortcut"
    override val host = Host.LINUX
    override val platforms: Set<PlatformId> = Platforms.all.toSet()
    override val capabilities = AdapterCapabilities(folders = FolderSupport.NONE)
    override val limitations = listOf("Runs the shortcut's own command; Fuse does not change it.")
    override val source = "${Sources.ESDE_LINUX}: %ENABLESHORTCUTS% (FileData.cpp Exec/Path parsing); GLib gio(1) 'gio launch'"
    override val confidence = Confidence.VERIFIED_ESDE
    override val homepage: String? = null
    override val idFileExtensions = setOf("desktop")
    override val builtIn = true
    override val shortcutsOnly = true

    override fun accepts(target: LaunchTarget, platform: PlatformId): Boolean =
        Paths.extension(target.path.orEmpty()) == "desktop"

    override fun plan(request: LaunchRequest): LaunchPlan {
        val path = request.target.path
        if (path == null || Paths.extension(path) != "desktop") return LaunchPlan.Unsupported(id, "Only .desktop files are shortcuts.")
        val appId = request.installed.appId
        if (appId == "gio" || appId.endsWith("/gio")) {
            return LaunchPlan.Command(id, listOf(appId, "launch", path), target = request.target)
        }
        val text = request.options.injectedText
            ?: return LaunchPlan.Unsupported(id, "Fuse needs the content of ${Paths.fileName(path)} to run it.")
        val entry = ShortcutParser.parseDesktop(text)
            ?: return LaunchPlan.Unsupported(id, "${Paths.fileName(path)} is not a valid .desktop file ([Desktop Entry] is missing).")
        val argv = entry.exec?.let(DesktopExec::toArgv)
            ?: return LaunchPlan.Unsupported(id, "${Paths.fileName(path)} has no Exec line.")
        return LaunchPlan.Command(id, argv, workingDir = entry.path, target = request.target)
    }
}
