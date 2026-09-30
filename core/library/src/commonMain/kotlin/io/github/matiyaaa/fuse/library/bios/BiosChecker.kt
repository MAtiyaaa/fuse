package io.github.matiyaaa.fuse.library.bios

import io.github.matiyaaa.fuse.library.FsAccessException
import io.github.matiyaaa.fuse.library.FsEntry
import io.github.matiyaaa.fuse.library.FsPath
import io.github.matiyaaa.fuse.library.FuseFileSystem
import io.github.matiyaaa.fuse.model.BiosFile
import io.github.matiyaaa.fuse.model.BiosRequirement
import io.github.matiyaaa.fuse.model.BiosState
import io.github.matiyaaa.fuse.model.BiosStatus
import io.github.matiyaaa.fuse.model.LibrarySource
import io.github.matiyaaa.fuse.model.LibrarySourceKind
import io.github.matiyaaa.fuse.model.Platform
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.cancellation.CancellationException

/**
 * Checks whether the firmware a platform needs is present. Never claims firmware is missing when it
 * could not look everywhere: unreadable locations make the answer [BiosState.UNKNOWN].
 *
 * Matching: a file matches a [BiosFile] when its name equals the name or one of the aliases,
 * ignoring case. A name containing `*` or `?` is a glob ("SCPH-*.bin"). [BiosFile.minSize] rejects
 * smaller files. When [BiosFile.md5] is set the file is hashed and must match; if the file system
 * cannot hash, the file is accepted by name and the note says it was not verified.
 *
 * States:
 * - [BiosState.NOT_REQUIRED]: the platform needs no firmware, or it is optional and none was found.
 * - [BiosState.UNKNOWN]: firmware lives inside the emulator (PS3, Vita, Switch), a relevant folder
 *   could not be read, or there was no existing folder to look in.
 * - [BiosState.READY]: at least [BiosRequirement.requiredCount] files were found (and verified).
 * - [BiosState.PARTIAL]: some, but not enough, were found and every location was readable
 *   (or the requirement is optional).
 * - [BiosState.MISSING]: every location was readable and nothing usable was found.
 *
 * @param searchDepth How many folder levels below each root are searched (RetroArch keeps some
 *   firmware in subfolders such as `system/dc/` or `system/pcsx2/bios/`).
 */
class BiosChecker(private val fs: FuseFileSystem, private val searchDepth: Int = 2) {
    /**
     * @param searchRoots Folders to look in (see [BiosSearchPaths.forPlatform]). Missing ones are skipped.
     * @param unreadable Paths known to be unreadable, for example other apps' `Android/data`
     *   folders on Android 11+. Anything at or below them counts as unreadable.
     */
    suspend fun check(platform: Platform, searchRoots: List<String>, unreadable: Set<String> = emptySet()): BiosStatus {
        val requirement = platform.bios ?: return BiosStatus.NotRequired
        val located = LinkedHashMap<BiosFile, MutableList<FsEntry>>()
        val searched = ArrayList<String>()
        val blocked = LinkedHashSet<String>()

        for (root in searchRoots.map(FsPath::normalize).distinct()) {
            currentCoroutineContext().ensureActive()
            if (isUnreadable(root, unreadable)) {
                blocked += root
                continue
            }
            val entry = fs.stat(root) ?: continue
            if (!entry.isDirectory) continue
            searched += root
            search(root, 0, requirement, unreadable, located, blocked)
        }

        val notes = ArrayList<String>()
        val found = ArrayList<String>()
        val unsatisfied = ArrayList<BiosFile>()
        for (file in requirement.files) {
            val accepted = accept(file, located[file].orEmpty(), notes)
            if (accepted != null) found += accepted.path else unsatisfied += file
        }
        val satisfied = requirement.files.size - unsatisfied.size
        val enough = satisfied >= requirement.requiredCount
        val missing = if (enough) emptyList() else unsatisfied.map { it.name }

        fun status(state: BiosState, vararg extra: String?) = BiosStatus(
            state = state,
            found = found,
            missing = missing,
            searched = searched + blocked.map { "$it (not readable)" },
            note = (listOfNotNull(*extra) + notes).joinToString(" ").ifBlank { null },
        )

        val blockedNote = if (blocked.isNotEmpty()) {
            "Fuse could not read ${blocked.joinToString()}, so it cannot tell whether the firmware is there."
        } else {
            null
        }
        return when {
            requirement.installedInEmulator && requirement.optional -> status(BiosState.NOT_REQUIRED, requirement.hint)
            requirement.installedInEmulator -> status(
                BiosState.UNKNOWN,
                requirement.hint,
                if (found.isNotEmpty()) "Found ${found.joinToString { FsPath.name(it) }}; install it from inside the emulator." else null,
            )
            enough -> status(BiosState.READY)
            requirement.optional && satisfied == 0 -> status(BiosState.NOT_REQUIRED, "Optional. ${requirement.hint}")
            requirement.optional -> status(BiosState.PARTIAL, requirement.hint)
            blocked.isNotEmpty() -> status(BiosState.UNKNOWN, blockedNote, requirement.hint)
            searched.isEmpty() -> status(BiosState.UNKNOWN, "No BIOS folder to check. Add your BIOS folder in Settings.", requirement.hint)
            satisfied > 0 -> status(BiosState.PARTIAL, requirement.hint)
            else -> status(BiosState.MISSING, requirement.hint)
        }
    }

    private suspend fun search(
        dir: String,
        depth: Int,
        requirement: BiosRequirement,
        unreadable: Set<String>,
        located: MutableMap<BiosFile, MutableList<FsEntry>>,
        blocked: MutableSet<String>,
    ) {
        currentCoroutineContext().ensureActive()
        val entries = try {
            fs.list(dir)
        } catch (e: CancellationException) {
            throw e
        } catch (e: FsAccessException) {
            blocked += dir
            return
        } catch (e: Exception) {
            blocked += dir
            return
        }
        for (entry in entries) {
            if (entry.isDirectory) {
                if (entry.name.startsWith(".") || depth >= searchDepth) continue
                if (isUnreadable(entry.path, unreadable)) {
                    blocked += FsPath.normalize(entry.path)
                    continue
                }
                search(entry.path, depth + 1, requirement, unreadable, located, blocked)
            } else {
                for (file in requirement.files) {
                    if (matchesName(file, entry.name)) located.getOrPut(file) { mutableListOf() } += entry
                }
            }
        }
    }

    /** The first candidate that passes size and checksum checks, noting why others failed. */
    private suspend fun accept(file: BiosFile, candidates: List<FsEntry>, notes: MutableList<String>): FsEntry? {
        var rejected: String? = null
        for (candidate in candidates) {
            val min = file.minSize
            if (min != null && candidate.sizeBytes in 1 until min) {
                rejected = "${candidate.name} is too small to be a real dump."
                continue
            }
            if (file.md5.isEmpty()) return candidate
            val md5 = fs.md5(candidate.path)?.lowercase()
            when {
                md5 == null -> {
                    notes += "${candidate.name} was found but its checksum could not be verified."
                    return candidate
                }
                md5 in file.md5 -> return candidate
                else -> rejected = "${candidate.name} has an unexpected checksum (not a known good dump; some emulators may still accept it)."
            }
        }
        rejected?.let { notes += it }
        return null
    }

    private fun isUnreadable(path: String, unreadable: Set<String>) = unreadable.any { FsPath.isWithin(path, it) }

    internal companion object {
        fun matchesName(file: BiosFile, name: String): Boolean =
            (listOf(file.name) + file.aliases).any { pattern -> nameMatches(pattern, name) }

        private fun nameMatches(pattern: String, name: String): Boolean {
            if ('*' !in pattern && '?' !in pattern) return pattern.equals(name, ignoreCase = true)
            val regex = buildString {
                append('^')
                for (c in pattern) {
                    when (c) {
                        '*' -> append(".*")
                        '?' -> append('.')
                        else -> append(Regex.escape(c.toString()))
                    }
                }
                append('$')
            }
            return Regex(regex, RegexOption.IGNORE_CASE).matches(name)
        }
    }
}

/** Builds the list of folders [BiosChecker] searches for one platform. */
object BiosSearchPaths {
    /**
     * @param biosRoots Configured BIOS folders, ES-DE's `BIOS/`, RetroArch's `system/`.
     * @param librarySources RomM libraries and ROM roots: `bios/{platform}` (Structure A) and
     *   `{platform}/bios` (Structure B) are added for the platform's id and folder aliases, next
     *   to the source and next to a `roms/` source.
     * @param emulatorFolders Emulator-specific BIOS folders the launch layer knows about.
     */
    fun forPlatform(
        platform: Platform,
        biosRoots: List<String> = emptyList(),
        librarySources: List<LibrarySource> = emptyList(),
        emulatorFolders: List<String> = emptyList(),
    ): List<String> {
        val names = (listOf(platform.id.value) + platform.folderAliases).distinct()
        val paths = ArrayList<String>()
        paths += biosRoots
        for (source in librarySources.filter { it.enabled }) {
            val base = FsPath.normalize(source.path)
            val libraryRoots = buildList {
                add(base)
                // A ROM root that is RomM's roms/ folder, or a platform folder inside it.
                if (FsPath.name(base).equals("roms", ignoreCase = true)) FsPath.parent(base)?.let(::add)
                if (source.kind == LibrarySourceKind.PLATFORM_FOLDER) {
                    FsPath.parent(base)?.let { parent ->
                        add(parent)
                        FsPath.parent(parent)?.let(::add)
                    }
                }
            }
            for (root in libraryRoots) {
                for (name in names) {
                    paths += FsPath.join(root, "bios", name)
                    paths += FsPath.join(root, name, "bios")
                }
            }
        }
        paths += emulatorFolders
        return paths.map(FsPath::normalize).distinct()
    }
}
