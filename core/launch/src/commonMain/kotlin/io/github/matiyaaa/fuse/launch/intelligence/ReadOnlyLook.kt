package io.github.matiyaaa.fuse.launch.intelligence

/**
 * Everything Fuse Intelligence may do outside Fuse: look. Whether a path is there, a folder, a
 * program that can run, when it last changed, and the names in a folder. There is no way here to
 * open a file, read what is in it, write, move, rename or delete anything, so a discovery can never
 * touch a ROM, a save or an emulator's own files: the boundary is this interface, not a promise.
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
}

/** Where Fuse keeps what Intelligence learned: Fuse's own file, and nothing else. */
interface KnowledgeStore {
    fun read(): String?

    fun write(text: String)
}
