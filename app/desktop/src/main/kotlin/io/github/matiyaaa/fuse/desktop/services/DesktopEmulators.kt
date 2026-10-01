package io.github.matiyaaa.fuse.desktop.services

import io.github.matiyaaa.fuse.launch.linux.LinuxDetector
import io.github.matiyaaa.fuse.model.InstalledEmulator
import io.github.matiyaaa.fuse.ui.shell.store.DeviceLocations
import io.github.matiyaaa.fuse.ui.shell.store.EmulatorDetector
import io.github.matiyaaa.fuse.ui.shell.store.LocationHint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Finds Linux emulators with the shared [LinuxDetector] rules over the real system. */
internal class DesktopEmulatorDetector(
    private val env: SystemLinuxEnvironment,
    private val folders: KnownFolders,
) : EmulatorDetector {

    /** The folder of the AppImage Fuse runs from is searched too (emulators often sit next to it). */
    private val extraDirs: List<String> =
        listOfNotNull(System.getenv("APPIMAGE")?.let { File(it).parent }).filter { File(it).isDirectory }

    override suspend fun detect(): List<InstalledEmulator> = withContext(Dispatchers.IO) {
        LinuxDetector.detect(env, extraDirs = extraDirs)
    }

    override fun biosFolders(installed: List<InstalledEmulator>): List<String> =
        (folders.retroArchSystemDirs(installed) + folders.biosRoots()).distinct()

    override fun retroArchCorePath(installed: InstalledEmulator, core: String): String? =
        LinuxDetector.findRetroArchCore(env, installed, core)

    override val homeDir: String get() = env.homeDir
}

/** Library and firmware suggestions for onboarding and Settings. */
internal class DesktopLocations(private val folders: KnownFolders) : DeviceLocations {
    override suspend fun libraryCandidates(): List<LocationHint> = withContext(Dispatchers.IO) {
        folders.romRoots().map { LocationHint(it.path, it.label) }
    }

    override suspend fun biosRoots(): List<String> = withContext(Dispatchers.IO) {
        (folders.biosRoots() + folders.retroArchSystemDirs()).distinct()
    }

    override suspend fun storageRoots(): List<LocationHint> = withContext(Dispatchers.IO) {
        folders.storageRoots().map { LocationHint(it.path, it.label) }
    }
}
