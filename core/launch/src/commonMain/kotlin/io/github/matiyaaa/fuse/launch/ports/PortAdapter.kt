package io.github.matiyaaa.fuse.launch.ports

import io.github.matiyaaa.fuse.launch.Confidence
import io.github.matiyaaa.fuse.launch.EmulatorAdapter
import io.github.matiyaaa.fuse.launch.LaunchRequest
import io.github.matiyaaa.fuse.launch.Paths
import io.github.matiyaaa.fuse.launch.path
import io.github.matiyaaa.fuse.model.AdapterCapabilities
import io.github.matiyaaa.fuse.model.EmulatorId
import io.github.matiyaaa.fuse.model.FolderSupport
import io.github.matiyaaa.fuse.model.Host
import io.github.matiyaaa.fuse.model.LaunchPlan
import io.github.matiyaaa.fuse.model.LaunchTarget
import io.github.matiyaaa.fuse.model.PlatformId

/**
 * Starts a game Fuse Ports installed: its launcher file ([PortLauncher], read by the caller as
 * the id file it is) names the program, its arguments, where it starts and what it needs set, all
 * worked out when the port was set up. Nothing else is looked up here, so Play, Fast Start and a
 * Steam entry start exactly the same thing.
 */
class PortAdapter(override val host: Host) : EmulatorAdapter {
    override val id = EmulatorId("${host.prefix}.port")
    override val name = "Fuse Ports"
    override val platforms: Set<PlatformId> = setOf(PLATFORM)
    override val capabilities = AdapterCapabilities(folders = FolderSupport.NONE)
    override val limitations = listOf("Starts the port as Fuse set it up: its own program, with your game's files linked in.")
    override val source = "Fuse's own launcher file, written by Fuse Ports from the port's entry in ports/index.json"
    override val confidence = Confidence.VERIFIED_SOURCE
    override val homepage: String? = null
    override val idFileExtensions = setOf(PortLauncher.EXTENSION)
    override val builtIn = true

    override fun accepts(target: LaunchTarget, platform: PlatformId): Boolean =
        Paths.extension(target.path.orEmpty()) == PortLauncher.EXTENSION

    override fun plan(request: LaunchRequest): LaunchPlan {
        val path = request.target.path
        if (path == null || Paths.extension(path) != PortLauncher.EXTENSION) return LaunchPlan.Unsupported(id, "Only ports Fuse set up start here.")
        val text = request.options.injectedText
            ?: return LaunchPlan.Unsupported(id, "Fuse couldn't read how this port starts. Set it up again in Addons, Ports.")
        val launcher = PortLauncher.decode(text)
            ?: return LaunchPlan.Unsupported(id, "This port's setup is damaged. Set it up again in Addons, Ports.")
        return LaunchPlan.Command(id, launcher.argv, workingDir = launcher.cwd, env = launcher.env, target = request.target)
    }

    companion object {
        /** The system ports are filed under, ES-DE's and the art pack's "ports". */
        val PLATFORM = PlatformId("ports")

        val all: List<PortAdapter> = listOf(PortAdapter(Host.LINUX), PortAdapter(Host.WINDOWS), PortAdapter(Host.MACOS))

        private val Host.prefix: String
            get() = when (this) {
                Host.LINUX -> "linux"
                Host.WINDOWS -> "windows"
                Host.MACOS -> "macos"
                Host.ANDROID -> "android"
            }
    }
}
