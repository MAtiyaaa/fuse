package io.github.matiyaaa.fuse.desktop

import io.github.matiyaaa.fuse.desktop.input.PadSnapshot
import io.github.matiyaaa.fuse.desktop.platform.Autostart
import io.github.matiyaaa.fuse.desktop.platform.BatteryReading
import io.github.matiyaaa.fuse.desktop.platform.OtherStatus
import io.github.matiyaaa.fuse.desktop.platform.StatusReader
import io.github.matiyaaa.fuse.desktop.services.DesktopLauncher
import io.github.matiyaaa.fuse.desktop.services.EmulatorLocations
import io.github.matiyaaa.fuse.desktop.system.DesktopOs
import io.github.matiyaaa.fuse.desktop.system.windowsArgument
import io.github.matiyaaa.fuse.model.Host
import io.github.matiyaaa.fuse.model.PadButton
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Windows and macOS pieces that don't need Windows or a Mac to check. */
class DesktopHostsTest {
    private val tmp: File = Files.createTempDirectory("fuse-hosts-test").toFile()

    @AfterTest
    fun cleanUp() {
        tmp.deleteRecursively()
    }

    @Test
    fun osNamesMapToHosts() {
        assertEquals(DesktopOs.WINDOWS, DesktopOs.of("Windows 11"))
        assertEquals(DesktopOs.MACOS, DesktopOs.of("Mac OS X"))
        assertEquals(DesktopOs.LINUX, DesktopOs.of("Linux"))
        assertEquals(Host.MACOS, DesktopOs.MACOS.host)
        assertTrue(Host.WINDOWS.isDesktop && !Host.ANDROID.isDesktop)
    }

    @Test
    fun foldersPerSystem() {
        val linux = FuseDirs.linux(mapOf("HOME" to "/home/u"))
        assertEquals("/home/u/.local/share/fuse", linux.data)
        assertEquals("/home/u/.cache/fuse", linux.cache)
        val windows = FuseDirs.windows(mapOf("USERPROFILE" to "C:\\Users\\u", "LOCALAPPDATA" to "C:\\Users\\u\\AppData\\Local", "APPDATA" to "C:\\Users\\u\\AppData\\Roaming"), launcher = null)
        assertEquals("C:/Users/u/AppData/Local/Fuse", windows.data)
        assertEquals("C:/Users/u/AppData/Local/Fuse/Cache", windows.cache)
        assertEquals("C:/Users/u/AppData/Roaming/Fuse", windows.config)
        assertFalse(windows.portable)
        // A FuseData folder next to Fuse.exe makes everything portable.
        File(tmp, "Fuse/FuseData").mkdirs()
        val portable = FuseDirs.windows(emptyMap(), launcher = File(tmp, "Fuse/Fuse.exe").path)
        assertTrue(portable.portable)
        assertTrue(portable.data.endsWith("Fuse/FuseData/data"))
        val mac = FuseDirs.macos("/Users/u")
        assertEquals("/Users/u/Library/Application Support/Fuse", mac.data)
        assertEquals("/Users/u/Library/Caches/Fuse", mac.cache)
    }

    @Test
    fun windowsProgramsGetBackslashes() {
        assertEquals("C:\\Games\\Halo (USA).iso", windowsArgument("C:/Games/Halo (USA).iso"))
        assertEquals("-batch", windowsArgument("-batch"))
        assertEquals("steam://rungameid/620", windowsArgument("steam://rungameid/620"))
    }

    @Test
    fun handoffsOnWindowsAndMac() {
        assertTrue(DesktopLauncher.isHandoff(listOf("C:/Program Files (x86)/Steam/steam.exe", "-applaunch", "620")))
        assertTrue(DesktopLauncher.isHandoff(listOf("/usr/bin/open", "steam://rungameid/620")))
        assertFalse(DesktopLauncher.isHandoff(listOf("/usr/bin/open", "-W", "/Games/Celeste.app")))
        assertFalse(DesktopLauncher.isHandoff(listOf("D:/Emulators/xemu/xemu.exe", "-dvd_path", "D:/x.iso")))
        assertEquals("/Applications/PPSSPPSDL.app", DesktopLauncher.macApp("/Applications/PPSSPPSDL.app/Contents/MacOS/PPSSPPSDL"))
        assertNull(DesktopLauncher.macApp("/opt/homebrew/bin/mednafen"))
    }

    @Test
    fun macAutostartOpensTheApp() {
        assertEquals("/Applications/Fuse.app", Autostart.macApp("/Applications/Fuse.app/Contents/MacOS/Fuse"))
        assertNull(Autostart.macApp("/opt/fuse/bin/fuse"))
        val plist = Autostart.launchAgentPlist("/Applications/Fuse & Co.app")
        assertTrue("<string>/Applications/Fuse &amp; Co.app</string>" in plist)
        assertTrue("<key>RunAtLoad</key>" in plist)
    }

    @Test
    fun batteriesParse() {
        val pmset = "Now drawing from 'AC Power'\n -InternalBattery-0 (id=4653155)\t85%; charging; 0:42 remaining present: true\n"
        assertEquals(BatteryReading(85, true, minutes = 42), OtherStatus.parsePmset(pmset))
        assertEquals(
            BatteryReading(40, false, minutes = 190),
            OtherStatus.parsePmset("Now drawing from 'Battery Power'\n -InternalBattery-0 (id=1)\t40%; discharging; 3:10 remaining present: true"),
        )
        assertEquals(
            BatteryReading(100, true, full = true),
            OtherStatus.parsePmset("Now drawing from 'AC Power'\n -InternalBattery-0 (id=1)\t100%; charged; 0:00 remaining present: true"),
        )
        assertEquals(
            BatteryReading(63, false),
            OtherStatus.parsePmset("Now drawing from 'Battery Power'\n -InternalBattery-0 (id=1)\t63%; discharging; (no estimate) present: true"),
        )
        assertEquals(BatteryReading.NONE, OtherStatus.parsePmset("Now drawing from 'AC Power'\n"))
        assertEquals(BatteryReading(72, false), OtherStatus.parseWin32Battery("72,1"))
        assertEquals(BatteryReading(95, true), OtherStatus.parseWin32Battery("95,2"))
        assertEquals(BatteryReading(72, false, minutes = 190), OtherStatus.parseWin32Battery("72,1,190,"))
        // 71582788 is Windows for "don't know".
        assertEquals(BatteryReading(72, false), OtherStatus.parseWin32Battery("72,1,71582788,"))
        assertEquals(BatteryReading(60, true, minutes = 50), OtherStatus.parseWin32Battery("60,6,71582788,50"))
        assertEquals(BatteryReading(100, true, full = true), OtherStatus.parseWin32Battery("100,3,71582788,"))
        assertEquals(BatteryReading.NONE, OtherStatus.parseWin32Battery(""))
    }

    private fun supply(name: String, vararg files: Pair<String, String>) {
        val dir = File(tmp, "power/$name").apply { mkdirs() }
        files.forEach { (f, v) -> File(dir, f).writeText("$v\n") }
    }

    @Test
    fun linuxBatteryTimes() {
        val root = File(tmp, "power")
        supply("AC", "type" to "Mains", "online" to "0")
        // 30 Wh left of 60, drawing 10 W: three hours.
        supply("BAT0", "type" to "Battery", "present" to "1", "capacity" to "50", "status" to "Discharging", "energy_now" to "30000000", "energy_full" to "60000000", "power_now" to "10000000")
        // A controller's battery is never the system's.
        supply("hidpp_battery_0", "type" to "Battery", "scope" to "Device", "capacity" to "10", "status" to "Discharging")
        assertEquals(BatteryReading(50, false, minutes = 180), StatusReader.readLinuxBattery(root))

        // Charging at 15 W with 30 Wh to go: two hours.
        supply("AC", "type" to "Mains", "online" to "1")
        supply("BAT0", "type" to "Battery", "present" to "1", "capacity" to "50", "status" to "Charging", "energy_now" to "30000000", "energy_full" to "60000000", "power_now" to "15000000")
        assertEquals(BatteryReading(50, true, minutes = 120), StatusReader.readLinuxBattery(root))

        // The kernel's own estimate wins.
        File(tmp, "power/BAT0/time_to_full_now").writeText("2700\n")
        assertEquals(BatteryReading(50, true, minutes = 45), StatusReader.readLinuxBattery(root))

        // Charge and current instead of energy and power, and almost no draw: no time.
        File(tmp, "power/BAT0").deleteRecursively()
        supply("AC", "type" to "Mains", "online" to "0")
        supply("BAT0", "type" to "Battery", "capacity" to "80", "status" to "Discharging", "charge_now" to "4000000", "charge_full" to "5000000", "current_now" to "2000000")
        assertEquals(BatteryReading(80, false, minutes = 120), StatusReader.readLinuxBattery(root))
        supply("BAT0", "type" to "Battery", "capacity" to "80", "status" to "Discharging", "charge_now" to "4000000", "charge_full" to "5000000", "current_now" to "1000")
        assertEquals(BatteryReading(80, false), StatusReader.readLinuxBattery(root))

        // Plugged in and full.
        supply("AC", "type" to "Mains", "online" to "1")
        supply("BAT0", "type" to "Battery", "capacity" to "100", "status" to "Full", "charge_now" to "5000000", "charge_full" to "5000000", "current_now" to "0")
        assertEquals(BatteryReading(100, true, full = true), StatusReader.readLinuxBattery(root))
    }

    @Test
    fun locatedEmulatorsAreRemembered() {
        val file = File(tmp, "config/emulators.json")
        EmulatorLocations(file).apply {
            locate("windows.ppsspp", "D:/Emus/PPSSPP/PPSSPPWindows64.exe")
            setFolders(listOf("D:/Emus"))
        }
        val again = EmulatorLocations(file)
        assertEquals(mapOf("windows.ppsspp" to "D:/Emus/PPSSPP/PPSSPPWindows64.exe"), again.located())
        assertEquals(listOf("D:/Emus"), again.folders())
        again.forget("windows.ppsspp")
        assertTrue(EmulatorLocations(file).located().isEmpty())
        // A damaged file starts empty instead of failing.
        file.writeText("{not json")
        assertTrue(EmulatorLocations(file).located().isEmpty())
    }

    @Test
    fun controllerChangesBecomeEvents() {
        val idle = PadSnapshot.EMPTY
        val pressA = PadSnapshot(buttons = setOf(PadButton.A))
        assertEquals(listOf(PadSnapshot.Event.Press(PadButton.A)), PadSnapshot.diff(idle, pressA))
        assertEquals(listOf(PadSnapshot.Event.Release(PadButton.A)), PadSnapshot.diff(pressA, idle))
        assertTrue(PadSnapshot.diff(pressA, pressA).isEmpty())
        // The stick reports when it moves and when it returns to the centre; tiny moves are skipped.
        val pushed = PadSnapshot(stickX = 0.6f)
        assertEquals(listOf(PadSnapshot.Event.Stick(0.6f, 0f)), PadSnapshot.diff(idle, pushed))
        assertTrue(PadSnapshot.diff(pushed, pushed.copy(stickX = 0.605f)).isEmpty())
        assertEquals(listOf(PadSnapshot.Event.Stick(0f, 0f)), PadSnapshot.diff(pushed, idle))
        assertEquals(0f, PadSnapshot.axis(0.01f))
        // Triggers, and two pads merged: either pad's buttons, the furthest stick.
        assertEquals(listOf(PadSnapshot.Event.Trigger(PadButton.R2, 1f)), PadSnapshot.diff(idle, PadSnapshot(rightTrigger = 1f)))
        val merged = PadSnapshot(buttons = setOf(PadButton.B), stickY = -0.2f).merge(PadSnapshot(buttons = setOf(PadButton.START), stickY = 0.9f))
        assertEquals(setOf(PadButton.B, PadButton.START), merged.buttons)
        assertEquals(0.9f, merged.stickY)
    }
}
