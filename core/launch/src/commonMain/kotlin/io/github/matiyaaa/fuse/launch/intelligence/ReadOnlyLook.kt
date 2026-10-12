package io.github.matiyaaa.fuse.launch.intelligence

/**
 * Everything Fuse Intelligence may do outside Fuse: look. Whether a path is there, a folder, a
 * program that can run, when it last changed, the names in a folder, a program's header (which
 * processor it is built for) and what the processor is. There is no way here to read a ROM, a save
 * or a settings file, write, move, rename or delete anything, so a discovery can never touch a
 * ROM, a save or an emulator's own files: the boundary is this interface, not a promise.
 *
 * Fuse's own knowledge is written by [KnowledgeStore], which only ever holds Fuse's file.
 */
interface ReadOnlyLook {
    /** The person's home folder. */
    val home: String

    /** An environment variable ("XDG_CONFIG_HOME", "APPDATA"), or null. */
    fun env(name: String): String?

    fun exists(path: String): Boolean

    fun isDirectory(path: String): Boolean

    /** True for a file this system would run (a program, an AppImage, a script with its bit set). */
    fun canExecute(path: String): Boolean

    /** When [path] last changed, in milliseconds, or null when it isn't there. Metadata only. */
    fun modified(path: String): Long?

    /** The names in the folder [path] (never what they hold), or nothing. */
    fun names(path: String): List<String>

    /**
     * The first bytes of the program at [path], at most [PROGRAM_HEADER] of them, read-only: its
     * executable header, which says which processor it is built for. Only for a file this system
     * would run (or a Windows program); never a ROM, a save or a settings file. Null otherwise.
     */
    fun programHeader(path: String): ByteArray? = null

    /** This computer's processor, as the system describes it; null where that isn't known. */
    fun processor(): Processor? = null

    companion object {
        /** Enough for an ELF header and a Windows program's PE header. */
        const val PROGRAM_HEADER = 1024
    }
}

/**
 * A processor: its [arch] ("x86_64", "arm64", "x86", "arm") and, where the system lists them, its
 * feature [flags] as Linux names them ("avx2", "sse4_1"); null when they aren't known.
 */
data class Processor(val arch: String, val flags: Set<String>?)

/** Where Fuse keeps what Intelligence learned: Fuse's own file, and nothing else. */
interface KnowledgeStore {
    fun read(): String?

    fun write(text: String)
}
