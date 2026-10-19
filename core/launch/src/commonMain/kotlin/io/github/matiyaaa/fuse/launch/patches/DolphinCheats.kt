package io.github.matiyaaa.fuse.launch.patches

/**
 * Dolphin's cheat codes, read and written as Dolphin keeps them in a game's own settings file
 * (`GameSettings/<game id>.ini`), from its own code:
 *
 * - `[Gecko]` (`Core/GeckoCodeConfig.cpp`, `LoadCodes`): a line starting with `$` begins a code
 *   (`$Name [Creator]`), `*` lines are its notes, and the rest are its lines, two eight-digit
 *   hexadecimal numbers each; empty and `#` lines are skipped. `[Gecko_Enabled]` lists the codes
 *   turned on, as `$Name`.
 * - `[ActionReplay]` (`Core/ActionReplay.cpp`, `LoadCodes` and `DeserializeLine`): `$Name`, then
 *   lines that are either decrypted (`XXXXXXXX YYYYYYYY`) or encrypted (`XXXX-XXXX-XXXXX`).
 * - A game's own `[Core] EnableCheats` is Dolphin's Enable Cheats for that game alone
 *   (`ConfigLoaders/GameConfigLoader.cpp` maps the game file's `[Core]` to its own Core settings).
 *
 * Dolphin's ini reader (`Common/IniFile.cpp`) keeps `$`, `*` and plain lines as they are and adds
 * a section met again to the one before, so codes can go in as one block at the end of the file and
 * come out again exactly. Pure text.
 */
object DolphinCheats {
    enum class Kind(val section: String, val label: String) { GECKO("Gecko", "Gecko"), ACTION_REPLAY("ActionReplay", "Action Replay") }

    /** One code: its [name] line as written after `$`, its notes and its lines. */
    data class Code(val kind: Kind, val name: String, val notes: List<String>, val lines: List<String>)

    /** What a file held: the codes Dolphin can read, and lines left out (with why). */
    data class Read(val codes: List<Code>, val rejected: List<String>)

    private val GECKO_LINE = Regex("^[0-9A-Fa-f]{8} [0-9A-Fa-f]{8}$")
    private val ENCRYPTED_LINE = Regex("^[0-9A-Za-z]{4}-[0-9A-Za-z]{4}-[0-9A-Za-z]{5}$")

    /**
     * The codes in [text]: its `[Gecko]` and `[ActionReplay]` sections, or, in a file with no
     * sections at all, `$`-named codes read as Gecko codes (as code sites list them). Other
     * sections are ignored; a code with no line Dolphin reads is left out.
     */
    fun parse(text: String): Read {
        val codes = ArrayList<Code>()
        val rejected = ArrayList<String>()
        val lines = text.lineSequence().map { it.removePrefix("﻿").trimEnd('\r').trim() }.toList()
        val sectioned = lines.any { it.startsWith("[") && it.endsWith("]") }
        var kind: Kind? = if (sectioned) null else Kind.GECKO
        var name: String? = null
        var notes = ArrayList<String>()
        var body = ArrayList<String>()
        fun close() {
            val n = name ?: return
            val k = kind ?: return
            if (body.isEmpty()) rejected += "$$n: no code lines Dolphin reads" else codes += Code(k, n, notes, body)
            name = null
            notes = ArrayList()
            body = ArrayList()
        }
        for (line in lines) {
            if (line.isEmpty() || line.startsWith("#")) continue
            if (line.startsWith("[") && line.endsWith("]")) {
                close()
                kind = when (line.substring(1, line.length - 1)) {
                    Kind.GECKO.section -> Kind.GECKO
                    Kind.ACTION_REPLAY.section -> Kind.ACTION_REPLAY
                    else -> null
                }
                continue
            }
            val k = kind ?: continue
            when {
                line.startsWith("+$") || line.startsWith("$") -> {
                    close()
                    name = line.removePrefix("+").removePrefix("$").trim().takeIf { it.isNotEmpty() }
                }
                name == null -> rejected += "$line: not part of a named code"
                line.startsWith("*") && k == Kind.GECKO -> notes += line.removePrefix("*")
                k == Kind.GECKO && GECKO_LINE.matches(line) -> body += line.uppercase()
                k == Kind.ACTION_REPLAY && (GECKO_LINE.matches(line) || ENCRYPTED_LINE.matches(line)) -> body += line.uppercase()
                else -> rejected += "$line: not a ${k.label} code line"
            }
        }
        close()
        return Read(codes, rejected)
    }

    /** The names of the codes of [kind] the game's settings file [ini] already has. */
    fun namesIn(ini: String, kind: Kind): Set<String> {
        var inside = false
        val out = HashSet<String>()
        for (raw in ini.lineSequence()) {
            val line = raw.trimEnd('\r').trim()
            if (line.startsWith("[") && line.endsWith("]")) {
                inside = line == "[${kind.section}]"
                continue
            }
            if (inside && line.startsWith("$")) out += line.removePrefix("$").trim()
        }
        return out
    }

    /** The name Dolphin's enabled list uses for [code]: its name without the `[Creator]` part. */
    fun shortName(code: Code): String = code.name.substringBefore('[').trim()

    /**
     * The block added to the end of the game's settings file for [codes]: each kind's codes in its
     * section, off (Dolphin's game properties turn each on), and Enable Cheats for this game.
     */
    fun block(codes: List<Code>): String = buildString {
        for (kind in Kind.entries) {
            val these = codes.filter { it.kind == kind }
            if (these.isEmpty()) continue
            appendLine("[${kind.section}]")
            for (c in these) {
                appendLine("$" + c.name)
                for (n in c.notes) appendLine("*$n")
                for (l in c.lines) appendLine(l)
            }
        }
        appendLine("[Core]")
        appendLine("EnableCheats = True")
    }.trimEnd('\n')
}
