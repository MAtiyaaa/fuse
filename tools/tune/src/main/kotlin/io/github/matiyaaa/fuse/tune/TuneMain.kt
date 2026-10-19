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
    // shadPS4's releases are v.0.20.0 and the like.
    Emulator("shadps4", "shadps4-emu/shadPS4", Regex("""^v\.\d+\.\d+\.\d+$"""), ShadPs4.FILES, ShadPs4::parse),
    // Cemu's releases are v2.6 and the like; its v2.0-95 builds before them were previews.
    Emulator("cemu", "cemu-project/Cemu", Regex("""^v\d+\.\d+$"""), Cemu.FILES, Cemu::parse),
    // Azahar's releases are numbered by year and week (2126.2, 2126.1.2); alphas and release candidates left out.
    Emulator("azahar", "azahar-emu/azahar", Regex("""^\d{4}(?:\.\d+)*$"""), Azahar.FILES, Azahar::parse),
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
    val fresh = read.map { (e, v) -> Triple(e.family, v.first, v.second) } + cores(only)
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

/** The family a libretro core's options are kept under (`core.snes9x`). */
const val CORE_FAMILY = "core."

/** Where Fuse lists the RetroArch cores it starts games with, from ES-DE's systems. */
const val CORES_SOURCE = "core/launch/src/commonMain/kotlin/io/github/matiyaaa/fuse/launch/RetroArchCores.kt"

/** libretro's own build recipe: each core's name, repository and branch (libretro-super, recipes/linux). */
const val CORE_RECIPE = "https://raw.githubusercontent.com/libretro/libretro-super/master/recipes/linux/cores-linux-x64-generic"

/** A core as libretro's recipe builds it: its repository, branch, and the macros its build defines (`HAVE_HW=1`, `-DLIBRETRO=ON`). */
data class CoreRecipe(val url: String, val branch: String, val flags: Set<String>)

/** libretro-super's recipe lines: `name dir url branch YES kind makefile subdir [FLAG=1 ...]`. */
fun coreRecipes(text: String): Map<String, CoreRecipe> = text.lines().mapNotNull { line ->
    val p = line.trim().split(Regex("""\s+"""))
    if (p.size < 4) return@mapNotNull null
    val flags = p.drop(4).mapNotNull { Regex("""^(?:-D)?(\w+)=(?:1|ON|YES|TRUE)$""", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }.toSet()
    p[0] to CoreRecipe(p[2], p[3], flags)
}.toMap()

/**
 * The core options of every RetroArch core Fuse starts games with ([CORES_SOURCE]; with `--only`,
 * those named `core.<name>` or all of them for `cores`): each core's repository and branch from
 * libretro's own recipe, its `libretro_core_options.h` found in the tree at the branch's head (a
 * core has no release tags of its own; the commit read is its release), read by [CoreOptions].
 * A key made of macros the file doesn't define is read through the `#define`s of the headers
 * beside it, then its sources, then the headers those include (Mupen64Plus-Next's `CORE_NAME` is
 * in `custom/mupen64plus-next_common.h`, which its `libretro.c` includes), as the recipe's build
 * sees them. A core with no such file, one
 * that makes its options as it starts (MAME's), or one not on GitHub, is left out and said.
 */
fun cores(only: Set<String>?): List<Triple<String, List<String>, List<Merged>>> {
    val names = coreNames(File(CORES_SOURCE).takeIf { it.isFile }?.readText() ?: return emptyList())
        .filter { only == null || "cores" in only || "$CORE_FAMILY$it" in only }
    if (names.isEmpty()) return emptyList()
    val recipe = coreRecipes(fetch(CORE_RECIPE).orEmpty())
    val out = ArrayList<Triple<String, List<String>, List<Merged>>>()
    for (name in names) {
        val core = recipe[name] ?: run { println("core $name: not in libretro's recipe"); null } ?: continue
        val repo = Regex("""github\.com/([^/]+/[^/.]+)""").find(core.url)?.groupValues?.get(1) ?: run { println("core $name: not on GitHub (${core.url})"); null } ?: continue
        val tree = optionsFile(core.url, core.branch) ?: run { println("core $name: no libretro_core_options.h"); null } ?: continue
        val sha = tree.sha
        val text = fetch("https://raw.githubusercontent.com/$repo/$sha/${tree.path}") ?: continue
        var found = CoreOptions.parse(text)
        if (CoreOptions.unknown(text).isNotEmpty()) {
            // Headers beside it first, then the sources, then what they include, until every macro a key uses is known.
            val more = LinkedHashMap<String, CoreOptions.Define>()
            val read = mutableListOf(text)
            val seen = HashSet<String>()
            fun take(files: List<String>) {
                for (file in files) {
                    if (!seen.add(file)) continue
                    val source = fetch("https://raw.githubusercontent.com/$repo/$sha/$file") ?: continue
                    read += source
                    CoreOptions.defines(source, core.flags).forEach { (k, v) -> more.putIfAbsent(k, v) }
                }
            }
            fun known() = CoreOptions.unknown(text).all { it in more }
            take(tree.siblings.filter { it.endsWith(".h") })
            if (!known()) take(tree.siblings.filterNot { it.endsWith(".h") })
            if (!known()) {
                val included = read.flatMap { t -> Regex("""#\s*include\s+"([^"]+)"""").findAll(t).map { it.groupValues[1].substringAfterLast('/') }.toList() }.toSet()
                take(included.flatMap { name -> tree.files.filter { it.substringAfterLast('/') == name }.take(2) }.take(60))
            }
            found = CoreOptions.parse(text, more)
        }
        val release = sha.take(10)
        println("core $name ${repo}@$release: ${found.size} options")
        if (found.isNotEmpty()) out += Triple("$CORE_FAMILY$name", listOf(release), found.map { Merged(it, listOf(release)) })
    }
    return out
}

/** The cores [source] (RetroArchCores.kt) names: every quoted name in it that isn't a system's. */
fun coreNames(source: String): List<String> {
    val systems = Regex(""""([a-z0-9-]+)"\s+to\s""").findAll(source).map { it.groupValues[1] }.toSet()
    return Regex(""""([a-z0-9_]+)"""").findAll(source).map { it.groupValues[1] }.filter { it !in systems }.distinct().sorted().toList()
}

/** Where a core's options are: the commit read, the options file's path, and the other C files beside it (not its translations). */
private class CoreTree(val sha: String, val path: String, val siblings: List<String>, val files: List<String>)

/** The head commit of [branch] at [git] and the path of its `libretro_core_options.h` (the one nearest the root), from the tree alone. */
private fun optionsFile(git: String, branch: String): CoreTree? {
    val dir = java.nio.file.Files.createTempDirectory("tune-core").toFile()
    try {
        fun git(vararg a: String): String? {
            val p = ProcessBuilder(listOf("git") + a).directory(dir).redirectErrorStream(true).start()
            val text = p.inputStream.bufferedReader().readText()
            return if (p.waitFor() == 0) text else null
        }
        git("clone", "-q", "--depth", "1", "--filter=blob:none", "--no-checkout", "--branch", branch, git, ".") ?: return null
        val sha = git("rev-parse", "HEAD")?.trim() ?: return null
        val files = git("ls-tree", "-r", "--name-only", "HEAD")?.lines().orEmpty()
        val path = files.filter { it.endsWith("libretro_core_options.h") }.minByOrNull { it.count { c -> c == '/' } } ?: return null
        val dir = path.substringBeforeLast('/', "")
        val siblings = files.filter { f ->
            f != path && f.substringBeforeLast('/', "") == dir && "intl" !in f.substringAfterLast('/') &&
                Regex("""\.(h|hpp|c|cpp)$""").containsMatchIn(f)
        }.take(40)
        return CoreTree(sha, path, siblings, files)
    } finally {
        dir.deleteRecursively()
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
