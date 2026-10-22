package io.github.matiyaaa.fuse.launch.ports

import io.github.matiyaaa.fuse.model.Port
import io.github.matiyaaa.fuse.model.PortBuild
import io.github.matiyaaa.fuse.model.PortData
import io.github.matiyaaa.fuse.model.PortRunner
import io.github.matiyaaa.fuse.model.PortTokens
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Where a port lives in Fuse's own folder of ports: [install] its unpacked build (replaced by an
 * update), [home] what it writes and keeps (saves, settings, a Proton prefix), and [launcher] the
 * small file the library knows the game by, which says how it starts. Paths use `/`.
 */
data class PortLayout(val root: String, val id: String) {
    val install: String get() = "$root/build"
    val home: String get() = "$root/home"
    val launcher: String get() = "$root/$id.${PortLauncher.EXTENSION}"
}

/**
 * How one installed port starts, written as [PortLayout.launcher] when it is installed or its game
 * files are chosen, and read back by [PortAdapter]: Play, Fast Start and a Steam entry all start
 * exactly this. [saves] is the folder its saves are in, for keeping them in step ([saveFiles]
 * when only some files there are).
 */
@Serializable
data class PortLauncher(
    val port: String,
    val version: String,
    val argv: List<String>,
    val cwd: String,
    val env: Map<String, String> = emptyMap(),
    val saves: String? = null,
    /** When only these files in [saves] are its saves (the rest is its settings and caches). */
    val saveFiles: List<String> = emptyList(),
) {
    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        const val EXTENSION = "fuseport"
        private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

        /** The launcher in [text], or null when it isn't one (or names nothing to run). */
        fun decode(text: String): PortLauncher? =
            runCatching { json.decodeFromString(serializer(), text) }.getOrNull()?.takeIf { it.argv.isNotEmpty() && it.argv.all(String::isNotEmpty) }
    }
}

/** What to do with one entry of the person's folder when it is linked into Fuse's copy. */
enum class OverlayStep {
    /** Link it: the port reads it where it is. */
    LINK,

    /** Make a folder of Fuse's own here and look at what is inside: an own path is further down. */
    DESCEND,

    /** One of the port's own folders: a link to the same folder in the port's home. */
    OWN_FOLDER,

    /** One of the port's own files, or a name the port's build already has: left alone. */
    SKIP,
}

/**
 * The parts of a port's setup that are only data: which build this computer runs, what is missing
 * from the folder the person chose, the overlay's steps and the command. The files themselves are
 * the store's and the system's to touch.
 */
object PortPlan {
    /** umu-launcher's own default game id: no fixes looked up for it (umu-launcher README, "GAMEID"). */
    const val UMU_GAME_ID = "umu-default"

    /**
     * The build of [port] for this computer ([os], [arch]): its own system's first; on Linux, with
     * umu-launcher installed ([umu]), a Windows build marked to run through it.
     */
    fun build(port: Port, os: String, arch: String, umu: Boolean): PortBuild? =
        port.builds.firstOrNull { it.os == os && it.arch == arch && it.runner == PortRunner.NATIVE }
            ?: port.builds.firstOrNull { umu && os == "linux" && it.os == "windows" && it.arch == arch && it.runner == PortRunner.UMU }

    /** Why [port] has no build here, said for the person. */
    fun noBuild(port: Port, os: String): String {
        val umuOnly = os == "linux" && port.builds.any { it.os == "windows" && it.runner == PortRunner.UMU }
        return when {
            umuOnly -> "${port.name} is published for Windows; on Linux it runs through umu-launcher, which isn't installed here."
            else -> "${port.name} isn't published for this computer."
        }
    }

    /**
     * What [data] still needs from the chosen folder: each need none of whose names [exists] finds
     * there (the caller looks, exactly or ignoring case as [PortData.exact] says). Empty when ready.
     */
    fun missing(data: PortData, exists: (String) -> Boolean): List<List<String>> = data.needs.filter { names -> names.none(exists) }

    /** [missing] in words: "DIABDAT.MPQ or spawn.mpq". */
    fun describe(needs: List<List<String>>): String = needs.joinToString(", ") { it.joinToString(" or ") }

    /**
     * What to do with [relative] (a path in the person's folder, with `/`) for an overlay whose own
     * paths are [own] (a folder ends with `/`). Compared ignoring letter case: the ports that read the
     * game's files from their own folder find them whatever their case (fallout2-ce's
     * `compat_resolve_path`), so an own path must be kept from the person's folder in any case.
     */
    fun step(relative: String, isFolder: Boolean, own: List<String>): OverlayStep {
        val path = relative.trim('/').lowercase()
        for (o in own) {
            val folder = o.endsWith("/")
            val p = o.trim('/').lowercase()
            if (p == path) return if (folder && isFolder) OverlayStep.OWN_FOLDER else OverlayStep.SKIP
            if (isFolder && p.startsWith("$path/")) return OverlayStep.DESCEND
        }
        return OverlayStep.LINK
    }

    /** The overlay's own folders, relative to where it goes (each made a link into the home, there or not in the person's folder). */
    fun ownFolders(own: List<String>): List<String> = own.filter { it.endsWith("/") }.map { it.trim('/') }

    /** The folder [build]'s program is in, relative to its copy: empty when it is at the top. */
    fun programFolder(build: PortBuild): String = build.run.substringBeforeLast('/', "")

    /** [Port.keep]'s files (not its folders), carried from the old copy to the new one on an update. */
    fun keptFiles(keep: List<String>): List<String> = keep.filter { !it.endsWith("/") }.map { it.trim('/') }

    /**
     * How [port]'s [build] starts in [layout] with the person's folders by data id ([folders]): the
     * program and its arguments with every placeholder filled, from the build's own folder (a port
     * looks beside its program and in the folder it is started from for its own files, as
     * DevilutionX's `GetMPQSearchPaths` does). A Windows build on Linux goes through [umuRun] with a
     * Proton prefix of its own in its home, and the paths it is given as Windows sees them through
     * Proton (`Z:` is the root of the file system in a prefix Wine makes). Null when a placeholder
     * names a folder the person hasn't chosen.
     */
    fun launcher(port: Port, build: PortBuild, layout: PortLayout, folders: Map<String, String>, umuRun: String?): PortLauncher? {
        val umu = build.runner == PortRunner.UMU
        if (umu && umuRun == null) return null
        var unresolved = false
        fun fill(arg: String): String {
            var out = arg.replace(PortTokens.INSTALL, layout.install).replace(PortTokens.HOME, layout.home)
            while (true) {
                val at = out.indexOf(PortTokens.DATA_PREFIX)
                if (at < 0) break
                val end = out.indexOf('}', at)
                if (end < 0) break
                val id = out.substring(at + PortTokens.DATA_PREFIX.length, end)
                val folder = folders[id]
                if (folder == null) {
                    unresolved = true
                    break
                }
                out = out.substring(0, at) + folder + out.substring(end + 1)
            }
            return out
        }
        // Under Proton the path in an argument, from its first placeholder on ("--log={install}/log.txt"), is Windows'.
        fun windowsArg(arg: String): String {
            val at = listOf(PortTokens.INSTALL, PortTokens.HOME, PortTokens.DATA_PREFIX).map(arg::indexOf).filter { it >= 0 }.minOrNull()
                ?: return arg
            return arg.substring(0, at) + windowsPath(fill(arg.substring(at)))
        }
        val args = port.args.map { if (umu) windowsArg(it) else fill(it) }
        val env = port.env.mapValues { (_, v) -> if (umu) windowsArg(v) else fill(v) }
        if (unresolved) return null
        val program = "${layout.install}/${build.run}"
        val saves = port.saves?.let { s -> if (s.isEmpty()) layout.home else "${layout.home}/${s.trim('/')}" }
        return if (umu) {
            PortLauncher(
                port = port.id, version = port.version,
                argv = listOf(umuRun!!, program) + args,
                cwd = layout.install,
                // umu-launcher README: WINEPREFIX is where the prefix is made, GAMEID which fixes apply.
                env = env + mapOf("WINEPREFIX" to "${layout.home}/prefix", "GAMEID" to UMU_GAME_ID),
                saves = saves,
            )
        } else {
            PortLauncher(port = port.id, version = port.version, argv = listOf(program) + args, cwd = layout.install, env = env, saves = saves)
        }
    }

    /**
     * [plan] with the Proton at [protonPath] for a Windows build started through umu-launcher (one
     * whose command has [UMU_GAME_ID] as its `GAMEID`): umu-launcher's README, "PROTONPATH", a path
     * to a Proton directory. Any other plan, or one that names its own, as it is.
     */
    fun withProton(plan: io.github.matiyaaa.fuse.model.LaunchPlan, protonPath: String?): io.github.matiyaaa.fuse.model.LaunchPlan =
        if (plan is io.github.matiyaaa.fuse.model.LaunchPlan.Command && protonPath != null && plan.env["GAMEID"] == UMU_GAME_ID && "PROTONPATH" !in plan.env) {
            plan.copy(env = plan.env + ("PROTONPATH" to protonPath))
        } else plan

    /** An N64 ROM's first four bytes in its own byte order (librecomp's `first_rom_bytes`). */
    private val ROM_START = byteArrayOf(0x80.toByte(), 0x37, 0x12, 0x40)

    /**
     * The N64 ROM [bytes] as N64: Recompiled's runtime keeps it (librecomp `select_rom`): padded
     * to a multiple of four bytes, then turned round from the `.v64` (pairs swapped) or `.n64`
     * (fours reversed) order where its first bytes show one. Null when it isn't an N64 ROM.
     */
    fun bigEndianRom(bytes: ByteArray): ByteArray? {
        if (bytes.size < 4) return null
        val data = bytes.copyOf((bytes.size + 3) and 3.inv())
        fun starts(i0: Int, i1: Int, i2: Int, i3: Int) =
            data[0] == ROM_START[i0] && data[1] == ROM_START[i1] && data[2] == ROM_START[i2] && data[3] == ROM_START[i3]
        val xor = when {
            starts(0, 1, 2, 3) -> 0
            starts(3, 2, 1, 0) -> 3
            starts(1, 0, 3, 2) -> 1
            else -> return null
        }
        if (xor != 0) {
            for (at in data.indices step 4) {
                val t = data.copyOfRange(at, at + 4)
                for (i in 0 until 4) data[at + (i xor xor)] = t[i]
            }
        }
        return data
    }

    /** True when [bytes] are an N64 ROM already as [bigEndianRom] would make it: nothing to turn round or pad. */
    fun isBigEndianRom(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes.size % 4 == 0 && (0 until 4).all { bytes[it] == ROM_START[it] }

    /** Whether [bytes] are [rom]'s ROM, in any byte order: the hash of their big-endian form is its. */
    fun isRom(rom: io.github.matiyaaa.fuse.model.PortRom, bytes: ByteArray): Boolean =
        bigEndianRom(bytes)?.let { Xxh3.hex(it) == rom.xxh3.lowercase() } == true

    /** [path] (a Linux path) as a Windows program sees it under Proton: `Z:\` and backslashes. */
    fun windowsPath(path: String): String = "Z:" + path.replace('/', '\\')
}
