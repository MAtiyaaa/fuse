package io.github.matiyaaa.fuse.desktop

import io.github.matiyaaa.fuse.desktop.services.DesktopStoreInstaller
import io.github.matiyaaa.fuse.desktop.system.DesktopOs
import io.github.matiyaaa.fuse.integrations.obtainium.DesktopAssetKind
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Tarballs and 7-Zip archives from the Store, as emulators publish them for Linux. */
class LinuxArchivesTest {
    private val home = Files.createTempDirectory("fuse-archives").toFile()
    private val installer = DesktopStoreInstaller(DesktopOs.LINUX, home, File(home, ".cache/fuse/store-downloads"))
    private val apps = File(home, "Applications")

    @AfterTest
    fun tearDown() {
        home.deleteRecursively()
    }

    /** A tarball of [files] (name to (text, runnable)), compressed with [compress]. */
    private fun tar(name: String, files: Map<String, Pair<String, Boolean>>, compress: (OutputStream) -> OutputStream): File =
        File(home, name).also { f ->
            TarArchiveOutputStream(compress(f.outputStream().buffered())).use { t ->
                t.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                for ((path, content) in files) {
                    val bytes = content.first.toByteArray()
                    val e = TarArchiveEntry(path)
                    e.size = bytes.size.toLong()
                    e.mode = if (content.second) "100755".toInt(8) else "100644".toInt(8)
                    t.putArchiveEntry(e)
                    t.write(bytes)
                    t.closeArchiveEntry()
                }
            }
        }

    @Test
    fun aProgramFolderGoesInWholeWithItsProgramRunnable(): Unit = runBlocking {
        // DOSBox Staging's tarball: one folder, the program "dosbox" beside its resources.
        val archive = tar("dosbox-staging-linux-x86_64-v0.83.0.tar.xz", mapOf(
            "dosbox-staging-linux-x86_64-v0.83.0/dosbox" to ("program" to true),
            "dosbox-staging-linux-x86_64-v0.83.0/resources/a.txt" to ("res" to false),
            "dosbox-staging-linux-x86_64-v0.83.0/install-icons.sh" to ("script" to true),
        )) { XZCompressorOutputStream(it) }
        val path = installer.install("DOSBox Staging", archive.absolutePath, archive.name, DesktopAssetKind.TAR, null)
        assertEquals(File(apps, "DOSBox Staging/dosbox").absolutePath, path)
        assertTrue(File(path).canExecute())
        assertTrue(File(apps, "DOSBox Staging/resources/a.txt").isFile)
        assertFalse(File(apps, ".DOSBox Staging.part").exists())
    }

    @Test
    fun anAppImageInATarballIsTakenOut(): Unit = runBlocking {
        // Mandarine's Linux build: a tarball holding its AppImage.
        val archive = tar("mandarine-linux-appimage-20260415-3f023d0.tar.gz", mapOf(
            "mandarine-linux-appimage/mandarine-qt.AppImage" to ("image" to false),
            "mandarine-linux-appimage/README.md" to ("read me" to false),
        )) { GzipCompressorOutputStream(it) }
        val path = installer.install("Mandarine", archive.absolutePath, archive.name, DesktopAssetKind.TAR, null)
        assertEquals(File(apps, "mandarine-qt.AppImage").absolutePath, path)
        assertTrue(File(path).canExecute())
    }

    @Test
    fun retroArchsSevenZipGivesItsAppImage(): Unit = runBlocking {
        val archive = File(home, "RetroArch.7z")
        SevenZOutputFile(archive).use { z ->
            val dir = z.createArchiveEntry(File(home, "x").apply { mkdirs() }, "RetroArch-Linux-x86_64")
            z.putArchiveEntry(dir)
            z.closeArchiveEntry()
            val src = File(home, "ra").apply { writeText("appimage") }
            z.putArchiveEntry(z.createArchiveEntry(src, "RetroArch-Linux-x86_64/RetroArch-Linux-x86_64.AppImage"))
            z.write(src.readBytes())
            z.closeArchiveEntry()
        }
        val path = installer.install("RetroArch", archive.absolutePath, archive.name, DesktopAssetKind.SEVEN_ZIP, null)
        assertEquals(File(apps, "RetroArch-Linux-x86_64.AppImage").absolutePath, path)
        assertEquals("appimage", File(path).readText())
        assertTrue(File(path).canExecute())
    }

    @Test
    fun nothingLandsOutsideItsFolder(): Unit = runBlocking {
        val archive = tar("evil.tar.gz", mapOf("../../escape" to ("x" to true))) { GzipCompressorOutputStream(it) }
        assertFailsWith<Exception> { installer.install("Evil", archive.absolutePath, archive.name, DesktopAssetKind.TAR, null) }
        assertFalse(File(home.parentFile, "escape").exists())
        assertFalse(File(apps, "Evil").exists())
    }
}
