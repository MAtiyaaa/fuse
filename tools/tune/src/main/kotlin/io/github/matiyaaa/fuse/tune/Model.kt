package io.github.matiyaaa.fuse.tune

/** A setting's kind of value, as the emulator's code declares it. */
enum class Kind { BOOL, INT, FLOAT, STRING, ENUM }

/**
 * One value an enum is written as: the [value] in the file, what the emulator's code calls it when
 * that differs ([label]), and the systems whose builds have it (null for all).
 */
data class Choice(val value: String, val label: String? = null, val hosts: Set<String>? = null)

/**
 * One setting as an emulator's own settings code declares it at one release: the file it lives in
 * ([file], null for the emulator's main settings file), its [section] and [key], its [kind], the
 * [default] when the code gives one as a plain value (null when it is worked out at run time), the
 * range a number may take, the values an enum is written as (in the order the code lists them),
 * and whether a game's own settings can hold it. Where the code names it for people, its [label],
 * its description ([about]) and the tab or page its own settings show it on ([tab]).
 */
data class Found(
    val file: String?,
    val section: String,
    val key: String,
    val kind: Kind,
    val default: String? = null,
    val min: Long? = null,
    val max: Long? = null,
    val choices: List<Choice> = emptyList(),
    val perGame: Boolean = true,
    /** Its section in a game's own file, when that differs from [section] (Dolphin's `Video_Settings`). */
    val gameSection: String? = null,
    val label: String? = null,
    val about: String? = null,
    val tab: String? = null,
    /** Its key in a game's own file, when that differs from [key] (Vita3K's `enable-ngs` for `ngs-enable`). */
    val gameKey: String? = null,
) {
    /** What makes it the same setting in another release. */
    val identity: String get() = "${file.orEmpty()}\u0000$section\u0000$key"

    companion object {
        /** Joins the parts of a section that is a path (RPCS3's `Video`, then `Vulkan`). */
        const val PATH = "\u001f"
    }
}

/**
 * A sign-in an emulator keeps among its settings (RetroArch's `cheevos_password` and
 * `webdav_password`, Flycast's RetroAchievements `Token`): text whose key names a password, token,
 * secret or key to a service. Fuse never offers one, so none is shown or written by it.
 */
object Credentials {
    private val NAMES = Regex("""(?i)pass(?:word|wd|phrase)|token|secret|api_?key""")

    fun named(key: String): Boolean = NAMES.containsMatchIn(key)

    fun held(f: Found): Boolean = f.kind == Kind.STRING && named(f.key)
}

/** Small pieces of reading C and C++ source as text: no compiler, only what the declarations need. */
internal object Source {
    /**
     * [text] with only the lines a build would compile: each `#if`, `#ifdef`, `#ifndef`, `#elif`
     * and `#else` decided by [truth] for the macros it names (`defined(X)`, a bare `X`, or a call
     * such as `SDL_VERSION_ATLEAST(2, 0, 0)`, given as written without spaces), with `!`, `&&`,
     * `||` and brackets. A macro [truth] doesn't know (null) is taken as not defined. Lines left
     * out become empty, so line numbers stay; the directives themselves go too.
     */
    fun select(text: String, truth: (String) -> Boolean?): String {
        // Each level: whether its own branch is taken, whether one of its branches already was, and whether its parent is live.
        data class Level(var taking: Boolean, var taken: Boolean, val live: Boolean)
        val stack = ArrayList<Level>()
        fun live() = stack.all { it.taking }
        val out = StringBuilder(text.length)
        val lines = text.split('\n')
        var i = 0
        while (i < lines.size) {
            var line = lines[i]
            var consumed = 1
            // A directive continued over lines with a backslash is one directive.
            while (line.trimStart().startsWith("#") && line.endsWith("\\") && i + consumed < lines.size) {
                line = line.dropLast(1) + " " + lines[i + consumed]
                consumed++
            }
            val t = line.trim()
            val directive = if (t.startsWith("#")) t.removePrefix("#").trim() else null
            when {
                directive == null -> out.append(if (live()) lines[i] else "")
                directive.startsWith("ifdef") -> { val parent = live(); val v = truth(directive.removePrefix("ifdef").trim()) == true; stack += Level(parent && v, v, parent) }
                directive.startsWith("ifndef") -> { val parent = live(); val v = truth(directive.removePrefix("ifndef").trim()) != true; stack += Level(parent && v, v, parent) }
                directive.startsWith("if") -> { val parent = live(); val v = condition(directive.removePrefix("if"), truth); stack += Level(parent && v, v, parent) }
                directive.startsWith("elif") -> stack.lastOrNull()?.let { l ->
                    val v = !l.taken && condition(directive.removePrefix("elif"), truth)
                    l.taking = l.live && v
                    if (v) l.taken = true
                }
                directive.startsWith("else") -> stack.lastOrNull()?.let { l -> l.taking = l.live && !l.taken; l.taken = true }
                directive.startsWith("endif") -> if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                else -> out.append(if (live()) lines[i] else "")
            }
            repeat(consumed) { out.append('\n') }
            i += consumed
        }
        if (out.isNotEmpty()) out.setLength(out.length - 1)
        return out.toString()
    }

    /** A preprocessor condition's value, as [select] reads one. */
    fun condition(expression: String, truth: (String) -> Boolean?): Boolean {
        val tokens = Regex("""defined\s*\(\s*\w+\s*\)|defined\s+\w+|\w+\s*\([^()]*\)|\w+|&&|\|\||[!()]""").findAll(expression).map { it.value }.toList()
        var at = 0
        fun atom(t: String): Boolean = when {
            t.startsWith("defined") -> truth(t.removePrefix("defined").trim().trim('(', ')').trim()) == true
            t.toLongOrNull() != null -> t.toLong() != 0L
            else -> truth(t.replace(Regex("""\s+"""), "")) == true
        }
        fun or(): Boolean {
            fun unary(): Boolean {
                val t = tokens.getOrNull(at++) ?: return false
                return when (t) {
                    "!" -> !unary()
                    "(" -> or().also { if (tokens.getOrNull(at) == ")") at++ }
                    else -> atom(t)
                }
            }
            fun and(): Boolean {
                var v = unary()
                while (tokens.getOrNull(at) == "&&") { at++; val r = unary(); v = v && r }
                return v
            }
            var v = and()
            while (tokens.getOrNull(at) == "||") { at++; val r = and(); v = v || r }
            return v
        }
        return or()
    }

    /** [text] without its comments (`//` to the line's end, and block comments), strings kept. */
    fun withoutComments(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        var inString = false
        while (i < text.length) {
            val c = text[i]
            when {
                inString -> {
                    out.append(c)
                    if (c == '\\' && i + 1 < text.length) {
                        out.append(text[i + 1])
                        i++
                    } else if (c == '"') {
                        inString = false
                    }
                }
                c == '"' -> {
                    inString = true
                    out.append(c)
                }
                c == '/' && text.startsWith("//", i) -> {
                    while (i < text.length && text[i] != '\n') i++
                    continue
                }
                c == '/' && text.startsWith("/*", i) -> {
                    val end = text.indexOf("*/", i + 2)
                    // Keep the line breaks, so nothing joins up.
                    text.substring(i, if (end < 0) text.length else end + 2).forEach { if (it == '\n') out.append('\n') }
                    i = if (end < 0) text.length else end + 2
                    continue
                }
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }

    /**
     * The arguments of the call whose opening parenthesis is at [open] in [text], split at the
     * commas between them (not those inside nested brackets or strings), and where it ends.
     */
    fun arguments(text: String, open: Int, opening: Char = '(', closing: Char = ')'): Pair<List<String>, Int>? {
        if (open !in text.indices || text[open] != opening) return null
        val args = ArrayList<String>()
        val cur = StringBuilder()
        var depth = 0
        var i = open + 1
        var inString = false
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                cur.append(c)
                if (c == '\\' && i + 1 < text.length) {
                    cur.append(text[i + 1])
                    i++
                } else if (c == '"') {
                    inString = false
                }
            } else {
                when (c) {
                    '"' -> { inString = true; cur.append(c) }
                    '(', '[', '{' -> { depth++; cur.append(c) }
                    ')', ']', '}' -> {
                        if (depth == 0 && c == closing) {
                            if (cur.isNotBlank() || args.isNotEmpty()) args += cur.toString().trim()
                            return args to i
                        }
                        depth--
                        cur.append(c)
                    }
                    ',' -> if (depth == 0) { args += cur.toString().trim(); cur.clear() } else cur.append(c)
                    else -> cur.append(c)
                }
            }
            i++
        }
        return null
    }

    /** The text of a string literal ("…", with its escapes read), or null for anything else. */
    fun string(arg: String): String? {
        val t = arg.trim()
        if (t.length < 2 || !t.startsWith('"') || !t.endsWith('"')) return null
        val body = t.substring(1, t.length - 1)
        if ('"' in body.replace("\\\"", "")) return null
        return body.replace("\\\"", "\"").replace("\\\\", "\\")
    }

    /** A plain default written in the code: true or false, a number (its suffix taken off), or a string. */
    fun literal(arg: String): String? {
        val t = arg.trim()
        string(t)?.let { return it }
        if (t == "true" || t == "false") return t
        val number = Regex("""^-?(?:\d+\.\d*|\.\d+|\d+)(?:[eE][-+]?\d+)?[fFuUlL]*$""")
        if (number.matches(t)) return t.trimEnd('f', 'F', 'u', 'U', 'l', 'L')
        if (Regex("^0[xX][0-9a-fA-F]+[uUlL]*$").matches(t)) return t.trimEnd('u', 'U', 'l', 'L').substring(2).toBigInteger(16).toString()
        return null
    }

    /** `static constexpr Type NAME = value;` in [text], for names defined exactly once (not once per system). */
    fun constants(text: String): Map<String, String> {
        val seen = HashMap<String, MutableList<String>>()
        for (m in Regex("""static\s+constexpr\s+[\w:<>]+\s+(\w+)\s*=\s*([^;]+);""").findAll(text)) {
            seen.getOrPut(m.groupValues[1]) { mutableListOf() } += m.groupValues[2].trim()
        }
        return seen.filterValues { it.size == 1 }.mapValues { it.value.single() }
    }

    /** [raw] with a constant it names read through [constants] (`DEFAULT_HW_RENDERER` is `GSRendererType::Auto`). */
    fun resolve(raw: String?, constants: Map<String, String>): String? {
        val t = raw?.trim() ?: return null
        if (!Regex("""^(?:\w+::)*\w+$""").matches(t)) return t
        return constants[t.substringAfterLast("::")] ?: t
    }

    /** Members that only count the others (`Count`, `MaxCount`), which no setting is ever given. */
    private val SENTINELS = setOf("Count", "MaxCount", "COUNT")

    /**
     * Enum declarations in [text]: each enum's name to its members in order, with the value each is
     * given (explicit `= N`, or one more than the one before). A member that only counts the others
     * (`Count`) is left out.
     */
    fun enums(text: String): Map<String, List<Pair<String, Long>>> {
        val out = LinkedHashMap<String, List<Pair<String, Long>>>()
        val clean = withoutComments(text)
        for (m in Regex("""enum\s+(?:class\s+|struct\s+)?(\w+)\s*(?::\s*[\w:]+\s*)?\{([^}]*)\}""").findAll(clean)) {
            var next = 0L
            val members = ArrayList<Pair<String, Long>>()
            // Members inside an `#ifdef` are kept (the data says which systems have them elsewhere).
            val body = m.groupValues[2].lines().filterNot { it.trim().startsWith("#") }.joinToString("\n")
            for (part in body.split(',')) {
                val p = part.trim()
                if (p.isEmpty()) continue
                val name = p.substringBefore('=').trim().takeIf { Regex("^\\w+$").matches(it) } ?: continue
                val value = p.substringAfter('=', "").trim().let { v ->
                    v.toLongOrNull() ?: v.removePrefix("0x").toLongOrNull(16)?.takeIf { v.startsWith("0x") } ?: members.firstOrNull { it.first == v }?.second
                } ?: next
                if (name !in SENTINELS) members += name to value
                next = value + 1
            }
            out[m.groupValues[1]] = members
        }
        return out
    }
}
