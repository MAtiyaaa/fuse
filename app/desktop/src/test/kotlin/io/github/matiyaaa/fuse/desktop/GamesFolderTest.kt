package io.github.matiyaaa.fuse.desktop

import io.github.matiyaaa.fuse.desktop.services.KnownFolders
import io.github.matiyaaa.fuse.desktop.system.DesktopOs
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GamesFolderTest {
    private fun home() = Files.createTempDirectory("fuse-home").toFile().apply { deleteOnExit() }

    @Test
    fun aGamesFolderInHomeIsOffered() {
        val home = home()
        File(home, "Games/gba").mkdirs()
        val roots = KnownFolders(home.path, DesktopOs.LINUX).romRoots().map { it.label to File(it.path).name }
        assertTrue("Games" to "Games" in roots, roots.toString())
        home.deleteRecursively()
    }

    @Test
    fun aRomsFolderInsideGamesIsOfferedInsteadOfGamesItself() {
        val home = home()
        File(home, "Games/ROMs/snes").mkdirs()
        val roots = KnownFolders(home.path, DesktopOs.LINUX).romRoots()
        assertEquals(listOf("ROMs"), roots.filter { it.label.startsWith("Games") }.map { File(it.path).name })
        home.deleteRecursively()
    }
}
