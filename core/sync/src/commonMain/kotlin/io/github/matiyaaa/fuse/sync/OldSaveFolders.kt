package io.github.matiyaaa.fuse.sync

/**
 * A save folder [query]'s emulator used before, holding the game's save: another install (the
 * Flatpak beside the native one, a portable copy beside an installed one) or a data folder since
 * moved. [inUse] is where the emulator reads the save now; [modifiedAt] and [inUseModifiedAt] are
 * each one's newest file (the latter null when there is no save of it in use yet).
 */
data class OldSave(
    val spot: SaveSpot,
    val inUse: SaveSpot,
    /** The folder it is in, to show the person. */
    val where: String,
    val modifiedAt: Long?,
    val inUseModifiedAt: Long?,
)

/**
 * Finds [OldSave]s by asking the emulator's own save adapter where it would look were the folder in
 * use not there: each folder from the save's own up to the one below the person's home is hidden in
 * turn, and the first that makes the adapter answer with another folder is the next install it
 * knows. Only folders that hold this game's save, newer than the one in use (or with none in use),
 * are offered. Reads only; nothing moves.
 */
object OldSaveFolders {
    /** The most folders offered for one game. */
    private const val MOST = 4

    /** The most times an adapter is asked for one game (each ask may search storage). */
    private const val ASKS = 24

    fun find(query: SaveQuery, env: SaveEnvironment): List<OldSave> {
        val adapter = SaveAdapters.forEmulator(query.emulatorId) ?: return emptyList()
        val out = ArrayList<OldSave>()
        var asks = 0
        fun ask(e: SaveEnvironment): List<SaveSpot> {
            asks++
            return runCatching { adapter.locate(query, e) }.getOrDefault(emptyList())
        }
        for (inUse in ask(env).filter(::movable)) {
            val start = baseOf(inUse) ?: continue
            val inUseAt = newest(inUse, env)
            val hidden = ArrayList<String>()
            val seen = hashSetOf(norm(start, env))
            var from = start
            search@ while (out.size < MOST && asks < ASKS) {
                for (level in levels(from, env)) {
                    if (asks >= ASKS) break@search
                    val shadow = WithoutFolders(env, hidden + level)
                    val other = ask(shadow).firstOrNull { it.kind == inUse.kind && it.format == inUse.format && movable(it) } ?: continue
                    val base = baseOf(other) ?: continue
                    if (norm(base, env) in seen || shadow.hides(base) || !env.isDirectory(base)) continue
                    seen += norm(base, env)
                    hidden += level
                    from = base
                    val at = newest(other, env)
                    if (at != null && (inUseAt == null || at > inUseAt)) out += OldSave(other, inUse, base, at, inUseAt)
                    continue@search
                }
                break
            }
        }
        return out
    }

    /** A save Fuse can copy whole: its files named, or its folders known; not one it can't reach. */
    private fun movable(s: SaveSpot) = s.kind == SaveKind.SAVE && s.available && s.mounts.isEmpty() && s.learnIn == null && (s.root != null || s.files.isNotEmpty())

    /** The folder a save is in: its root, or its first file's folder. */
    private fun baseOf(s: SaveSpot): String? = (s.root ?: s.files.firstOrNull()?.path?.let(SaveAdapters::parent))?.replace('\\', '/')?.trimEnd('/')

    /** When the game's save in [s] last changed, or null when there is none of it there. */
    private fun newest(s: SaveSpot, env: SaveEnvironment): Long? {
        val root = s.root
        val times = if (root != null) {
            s.folders.ifEmpty { listOf("") }.map { if (it.isEmpty()) root else "${root.trimEnd('/')}/$it" }
                .filter { env.isDirectory(it) && env.list(it).isNotEmpty() }.map { env.modified(it) ?: 0L }
        } else {
            s.files.filter { env.exists(it.path) && !env.isDirectory(it.path) }.map { env.modified(it.path) ?: 0L }
        }
        return times.maxOrNull()
    }

    /** [from] and the folders above it, up to (never including) the person's home, a storage root or a drive. */
    private fun levels(from: String, env: SaveEnvironment): List<String> {
        val stops = (listOf(env.home) + env.storageRoots()).map { norm(it, env) }.toSet()
        val out = ArrayList<String>()
        var at: String? = from.trimEnd('/')
        while (at != null && out.size < 6) {
            val n = norm(at, env)
            if (n in stops || n.count { it == '/' } < 2) break
            out += at
            at = at.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }
        }
        return out
    }

    internal fun norm(path: String, env: SaveEnvironment): String =
        path.replace('\\', '/').trimEnd('/').let { if (env.host == "WINDOWS") it.lowercase() else it }
}

/**
 * [base] as though the folders in [hidden] (and all inside them) weren't there: nothing in them
 * exists, lists or reads, a save folder chosen in one isn't chosen, and nothing is remembered, so
 * an adapter asked through it answers with where else it would look.
 */
internal class WithoutFolders(private val base: SaveEnvironment, hidden: List<String>) : SaveEnvironment by base {
    private val hidden = hidden.map { OldSaveFolders.norm(it, base) }

    fun hides(path: String): Boolean {
        val p = OldSaveFolders.norm(path, base)
        return hidden.any { p == it || p.startsWith("$it/") }
    }

    override fun exists(path: String) = !hides(path) && base.exists(path)
    override fun isDirectory(path: String) = !hides(path) && base.isDirectory(path)
    override fun list(path: String) = if (hides(path)) emptyList() else base.list(path).filterNot { hides("${path.trimEnd('/', '\\')}/$it") }
    override fun readText(path: String, limit: Int) = if (hides(path)) null else base.readText(path, limit)
    override fun readBytes(path: String, offset: Long, length: Int) = if (hides(path)) null else base.readBytes(path, offset, length)
    override fun modified(path: String) = if (hides(path)) null else base.modified(path)
    override fun saveFolder(emulator: String) = base.saveFolder(emulator)?.takeUnless(::hides)
    override fun learned(game: String, format: String) = base.learned(game, format).filterNot(::hides)
    override fun storageRoots() = base.storageRoots().filterNot(::hides)
    override fun remember(key: String, compute: () -> List<String>) = compute()
}
