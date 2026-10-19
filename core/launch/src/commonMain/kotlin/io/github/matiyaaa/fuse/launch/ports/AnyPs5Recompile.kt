package io.github.matiyaaa.fuse.launch.ports

import io.github.matiyaaa.fuse.model.Port
import io.github.matiyaaa.fuse.model.PortBuild
import io.github.matiyaaa.fuse.model.PortFileKind

/**
 * A PlayStation 5 game recompiled to run natively with AnyPS5 (github.com/boykopovar/AnyPS5,
 * GPL-2.0), from the person's own dump, as AnyPS5's own documents say to do it (docs/user/USAGE.md
 * at v0.1.3; tools/package_release.py and .github/workflows/release.yml for what a release holds):
 *
 * - Each release holds the relinker for Linux (`relinker-<tag>`) and for Windows
 *   (`relinker-<tag>.exe`), and AnyPS5's system libraries for each (`prx-linux-<tag>.zip`,
 *   `prx-windows-<tag>.zip`, every file under `libs/`; the Windows one with the MinGW runtime its
 *   libraries need). No relinker is published for the Mac.
 * - `relinker [--windows] <eboot.bin> <output>` ("Input and conversion"): a Linux program unless
 *   `--windows`, whatever the output's name. The input is a clean ELF (a SELF container isn't
 *   relinked), with the game's own modules in `sce_module/`, `sce_modules/` or `prx/` beside it:
 *   one of the three at least, and not both of the first two. It exits 0 when done, 1 for a wrong
 *   argument and 2 when the conversion failed, with the reason on its error output ("Exit codes").
 * - The program runs from its folder ("Runtime layout"): AnyPS5's libraries in `libs/` beside it
 *   (the default `--rpath`, `$ORIGIN/libs`), the game's own files in `app0/`, its `eboot.bin` among
 *   them (games look for `/app0/eboot.bin`), and the converted modules the relinker writes into
 *   `app0/<the module folder>/`. The game's paths start at the folder it runs in: its saves in
 *   `_sd`, its trophies in `_trophy`, and `data/` and `download0/` made beside `app0/`.
 * - It needs the processor extensions the PS5's code uses unchecked (AVX2, FMA, F16C, BMI1, BMI2,
 *   LZCNT, MOVBE), and stops at start naming any that is missing.
 *
 * Fuse makes each game's build in a folder of its own ([PortLayout] under its folder of ports, filed
 * with Fuse Ports): the relinked program, `libs/` linked to the libraries of the release that made
 * it, `app0/` made of links to the person's dump (never copied, never changed), and the folders the
 * game writes linked into the build's home, so a recompile or a removal never loses its saves.
 */
object AnyPs5Recompile {
    const val OWNER = "boykopovar"
    const val REPO = "AnyPS5"
    const val HOMEPAGE = "https://github.com/boykopovar/AnyPS5"
    const val SOURCE = "AnyPS5 v0.1.3: docs/user/USAGE.md (\"Input and conversion\", \"Runtime layout\", \"Exit codes\"), " +
        "tools/package_release.py and .github/workflows/release.yml (relinker-<tag>, relinker-<tag>.exe, prx-<system>-<tag>.zip with libs/)"

    /** Fuse's id for a game's build is this and its title id, lower case (`anyps5-ppsa01234`). */
    const val PREFIX = "anyps5-"

    /** The folders of the game's own modules the relinker reads (and writes converted into `app0/`). */
    val MODULE_FOLDERS: List<String> = listOf("sce_module", "sce_modules", "prx")

    /** The folders the game writes beside `app0/`, kept in the build's home. */
    val OWN_FOLDERS: List<String> = listOf("_sd", "_trophy", "data", "download0")

    /** Where its saves are, in the build's home (for keeping them in step, as a port's). */
    const val SAVES = "_sd"

    /** The extensions a recompiled game needs, by the names Linux's `/proc/cpuinfo` gives them (LZCNT's is `abm`). */
    private val EXTENSIONS = listOf("avx2" to "AVX2", "fma" to "FMA", "f16c" to "F16C", "bmi1" to "BMI1", "bmi2" to "BMI2", "abm" to "LZCNT", "movbe" to "MOVBE")

    private val TITLE_ID = Regex("^[A-Z]{4}\\d{5}$")

    /** Why this computer ([os], [arch], as Fuse Ports names them) can't recompile; null when it can. */
    fun unsupported(os: String, arch: String): String? = when {
        os == "macos" -> "AnyPS5 publishes its recompiler for Linux and Windows only. A game recompiled for the Mac on one of those (relinker --macos) plays here once it is in your library."
        os != "linux" && os != "windows" -> "AnyPS5 recompiles games on Linux and Windows."
        arch != "x86_64" -> "AnyPS5's games run on x86-64 processors only, as the PS5's own code does."
        else -> null
    }

    /** The relinker's file in a release [tag] for [os]. */
    fun relinkerAsset(os: String, tag: String): String = if (os == "windows") "relinker-$tag.exe" else "relinker-$tag"

    /** The system libraries' file in a release [tag] for [os]. */
    fun librariesAsset(os: String, tag: String): String = "prx-$os-$tag.zip"

    /** The relinker's name once Fuse has it. */
    fun relinker(os: String): String = if (os == "windows") "relinker.exe" else "relinker"

    /** The recompiled program's name in a game's build. */
    fun program(os: String): String = if (os == "windows") "app.exe" else "app.elf"

    /** Fuse's id for [titleId]'s build (`PPSA01234`), or null when it isn't a PS5 title id. */
    fun id(titleId: String?): String? = titleId?.trim()?.uppercase()?.takeIf { TITLE_ID.matches(it) }?.let { PREFIX + it.lowercase() }

    /** Whether [portId] is a recompiled game's, not one from the list of ports. */
    fun isRecompiled(portId: String): Boolean = portId.startsWith(PREFIX)

    /** The relinker's command: the dump's [eboot] made into [output], a Windows program on Windows. */
    fun argv(relinker: String, os: String, eboot: String, output: String): List<String> = buildList {
        add(relinker)
        if (os == "windows") add("--windows")
        add(eboot)
        add(output)
    }

    /** What a game's `eboot.bin` is, from its first bytes. */
    enum class Executable { ELF, SELF, OTHER }

    /** ELF's magic (`7F 45 4C 46`), or the PS4 and PS5's signed container's (`4F 15 3D 1D`). */
    fun executable(head: ByteArray): Executable = when {
        head.size >= 4 && head[0] == 0x7F.toByte() && head[1] == 'E'.code.toByte() && head[2] == 'L'.code.toByte() && head[3] == 'F'.code.toByte() -> Executable.ELF
        head.size >= 4 && head[0] == 0x4F.toByte() && head[1] == 0x15.toByte() && head[2] == 0x3D.toByte() && head[3] == 0x1D.toByte() -> Executable.SELF
        else -> Executable.OTHER
    }

    /**
     * What the dump with [names] at its top (folders and files) lacks for the relinker, in words;
     * null when it has what it needs. [eboot] is what its `eboot.bin` begins with.
     */
    fun lacks(names: List<String>, eboot: Executable?): String? {
        val has = names.toSet()
        return when {
            eboot == null -> "The dump has no eboot.bin at its top."
            eboot == Executable.SELF -> "This dump's eboot.bin is still in its signed container (a SELF). AnyPS5 recompiles a dump whose eboot.bin is a plain ELF: unwrap it first (AnyPS5's tools/unwrap_self.py does for a decrypted one)."
            eboot == Executable.OTHER -> "This dump's eboot.bin isn't a program AnyPS5 can read."
            "sce_sys" !in has -> "The dump has no sce_sys folder, whose param.json the game reads for its title."
            "sce_module" in has && "sce_modules" in has -> "The dump has both sce_module and sce_modules beside eboot.bin, and AnyPS5 takes one."
            MODULE_FOLDERS.none { it in has } -> "The dump has none of sce_module, sce_modules or prx beside eboot.bin, which hold the game's own modules AnyPS5 recompiles with it."
            else -> null
        }
    }

    /** The entries of the dump linked into `app0/`: all but the module folders, which the relinker fills with converted ones. */
    fun app0Links(names: List<String>): List<String> = names.filter { it !in MODULE_FOLDERS && it.isNotEmpty() }.sorted()

    /** The extensions [cpuinfo] (Linux's `/proc/cpuinfo`) doesn't list, by their names; empty when it has them all. */
    fun missingExtensions(cpuinfo: String): List<String> {
        val flags = cpuinfo.lineSequence().firstOrNull { it.trimStart().startsWith("flags") }?.substringAfter(':')?.trim()?.split(Regex("\\s+"))?.toSet()
            ?: return emptyList()
        return EXTENSIONS.filter { (flag, _) -> flag !in flags }.map { it.second }
    }

    /** How a game's build in [layout] starts: its program, from the build's folder (its `app0/` and save folders are there). */
    fun launcher(layout: PortLayout, os: String, tag: String): PortLauncher =
        PortLauncher(port = layout.id, version = tag, argv = listOf("${layout.install}/${program(os)}"), cwd = layout.install, saves = "${layout.home}/$SAVES")

    /**
     * The build recorded as a port of its own ([id]) for [title]: AnyPS5 at [tag], its relinker for
     * [os] (the file at [url], [size] bytes, [sha256]), started as [program].
     */
    fun port(id: String, title: String, tag: String, os: String, url: String, size: Long, sha256: String): Port = Port(
        id = id,
        name = "AnyPS5",
        game = title,
        about = "$title recompiled with AnyPS5 from your own dump to run natively, without an emulator.",
        homepage = HOMEPAGE,
        licence = "GPL-2.0",
        version = tag,
        builds = listOf(PortBuild(os = os, url = url, size = size, sha256 = sha256, kind = PortFileKind.FILE, run = program(os))),
        saves = SAVES,
        notes = listOf(
            "Recompiled from your dump, which Fuse links in and never changes.",
            "Only some games play yet, as AnyPS5's own list of games says.",
        ),
        source = SOURCE,
    )
}
