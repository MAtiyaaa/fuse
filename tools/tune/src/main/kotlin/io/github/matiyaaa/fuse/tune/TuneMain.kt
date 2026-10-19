package io.github.matiyaaa.fuse.tune

import java.io.File
import java.net.HttpURLConnection
import java.net.URI

/**
 * An emulator Fuse Tune reads: its repository, which of its tags are releases (and how they sort),
 * the files its settings code is in, and how to read them. A repository on GitHub by default; one
 * elsewhere gives its git address and how a file at a tag is fetched there ([raw]).
 */
class Emulator(
    val family: String,
    val repo: String,
    val release: Regex,
    val files: List<String>,
    val parse: (Map<String, String>) -> List<Found>,
    val git: String = "https://github.com/$repo",
    val raw: (tag: String, path: String) -> String = { tag, path -> "https://raw.githubusercontent.com/$repo/$tag/$path" },
)

val EMULATORS = listOf(
    // Dolphin's releases are named by year and month (2609, then 2609a for a fix).
    Emulator("dolphin", "dolphin-emu/dolphin", Regex("""^\d{4}[a-z]?$"""), Dolphin.FILES, Dolphin::parse),
    // DuckStation's releases are its build numbers.
    Emulator("duckstation", "stenzek/duckstation", Regex("""^v0\.1-\d+$"""), DuckStation.FILES, DuckStation::parse),
    Emulator("ppsspp", "hrydgard/ppsspp", Regex("""^v\d+\.\d+(?:\.\d+)?$"""), Ppsspp.FILES, Ppsspp::parse),
    // PCSX2's stable releases have an even minor number; odd ones are its nightlies.
    Emulator("pcsx2", "PCSX2/pcsx2", Regex("""^v\d+\.\d*[02468]\.\d+$"""), Pcsx2.FILES, Pcsx2::parse),
    Emulator("rpcs3", "RPCS3/rpcs3", Regex("""^v\d+\.\d+\.\d+$"""), Rpcs3.FILES, Rpcs3::parse),
    // Fuse Ports' ports, by their ids in ports/index.json.
    Emulator("devilutionx", "diasurgical/devilutionX", Regex("""^\d+\.\d+\.\d+$"""), DevilutionX.FILES, DevilutionX::parse),
    Emulator("openrct2", "OpenRCT2/OpenRCT2", Regex("""^v\d+\.\d+\.\d+$"""), OpenRct2.FILES, OpenRct2::parse),
    Emulator("fallout2-ce", "alexbatalov/fallout2-ce", Regex("""^v\d+\.\d+\.\d+$"""), Fallout2Ce.FILES, Fallout2Ce::parse),
    // RetroArch's own settings, for a game's override file.
    Emulator("retroarch", "libretro/RetroArch", Regex("""^v\d+\.\d+\.\d+$"""), RetroArch.FILES, RetroArch::parse),
    // Eden is kept on its own Forgejo; its releases are v0.2.1 and the like (release candidates left out).
    Emulator(
        "eden", "eden-emu/eden", Regex("""^v\d+\.\d+\.\d+$"""), Eden.FILES, Eden::parse,
        git = "https://git.eden-emu.dev/eden-emu/eden",
        raw = { tag, path -> "https://git.eden-emu.dev/eden-emu/eden/raw/tag/$tag/$path" },
    ),
)

/** How many releases back each emulator is read. */
const val RELEASES = 4

/** A setting with the releases (of those read) it is in, newest first. */
data class Merged(val found: Found, val tags: List<String>)

/**
 * Reads every emulator in [EMULATORS] at its last [RELEASES] release tags and writes the data to
 * the file named first on the command line. A setting is described as its newest release has it.
 * With `--only=a,b`, only those families are read again and the others are kept as the file has them.
 */
fun main(args: Array<String>) {
    val target = File(args.firstOrNull() ?: error("usage: tune <TuneData.kt> [--only=family,...]"))
    val only = args.firstOrNull { it.startsWith("--only=") }?.removePrefix("--only=")?.split(',')?.filter { it.isNotBlank() }?.toSet()
    val read = LinkedHashMap<Emulator, Pair<List<String>, List<Merged>>>()
    for (e in EMULATORS) {
        if (only != null && e.family !in only) continue
        val tags = releases(tagsOf(e.git), e.release).take(RELEASES)
        require(tags.isNotEmpty()) { "${e.family}: no release tags found" }
        val perTag = tags.associateWith { tag -> e.parse(e.files.mapNotNull { path -> fetch(e.raw(tag, path))?.let { path to it } }.toMap()) }
        perTag.forEach { (tag, found) -> println("${e.family} $tag: ${found.size} settings") }
        read[e] = tags to merge(tags, perTag)
    }
    val fresh = read.map { (e, v) -> Triple(e.family, v.first, v.second) }
    if (only == null) {
        target.writeText(TuneWriter.kotlin(fresh))
    } else {
        target.writeText(keep(target.takeIf { it.isFile }?.readText(), fresh))
    }
    println("Wrote ${target.path}")
}

/** The data file [before] with the families in [fresh] replaced (or added after the others). */
fun keep(before: String?, fresh: List<Triple<String, List<String>, List<Merged>>>): String {
    val (releases, lines) = before?.let(TuneWriter::readBack) ?: (emptyList<Pair<String, List<String>>>() to emptyList())
    val families = fresh.map { it.first }.toSet()
    val keptReleases = releases.filter { it.first !in families } + fresh.map { it.first to it.second }
    val keptLines = lines.filter { it.substringBefore('\t') !in families } + fresh.flatMap { (family, _, settings) -> settings.map { TuneWriter.line(family, it) } }
    return TuneWriter.kotlinOf(keptReleases, keptLines)
}

/** One entry per setting, across [tags] (newest first): as the newest that has it describes it. */
fun merge(tags: List<String>, perTag: Map<String, List<Found>>): List<Merged> {
    val order = LinkedHashMap<String, Found>()
    val inTags = HashMap<String, MutableList<String>>()
    for (tag in tags) {
        for (f in perTag[tag].orEmpty()) {
            order.putIfAbsent(f.identity, f)
            inTags.getOrPut(f.identity) { mutableListOf() } += tag
        }
    }
    return order.values.map { Merged(it, inTags.getValue(it.identity)) }
}

/** [tags] that are releases, newest first, by the numbers in them (`2609a` after `2609`). */
fun releases(tags: List<String>, release: Regex): List<String> =
    tags.filter { release.matches(it) }.sortedWith(VersionOrder).reversed()

/** Orders version names by their numbers, then by a trailing letter. */
object VersionOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        val x = Regex("""\d+""").findAll(a).map { it.value.toLong() }.toList()
        val y = Regex("""\d+""").findAll(b).map { it.value.toLong() }.toList()
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = (x.getOrNull(i) ?: -1).compareTo(y.getOrNull(i) ?: -1)
            if (c != 0) return c
        }
        return a.compareTo(b)
    }
}

/** The tag names of the repository at [git], as `git ls-remote` lists them. */
private fun tagsOf(git: String): List<String> {
    val p = ProcessBuilder("git", "ls-remote", "--tags", git).redirectErrorStream(true).start()
    val text = p.inputStream.bufferedReader().readText()
    check(p.waitFor() == 0) { "git ls-remote $git failed: $text" }
    return text.lines().mapNotNull { it.substringAfter("refs/tags/", "").takeIf { t -> t.isNotEmpty() && !t.endsWith("^{}") } }
}

/** The file at [url] (one file of a repository at a tag), or null when that release hasn't got it. */
private fun fetch(url: String): String? {
    val c = URI(url).toURL().openConnection() as HttpURLConnection
    c.connectTimeout = 20_000
    c.readTimeout = 60_000
    return try {
        when (c.responseCode) {
            200 -> c.inputStream.bufferedReader().readText()
            404 -> null
            else -> error("$url: HTTP ${c.responseCode}")
        }
    } finally {
        c.disconnect()
    }
}
