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
    /** For a project that tags no releases: the branch its builds come from, read at its head (the commit is the release). */
    val branch: String? = null,
    /** For settings spread over a whole tree: the files read, as patterns, from a sparse checkout at each release. */
    val tree: List<String>? = null,
    /** A release as the program names its own version, where its tag differs (MAME's `mame0289` is 0.289). */
    val releaseName: (String) -> String = { it },
    /** How many releases back it is read; one for daily builds that all say the same version. */
    val reads: Int = RELEASES,
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
    // Azahar's family: Lime3DS (archived; 2119.1 its last), Mandarine (r1.3.0 and the like) and AzaharPlus, whose releases are branches of Azahar's, read at its own branch's head.
    Emulator("lime3ds", "Lime3DS/lime3ds-archive", Regex("""^\d{4}(?:\.\d+)*$"""), Azahar.LIME3DS.files, Azahar.LIME3DS::parse),
    Emulator("mandarine", "mandarine3ds/mandarine", Regex("""^r\d+\.\d+(?:\.\d+)?$"""), Azahar.MANDARINE.files, Azahar.MANDARINE::parse),
    Emulator("azaharplus", "AzaharPlus/AzaharPlus", Regex(".^"), Azahar.AZAHAR.files, Azahar.AZAHAR::parse, branch = "AzaharPlus"),
    // MAME's releases are mame0289 and the like.
    Emulator("mame", "mamedev/mame", Regex("""^mame\d{4}$"""), Mame.FILES, Mame::parse, releaseName = { "0." + it.removePrefix("mame").trimStart('0') }),
    Emulator("flycast", "flyinghead/flycast", Regex("""^v\d+\.\d+(?:\.\d+)?$"""), Flycast.FILES, Flycast::parse),
    // Supermodel's builds are tagged by day and commit (v0.3a-20261018-git-ddbc306), every one 0.3a: its newest is read.
    Emulator("supermodel", "trzy/Supermodel", Regex("""^v\d+\.\d+[a-z]?-\d{8}-git-[0-9a-f]+$"""), Supermodel.FILES, Supermodel::parse, reads = 1),
    // melonDS keeps melonDS.toml from 1.0 (melonDS.ini before), its releases 1.0, 1.1 and on; its release candidates left out.
    Emulator("melonds", "melonDS-emu/melonDS", Regex("""^[1-9]\d*\.\d+(?:\.\d+)?$"""), emptyList(), MelonDs::parse, tree = MelonDs.TREE),
    // Vita3K tags only `continuous`, its rolling build.
    Emulator("vita3k", "Vita3K/Vita3K", Regex("^continuous$"), Vita3K.FILES, Vita3K::parse),
    // Xenia Canary's builds come from its canary_experimental branch; its tags name commits, not releases.
    Emulator("xenia", "xenia-canary/xenia-canary", Regex(".^"), emptyList(), Xenia::parse, branch = "canary_experimental", tree = Xenia.TREE),
    // Citron: yuzu's code as Eden keeps it, its releases named by date (2026-04-27), on GitHub as citron-neo/emulator.
    Emulator("citron", "citron-neo/emulator", Regex("""^\d{4}-\d{2}-\d{2}$"""), Eden.CITRON_FILES, { Eden.parse(it, Eden.CITRON_WORDS) }),
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
        val head = e.branch?.let { b -> headOf(e.git, b) ?: error("${e.family}: no branch $b") }
        val tags = if (head != null) listOf(head) else releases(tagsOf(e.git), e.release).take(e.reads)
        require(tags.isNotEmpty()) { "${e.family}: no release tags found" }
        val perTag = tags.associateWith { tag ->
            val files = e.tree?.let { treeFiles(e.git, tag, it) } ?: e.files.mapNotNull { path -> fetch(e.raw(tag, path))?.let { path to it } }.toMap()
            e.parse(files)
        }
        perTag.forEach { (tag, found) -> println("${e.family} $tag: ${found.size} settings") }
        // A branch's head is named by its commit's first ten digits, as a core's is; a tag as the program names its version.
        fun name(tag: String) = if (head != null) tag.take(10) else e.releaseName(tag)
        val named = tags.map(::name)
        read[e] = named to merge(named, perTag.mapKeys { (k, _) -> name(k) })
    }
    // No sign-in is offered (see [Credentials]).
    val fresh = (read.map { (e, v) -> Triple(e.family, v.first, v.second) } + cores(only))
        .map { (family, tags, settings) -> Triple(family, tags, settings.filterNot { Credentials.held(it.found) }) }
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
    val keptLines = lines.filter { it.substringBefore('\t') !in families && !heldCredential(it) } + fresh.flatMap { (family, _, settings) -> settings.map { TuneWriter.line(family, it) } }
    return TuneWriter.kotlinOf(keptReleases, keptLines)
}

/** Whether a data line (family, file, section, key, kind, ...) is a sign-in's (see [Credentials]). */
fun heldCredential(line: String): Boolean = line.split('\t').let { f -> f.getOrNull(4) == Kind.STRING.name && Credentials.named(f.getOrNull(3).orEmpty()) }

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

/** The files matching [patterns] at [ref] (a tag or commit) in the repository at [git], from a shallow sparse checkout. */
private fun treeFiles(git: String, ref: String, patterns: List<String>): Map<String, String> {
    val dir = java.nio.file.Files.createTempDirectory("tune-tree").toFile()
    try {
        fun run(vararg a: String): Boolean {
            val p = ProcessBuilder(listOf("git") + a).directory(dir).redirectErrorStream(true).start()
            p.inputStream.bufferedReader().readText()
            return p.waitFor() == 0
        }
        if (!run("init", "-q") || !run("remote", "add", "origin", git)) return emptyMap()
        if (!run("fetch", "-q", "--depth", "1", "--filter=blob:none", "origin", ref)) return emptyMap()
        if (!run("sparse-checkout", "set", "--no-cone", *patterns.toTypedArray()) || !run("checkout", "-q", "FETCH_HEAD")) return emptyMap()
        return dir.walkTopDown().filter { it.isFile && ".git" !in it.relativeTo(dir).path.split(java.io.File.separatorChar) }
            .associate { it.relativeTo(dir).invariantSeparatorsPath to it.readText() }
    } finally {
        dir.deleteRecursively()
    }
}

/** The commit at the head of [branch] in the repository at [git]. */
private fun headOf(git: String, branch: String): String? {
    val p = ProcessBuilder("git", "ls-remote", git, "refs/heads/$branch").redirectErrorStream(true).start()
    val text = p.inputStream.bufferedReader().readText()
    return if (p.waitFor() == 0) text.lineSequence().firstOrNull { it.endsWith("refs/heads/$branch") }?.substringBefore('\t')?.trim() else null
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
