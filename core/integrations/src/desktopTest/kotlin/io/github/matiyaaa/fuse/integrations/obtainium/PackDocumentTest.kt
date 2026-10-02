package io.github.matiyaaa.fuse.integrations.obtainium

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Reads a test fixture from `src/desktopTest/resources/obtainium`. */
internal fun fixture(name: String): String =
    requireNotNull(PackDocumentTest::class.java.getResource("/obtainium/$name")) { "Missing fixture $name" }.readText()

/**
 * The real Obtainium Emulation Pack (public domain, github.com/RJNY/Obtainium-Emulation-Pack at
 * 8d52c82, release v7.18.0), both editions, read as the Store reads it.
 */
class PackDocumentTest {
    private val standard = PackDocument.parse(fixture("pack-standard.json")).getOrThrow()
    private val dual = PackDocument.parse(fixture("pack-dual-screen.json")).getOrThrow()

    @Test
    fun readsEveryAppOfBothEditions() {
        assertEquals(64, standard.apps.size)
        assertEquals(68, dual.apps.size)
        assertTrue(standard.apps.all { it.url.startsWith("https://") })
    }

    @Test
    fun categoriesComeWithThePacksColours() {
        val names = standard.categories.map { it.name }
        assertTrue(names.containsAll(listOf("Emulator", "Frontend", "PC Emulation", "Streaming", "Utilities", "Track Only")), names.toString())
        assertEquals(4292386421L, standard.categories.first { it.name == "Emulator" }.color)
    }

    @Test
    fun generatedIdsAreNotTakenForPackageNames() {
        val retroArch = standard.apps.first { it.name.startsWith("RetroArch") }
        assertEquals("487343354", retroArch.id)
        assertNull(retroArch.packageName)
        assertEquals("aenu.aps3e", standard.apps.first { it.name == "aPS3e" }.packageName)
    }

    @Test
    fun sourcesAndRulesAreRead() {
        assertEquals(7, standard.apps.count { it.source == PackSourceKind.HTML })
        assertEquals(PackSourceKind.GITHUB, standard.apps.first { it.name == "aPS3e" }.source)
        val dolphin = standard.apps.first { it.name == "Dolphin Emulator" }
        assertEquals("Obtainium/1.0", dolphin.rules.requestHeaders["User-Agent"])
        assertEquals("\$1", dolphin.rules.versionGroups)
        assertTrue(dolphin.rules.sortByLastLinkSegment)
        val retroArch = standard.apps.first { it.name.startsWith("RetroArch") }
        assertEquals(2, retroArch.rules.intermediateLinks.size)
        assertTrue(retroArch.rules.skipSort)
        assertNotNull(standard.apps.first { it.name == "aPS3e" }.about)
    }

    @Test
    fun trackOnlyAppsAreMarked() {
        val tracked = standard.apps.filter { it.rules.trackOnly }
        assertTrue(tracked.isNotEmpty())
        assertTrue(tracked.all { "Track Only" in it.categories }, tracked.map { it.name }.toString())
    }

    @Test
    fun theEditionsReallyDiffer() {
        val stdCemu = standard.apps.first { it.id == "info.cemu.cemu" }
        val dualCemu = dual.apps.first { it.id == "info.cemu.cemu" }
        assertTrue(stdCemu.url != dualCemu.url)
        assertTrue(dual.apps.any { it.id == "xyz.blacksheep.mjolnir" })
        assertFalse(standard.apps.any { it.id == "xyz.blacksheep.mjolnir" })
    }

    @Test
    fun settingsMayBeAnObjectToo() {
        val apps = (1..5).joinToString(",") {
            """{"id":"com.example.app$it","url":"https://github.com/example/app$it","name":"App $it","author":"example","additionalSettings":{"about":"Plain object","trackOnly":true},"categories":["Utilities"]}"""
        }
        val pack = PackDocument.parse("""{"apps":[$apps]}""").getOrThrow()
        assertEquals("Plain object", pack.apps[0].about)
        assertTrue(pack.apps[0].rules.trackOnly)
        assertEquals(PackSourceKind.GITHUB, pack.apps[0].source)
    }

    @Test
    fun brokenOrTinyFilesAreRefused() {
        assertTrue(PackDocument.parse("<html>Rate limited</html>").isFailure)
        assertTrue(PackDocument.parse("""{"apps":[]}""").isFailure)
        assertTrue(PackDocument.parse("""{"settings":{}}""").isFailure)
        val truncated = fixture("pack-standard.json").let { it.substring(0, it.length / 2) }
        assertTrue(PackDocument.parse(truncated).isFailure)
    }
}
