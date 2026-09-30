package io.github.matiyaaa.fuse.launch

import io.github.matiyaaa.fuse.launch.linux.LinuxEnvironment
import io.github.matiyaaa.fuse.model.ChildContent
import io.github.matiyaaa.fuse.model.Disc
import io.github.matiyaaa.fuse.model.EmulatorId
import io.github.matiyaaa.fuse.model.FilenameTags
import io.github.matiyaaa.fuse.model.FolderInterpretation
import io.github.matiyaaa.fuse.model.Game
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.GameLocation
import io.github.matiyaaa.fuse.model.GameTitles
import io.github.matiyaaa.fuse.model.Host
import io.github.matiyaaa.fuse.model.InstalledEmulator
import io.github.matiyaaa.fuse.model.LibrarySourceId
import io.github.matiyaaa.fuse.model.LocationKind
import io.github.matiyaaa.fuse.model.PlatformId

internal const val ROMS = "/storage/emulated/0/ROMs"

internal fun game(
    platform: String,
    path: String,
    kind: LocationKind = LocationKind.FILE,
    launchPath: String = path,
    interpretation: FolderInterpretation = if (kind == LocationKind.FILE) FolderInterpretation.SINGLE_FILE else FolderInterpretation.FOLDER_IS_GAME,
    discs: List<Disc> = emptyList(),
    content: List<ChildContent> = emptyList(),
    serial: String? = null,
    override: String? = null,
    title: String = Paths.baseName(path),
) = Game(
    id = GameId(1),
    platformId = PlatformId(platform),
    titles = GameTitles(original = title),
    location = GameLocation(LibrarySourceId(1), path, kind, launchPath, interpretation = interpretation),
    content = content,
    discs = discs,
    tags = FilenameTags(serial = serial),
    emulatorOverride = override?.let(::EmulatorId),
)

internal fun androidEmu(id: String, appId: String, family: Boolean = false) = InstalledEmulator(
    id = EmulatorId(id), name = id, host = Host.ANDROID, appId = appId, platforms = emptySet(),
    detectedVia = "Package", isFamilyMatch = family,
)

internal fun linuxEmu(id: String, appId: String, via: String = "PATH") = InstalledEmulator(
    id = EmulatorId(id), name = id, host = Host.LINUX, appId = appId, platforms = emptySet(), detectedVia = via,
)

/** In-memory Linux file system for detector tests. */
internal class FakeLinux(
    override val homeDir: String = "/home/u",
    private val path: List<String> = listOf("/usr/local/bin", "/usr/bin"),
    private val executables: Set<String> = emptySet(),
    private val files: Set<String> = emptySet(),
    private val dirs: Map<String, List<String>> = emptyMap(),
    private val flatpaks: Set<String> = emptySet(),
    private val texts: Map<String, String> = emptyMap(),
) : LinuxEnvironment {
    override fun pathDirectories() = path
    override fun isExecutable(path: String) = path in executables
    override fun exists(path: String) = path in executables || path in files || path in dirs
    override fun listFiles(dir: String) = dirs[dir].orEmpty()
    override fun flatpakApps() = flatpaks
    override fun readText(path: String) = texts[path]
}
