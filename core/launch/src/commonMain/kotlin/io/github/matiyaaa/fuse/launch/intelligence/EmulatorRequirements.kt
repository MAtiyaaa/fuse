package io.github.matiyaaa.fuse.launch.intelligence

import io.github.matiyaaa.fuse.launch.linux.LinuxInstallKind
import io.github.matiyaaa.fuse.model.Host
import io.github.matiyaaa.fuse.model.InstalledEmulator

/**
 * What an emulator needs of the computer before it can start at all, as its developers publish it:
 * the processor's kind (a program built for another processor never starts) and features, and the
 * graphics library it draws with. Read through [ReadOnlyLook] only. Used for an installed
 * emulator's check, by the Store before installing, and by launch diagnostics. Where something
 * can't be known (no feature list, an unusual layout), nothing is said rather than guessed.
 */
object EmulatorRequirements {

    /** What one emulator needs, with where that is published. */
    data class Needs(
        /** Processor features, as Linux names them, checked only on x86-64 processors. */
        val cpuFlags: Set<String> = emptySet(),
        /** The features in words ("x86-64-v3: Intel Haswell, AMD Excavator or newer"). */
        val cpuWords: String = "",
        val vulkanOnly: Boolean = false,
        val source: String,
    )

    /** By the end of an emulator's id ("shadps4", "pcsx2"). */
    val table: Map<String, Needs> = mapOf(
        "shadps4" to Needs(
            cpuFlags = setOf("avx2", "fma", "bmi2"), cpuWords = "x86-64-v3 (Intel Haswell, AMD Excavator or newer)", vulkanOnly = true,
            source = "shadps4.net quickstart and FAQ: an x86-64-v3 processor and Vulkan 1.3",
        ),
        "pcsx2" to Needs(
            cpuFlags = setOf("sse4_1"), cpuWords = "SSE4.1",
            source = "pcsx2.net/docs/setup/requirements: x86-64 with SSE4.1",
        ),
    )

    /**
     * Why the installed emulator [e] can't run on this computer: its program built for another
     * processor first, then what [table] says it needs. Null when nothing is known to stop it.
     */
    fun forInstalled(e: InstalledEmulator, look: ReadOnlyLook): String? {
        if (e.host == Host.ANDROID) return null
        val flatpak = e.host == Host.LINUX && e.detectedVia == LinuxInstallKind.FLATPAK.label
        val path = e.appId.takeIf { !flatpak && (it.startsWith("/") || it.contains(":\\") || it.contains(":/")) }
        return path?.let { wrongProcessor(it, e.host, look) }
            ?: unmet(e.id.value.substringAfterLast('.'), e.host, look, bringsOwnLibraries = flatpak)
    }

    /**
     * Why [emulator] can't run on this computer, from [table], or null when nothing is known to
     * stop it. [bringsOwnLibraries]: a Flatpak carries its own graphics libraries, so those aren't asked.
     */
    fun unmet(emulator: String, host: Host, look: ReadOnlyLook, bringsOwnLibraries: Boolean = false): String? {
        val needs = table[emulator] ?: return null
        val cpu = look.processor()
        if (needs.cpuFlags.isNotEmpty() && cpu?.arch == "x86_64") {
            val flags = cpu.flags
            if (flags != null && needs.cpuFlags.any { it !in flags }) return "It needs a processor with ${needs.cpuWords}, and this one doesn't have it"
        }
        if (needs.vulkanOnly && !bringsOwnLibraries && vulkanMissing(host, look)) {
            return "It draws only with Vulkan, and no Vulkan library was found. Install your graphics driver's Vulkan support"
        }
        return null
    }

    /**
     * Why the program at [path] can't run here: it is built for another processor (an ARM build on
     * an x86-64 PC, or the other way round). Windows on ARM runs x86 programs, and 64-bit x86
     * runs 32-bit ones, so those pass. Null when it fits or its header can't be read.
     */
    fun wrongProcessor(path: String, host: Host, look: ReadOnlyLook): String? {
        if (host == Host.MACOS || host == Host.ANDROID) return null
        val built = builtFor(look.programHeader(path) ?: return null) ?: return null
        val cpu = look.processor()?.arch ?: return null
        val fits = built == cpu ||
            (cpu == "x86_64" && built == "x86") ||
            (cpu == "arm64" && built == "arm") ||
            (host == Host.WINDOWS && cpu == "arm64" && (built == "x86_64" || built == "x86"))
        return if (fits) null else "This build is for ${words(built)} computers, and this one is ${words(cpu)}. Get its ${words(cpu)} build"
    }

    /** The processor a program is built for, from its ELF or PE header; null for anything else. */
    fun builtFor(header: ByteArray): String? {
        fun u16(at: Int) = if (at + 1 < header.size) (header[at].toInt() and 0xFF) or ((header[at + 1].toInt() and 0xFF) shl 8) else -1
        fun u32(at: Int) = if (at + 3 < header.size) u16(at) or (u16(at + 2) shl 16) else -1
        // ELF: 0x7F 'E' 'L' 'F', then the machine at 18 (little-endian, as every PC and ARM build is).
        if (header.size > 20 && header[0] == 0x7F.toByte() && header[1] == 'E'.code.toByte() && header[2] == 'L'.code.toByte() && header[3] == 'F'.code.toByte()) {
            if (header[5].toInt() != 1) return null
            return when (u16(18)) {
                0x3E -> "x86_64"
                0xB7 -> "arm64"
                0x03 -> "x86"
                0x28 -> "arm"
                else -> null
            }
        }
        // Windows: 'M' 'Z', the PE header's place at 0x3C, then "PE\0\0" and the machine.
        if (header.size > 0x40 && header[0] == 'M'.code.toByte() && header[1] == 'Z'.code.toByte()) {
            val pe = u32(0x3C)
            if (pe < 0 || pe + 6 > header.size || header[pe] != 'P'.code.toByte() || header[pe + 1] != 'E'.code.toByte()) return null
            return when (u16(pe + 4)) {
                0x8664 -> "x86_64"
                0xAA64 -> "arm64"
                0x014C -> "x86"
                else -> null
            }
        }
        return null
    }

    private fun words(arch: String) = when (arch) {
        "x86_64" -> "64-bit x86 (Intel and AMD)"
        "arm64" -> "64-bit ARM"
        "x86" -> "32-bit x86"
        "arm" -> "32-bit ARM"
        else -> arch
    }

    /**
     * No Vulkan library where Linux distributions and Windows keep it, looked for by name, never
     * opened. False where none of the usual folders exists (an unusual layout): nothing is guessed.
     */
    private fun vulkanMissing(host: Host, look: ReadOnlyLook): Boolean {
        val places = when (host) {
            Host.LINUX -> listOf("/usr/lib", "/usr/lib64", "/usr/lib/x86_64-linux-gnu", "/usr/lib/aarch64-linux-gnu", "/lib/x86_64-linux-gnu", "/lib64")
                .map { it to "$it/libvulkan.so.1" }
            Host.WINDOWS -> listOf((look.env("SystemRoot") ?: "C:/Windows").replace('\\', '/') + "/System32").map { it to "$it/vulkan-1.dll" }
            else -> return false
        }
        val looked = places.filter { look.isDirectory(it.first) }
        return looked.isNotEmpty() && looked.none { look.exists(it.second) }
    }
}
