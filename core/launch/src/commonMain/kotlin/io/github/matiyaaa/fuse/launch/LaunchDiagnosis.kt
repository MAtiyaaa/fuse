package io.github.matiyaaa.fuse.launch

/** What an emulator that stopped at once left behind: the command it was given, how it ended and what it wrote. */
data class LaunchDiagnostic(
    val command: List<String>,
    val exitCode: Int?,
    /** The start of what it wrote to its error output. */
    val output: String,
)

/** The likely reason an emulator stopped as it started, read from what it wrote. */
enum class LaunchCause {
    /** RetroArch couldn't load the core it was given. */
    CORE_MISSING,

    /** It wanted firmware (a BIOS, keys) it couldn't find. */
    FIRMWARE,

    /** Its graphics interface (Vulkan, OpenGL, Direct3D, Metal) couldn't start on this computer. */
    GRAPHICS,

    /** A library it needs isn't on this computer, or is too old. */
    LIBRARY,

    /** It couldn't open the game's file or folder. */
    GAME_FILE,

    /** The system didn't let it read or run something. */
    PERMISSION,

    /** It crashed. */
    CRASH,

    /** Nothing it wrote says why. */
    UNKNOWN,
}

/**
 * Reads what an emulator wrote as it stopped and says, in plain words, the likely reason: only from
 * what it actually wrote, never a guess dressed up as a finding ([LaunchCause.UNKNOWN] when nothing
 * it wrote says).
 */
object LaunchDiagnosis {
    fun cause(d: LaunchDiagnostic): LaunchCause {
        val t = d.output.lowercase()
        return when {
            "failed to load libretro core" in t || ("libretro" in t && "no such file" in t) || "core not found" in t -> LaunchCause.CORE_MISSING
            BIOS.containsMatchIn(t) -> LaunchCause.FIRMWARE
            GRAPHICS.containsMatchIn(t) -> LaunchCause.GRAPHICS
            "error while loading shared libraries" in t || "cannot open shared object file" in t || "glibc_" in t ||
                "library not loaded" in t || ".dll was not found" in t || "image not found" in t -> LaunchCause.LIBRARY
            "permission denied" in t || "operation not permitted" in t || "access is denied" in t -> LaunchCause.PERMISSION
            ("no such file" in t || "could not open" in t || "failed to open" in t || "unable to open" in t) &&
                d.command.drop(1).any { it.length > 1 && it.lowercase() in t } -> LaunchCause.GAME_FILE
            "segmentation fault" in t || "core dumped" in t || "sigsegv" in t || "access violation" in t ||
                d.exitCode in CRASH_CODES -> LaunchCause.CRASH
            else -> LaunchCause.UNKNOWN
        }
    }

    /** [cause] in plain words, for [emulator]. */
    fun explain(cause: LaunchCause, emulator: String): String = when (cause) {
        LaunchCause.CORE_MISSING -> "RetroArch couldn't load the core for this game. Install the core in RetroArch (Online Updater, Core Downloader), or choose another core for this system."
        LaunchCause.FIRMWARE -> "$emulator says firmware it needs is missing. Put the system's BIOS files where $emulator looks for them, then try again."
        LaunchCause.GRAPHICS -> "$emulator's graphics couldn't start on this computer. Choosing another renderer in its settings (OpenGL instead of Vulkan, for example) usually gets it going."
        LaunchCause.LIBRARY -> "A library $emulator needs is missing or too old on this computer. A Flatpak or AppImage of $emulator brings its own, or update the system's packages."
        LaunchCause.GAME_FILE -> "$emulator couldn't open the game's file. Check that it is still there and that $emulator can open this kind of file."
        LaunchCause.PERMISSION -> "The system didn't let $emulator read or run something it needed. A Flatpak may need access to the game's folder (Flatseal), or the file may need to be readable."
        LaunchCause.CRASH -> "$emulator crashed as the game started. Updating it, or another version or emulator, often helps."
        LaunchCause.UNKNOWN -> "$emulator stopped without saying why."
    }

    /** The diagnostic as text for the problem's details, with [home] shown as `~`. */
    fun report(d: LaunchDiagnostic, emulator: String, version: String?, cause: LaunchCause, home: String?): String {
        fun clean(s: String) = if (!home.isNullOrBlank() && home.length > 1) s.replace(home.trimEnd('/', '\\'), "~") else s
        return buildString {
            append("Launch diagnostic: ").append(emulator).append(version?.let { " $it" } ?: "").append('\n')
            append("Likely cause: ").append(cause.name.lowercase().replace('_', ' ')).append('\n')
            append("Command: ").append(clean(d.command.joinToString(" ") { if (' ' in it) "\"$it\"" else it })).append('\n')
            append("Exit code: ").append(d.exitCode?.toString() ?: "none").append('\n')
            if (d.output.isNotBlank()) append("Output:\n").append(clean(d.output.trim().lines().take(OUTPUT_LINES).joinToString("\n")))
        }.trimEnd()
    }

    private const val OUTPUT_LINES = 12
    private val BIOS = Regex("""\bbios\b|firmware (?:is )?(?:missing|not found)|prod\.keys|no (?:valid )?bios|scph\d+""")
    private val GRAPHICS = Regex("""vk_error|vulkan[^\n]*(?:fail|error|not supported)|failed to create (?:vulkan|opengl|gl|d3d|metal)|opengl[^\n]*(?:not supported|fail)|glx|egl[^\n]*fail|d3d1[12][^\n]*fail|no suitable (?:gpu|device)""")

    /** Exit codes that mean a crash: SIGSEGV/SIGABRT through a shell (139, 134), and Windows' access violation. */
    private val CRASH_CODES = setOf(134, 139, -1073741819)
}
