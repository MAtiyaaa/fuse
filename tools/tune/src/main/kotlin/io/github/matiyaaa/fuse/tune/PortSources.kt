package io.github.matiyaaa.fuse.tune

import java.io.File

/**
 * The Fuse Ports ports Fuse Tune reads, each at the release ports/index.json lists for it (the one
 * Fuse installs), from the repository its homepage names. Each says how its settings are declared
 * ([parse]), which of its files and its submodules' are read, and the port's family in the data is
 * its id, as the list names it.
 */
object PortSources {
    const val INDEX = "ports/index.json"

    class Port(
        val id: String,
        val parse: (Map<String, String>) -> List<Found>,
        val tree: List<String>,
        val submodules: Map<String, List<String>> = emptyMap(),
        /** The repository, where the homepage isn't it. */
        val repo: String? = null,
        /** How its settings file is written, where it isn't ini. */
        val format: String = "ini",
        /** How it writes a switch, on then off, where it isn't `true` and `false`. */
        val switches: Pair<String, String>? = null,
    )

    private fun classic(id: String) = Port(id, N64Recomp.Classic::parse, N64Recomp.CLASSIC_TREE, N64Recomp.CLASSIC_SUBMODULES, format = "json")
    private fun frontend(id: String) = Port(id, N64Recomp.Frontend::parse, N64Recomp.FRONTEND_TREE, N64Recomp.FRONTEND_SUBMODULES, format = "json")

    val PORTS: List<Port> = listOf(
        // N64ModernRuntime's games from Zelda64Recomp's template: their own config.cpp.
        classic("zelda64recomp"),
        classic("dino-recomp"),
        classic("megaman64-recomp"),
        classic("bm64-recomp"),
        classic("goemon64-recomp"),
        classic("dnzh-recomp"),
        classic("mariokart64-recomp"),
        classic("starfox64-recomp"),
        // Those on RecompFrontend's settings tabs.
        frontend("banjorecomp"),
        frontend("sssv-recomp"),
        frontend("harvestmoon64-recomp"),
        frontend("dk64-recomp"),
        frontend("lamborghini-recomp"),
        frontend("aerogauge-recomp"),
        frontend("snowboardkids2-recomp"),
        frontend("battlezone-recomp"),
        frontend("dinotale"),
    )

    /** Each port in [PORTS] the list has, as an [Emulator] read at the one release the list names. */
    fun emulators(index: String? = File(INDEX).takeIf { it.isFile }?.readText()): List<Emulator> {
        val listed = listed(index ?: return emptyList())
        return PORTS.mapNotNull { p ->
            val (tag, homepage) = listed[p.id] ?: return@mapNotNull null.also { println("port ${p.id}: not in $INDEX") }
            val repo = p.repo ?: Regex("""github\.com/([^/]+/[^/#?]+)""").find(homepage)?.groupValues?.get(1)?.removeSuffix(".git")
                ?: return@mapNotNull null.also { println("port ${p.id}: its homepage isn't a GitHub repository") }
            Emulator(p.id, repo, Regex("^" + Regex.escape(tag) + "$"), emptyList(), p.parse, tree = p.tree, submodules = p.submodules, reads = 1, format = p.format, switches = p.switches)
        }
    }

    /** Each port's id in the list to its release tag and homepage. */
    fun listed(index: String): Map<String, Pair<String, String>> {
        val root = MiniJson.parse(index) as? Map<*, *> ?: return emptyMap()
        return (root["ports"] as? List<*>).orEmpty().mapNotNull { p ->
            val port = p as? Map<*, *> ?: return@mapNotNull null
            val id = port["id"] as? String ?: return@mapNotNull null
            // The release's tag, as its downloads name it (the version is shown without its `v`).
            val tag = (port["builds"] as? List<*>).orEmpty().firstNotNullOfOrNull { b ->
                ((b as? Map<*, *>)?.get("url") as? String)?.let { Regex("""/releases/download/([^/]+)/""").find(it)?.groupValues?.get(1) }
                    ?.let { java.net.URLDecoder.decode(it, Charsets.UTF_8) }
            } ?: port["version"] as? String ?: return@mapNotNull null
            id to (tag to (port["homepage"] as? String).orEmpty())
        }.toMap()
    }
}

/** A small JSON reader for the ports list: objects as maps, arrays as lists, numbers as doubles. */
object MiniJson {
    fun parse(text: String): Any? = Reader(text).run { value().also { space(); require(at == text.length) { "JSON: text after the value at $at" } } }

    private class Reader(val s: String) {
        var at = 0

        fun space() {
            while (at < s.length && s[at].isWhitespace()) at++
        }

        fun value(): Any? {
            space()
            require(at < s.length) { "JSON: ends early" }
            return when (s[at]) {
                '{' -> {
                    at++
                    val out = LinkedHashMap<String, Any?>()
                    space()
                    if (s[at] == '}') { at++; return out }
                    while (true) {
                        space()
                        val key = string()
                        space()
                        require(s[at] == ':') { "JSON: expected : at $at" }
                        at++
                        out[key] = value()
                        space()
                        if (s[at] == ',') { at++; continue }
                        require(s[at] == '}') { "JSON: expected } at $at" }
                        at++
                        return out
                    }
                    @Suppress("UNREACHABLE_CODE") out
                }
                '[' -> {
                    at++
                    val out = ArrayList<Any?>()
                    space()
                    if (s[at] == ']') { at++; return out }
                    while (true) {
                        out += value()
                        space()
                        if (s[at] == ',') { at++; continue }
                        require(s[at] == ']') { "JSON: expected ] at $at" }
                        at++
                        return out
                    }
                    @Suppress("UNREACHABLE_CODE") out
                }
                '"' -> string()
                't' -> word("true", true)
                'f' -> word("false", false)
                'n' -> word("null", null)
                else -> {
                    val start = at
                    while (at < s.length && (s[at].isDigit() || s[at] in "+-.eE")) at++
                    s.substring(start, at).toDouble()
                }
            }
        }

        fun word(w: String, v: Any?): Any? {
            require(s.startsWith(w, at)) { "JSON: unexpected text at $at" }
            at += w.length
            return v
        }

        fun string(): String {
            require(s[at] == '"') { "JSON: expected a string at $at" }
            at++
            val out = StringBuilder()
            while (s[at] != '"') {
                val c = s[at++]
                if (c != '\\') { out.append(c); continue }
                when (val e = s[at++]) {
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    'r' -> out.append('\r')
                    'b' -> out.append('\b')
                    'f' -> out.append('\u000c')
                    'u' -> { out.append(s.substring(at, at + 4).toInt(16).toChar()); at += 4 }
                    else -> out.append(e)
                }
            }
            at++
            return out.toString()
        }
    }
}
