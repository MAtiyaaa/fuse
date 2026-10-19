package io.github.matiyaaa.fuse.launch.settings

import io.github.matiyaaa.fuse.model.Host

/** What a setting holds, as the emulator's own code declares it. */
enum class TuneKind { SWITCH, NUMBER, DECIMAL, TEXT, CHOICE }

/**
 * One value a choice can take: what the emulator writes ([value]), what its own code calls it when
 * that differs ([label]), and the systems whose builds have it (null for every one).
 */
data class TuneChoice(val value: String, val label: String? = null, val hosts: Set<Host>? = null) {
    /** What to show for it: the code's own name, else the value as written. */
    val shown: String get() = label ?: value
}

/**
 * One of an emulator's settings as Fuse Tune read it from the emulator's own settings code (see
 * tools/tune): the file it is kept in ([file], null for the emulator's main one), where in it
 * ([path]: the section of an ini file, or the nodes of a YAML one), its [key], what it holds, the
 * value it has when nothing is set (null when the emulator works it out when it runs), the range a
 * number may take, and whether a game's own settings can hold it. [releases] are the releases Fuse
 * Tune read that have it, newest first. Where the emulator's code names it for people: its [label],
 * its description ([about]) and the tab its own settings show it on ([tab]).
 */
data class TuneSetting(
    val family: String,
    val file: String?,
    val path: List<String>,
    val key: String,
    val kind: TuneKind,
    val default: String?,
    val min: Long?,
    val max: Long?,
    val choices: List<TuneChoice>,
    val perGame: Boolean,
    /** Its section in a game's own file, when that differs (Dolphin's `Video_Settings`). */
    val gameSection: String?,
    val releases: List<String>,
    val label: String? = null,
    val about: String? = null,
    val tab: String? = null,
    /** Its key in a game's own file, when that differs from [key] (Vita3K's `enable-ngs` for `ngs-enable`). */
    val gameKey: String? = null,
) {
    /** The key it is kept under: in a game's own file when [game], else in the file for every game. */
    fun keyIn(game: Boolean): String = if (game) gameKey ?: key else key

    /** Its ini section (the only part of an ini file's path). */
    val section: String get() = path.joinToString("/")

    /** The same setting in any release: its family, file, path and key. */
    val id: String get() = listOf(family, file.orEmpty(), path.joinToString("\u001f"), key).joinToString("|")

    /** The choices this system's build of the emulator has. */
    fun choicesOn(host: Host): List<TuneChoice> = choices.filter { it.hosts == null || host in it.hosts }

    /** The section it is kept under: in a game's own file when [game], else in the file for every game. */
    fun sectionIn(game: Boolean): String = if (game) gameSection ?: section else section

    /** Whether [value] is one the emulator reads for it (a choice it has, a number in range, a switch). */
    fun accepts(value: String, host: Host): Boolean = when (kind) {
        TuneKind.SWITCH -> value.lowercase() in setOf("true", "false")
        TuneKind.NUMBER -> value.trim().let { t ->
            t.toLongOrNull()?.let { n -> (min == null || n >= min) && (max == null || n <= max) }
                // An unsigned 64-bit number past a Long's reach (RPCS3's `uint64`), where nothing caps it.
                ?: (t.toULongOrNull() != null && max == null)
        }
        TuneKind.DECIMAL -> value.trim().toDoubleOrNull()?.isFinite() == true
        TuneKind.TEXT -> '\n' !in value && '\r' !in value
        TuneKind.CHOICE -> choicesOn(host).any { it.value == value }
    }
}

/**
 * Fuse Tune: every setting of the emulators it reads, generated from their own settings code at
 * their last releases (tools/tune writes [TUNE_DATA]), so what Fuse offers is what the emulator
 * itself reads, by its own names and values.
 */
object Tune {
    private const val NONE = "\u0001"
    private const val JOIN = "\u001f"
    private const val LABEL = "\u001e"
    private const val HOSTS = "\u001d"

    private val all: Map<String, List<TuneSetting>> by lazy {
        TUNE_DATA.asSequence().flatMap { it.lineSequence() }.filter { it.isNotEmpty() }.mapNotNull(::parse).groupBy { it.family }
    }

    /** The emulators Fuse Tune has read, by family. */
    val families: Set<String> get() = TUNE_RELEASES.keys

    /** The releases read for [family], newest first. */
    fun releases(family: String): List<String> = TUNE_RELEASES[family].orEmpty()

    /** Every setting of [family] read in any of its releases. */
    fun forEmulator(family: String): List<TuneSetting> = all[family].orEmpty()

    /**
     * The settings [family]'s [version] has: those in the newest release read that is no newer
     * than it. A version newer than every release read has the newest's; one older than them all,
     * or one Fuse can't read, has the oldest's (the settings every release read has, at least).
     * Null (the version isn't known) is taken as the newest.
     */
    fun forVersion(family: String, version: String?): List<TuneSetting> {
        val release = releaseFor(family, version) ?: return forEmulator(family)
        return forEmulator(family).filter { release in it.releases }
    }

    /** The release read for [family] whose settings [version] has (see [forVersion]). */
    fun releaseFor(family: String, version: String?): String? {
        val read = releases(family).ifEmpty { return null }
        if (version.isNullOrBlank()) return read.first()
        val numbers = TuneVersions.numbers(version)
        if (numbers.isEmpty()) return read.last()
        return read.firstOrNull { TuneVersions.compare(TuneVersions.numbers(it), numbers) <= 0 } ?: read.last()
    }

    /** A setting by [TuneSetting.id]. */
    fun find(id: String): TuneSetting? = all[id.substringBefore('|')]?.firstOrNull { it.id == id }

    /** A setting of [family] by where it is kept. */
    fun find(family: String, file: String?, section: String, key: String): TuneSetting? = forEmulator(family).firstOrNull {
        it.file == file && it.section.equals(section, ignoreCase = true) && it.key.equals(key, ignoreCase = true)
    }

    private fun parse(line: String): TuneSetting? {
        val f = line.split('\t')
        if (f.size < 12) return null
        fun opt(s: String) = s.takeIf { it != NONE }
        val kind = when (f[4]) {
            "BOOL" -> TuneKind.SWITCH
            "INT" -> TuneKind.NUMBER
            "FLOAT" -> TuneKind.DECIMAL
            "STRING" -> TuneKind.TEXT
            "ENUM" -> TuneKind.CHOICE
            else -> return null
        }
        val choices = if (f[8].isEmpty()) emptyList() else f[8].split(JOIN).map { c ->
            val value = c.substringBefore(LABEL).substringBefore(HOSTS)
            val label = c.substringAfter(LABEL, "").substringBefore(HOSTS).takeIf { LABEL in c }
            val hosts = c.substringAfter(HOSTS, "").takeIf { HOSTS in c }?.split('+')?.mapNotNull(::host)?.toSet()
            TuneChoice(value, label, hosts)
        }
        return TuneSetting(
            family = f[0], file = opt(f[1]), path = f[2].split(JOIN), key = f[3], kind = if (kind == TuneKind.CHOICE && choices.isEmpty()) TuneKind.TEXT else kind,
            // A switch's default as Fuse shows every switch, whatever the emulator writes.
            default = opt(f[5])?.let { if (kind == TuneKind.SWITCH) switchShown(f[0], it) else it }, min = opt(f[6])?.toLongOrNull(), max = opt(f[7])?.toLongOrNull(), choices = choices,
            perGame = f[9] == "1", gameSection = opt(f[10]), releases = f[11].split(',').filter { it.isNotEmpty() },
            label = f.getOrNull(12)?.let(::opt), about = f.getOrNull(13)?.let(::opt), tab = f.getOrNull(14)?.let(::opt),
            gameKey = f.getOrNull(15)?.let(::opt),
        )
    }

    /**
     * How [family] writes a switch, on then off, where it isn't `true` and `false`: DevilutionX
     * `1` and `0` (`Ini::set(bool)`, Source/utils/ini.cpp).
     */
    private val SWITCH_WORDS = mapOf("devilutionx" to ("1" to "0"))

    /** [value] of a switch as Fuse shows it (`true` or `false`), from what [family] writes. */
    fun switchShown(family: String, value: String?): String? {
        val (on, off) = SWITCH_WORDS[family] ?: return value
        return when (value?.trim()) {
            on -> "true"
            off -> "false"
            else -> value
        }
    }

    /** A switch as [family] writes it, from `true` or `false`; null when it writes them as they are. */
    fun switchWritten(family: String, on: Boolean): String? = SWITCH_WORDS[family]?.let { if (on) it.first else it.second }

    private fun host(name: String): Host? = when (name) {
        "windows" -> Host.WINDOWS
        "macos" -> Host.MACOS
        "linux" -> Host.LINUX
        "android" -> Host.ANDROID
        else -> null
    }
}

/**
 * Versions read as their numbers: the first part shaped like a version, never digits inside a name
 * (`pcsx2-v2.8.2` is 2, 8, 2; `v0.1-11894` is 0, 1, 11894), with a fix release's letter after its
 * number (`2609a` is 2609, then 1). Anything after (`-rc1`, a commit) is left out.
 */
object TuneVersions {
    private val VERSION = Regex("""(?<![A-Za-z0-9])[vV]?(\d+(?:[.\-_]\d+)*)([a-z](?![A-Za-z0-9]))?""")

    fun numbers(version: String): List<Long> {
        val m = VERSION.find(version) ?: return emptyList()
        val parts = m.groupValues[1].split('.', '-', '_').mapNotNull { it.toLongOrNull() }
        val letter = m.groupValues[2].firstOrNull()?.let { (it - 'a' + 1).toLong() }
        return if (letter != null) parts + letter else parts
    }

    fun compare(a: List<Long>, b: List<Long>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val c = (a.getOrNull(i) ?: -1).compareTo(b.getOrNull(i) ?: -1)
            if (c != 0) return c
        }
        return 0
    }
}
