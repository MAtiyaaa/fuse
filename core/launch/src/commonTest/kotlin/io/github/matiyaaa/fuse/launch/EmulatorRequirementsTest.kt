package io.github.matiyaaa.fuse.launch

import io.github.matiyaaa.fuse.launch.intelligence.EmulatorRequirements
import io.github.matiyaaa.fuse.launch.intelligence.Processor
import io.github.matiyaaa.fuse.launch.intelligence.ReadOnlyLook
import io.github.matiyaaa.fuse.model.EmulatorId
import io.github.matiyaaa.fuse.model.Host
import io.github.matiyaaa.fuse.model.InstalledEmulator
import io.github.matiyaaa.fuse.model.PlatformId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What an emulator needs of the computer: a program built for another processor is caught from its
 * header, missing processor features from the system's list, a missing Vulkan library by name; and
 * where any of that can't be known, nothing is said.
 */
class EmulatorRequirementsTest {
    private class Machine(var cpu: Processor? = Processor("x86_64", setOf("sse4_1", "sse4_2", "avx", "avx2", "fma", "bmi2"))) : ReadOnlyLook {
        val dirs = HashSet<String>()
        val files = HashSet<String>()
        val headers = HashMap<String, ByteArray>()
        var systemRoot: String? = null
        override val home = "/home/mo"
        override fun env(name: String): String? = if (name == "SystemRoot") systemRoot else null
        override fun exists(path: String) = path in dirs || path in files || path in headers
        override fun isDirectory(path: String) = path in dirs
        override fun canExecute(path: String) = path in headers
        override fun modified(path: String): Long? = if (exists(path)) 1L else null
        override fun names(path: String): List<String> = emptyList()
        override fun programHeader(path: String): ByteArray? = headers[path]
        override fun processor(): Processor? = cpu
    }

    /** An ELF header's first bytes: the magic, 64-bit, little-endian, and the machine at 18. */
    private fun elf(machine: Int, littleEndian: Boolean = true) = ByteArray(64).also {
        it[0] = 0x7F; it[1] = 'E'.code.toByte(); it[2] = 'L'.code.toByte(); it[3] = 'F'.code.toByte()
        it[4] = 2; it[5] = if (littleEndian) 1 else 2
        it[18] = (machine and 0xFF).toByte(); it[19] = (machine shr 8).toByte()
    }

    /** A Windows program: "MZ", the PE header's place at 0x3C, "PE\0\0", then the machine. */
    private fun pe(machine: Int, at: Int = 0x80) = ByteArray(0x100).also {
        it[0] = 'M'.code.toByte(); it[1] = 'Z'.code.toByte()
        it[0x3C] = at.toByte(); it[0x3D] = (at shr 8).toByte()
        it[at] = 'P'.code.toByte(); it[at + 1] = 'E'.code.toByte()
        it[at + 4] = (machine and 0xFF).toByte(); it[at + 5] = (machine shr 8).toByte()
    }

    private fun emu(id: String, appId: String, via: String, host: Host = Host.LINUX) =
        InstalledEmulator(EmulatorId(id), id.substringAfterLast('.'), host, appId, null, setOf(PlatformId("ps4")), via)

    @Test
    fun aProgramsHeaderSaysWhichProcessorItIsBuiltFor() {
        assertEquals("x86_64", EmulatorRequirements.builtFor(elf(0x3E)))
        assertEquals("arm64", EmulatorRequirements.builtFor(elf(0xB7)))
        assertEquals("x86", EmulatorRequirements.builtFor(elf(0x03)))
        assertEquals("arm", EmulatorRequirements.builtFor(elf(0x28)))
        assertEquals("x86_64", EmulatorRequirements.builtFor(pe(0x8664)))
        assertEquals("arm64", EmulatorRequirements.builtFor(pe(0xAA64)))
        assertEquals("x86", EmulatorRequirements.builtFor(pe(0x014C)))
        // Anything else says nothing: a script, a big-endian build, a PE header past what was read, an unknown machine.
        assertNull(EmulatorRequirements.builtFor("#!/bin/sh\nexec x\n".encodeToByteArray()))
        assertNull(EmulatorRequirements.builtFor(elf(0x3E, littleEndian = false)))
        assertNull(EmulatorRequirements.builtFor(pe(0x8664).copyOf(0x82)))
        assertNull(EmulatorRequirements.builtFor(elf(0xF3)))
        assertNull(EmulatorRequirements.builtFor(ByteArray(0)))
    }

    @Test
    fun aBuildForAnotherProcessorIsCaughtAndOnesThatRunArent() {
        val m = Machine()
        m.headers["/apps/Arm.AppImage"] = elf(0xB7)
        m.headers["/apps/Pc.AppImage"] = elf(0x3E)
        m.headers["/apps/Old.AppImage"] = elf(0x03)
        val wrong = assertNotNull(EmulatorRequirements.wrongProcessor("/apps/Arm.AppImage", Host.LINUX, m))
        assertTrue("64-bit ARM" in wrong && "64-bit x86" in wrong)
        assertNull(EmulatorRequirements.wrongProcessor("/apps/Pc.AppImage", Host.LINUX, m))
        // 64-bit x86 runs 32-bit x86 programs.
        assertNull(EmulatorRequirements.wrongProcessor("/apps/Old.AppImage", Host.LINUX, m))
        // On an ARM computer, the x86 build is the wrong one on Linux, but Windows on ARM runs it.
        m.cpu = Processor("arm64", null)
        m.headers["C:/Emu/emu.exe"] = pe(0x8664)
        assertNotNull(EmulatorRequirements.wrongProcessor("/apps/Pc.AppImage", Host.LINUX, m))
        assertNull(EmulatorRequirements.wrongProcessor("C:/Emu/emu.exe", Host.WINDOWS, m))
        assertNull(EmulatorRequirements.wrongProcessor("/apps/Arm.AppImage", Host.LINUX, m))
        // A header that can't be read, an unknown processor, or a Mac (universal apps): nothing is said.
        assertNull(EmulatorRequirements.wrongProcessor("/apps/Missing.AppImage", Host.LINUX, m))
        m.cpu = null
        assertNull(EmulatorRequirements.wrongProcessor("/apps/Pc.AppImage", Host.LINUX, m))
        m.cpu = Processor("x86_64", null)
        assertNull(EmulatorRequirements.wrongProcessor("/apps/Arm.AppImage", Host.MACOS, m))
    }

    @Test
    fun missingProcessorFeaturesAreNamedOnlyWhenTheSystemListsThem() {
        val m = Machine()
        m.dirs += "/usr/lib"; m.files += "/usr/lib/libvulkan.so.1"
        assertNull(EmulatorRequirements.unmet("shadps4", Host.LINUX, m))
        assertNull(EmulatorRequirements.unmet("pcsx2", Host.LINUX, m))
        // A processor without AVX2 (older than Haswell) can't run shadPS4, and says so.
        m.cpu = Processor("x86_64", setOf("sse4_1", "sse4_2", "avx"))
        val shad = assertNotNull(EmulatorRequirements.unmet("shadps4", Host.LINUX, m))
        assertTrue("x86-64-v3" in shad)
        assertNull(EmulatorRequirements.unmet("pcsx2", Host.LINUX, m))
        m.cpu = Processor("x86_64", setOf("sse2", "ssse3"))
        assertTrue("SSE4.1" in assertNotNull(EmulatorRequirements.unmet("pcsx2", Host.LINUX, m)))
        // No feature list (Windows, macOS), or an ARM processor whose features are named otherwise: nothing is said.
        m.cpu = Processor("x86_64", null)
        assertNull(EmulatorRequirements.unmet("shadps4", Host.LINUX, m))
        m.cpu = Processor("arm64", setOf("fp", "asimd"))
        assertNull(EmulatorRequirements.unmet("pcsx2", Host.LINUX, m))
        // An emulator with nothing published needs nothing here.
        m.cpu = Processor("x86_64", emptySet())
        assertNull(EmulatorRequirements.unmet("duckstation", Host.LINUX, m))
    }

    @Test
    fun vulkanIsLookedForWhereSystemsKeepItAndAFlatpakBringsItsOwn() {
        val m = Machine()
        // No usual library folder at all (an unusual layout): nothing is guessed.
        assertNull(EmulatorRequirements.unmet("shadps4", Host.LINUX, m))
        m.dirs += "/usr/lib"
        assertTrue("Vulkan" in assertNotNull(EmulatorRequirements.unmet("shadps4", Host.LINUX, m)))
        assertNull(EmulatorRequirements.unmet("shadps4", Host.LINUX, m, bringsOwnLibraries = true))
        m.dirs += "/usr/lib/x86_64-linux-gnu"; m.files += "/usr/lib/x86_64-linux-gnu/libvulkan.so.1"
        assertNull(EmulatorRequirements.unmet("shadps4", Host.LINUX, m))
        // Windows keeps it in System32, under SystemRoot.
        m.systemRoot = "D:\\Windows"
        m.dirs += "D:/Windows/System32"
        assertNotNull(EmulatorRequirements.unmet("shadps4", Host.WINDOWS, m))
        m.files += "D:/Windows/System32/vulkan-1.dll"
        assertNull(EmulatorRequirements.unmet("shadps4", Host.WINDOWS, m))
    }

    @Test
    fun anInstalledEmulatorIsCheckedByItsProgramThenByWhatItNeeds() {
        val m = Machine(Processor("x86_64", setOf("sse4_1", "avx")))
        m.dirs += "/usr/lib"; m.files += "/usr/lib/libvulkan.so.1"
        val armBuild = emu("linux.shadps4", "/home/mo/Apps/shadPS4-arm64.AppImage", "AppImage")
        m.headers[armBuild.appId] = elf(0xB7)
        // The wrong build is the first thing to fix, so it is what is said.
        assertTrue("64-bit ARM" in assertNotNull(EmulatorRequirements.forInstalled(armBuild, m)))
        m.headers[armBuild.appId] = elf(0x3E)
        assertTrue("x86-64-v3" in assertNotNull(EmulatorRequirements.forInstalled(armBuild, m)))
        // A Flatpak's id isn't a path, and it brings its own graphics libraries.
        m.files.clear()
        val flat = emu("linux.shadps4", "net.shadps4.shadPS4", "Flatpak")
        m.cpu = Processor("x86_64", setOf("avx2", "fma", "bmi2"))
        assertNull(EmulatorRequirements.forInstalled(flat, m))
        // Android apps are Android's to check, whatever the processor.
        m.cpu = Processor("x86_64", emptySet())
        assertNull(EmulatorRequirements.forInstalled(emu("android.shadps4", "net.shadps4.android", "Package", Host.ANDROID), m))
    }
}
