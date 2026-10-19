package io.github.matiyaaa.fuse.launch.ports

import io.github.matiyaaa.fuse.model.PortFileKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Recompiling a PS5 dump with AnyPS5, as its docs/user/USAGE.md at v0.1.3 says. */
class AnyPs5RecompileTest {
    @Test
    fun theRelinkerAndItsLibrariesAreEachSystemsOwnFiles() {
        assertEquals("relinker-v0.1.3", AnyPs5Recompile.relinkerAsset("linux", "v0.1.3"))
        assertEquals("relinker-v0.1.3.exe", AnyPs5Recompile.relinkerAsset("windows", "v0.1.3"))
        assertEquals("prx-linux-v0.1.3.zip", AnyPs5Recompile.librariesAsset("linux", "v0.1.3"))
        assertEquals("prx-windows-v0.1.3.zip", AnyPs5Recompile.librariesAsset("windows", "v0.1.3"))
        // A Linux program unless --windows, whatever the output is called.
        assertEquals(listOf("/f/relinker", "/d/eboot.bin", "/b/app.elf"), AnyPs5Recompile.argv("/f/relinker", "linux", "/d/eboot.bin", "/b/app.elf"))
        assertEquals(listOf("C:/f/relinker.exe", "--windows", "D:/d/eboot.bin", "C:/b/app.exe"), AnyPs5Recompile.argv("C:/f/relinker.exe", "windows", "D:/d/eboot.bin", "C:/b/app.exe"))
        assertEquals("app.exe", AnyPs5Recompile.program("windows"))
        assertEquals("app.elf", AnyPs5Recompile.program("linux"))
    }

    @Test
    fun itRecompilesOnLinuxAndWindowsOnX86AndSaysWhyNotElsewhere() {
        assertNull(AnyPs5Recompile.unsupported("linux", "x86_64"))
        assertNull(AnyPs5Recompile.unsupported("windows", "x86_64"))
        assertTrue("Linux and Windows" in AnyPs5Recompile.unsupported("macos", "arm64")!!, "no relinker is published for the Mac")
        assertTrue("x86-64" in AnyPs5Recompile.unsupported("linux", "arm64")!!)
    }

    @Test
    fun aDumpNeedsAPlainElfItsParamsAndOneModuleFolder() {
        val elf = byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())
        val self = byteArrayOf(0x4F, 0x15, 0x3D, 0x1D)
        assertEquals(AnyPs5Recompile.Executable.ELF, AnyPs5Recompile.executable(elf))
        assertEquals(AnyPs5Recompile.Executable.SELF, AnyPs5Recompile.executable(self))
        assertEquals(AnyPs5Recompile.Executable.OTHER, AnyPs5Recompile.executable(byteArrayOf(1, 2)))
        val good = listOf("eboot.bin", "sce_sys", "sce_module", "data.pak")
        assertNull(AnyPs5Recompile.lacks(good, AnyPs5Recompile.Executable.ELF))
        assertNull(AnyPs5Recompile.lacks(listOf("eboot.bin", "sce_sys", "prx"), AnyPs5Recompile.Executable.ELF), "prx alone will do")
        assertTrue("SELF" in AnyPs5Recompile.lacks(good, AnyPs5Recompile.Executable.SELF)!!)
        assertTrue("eboot.bin" in AnyPs5Recompile.lacks(good, null)!!)
        assertTrue("sce_sys" in AnyPs5Recompile.lacks(listOf("eboot.bin", "sce_module"), AnyPs5Recompile.Executable.ELF)!!)
        assertTrue("both" in AnyPs5Recompile.lacks(listOf("eboot.bin", "sce_sys", "sce_module", "sce_modules"), AnyPs5Recompile.Executable.ELF)!!)
        assertTrue("none of" in AnyPs5Recompile.lacks(listOf("eboot.bin", "sce_sys"), AnyPs5Recompile.Executable.ELF)!!)
        // app0 holds everything but the module folders, which the relinker fills with converted ones.
        assertEquals(listOf("data.pak", "eboot.bin", "sce_sys"), AnyPs5Recompile.app0Links(good + "prx"))
    }

    @Test
    fun theProcessorsMissingExtensionsAreNamed() {
        val all = "processor\t: 0\nflags\t\t: fpu sse2 avx avx2 fma f16c bmi1 bmi2 abm movbe\n"
        assertEquals(emptyList(), AnyPs5Recompile.missingExtensions(all))
        assertEquals(listOf("AVX2", "LZCNT"), AnyPs5Recompile.missingExtensions("flags : fpu fma f16c bmi1 bmi2 movbe\n"))
        assertEquals(emptyList(), AnyPs5Recompile.missingExtensions("no flags line here"), "not known is never missing")
    }

    @Test
    fun eachGamesBuildIsAPortOfItsOwnStartedFromItsFolder() {
        assertEquals("anyps5-ppsa01234", AnyPs5Recompile.id("ppsa01234"))
        assertNull(AnyPs5Recompile.id("SLUS-20946"))
        assertNull(AnyPs5Recompile.id(null))
        assertTrue(AnyPs5Recompile.isRecompiled("anyps5-ppsa01234"))
        val layout = PortLayout("/fuse/ports/anyps5-ppsa01234", "anyps5-ppsa01234")
        val launcher = AnyPs5Recompile.launcher(layout, "linux", "v0.1.3")
        assertEquals(listOf("/fuse/ports/anyps5-ppsa01234/build/app.elf"), launcher.argv)
        assertEquals("/fuse/ports/anyps5-ppsa01234/build", launcher.cwd, "app0/ and its save folders are there")
        assertEquals("/fuse/ports/anyps5-ppsa01234/home/_sd", launcher.saves)
        assertNotNull(PortLauncher.decode(launcher.encode()))
        val port = AnyPs5Recompile.port("anyps5-ppsa01234", "Astro Bot", "v0.1.3", "linux", "https://example.org/relinker", 10, "a".repeat(64))
        assertEquals("v0.1.3", port.version)
        assertEquals(PortFileKind.FILE, port.builds.single().kind)
        assertEquals("app.elf", port.builds.single().run)
    }
}
