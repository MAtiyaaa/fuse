package io.github.matiyaaa.fuse.launch

import io.github.matiyaaa.fuse.launch.android.AndroidEmulatorCatalog
import io.github.matiyaaa.fuse.launch.desktop.MacCatalog
import io.github.matiyaaa.fuse.launch.desktop.WindowsCatalog
import io.github.matiyaaa.fuse.launch.linux.LinuxCatalog
import io.github.matiyaaa.fuse.launch.pc.ShortcutParser
import io.github.matiyaaa.fuse.model.Host
import io.github.matiyaaa.fuse.model.ShortcutFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CatalogTest {
    private val registry = AdapterRegistry.Default

    @Test
    fun adapterIdsAreUnique() {
        val ids = registry.adapters.map { it.id }
        assertEquals(ids.distinct(), ids)
        assertEquals(AndroidEmulatorCatalog.defs.map { it.id }.distinct().size, AndroidEmulatorCatalog.defs.size)
        assertEquals(LinuxCatalog.defs.map { it.id }.distinct().size, LinuxCatalog.defs.size)
        assertEquals(WindowsCatalog.defs.map { it.def.id }.distinct().size, WindowsCatalog.defs.size)
        assertEquals(MacCatalog.defs.map { it.def.id }.distinct().size, MacCatalog.defs.size)
        assertTrue(runCatching { AdapterRegistry(registry.adapters + registry.adapters.first()) }.isFailure)
    }

    @Test
    fun priorityListsPointAtRealAdaptersOfTheRightHost() {
        for (host in Host.entries) {
            for ((platform, ids) in EmulatorPriority.map(host)) {
                assertTrue(Platforms.isKnown(platform), "unknown platform $platform")
                assertEquals(ids.distinct(), ids, "duplicates for $platform")
                for (id in ids) {
                    val a = assertNotNull(registry[id], "$id in $host priority for $platform")
                    assertEquals(host, a.host, "$id host")
                    assertTrue(platform in a.platforms, "$id does not list $platform")
                }
            }
        }
    }

    @Test
    fun everyCanonicalPlatformHasSomethingOnAndroid() {
        val missing = (Platforms.all - Platforms.desktopOnly.toSet()).filter { EmulatorPriority.forPlatform(Host.ANDROID, it).isEmpty() }
        assertTrue(missing.isEmpty(), "no Android priority for $missing")
    }

    @Test
    fun everyPlatformHasSomethingOnTheDesktop() {
        // Android apps only run on Android, and Xenia has no Mac build.
        val none = mapOf(Host.MACOS to setOf("xbox360"))
        for (host in listOf(Host.LINUX, Host.WINDOWS, Host.MACOS)) {
            val missing = Platforms.all.map { it.value }
                .filter { EmulatorPriority.forPlatform(host, io.github.matiyaaa.fuse.model.PlatformId(it)).isEmpty() }
                .filter { it != "android" && it !in none[host].orEmpty() }
            assertTrue(missing.isEmpty(), "no $host priority for $missing")
        }
    }

    @Test
    fun adaptersAreDocumented() {
        val emDash = '\u2014'
        for (a in registry.adapters) {
            assertTrue(a.source.isNotBlank(), "${a.id} has no source")
            assertTrue(a.platforms.all(Platforms::isKnown), "${a.id} lists unknown platforms")
            val text = listOf(a.name, a.source, a.homepage.orEmpty()) + a.limitations
            assertFalse(text.any { emDash in it }, "${a.id} uses an em dash")
            if (a.confidence == Confidence.UNVERIFIED) assertTrue(a.opensAppOnly, "${a.id} is UNVERIFIED but launches games")
        }
    }

    @Test
    fun retroArchCoresCoverTheRetroArchPlatforms() {
        for (host in Host.entries) for (p in Platforms.all) {
            val cores = RetroArchCores.coresFor(host, p)
            assertEquals(cores.distinct(), cores)
        }
        assertEquals("mupen64plus_next_gles3", RetroArchCores.defaultCore(Host.ANDROID, io.github.matiyaaa.fuse.model.PlatformId("n64")))
        assertEquals("mupen64plus_next", RetroArchCores.defaultCore(Host.LINUX, io.github.matiyaaa.fuse.model.PlatformId("n64")))
    }

    @Test
    fun desktopFilesParse() {
        val winlator = """
            [Desktop Entry]
            Name=Celeste
            Exec=env WINEPREFIX="/data/user/0/com.winlator.cmod/files/imagefs/home/xuser/.wine" wine C:\\\\Games\\\\Celeste\\\\Celeste.exe
            Type=Application
            Icon=celeste
            Path=/data/user/0/com.winlator.cmod/files/imagefs/home/xuser/.wine/dosdevices/c:/Games/Celeste

            [Extra Data]
            container_id=3
            execArgs=-windowed
        """.trimIndent()
        val e = assertNotNull(ShortcutParser.parseDesktop(winlator))
        assertEquals("Celeste", e.name)
        assertEquals(3, e.containerId)
        assertEquals("-windowed", e.extraData["execArgs"])
        assertEquals("celeste", e.icon)
        assertTrue(e.isWinlator)
        assertEquals("C:\\Games\\Celeste\\Celeste.exe", e.windowsTarget)
        assertEquals(ShortcutFormat.WINLATOR_DESKTOP, ShortcutParser.detectFormat("/x/Celeste.desktop", winlator))
        assertEquals(
            ShortcutFormat.STEAM_URL,
            ShortcutParser.detectFormat("/x/c.desktop", "[Desktop Entry]\nExec=steam steam://rungameid/504230\n"),
        )
        assertEquals(504230L, ShortcutParser.parseDesktop("[Desktop Entry]\nExec=steam steam://rungameid/504230\n")?.steamAppId)
        assertEquals(ShortcutFormat.GAMENATIVE, ShortcutParser.detectFormat("/x/c.steam"))
        assertNull(ShortcutParser.parseDesktop("Name=x\nExec=y"))
    }

    @Test
    fun idFilesParse() {
        assertEquals(504230L, ShortcutParser.parseNumericId("  504230 \n"))
        assertNull(ShortcutParser.parseNumericId("504 230"))
        assertNull(ShortcutParser.parseNumericId(""))
        assertEquals("PCSB00245", ShortcutParser.parseTitleId("\nPCSB00245\r\n"))
        val app = assertNotNull(ShortcutParser.parseAppFile("com.example.game/.ui.Main\r\n"))
        assertEquals("com.example.game", app.packageName)
        assertEquals("com.example.game.ui.Main", app.activityClass)
        assertNull(ShortcutParser.parseAppFile("hello world"))
    }

    @Test
    fun idFileContentIsNeededOnlyForIdFiles() {
        assertTrue(IdFiles.needsContent(game("steam", "/g/a.steam").location))
        assertTrue(IdFiles.needsContent(game("psvita", "/g/a.psvita").location))
        assertFalse(IdFiles.needsContent(game("psx", "/g/a.chd").location))
    }
}
