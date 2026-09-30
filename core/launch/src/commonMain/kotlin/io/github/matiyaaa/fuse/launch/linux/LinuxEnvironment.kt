package io.github.matiyaaa.fuse.launch.linux

/**
 * The few file system and process facts [LinuxDetector] needs. The desktop app implements it with real
 * I/O; tests use a fake. Paths are absolute.
 */
interface LinuxEnvironment {
    /** The user's home directory. */
    val homeDir: String

    /** Directories listed in `$PATH`, in order. */
    fun pathDirectories(): List<String>

    /** True when [path] is an existing, executable regular file. */
    fun isExecutable(path: String): Boolean

    /** True when [path] exists (file or directory). */
    fun exists(path: String): Boolean

    /** Names of the entries directly inside [dir]; empty when it is missing or unreadable. */
    fun listFiles(dir: String): List<String>

    /**
     * Installed Flatpak application ids. The desktop app runs `flatpak list --app --columns=application`
     * and passes the output through [LinuxDetector.parseFlatpakList].
     */
    fun flatpakApps(): Set<String>

    /** Text of a small file (a `.desktop` shortcut or id file), or null. */
    fun readText(path: String): String?
}
