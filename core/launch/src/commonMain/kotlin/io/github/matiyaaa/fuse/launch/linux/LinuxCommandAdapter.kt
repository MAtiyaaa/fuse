package io.github.matiyaaa.fuse.launch.linux

import io.github.matiyaaa.fuse.launch.EmulatorAdapter
import io.github.matiyaaa.fuse.launch.LaunchRequest
import io.github.matiyaaa.fuse.launch.LaunchTokens
import io.github.matiyaaa.fuse.launch.Paths
import io.github.matiyaaa.fuse.launch.RetroArchCores
import io.github.matiyaaa.fuse.launch.TargetKind
import io.github.matiyaaa.fuse.launch.TitleIdMode
import io.github.matiyaaa.fuse.launch.kind
import io.github.matiyaaa.fuse.launch.path
import io.github.matiyaaa.fuse.launch.pc.ShortcutParser
import io.github.matiyaaa.fuse.launch.select
import io.github.matiyaaa.fuse.model.AdapterCapabilities
import io.github.matiyaaa.fuse.model.ContentKind
import io.github.matiyaaa.fuse.model.EmulatorId
import io.github.matiyaaa.fuse.model.Host
import io.github.matiyaaa.fuse.model.InstalledEmulator
import io.github.matiyaaa.fuse.model.LaunchPlan
import io.github.matiyaaa.fuse.model.LaunchTarget
import io.github.matiyaaa.fuse.model.PlatformId

/**
 * Generic desktop adapter for a [LinuxEmulatorDef]. Produces [LaunchPlan.Command] with the program's
 * invocation followed by the mode's arguments. Linux, Windows and macOS share it ([host]); the
 * Windows and macOS catalogs reuse the Linux definitions with their own ids.
 *
 * [InstalledEmulator.appId] is an absolute executable path (PATH, AppImage, folder installs, the
 * program inside a macOS app bundle) or, on Linux, a Flatpak id when [InstalledEmulator.detectedVia]
 * is [LinuxInstallKind.FLATPAK]; Flatpaks run as `flatpak run [--command=...] <id> args...`.
 */
class LinuxCommandAdapter(val def: LinuxEmulatorDef, override val host: Host = Host.LINUX) : EmulatorAdapter {
    override val id: EmulatorId = EmulatorId(def.id)
    override val name: String get() = def.name
    override val platforms: Set<PlatformId> get() = def.platforms
    override val capabilities: AdapterCapabilities = def.capabilities.copy(titleIdLaunch = def.titleIdMode != TitleIdMode.NONE)
    override val limitations: List<String> get() = def.limitations
    override val source: String get() = def.source
    override val confidence get() = def.confidence
    override val homepage: String? get() = def.homepage
    override val titleIdMode: TitleIdMode get() = def.titleIdMode
    override val idFileExtensions: Set<String> =
        def.modes.filter { it.idFile }.flatMap { it.extensions.orEmpty() }.toSet()
    override val opensAppOnly: Boolean get() = def.openAppOnlyReason != null
    override val shortcutsOnly: Boolean get() = def.shortcutsOnly

    override fun accepts(target: LaunchTarget, platform: PlatformId): Boolean =
        opensAppOnly || def.modes.select(target, platform) != null

    override val packageExtensions: Set<String> get() = def.packageInstall?.extensions.orEmpty()
    override val packageNeedsKey: Boolean get() = def.packageInstall?.needsKey == true

    override fun packageInstall(installed: InstalledEmulator, path: String, key: String?): LaunchPlan.Command? {
        val spec = def.packageInstall ?: return null
        if (path.substringAfterLast('.', "").lowercase() !in spec.extensions) return null
        if (spec.needsKey && key.isNullOrBlank()) return null
        val args = spec.args.map { it.replace("{FILE}", path).replace("{KEY}", key.orEmpty().trim()) }
        // Programs that read their settings from their own folder (ES-DE's %EMUDIR%) install from there too.
        val ownFolder = def.modes.any { it.spec.workingDir == "{EMUDIR}" }
        val dir = if (ownFolder) installed.appId.replace('\\', '/').substringBeforeLast('/', "").ifEmpty { null } else null
        return LaunchPlan.Command(id, invocation(installed) + args, workingDir = dir, target = LaunchTarget.File(path))
    }

    override fun installHint(kind: ContentKind): String? =
        def.installHint?.takeIf { kind == ContentKind.DLC || kind == ContentKind.UPDATE }

    /** The program part of the argv. */
    fun invocation(installed: InstalledEmulator): List<String> =
        if (host == Host.LINUX && LinuxInstallKind.of(installed.detectedVia) == LinuxInstallKind.FLATPAK) {
            listOf("flatpak", "run") + listOfNotNull(def.detection.flatpakCommand?.let { "--command=$it" }) + installed.appId
        } else {
            listOf(installed.appId)
        }

    override fun plan(request: LaunchRequest): LaunchPlan {
        val installed = request.installed
        def.openAppOnlyReason?.let { return LaunchPlan.OpenAppOnly(id, installed.appId, it) }
        val target = request.target
        val platform = request.game.platformId
        val mode = def.modes.select(target, platform) ?: return unsupported(cannotOpen(target))
        val home = request.options.homeDir

        val path = target.path
        val injected = request.options.injectedText?.trim()?.ifEmpty { null }
        val serial = when {
            target is LaunchTarget.TitleId -> target.id
            mode.idFile -> injected?.let(ShortcutParser::parseTitleId) ?: request.game.tags.serial
            else -> request.game.tags.serial
        }
        val core = if (def.usesRetroArchCores) {
            request.options.core?.takeIf { it.isNotBlank() }
                ?: RetroArchCores.defaultCore(host, platform)
                ?: return unsupported("${def.name} has no core set for $platform. Pick a core in the platform's settings.")
        } else {
            null
        }
        val corePath = core?.let { request.options.corePath ?: defaultCorePath(installed, it, home, host) }

        val used = (mode.spec.args + listOfNotNull(mode.spec.workingDir)).flatMap { LaunchTokens.tokensIn(it) }.toSet()
        mode.idPattern?.let { pattern ->
            val value = if (LaunchTokens.INJECT in used) injected else serial
            if (value != null && !pattern.matches(value)) return unsupported("\"$value\" is not a valid id for ${def.name}.")
        }

        val values = mapOf(
            LaunchTokens.ROM to path,
            LaunchTokens.PATH to path,
            LaunchTokens.ROMDIR to path?.let(Paths::parent),
            LaunchTokens.BASENAME to path?.let(Paths::baseName),
            LaunchTokens.CORE to core,
            LaunchTokens.CORE_PATH to corePath,
            LaunchTokens.SERIAL to serial,
            LaunchTokens.INJECT to injected,
            LaunchTokens.EMUDIR to installed.appId.takeIf(Paths::isAbsolute)?.let(Paths::parent),
        )
        val args = ArrayList<String>()
        for (template in mode.spec.args) {
            when (val r = LaunchTokens.fill(template, values)) {
                is LaunchTokens.Filled.Ok -> args += r.value
                is LaunchTokens.Filled.Missing -> return unsupported(missingMessage(r.token, target))
            }
        }
        val workingDir = mode.spec.workingDir?.let { t ->
            when (val r = LaunchTokens.fill(t, values)) {
                is LaunchTokens.Filled.Ok -> Paths.expandHome(r.value, home)
                is LaunchTokens.Filled.Missing -> null
            }
        }
        return LaunchPlan.Command(id, invocation(installed) + args, workingDir = workingDir, target = target)
    }

    private fun unsupported(reason: String) = LaunchPlan.Unsupported(id, reason)

    private fun cannotOpen(target: LaunchTarget): String = when (target.kind) {
        TargetKind.FILE -> {
            val ext = Paths.extension(target.path.orEmpty())
            if (ext.isEmpty()) "${def.name} can't open this file." else "${def.name} can't open .$ext files."
        }
        TargetKind.DIRECTORY -> "${def.name} can't open a folder game; pick a file with Folder Behaviour."
        TargetKind.TITLE_ID -> "${def.name} can't start games by title id."
        TargetKind.APP -> "${def.name} can't start apps."
    }

    private fun missingMessage(token: String, target: LaunchTarget): String = when (token) {
        LaunchTokens.INJECT -> "Fuse needs the content of ${Paths.fileName(target.path.orEmpty())} to start it with ${def.name}."
        LaunchTokens.SERIAL -> "${def.name} needs a title id. Put it in an id file or in the file name."
        LaunchTokens.EMUDIR -> "${def.name} must be an executable file for this launch, not a Flatpak."
        else -> "${def.name} needs a file for this launch."
    }

    companion object {
        /**
         * Where a RetroArch install keeps its cores when the detector was not asked (ES-DE core paths):
         * on Linux the Flatpak's own config folder, else `~/.config/retroarch/cores`; on Windows the
         * `cores` folder next to retroarch.exe; on macOS `~/Library/Application Support/RetroArch/cores`.
         */
        fun defaultCorePath(installed: InstalledEmulator, core: String, home: String?, host: Host = Host.LINUX): String {
            val dir = when {
                host == Host.WINDOWS -> "${Paths.parent(installed.appId).trimEnd('/')}/cores"
                host == Host.MACOS -> "~/Library/Application Support/RetroArch/cores"
                LinuxInstallKind.of(installed.detectedVia) == LinuxInstallKind.FLATPAK -> "~/.var/app/${installed.appId}/config/retroarch/cores"
                else -> "~/.config/retroarch/cores"
            }
            return Paths.expandHome("$dir/${RetroArchCores.coreFile(host, core)}", home)
        }
    }
}
