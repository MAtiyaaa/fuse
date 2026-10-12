package io.github.matiyaaa.fuse.desktop.platform

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The privileged Decky install script, run here without privileges against folders like Decky's. */
class DeckyInstallScriptTest {
    private fun run(script: String, plugins: File, source: File): Int =
        ProcessBuilder("sh", "-c", script, "fuse-decky", plugins.absolutePath, source.absolutePath).redirectErrorStream(true).start().waitFor()

    private fun staged(root: File, version: String) = File(root, "staged").apply {
        mkdirs()
        File(this, "plugin.json").writeText("{\"version\":\"$version\"}")
    }

    @Test
    fun replacesThePluginAndLeavesOtherPluginsAndThemesAlone() {
        val root = Files.createTempDirectory("decky").toFile()
        try {
            val plugins = File(root, "homebrew/plugins").apply { mkdirs() }
            File(plugins, "Fuse").mkdirs(); File(plugins, "Fuse/plugin.json").writeText("old")
            File(plugins, "SDH-CssLoader").mkdirs(); File(plugins, "SDH-CssLoader/plugin.json").writeText("css")
            val themes = File(root, "homebrew/themes/MyTheme").apply { mkdirs() }; File(themes, "theme.json").writeText("t")
            assertEquals(0, run(DesktopDecky.installScript(restart = false), plugins, staged(root, "2")))
            assertEquals("{\"version\":\"2\"}", File(plugins, "Fuse/plugin.json").readText())
            assertFalse(File(plugins, "Fuse.previous").exists())
            assertFalse(File(plugins, "Fuse.new").exists())
            assertEquals("css", File(plugins, "SDH-CssLoader/plugin.json").readText())
            assertEquals("t", File(themes, "theme.json").readText())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun neverCreatesDeckysFolderAndNeverRestartsDeckyWhenAskedNotTo() {
        val root = Files.createTempDirectory("decky").toFile()
        try {
            val missing = File(root, "homebrew/plugins")
            assertEquals(3, run(DesktopDecky.installScript(restart = false), missing, staged(root, "2")))
            assertFalse(missing.exists())
            assertFalse("plugin_loader" in DesktopDecky.installScript(restart = false))
            assertTrue("systemctl restart plugin_loader" in DesktopDecky.installScript(restart = true))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun steamsMenuEntriesTurnOffWhereThePluginLooks() = kotlinx.coroutines.runBlocking {
        val root = Files.createTempDirectory("decky-menu").toFile()
        try {
            val data = File(root, "share")
            val decky = DesktopDecky(home = root, staging = File(root, "staging"), gameRunning = { false }, dataHome = data)
            assertTrue(decky.state().menuEntries)
            assertTrue(decky.setMenuEntries(false))
            // main.py looks beside decky-bridge.json, in the fuse folder of the data folder.
            assertTrue(File(data, "fuse/decky-menu-off").isFile)
            assertFalse(decky.state().menuEntries)
            assertTrue(decky.setMenuEntries(true))
            assertTrue(decky.state().menuEntries)
        } finally {
            root.deleteRecursively()
        }
    }
}
