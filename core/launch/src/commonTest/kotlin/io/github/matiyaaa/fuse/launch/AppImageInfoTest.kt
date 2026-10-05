package io.github.matiyaaa.fuse.launch

import io.github.matiyaaa.fuse.launch.linux.AppImageInfo
import io.github.matiyaaa.fuse.launch.linux.LinuxDetector
import io.github.matiyaaa.fuse.launch.linux.LinuxEnvironment
import io.github.matiyaaa.fuse.launch.linux.LinuxInstallKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppImageInfoTest {
    /** A minimal 64-bit AppImage: an ELF header marked "AI" type 2, and sections ending in .upd_info. */
    private fun appImage(update: String): ByteArray {
        val strtab = "\u0000.shstrtab\u0000.upd_info\u0000".encodeToByteArray()
        val upd = update.encodeToByteArray()
        val strOff = 64
        val updOff = strOff + strtab.size
        val shoff = updOff + upd.size
        val out = ByteArray(shoff + 64 * 3)
        fun put(o: Int, v: Long, n: Int) { for (i in 0 until n) out[o + i] = ((v shr (8 * i)) and 0xff).toByte() }
        out[0] = 0x7f; out[1] = 'E'.code.toByte(); out[2] = 'L'.code.toByte(); out[3] = 'F'.code.toByte()
        out[4] = 2; out[8] = 0x41; out[9] = 0x49; out[10] = 2
        put(0x28, shoff.toLong(), 8); put(0x3a, 64, 2); put(0x3c, 3, 2); put(0x3e, 1, 2)
        strtab.copyInto(out, strOff); upd.copyInto(out, updOff)
        fun section(i: Int, name: Int, off: Int, size: Int) { val o = shoff + i * 64; put(o, name.toLong(), 4); put(o + 0x18, off.toLong(), 8); put(o + 0x20, size.toLong(), 8) }
        section(1, 1, strOff, strtab.size)
        section(2, 11, updOff, upd.size)
        return out
    }

    private class Env(private val files: Map<String, ByteArray>) : LinuxEnvironment {
        override val homeDir = "/home/deck"
        override fun pathDirectories() = emptyList<String>()
        override fun isExecutable(path: String) = path in files
        override fun exists(path: String) = path in files
        override fun listFiles(dir: String) = files.keys.filter { it.substringBeforeLast('/') == dir }.map { it.substringAfterLast('/') }
        override fun flatpakApps() = emptySet<String>()
        override fun readText(path: String): String? = null
        override fun readBytes(path: String, offset: Long, length: Int): ByteArray? {
            val b = files[path] ?: return null
            if (offset >= b.size) return null
            return b.copyOfRange(offset.toInt(), minOf(b.size, offset.toInt() + length))
        }
    }

    @Test
    fun readsTheReleaseNameFromTheUpdateSection() {
        val env = Env(mapOf("/x/a" to appImage("gh-releases-zsync|RPCS3|rpcs3-binaries-linux|latest|rpcs3-*_linux64.AppImage.zsync")))
        assertEquals("rpcs3-*_linux64.AppImage", AppImageInfo.releaseName(env, "/x/a"))
        assertTrue(AppImageInfo.isAppImage(appImage("x")))
        assertFalse(AppImageInfo.isAppImage("#!/bin/sh\necho hi".encodeToByteArray()))
    }

    @Test
    fun aRenamedEmulatorAppImageIsFound() {
        val home = "/home/deck/Applications"
        val env = Env(mapOf("$home/my-ps3" to appImage("gh-releases-zsync|RPCS3|rpcs3-binaries-linux|latest|rpcs3-*_linux64.AppImage.zsync")))
        val found = LinuxDetector.detect(env).firstOrNull { it.id.value == "linux.rpcs3" }
        assertEquals("$home/my-ps3", found?.appId)
        assertEquals(LinuxInstallKind.APPIMAGE.label, found?.detectedVia)
    }

    @Test
    fun anElfWithoutUpdateInformationIsLeftAlone() {
        val bytes = appImage("x").also { it[10] = 0 }
        assertNull(AppImageInfo.releaseName(Env(mapOf("/x/b" to bytes)), "/x/b"))
    }
}
