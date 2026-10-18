package io.github.matiyaaa.fuse.launch.settings

import io.github.matiyaaa.fuse.model.Host
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Fuse Tune's data as core:launch reads it, against what Fuse already offers. */
class TuneTest {
    @Test
    fun everyEmulatorReadHasItsReleasesAndASettingsCountLikeItsOwn() {
        assertEquals(setOf("dolphin", "duckstation", "ppsspp", "pcsx2", "rpcs3"), Tune.families)
        for (family in Tune.families) {
            assertEquals(4, Tune.releases(family).size, family)
            // Each has a few hundred settings; a reader that broke would find a handful.
            assertTrue(Tune.forEmulator(family).size > 150, "$family: ${Tune.forEmulator(family).size}")
            assertTrue(Tune.forEmulator(family).all { it.releases.isNotEmpty() }, family)
        }
    }

    @Test
    fun everySettingFuseOffersIsInTheEmulatorsOwnCodeWithValuesItReads() {
        for (host in listOf(Host.LINUX, Host.WINDOWS, Host.MACOS)) {
            for (family in NativeSettings.families) {
                for (s in NativeSettings.forEmulator(family, host)) {
                    val t = assertNotNull(Tune.find(family, s.file, s.section, s.key), "${s.id}: ${s.section} ${s.key} isn't in ${family}'s code")
                    assertEquals(s.perGame, t.perGame, "${s.id} per game")
                    if (s.gameSection != null) assertEquals(s.gameSection, t.gameSection, "${s.id} game section")
                    for (c in s.choices) assertTrue(t.accepts(c.value, host), "${s.id} on $host: ${family} doesn't read ${c.value} (${t.kind}, ${t.choicesOn(host).map { it.value }})")
                    assertTrue(t.accepts(s.default, host), "${s.id}: default ${s.default}")
                }
            }
        }
    }

    @Test
    fun choicesASystemsBuildHasntGotArentOffered() {
        val renderer = assertNotNull(Tune.find("duckstation", null, "GPU", "Renderer"))
        assertEquals("Direct3D 11", renderer.choices.first { it.value == "D3D11" }.shown)
        assertTrue(renderer.choicesOn(Host.WINDOWS).any { it.value == "D3D12" })
        assertTrue(renderer.choicesOn(Host.LINUX).none { it.value == "D3D12" || it.value == "Metal" })
        assertTrue(renderer.accepts("Vulkan", Host.LINUX))
        assertTrue(!renderer.accepts("Metal", Host.LINUX))
    }

    @Test
    fun valuesAreCheckedAsTheEmulatorReadsThem() {
        val scale = assertNotNull(Tune.find("rpcs3", null, "Video", "Resolution Scale"))
        assertEquals(TuneKind.NUMBER, scale.kind)
        assertTrue(scale.accepts("150", Host.LINUX))
        assertTrue(!scale.accepts("5000", Host.LINUX), "past RPCS3's own range")
        assertTrue(!scale.accepts("1.5", Host.LINUX))
        val vsync = assertNotNull(Tune.find("dolphin", "GFX", "Hardware", "VSync"))
        assertTrue(vsync.accepts("True", Host.LINUX))
        assertTrue(!vsync.accepts("maybe", Host.LINUX))
        // RPCS3's unsigned 64-bit numbers reach past a Long.
        val bound = assertNotNull(Tune.find("rpcs3", null, "Core", "SPU LLVM Upper Bound"))
        assertTrue(bound.accepts(bound.default!!, Host.LINUX))
        // A YAML section is its path of nodes.
        assertEquals(listOf("Video", "Vulkan"), assertNotNull(Tune.find("rpcs3", null, "Video/Vulkan", "Adapter")).path)
    }

    @Test
    fun theInstalledVersionDecidesWhichSettingsShow() {
        for (family in Tune.families) {
            val read = Tune.releases(family)
            assertEquals(read.first(), Tune.releaseFor(family, null), "unknown: the newest")
            val newer = TuneVersions.numbers(read.first()).let { listOf(it[0] + 1) + it.drop(1) }.joinToString(".")
            assertEquals(read.first(), Tune.releaseFor(family, newer), "newer than any read ($newer)")
            assertEquals(read.last(), Tune.releaseFor(family, "0.0.1"), "older than any read: the oldest")
            for (r in read) {
                assertEquals(r, Tune.releaseFor(family, r))
                assertTrue(Tune.forVersion(family, r).all { r in it.releases }, "$family $r")
            }
        }
        // A setting only the newest release has isn't offered to an older one.
        val newer = Tune.families.flatMap { f -> Tune.forEmulator(f).filter { Tune.releases(f).last() !in it.releases } }
        assertTrue(newer.isNotEmpty(), "some setting came in a release read")
        for (s in newer) assertTrue(s !in Tune.forVersion(s.family, Tune.releases(s.family).last()), s.id)
    }

    @Test
    fun versionsAreReadByTheirNumbers() {
        assertEquals(listOf(0L, 1L, 11894L), TuneVersions.numbers("v0.1-11894"))
        assertEquals(listOf(2609L, 1L), TuneVersions.numbers("2609a"))
        assertEquals(listOf(2L, 8L, 2L), TuneVersions.numbers("PCSX2 v2.8.2"))
        assertEquals(listOf(2L, 8L, 2L), TuneVersions.numbers("pcsx2-v2.8.2-linux-appimage-x64-Qt"))
        assertEquals(listOf(1L, 20L, 4L), TuneVersions.numbers("1.20.4-rc1"))
        assertTrue(TuneVersions.compare(TuneVersions.numbers("2609a"), TuneVersions.numbers("2609")) > 0)
        assertTrue(TuneVersions.compare(TuneVersions.numbers("2609-120"), TuneVersions.numbers("2609a")) > 0)
        assertTrue(TuneVersions.compare(TuneVersions.numbers("v1.20"), TuneVersions.numbers("v1.20.4")) < 0)
        assertEquals("v0.1-11826", Tune.releaseFor("duckstation", "0.1-11830"))
    }
}
