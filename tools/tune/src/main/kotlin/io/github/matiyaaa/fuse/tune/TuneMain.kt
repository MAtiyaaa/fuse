package io.github.matiyaaa.fuse.tune

import java.io.File
import java.net.HttpURLConnection
import java.net.URI

/**
 * An emulator Fuse Tune reads: its repository, which of its tags are releases (and how they sort),
 * the files its settings code is in, and how to read them.
 */
class Emulator(
    val family: String,
    val repo: String,
    val release: Regex,
    val files: List<String>,
    val parse: (Map<String, String>) -> List<Found>,
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
)

/** How many releases back each emulator is read. */
const val RELEASES = 4

/** A setting with the releases (of those read) it is in, newest first. */
data class Merged(val found: Found, val tags: List<String>)

/**
 * Reads every emulator in [EMULATORS] at its last [RELEASES] release tags and writes the data to
 * the file named first on the command line. A setting is described as its newest release has it.
 */
fun main(args: Array<String>) {
    val target = File(args.firstOrNull() ?: error("usage: tune <TuneData.kt>"))
    val read = LinkedHashMap<Emulator, Pair<List<String>, List<Merged>>>()
    for (e in EMULATORS) {
        val tags = releases(tagsOf(e.repo), e.release).take(RELEASES)
        require(tags.isNotEmpty()) { "${e.family}: no release tags found" }
        val perTag = tags.associateWith { tag -> e.parse(e.files.mapNotNull { path -> fetch(e.repo, tag, path)?.let { path to it } }.toMap()) }
        perTag.forEach { (tag, found) -> println("${e.family} $tag: ${found.size} settings") }
        read[e] = tags to merge(tags, perTag)
    }
    target.writeText(TuneWriter.kotlin(read.map { (e, v) -> Triple(e.family, v.first, v.second) }))
    println("Wrote ${target.path}")
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

/** The repository's tag names, as `git ls-remote` lists them. */
private fun tagsOf(repo: String): List<String> {
    val p = ProcessBuilder("git", "ls-remote", "--tags", "https://github.com/$repo").redirectErrorStream(true).start()
    val text = p.inputStream.bufferedReader().readText()
    check(p.waitFor() == 0) { "git ls-remote $repo failed: $text" }
    return text.lines().mapNotNull { it.substringAfter("refs/tags/", "").takeIf { t -> t.isNotEmpty() && !t.endsWith("^{}") } }
}

/** One file of [repo] at [tag], or null when that release hasn't got it. */
private fun fetch(repo: String, tag: String, path: String): String? {
    val c = URI("https://raw.githubusercontent.com/$repo/$tag/$path").toURL().openConnection() as HttpURLConnection
    c.connectTimeout = 20_000
    c.readTimeout = 60_000
    return try {
        when (c.responseCode) {
            200 -> c.inputStream.bufferedReader().readText()
            404 -> null
            else -> error("$repo $tag $path: HTTP ${c.responseCode}")
        }
    } finally {
        c.disconnect()
    }
}
