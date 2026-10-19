package io.github.matiyaaa.fuse.desktop

import io.github.matiyaaa.fuse.desktop.services.DesktopPortFiles
import io.github.matiyaaa.fuse.desktop.system.DesktopOs
import io.github.matiyaaa.fuse.desktop.system.fusePath
import io.github.matiyaaa.fuse.integrations.ports.PortsIndexReader
import io.github.matiyaaa.fuse.model.PortFileKind
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopPortFilesTest {
    private val dir = Files.createTempDirectory("fuse-port-files").toFile()
    private val data = File(dir, "data")
    private val person = File(dir, "Games/Diablo").apply { mkdirs() }

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun files(os: DesktopOs = DesktopOs.current) = DesktopPortFiles(data, File(dir, "downloads"), os, findUmu = { null })

    @Test
    fun theListInsideFuseIsCarriedAndEveryPortInItPassesItsChecks() {
        val text = assertNotNull(files().bundledIndex(), "ports/index.json is in the build")
        val list = PortsIndexReader.read(text).getOrThrow()
        assertEquals(emptyMap(), list.skipped, "no port in Fuse's own list fails a check")
        assertEquals(listOf("devilutionx", "openrct2", "fallout2-ce", "zelda64recomp"), list.index.ports.map { it.id })
        for (p in list.index.ports) {
            assertTrue(p.builds.any { it.os == "linux" }, "${p.id} runs on Linux")
            // Windows too, unless the port keeps its files where Fuse doesn't set it up there, and says so.
            assertTrue(p.builds.any { it.os == "windows" } || p.notes.any { "Linux only" in it }, "${p.id} runs on Windows or says why not")
            assertTrue(p.builds.all { it.verified == null }, "only the nightly job says a build is verified")
            assertTrue(p.source.isNotBlank() && p.data.isNotEmpty(), "${p.id} says where its facts are from and what it needs")
        }
    }

    @Test
    fun archivesUnpackWithTheirRunnableBitsAndNothingOutsideTheirFolder(): Unit = runBlocking {
        val f = files()
        val zip = File(dir, "port.zip")
        ZipArchiveOutputStream(zip).use { z ->
            z.putArchiveEntry(ZipArchiveEntry("port/run").apply { unixMode = "100755".toInt(8) })
            z.write("#!/bin/sh\n".toByteArray())
            z.closeArchiveEntry()
            z.putArchiveEntry(ZipArchiveEntry("port/engine.dat").apply { unixMode = "100644".toInt(8) })
            z.write(ByteArray(8))
            z.closeArchiveEntry()
        }
        val into = File(f.folder, "zipport/build.new")
        f.unpack(zip.path, PortFileKind.ZIP, "port.zip", into.path)
        assertTrue(File(into, "port/run").isFile && File(into, "port/engine.dat").isFile)
        if (DesktopOs.current != DesktopOs.WINDOWS) {
            assertTrue(File(into, "port/run").canExecute())
            assertFalse(File(into, "port/engine.dat").canExecute())
        }

        val tar = File(dir, "port.tar.gz")
        TarArchiveOutputStream(GzipCompressorOutputStream(tar.outputStream())).use { t ->
            val body = "#!/bin/sh\n".toByteArray()
            t.putArchiveEntry(TarArchiveEntry("game").apply { size = body.size.toLong(); mode = "100755".toInt(8) })
            t.write(body)
            t.closeArchiveEntry()
        }
        val tarInto = File(f.folder, "tarport/build.new")
        f.unpack(tar.path, PortFileKind.TAR_GZ, "port.tar.gz", tarInto.path)
        assertTrue(File(tarInto, "game").isFile)
        if (DesktopOs.current != DesktopOs.WINDOWS) assertTrue(File(tarInto, "game").canExecute())

        // A zip holding one tarball, as a project's build artifacts wrap one: the tarball unpacked, then gone.
        val wrapped = File(dir, "wrapped.zip")
        ZipArchiveOutputStream(wrapped).use { z ->
            z.putArchiveEntry(ZipArchiveEntry("Game.tar.gz").apply { unixMode = "100644".toInt(8) })
            z.write(tar.readBytes())
            z.closeArchiveEntry()
        }
        val wrappedInto = File(f.folder, "wrappedport/build.new")
        f.unpack(wrapped.path, PortFileKind.ZIP_TAR_GZ, "wrapped.zip", wrappedInto.path)
        assertTrue(File(wrappedInto, "game").isFile)
        if (DesktopOs.current != DesktopOs.WINDOWS) assertTrue(File(wrappedInto, "game").canExecute())
        assertFalse(File(dir, "wrapped.zip.tar.gz").exists(), "the tarball taken out is gone")
        assertFailsWith<java.io.IOException>("a zip of anything else") { f.unpack(zip.path, PortFileKind.ZIP_TAR_GZ, "port.zip", File(f.folder, "notwrapped/build.new").path) }

        val image = File(dir, "Port-x86_64.AppImage").apply { writeBytes(ByteArray(16)) }
        val imageInto = File(f.folder, "imageport/build.new")
        f.unpack(image.path, PortFileKind.APPIMAGE, image.name, imageInto.path)
        assertTrue(File(imageInto, image.name).isFile)
        if (DesktopOs.current != DesktopOs.WINDOWS) assertTrue(File(imageInto, image.name).canExecute())

        val sly = File(dir, "sly.zip")
        ZipArchiveOutputStream(sly).use { z ->
            z.putArchiveEntry(ZipArchiveEntry("../../escaped.txt"))
            z.write("x".toByteArray())
            z.closeArchiveEntry()
        }
        assertFailsWith<java.io.IOException> { f.unpack(sly.path, PortFileKind.ZIP, "sly.zip", File(f.folder, "sly/build.new").path) }
        assertFalse(File(f.folder, "escaped.txt").exists() || File(dir, "escaped.txt").exists())
        assertFailsWith<java.io.IOException>("never into a folder that has things in it") { f.unpack(zip.path, PortFileKind.ZIP, "port.zip", into.path) }
    }

    @Test
    fun theGamesFilesAreLinkedAndRemovingNeverFollowsALinkOut(): Unit = runBlocking {
        val f = files()
        File(person, "DIABDAT.MPQ").writeBytes(ByteArray(64) { 3 })
        File(person, "music").mkdirs()
        File(person, "music/theme.wav").writeBytes(ByteArray(8) { 4 })
        val build = File(f.folder, "devilutionx/build").apply { mkdirs() }
        assertNull(f.link(File(person, "DIABDAT.MPQ").path, File(build, "DIABDAT.MPQ").path))
        assertNull(f.link(File(person, "music").path, File(build, "music").path))
        assertTrue(f.isLink(File(build, "DIABDAT.MPQ").path) || DesktopOs.current == DesktopOs.WINDOWS)
        assertEquals(64, File(build, "DIABDAT.MPQ").readBytes().size, "read through the link, where it is")
        assertTrue(File(build, "music/theme.wav").isFile)
        assertTrue(f.link(File(person, "music").path, File(build, "music").path) != null, "never over something already there")

        // Removing Fuse's copy takes the links themselves; the person's files stay, every byte.
        assertTrue(f.remove(build.path))
        assertFalse(Files.exists(build.toPath(), LinkOption.NOFOLLOW_LINKS))
        assertTrue(File(person, "DIABDAT.MPQ").readBytes().all { it == 3.toByte() })
        assertTrue(File(person, "music/theme.wav").readBytes().all { it == 4.toByte() })
        assertFalse(f.remove(person.path), "nothing outside Fuse's folder of ports is ever removed")
        assertTrue(person.isDirectory)
        assertFalse(f.move(person.path, File(f.folder, "taken").path), "or moved")
    }

    @Test
    fun theLauncherIsWrittenWholeAndUmuIsOnlyLinuxs(): Unit = runBlocking {
        val f = files()
        val launcher = File(f.folder, "openrct2/openrct2.fuseport").path
        assertTrue(f.writeText(launcher, "{\"port\":\"openrct2\"}"))
        assertEquals("{\"port\":\"openrct2\"}", File(launcher).readText())
        assertFalse(File(f.folder, "openrct2/.openrct2.fuseport.part").exists())
        assertNull(DesktopPortFiles(data, File(dir, "downloads"), DesktopOs.WINDOWS, findUmu = { "/usr/bin/umu-run" }).umuRun(), "umu-launcher runs Windows builds on Linux only")
        assertEquals("/usr/bin/umu-run", DesktopPortFiles(data, File(dir, "downloads"), DesktopOs.LINUX, findUmu = { "/usr/bin/umu-run" }).umuRun())
    }

    @Test
    fun aWindowsBuildGetsTheProtonThePersonUsesInSteamElseTheNewest(): Unit = runBlocking {
        val home = File(dir, "home")
        val steam = File(home, ".local/share/Steam")
        fun proton(folder: File) = folder.apply { mkdirs(); File(this, "proton").writeText("#!/usr/bin/env python3\n") }
        val valve9 = proton(File(steam, "steamapps/common/Proton 9.0 (Beta)"))
        proton(File(steam, "steamapps/common/Proton 8.0"))
        // Another library, as libraryfolders.vdf lists it.
        val other = File(dir, "games/SteamLibrary")
        val valve10 = proton(File(other, "steamapps/common/Proton 10.0"))
        // Paths as Steam writes them in a VDF: a backslash (Windows') escaped.
        fun vdf(f: File) = f.path.replace("\\", "\\\\")
        File(steam, "steamapps/libraryfolders.vdf").writeText("\"libraryfolders\"\n{\n\t\"0\"\n\t{\n\t\t\"path\"\t\t\"${vdf(steam)}\"\n\t}\n\t\"1\"\n\t{\n\t\t\"path\"\t\t\"${vdf(other)}\"\n\t}\n}\n")
        val ge = proton(File(steam, "compatibilitytools.d/GE-Proton9-20"))
        File(ge, "compatibilitytool.vdf").writeText("\"compatibilitytools\"\n{\n  \"compat_tools\"\n  {\n    \"GE-Proton9-20\"\n    {\n      \"install_path\" \".\"\n    }\n  }\n}\n")
        val linux = DesktopPortFiles(data, File(dir, "downloads"), DesktopOs.LINUX, findUmu = { null }, home = home.path)

        // Nothing chosen in Steam: the newest.
        assertEquals(valve10.absoluteFile.fusePath, linux.protonPath())
        // Steam Play's tool for every other title.
        File(steam, "config").mkdirs()
        File(steam, "config/config.vdf").writeText("\"InstallConfigStore\" { \"Software\" { \"Valve\" { \"Steam\" { \"CompatToolMapping\" { \"0\" { \"name\" \"GE-Proton9-20\" } } } } } }")
        assertEquals(ge.absoluteFile.fusePath, linux.protonPath())
        File(steam, "config/config.vdf").writeText("\"InstallConfigStore\" { \"Software\" { \"Valve\" { \"Steam\" { \"CompatToolMapping\" { \"0\" { \"name\" \"proton_9\" } } } } } }")
        assertEquals(valve9.absoluteFile.fusePath, linux.protonPath())
        assertNull(DesktopPortFiles(data, File(dir, "downloads"), DesktopOs.WINDOWS, findUmu = { null }, home = home.path).protonPath(), "Linux only")
    }
}
