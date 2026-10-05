package io.github.matiyaaa.fuse.desktop

import io.github.matiyaaa.fuse.desktop.input.AxisRole
import io.github.matiyaaa.fuse.desktop.input.JoystickMapping
import io.github.matiyaaa.fuse.desktop.platform.Autostart
import io.github.matiyaaa.fuse.desktop.services.DesktopFuseServices
import io.github.matiyaaa.fuse.desktop.services.DesktopLauncher
import io.github.matiyaaa.fuse.desktop.services.EncryptedFileSecretStore
import io.github.matiyaaa.fuse.desktop.services.IconIndex
import io.github.matiyaaa.fuse.desktop.services.NioFileSystem
import io.github.matiyaaa.fuse.desktop.system.DesktopOs
import io.github.matiyaaa.fuse.model.PadButton
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopUnitTest {
    private val tmp: File = Files.createTempDirectory("fuse-desktop-test").toFile()

    @AfterTest
    fun cleanUp() {
        tmp.deleteRecursively()
    }

    @Test
    fun xpadCapabilitiesGiveTheXboxLayout() {
        // Real sysfs values of an Xbox 360 pad (xpad) on x86_64.
        val keys = JoystickMapping.parseBitmap("7cdb000000000000 0 0 0 0")!!
        val abs = JoystickMapping.parseBitmap("3003f")!!
        val m = JoystickMapping.fromCapabilities(keys, abs, playStation = false)
        val expected = listOf(
            PadButton.A, PadButton.B, PadButton.X, PadButton.Y, PadButton.L1, PadButton.R1,
            PadButton.SELECT, PadButton.START, PadButton.MODE, PadButton.L3, PadButton.R3,
        )
        expected.forEachIndexed { i, b -> assertEquals(b, m.button(i), "button $i") }
        assertNull(m.button(11))
        assertEquals(AxisRole.LEFT_X, m.axis(0))
        assertEquals(AxisRole.LEFT_Y, m.axis(1))
        assertEquals(AxisRole.LEFT_TRIGGER, m.axis(2))
        assertEquals(AxisRole.RIGHT_X, m.axis(3))
        assertEquals(AxisRole.RIGHT_Y, m.axis(4))
        assertEquals(AxisRole.RIGHT_TRIGGER, m.axis(5))
        assertEquals(AxisRole.HAT_X, m.axis(6))
        assertEquals(AxisRole.HAT_Y, m.axis(7))
    }

    @Test
    fun playStationSwapsNorthAndWestAndKeepsDigitalTriggers() {
        // South, East, North, West, TL, TR, TL2, TR2, Select, Start, Mode, ThumbL, ThumbR.
        val codes = listOf(0x130, 0x131, 0x133, 0x134, 0x136, 0x137, 0x138, 0x139, 0x13a, 0x13b, 0x13c, 0x13d, 0x13e)
        val keys = codes.fold(java.math.BigInteger.ZERO) { acc, c -> acc.setBit(c) }
        val abs = JoystickMapping.parseBitmap("3003f")!!
        val m = JoystickMapping.fromCapabilities(keys, abs, playStation = true)
        assertEquals(PadButton.A, m.button(0))
        assertEquals(PadButton.B, m.button(1))
        assertEquals(PadButton.Y, m.button(2))
        assertEquals(PadButton.X, m.button(3))
        assertEquals(PadButton.L2, m.button(6))
        assertEquals(PadButton.START, m.button(9))
        assertTrue(JoystickMapping.isPlayStation("Sony Interactive Entertainment DualSense Wireless Controller"))
        assertFalse(JoystickMapping.isPlayStation("Microsoft X-Box 360 pad"))
    }

    @Test
    fun nintendoPadsConfirmWithTheirAOnLinuxToo() {
        // hid-nintendo: A on BTN_EAST and B on BTN_SOUTH, X on BTN_NORTH and Y on BTN_WEST (positions).
        val keys = JoystickMapping.parseBitmap("7cdb000000000000 0 0 0 0")!!
        val abs = JoystickMapping.parseBitmap("3003f")!!
        val m = JoystickMapping.fromCapabilities(keys, abs, playStation = false, nintendo = true)
        // By label, as on Windows, macOS and Android: the button marked A is A (it confirms), X is X.
        assertEquals(PadButton.B, m.button(0))
        assertEquals(PadButton.A, m.button(1))
        assertEquals(PadButton.X, m.button(2))
        assertEquals(PadButton.Y, m.button(3))
        assertTrue(JoystickMapping.isNintendo("Nintendo Switch Pro Controller"))
        assertTrue(JoystickMapping.isNintendo("Nintendo Switch Combined Joy-Cons"))
        assertFalse(JoystickMapping.isNintendo("Microsoft X-Box 360 pad"))
        assertFalse(JoystickMapping.isNintendo("8BitDo Pro 2"))
    }

    @Test
    fun windowFlagsPickTheLastOne() {
        assertEquals(WindowMode.WINDOWED, WindowMode.fromArgs(listOf("--fullscreen", "--windowed")))
        assertEquals(WindowMode.BORDERLESS, WindowMode.fromArgs(listOf("cartridge://home", "--borderless")))
        assertNull(WindowMode.fromArgs(listOf("%u")))
    }

    @Test
    fun windowPrefsRoundTrip() {
        val prefs = WindowPrefs(File(tmp, "config/window.properties"))
        assertNull(prefs.load())
        prefs.save(WindowPrefs.Saved(WindowMode.FULLSCREEN, WindowMode.WINDOWED))
        assertEquals(WindowPrefs.Saved(WindowMode.FULLSCREEN, WindowMode.WINDOWED), prefs.load())
    }

    @Test
    fun execQuotingFollowsTheDesktopEntrySpec() {
        assertEquals("/home/u/Applications/Fuse.AppImage", Autostart.quoteExec("/home/u/Applications/Fuse.AppImage"))
        assertEquals("\"/home/u/My Apps/Fuse.AppImage\"", Autostart.quoteExec("/home/u/My Apps/Fuse.AppImage"))
        assertEquals("\"/home/u/a\\\$b\"", Autostart.quoteExec("/home/u/a\$b"))
    }

    @Test
    fun handoffAndFlatpakRecognition() {
        assertTrue(DesktopLauncher.isHandoff(listOf("/usr/bin/steam", "-applaunch", "620")))
        assertTrue(DesktopLauncher.isHandoff(listOf("/usr/bin/flatpak", "run", "com.valvesoftware.Steam", "-applaunch", "620")))
        assertTrue(DesktopLauncher.isHandoff(listOf("/usr/bin/gio", "launch", "/x.desktop")))
        assertFalse(DesktopLauncher.isHandoff(listOf("/usr/bin/flatpak", "run", "org.libretro.RetroArch")))
        assertTrue(DesktopLauncher.isFlatpakId("org.libretro.RetroArch"))
        assertFalse(DesktopLauncher.isFlatpakId("retroarch"))
        assertFalse(DesktopLauncher.isFlatpakId("org.foo.Bar.desktop"))
    }

    @Test
    fun cacheWritesStayInsideTheCache() {
        val root = File(tmp, "cache").path
        assertNull(DesktopFuseServices.writeBelow(root, "../escape.txt", "x"))
        assertNull(DesktopFuseServices.writeBelow(root, "playlists/../../escape.txt", "x"))
        assertNull(DesktopFuseServices.writeBelow(root, "", "x"))
        val written = DesktopFuseServices.writeBelow(root, "/playlists/42/Game.m3u", "a.cue\nb.cue\n")
        assertEquals(File(root, "playlists/42/Game.m3u").absoluteFile.invariantSeparatorsPath, written)
        // Windows ways out of the folder.
        assertNull(DesktopFuseServices.writeBelow(root, "..\\escape.txt", "x"))
        assertNull(DesktopFuseServices.writeBelow(root, "C:/escape.txt", "x"))
        assertEquals("a.cue\nb.cue\n", File(written!!).readText())
        assertFalse(File(tmp, "escape.txt").exists())
    }

    @Test
    fun encryptedSecretsRoundTripWithPrivateFiles() = runBlocking<Unit> {
        val store = EncryptedFileSecretStore(tmp)
        assertNull(store.get("ra.apikey"))
        store.put("ra.apikey", "s3cret value")
        store.put("sgdb.apikey", "other")
        assertEquals("s3cret value", EncryptedFileSecretStore(tmp).get("ra.apikey"))
        val raw = File(tmp, "secrets.bin").readBytes().toString(Charsets.ISO_8859_1)
        assertFalse("s3cret" in raw)
        // Windows has no POSIX modes; AppData is the user's own there.
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            for (name in listOf("secrets.bin", "secrets.key")) {
                assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(File(tmp, name).toPath())))
            }
        }
        store.remove("ra.apikey")
        assertNull(store.get("ra.apikey"))
        assertEquals("other", store.get("sgdb.apikey"))
    }

    @Test
    fun fileSystemReportsLinksAndSurvivesLoops() = runBlocking<Unit> {
        // Making symbolic links on Windows needs Developer Mode or an administrator.
        if (DesktopOs.isWindows) return@runBlocking
        val fs = NioFileSystem()
        val roms = File(tmp, "roms").apply { mkdirs() }
        File(roms, "snes").mkdirs()
        File(roms, "snes/Game (USA).sfc").writeText("rom")
        Files.createSymbolicLink(File(roms, "loop").toPath(), roms.toPath())
        Files.createSymbolicLink(File(roms, "dangling").toPath(), File(tmp, "missing").toPath())
        val entries = fs.list(roms.path).associateBy { it.name }
        assertTrue(entries.getValue("snes").isDirectory)
        assertTrue(entries.getValue("loop").isSymlink)
        assertTrue(entries.getValue("loop").isDirectory)
        assertFalse(entries.getValue("dangling").isDirectory)
        assertEquals(fs.canonical(roms.path), fs.canonical("${roms.path}/loop/loop/loop"))
        assertEquals("rom", fs.readText("${roms.path}/snes/Game (USA).sfc"))
        assertEquals("ro", fs.readText("${roms.path}/snes/Game (USA).sfc", maxBytes = 2))
        assertEquals(java.security.MessageDigest.getInstance("MD5").digest("rom".toByteArray()).joinToString("") { "%02x".format(it) },
            fs.md5("${roms.path}/snes/Game (USA).sfc"))
        assertTrue(fs.list("${roms.path}/nope").isEmpty())
        assertNull(fs.stat("${roms.path}/nope"))
        assertNotNull(fs.stat("${roms.path}/snes"))
    }

    @Test
    fun iconsResolveFromHicolor() {
        val base = File(tmp, "icons")
        File(base, "hicolor/48x48/apps").mkdirs()
        File(base, "hicolor/256x256/apps").mkdirs()
        File(base, "hicolor/48x48/apps/retroarch.png").writeText("small")
        File(base, "hicolor/256x256/apps/retroarch.png").writeText("big")
        File(base, "hicolor/scalable/apps").mkdirs()
        File(base, "hicolor/scalable/apps/dolphin.svg").writeText("<svg/>")
        val index = IconIndex(listOf(base.path))
        assertEquals(File(base, "hicolor/256x256/apps/retroarch.png").path, index.resolve("retroarch"))
        assertEquals(File(base, "hicolor/scalable/apps/dolphin.svg").path, index.resolve("dolphin"))
        assertNull(index.resolve("missing"))
    }
}
