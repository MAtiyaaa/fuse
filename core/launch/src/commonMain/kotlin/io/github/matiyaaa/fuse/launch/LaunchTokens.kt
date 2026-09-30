package io.github.matiyaaa.fuse.launch

/**
 * Tokens used in adapter templates (intent data/extras, Linux argv).
 *
 * Resolved by adapters: [PATH]/[ROM], [ROMDIR], [BASENAME], [PKG], [CORE], [CORE_PATH], [SERIAL],
 * [INJECT], [EMUDIR].
 *
 * Left in place for the Android app (see [io.github.matiyaaa.fuse.launch.android.AndroidIntentPlan]):
 * [SAF], [PROVIDER], [EXTDATA], [INTDATA].
 */
object LaunchTokens {
    /** Absolute path of the target file or folder. */
    const val PATH = "{PATH}"

    /** Linux alias of [PATH], as in ES-DE's `%ROM%`. */
    const val ROM = "{ROM}"

    /** Folder that contains the target (ES-DE `%GAMEDIR%`). */
    const val ROMDIR = "{ROMDIR}"

    /** Target file name without extension (ES-DE `%BASENAME%`). */
    const val BASENAME = "{BASENAME}"

    /** Android package of the installed emulator (ES-DE `%ANDROIDPACKAGE%`). */
    const val PKG = "{PKG}"

    /** RetroArch core name, for example "mgba". */
    const val CORE = "{CORE}"

    /** Linux: absolute RetroArch core file. */
    const val CORE_PATH = "{CORE_PATH}"

    /** Title id / serial (from a title id target, an id file, or the file name). */
    const val SERIAL = "{SERIAL}"

    /** Trimmed content of the id file (ES-DE `%INJECT%`). */
    const val INJECT = "{INJECT}"

    /** Linux: folder of the emulator executable (ES-DE `%EMUDIR%`). */
    const val EMUDIR = "{EMUDIR}"

    /** Android: SAF tree document URI of the target, the tree being the per-system ROM folder (ES-DE `%ROMSAF%`). */
    const val SAF = "{SAF}"

    /** Android: Fuse FileProvider content URI with a read grant (ES-DE `%ROMPROVIDER%`). Data only. */
    const val PROVIDER = "{PROVIDER}"

    /**
     * Android: the best content URI for the file. Single-file disc images ([singleFileImages]) go
     * through Fuse's FileProvider with a read grant, so the emulator needs no folder grant of its
     * own; everything else (cue/bin sets, playlists) is the [SAF] document. Allowed in extras.
     */
    const val DOC = "{DOC}"

    /** Disc image formats that are one self-contained file, for [DOC]. */
    val singleFileImages: Set<String> = setOf("chd", "pbp", "iso", "img", "ecm", "cso", "zso", "rvz", "gcz", "wbfs")

    /** Android: `/storage/emulated/<user>` (ES-DE `%EXTERNALDATA%`). */
    const val EXTDATA = "{EXTDATA}"

    /** Android: `/data/user/<user>` (ES-DE `%INTERNALDATA%`). */
    const val INTDATA = "{INTDATA}"

    /** Tokens the Android app fills because they need Android APIs or the user id. */
    val androidResolved: Set<String> = setOf(SAF, PROVIDER, DOC, EXTDATA, INTDATA)

    /** Every token a template may use. */
    val all: Set<String> =
        setOf(PATH, ROM, ROMDIR, BASENAME, PKG, CORE, CORE_PATH, SERIAL, INJECT, EMUDIR) + androidResolved

    private val tokenRegex = Regex("""\{[A-Z_]+\}""")

    /** Tokens that appear in [template]. */
    fun tokensIn(template: String): Set<String> = tokenRegex.findAll(template).map { it.value }.toSet()

    /**
     * Replaces every token present in [values]. Tokens not in [values] (the Android ones) stay as they
     * are. Returns [Filled.Missing] when a token maps to null, so adapters can explain what is missing.
     */
    fun fill(template: String, values: Map<String, String?>): Filled {
        var missing: String? = null
        val out = tokenRegex.replace(template) { m ->
            if (m.value in values) {
                values[m.value] ?: run {
                    if (missing == null) missing = m.value
                    m.value
                }
            } else {
                m.value
            }
        }
        return missing?.let { Filled.Missing(it) } ?: Filled.Ok(out)
    }

    /** Result of [fill]. */
    sealed interface Filled {
        data class Ok(val value: String) : Filled
        data class Missing(val token: String) : Filled
    }
}
