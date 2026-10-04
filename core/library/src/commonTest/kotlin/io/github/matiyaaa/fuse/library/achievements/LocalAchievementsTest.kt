package io.github.matiyaaa.fuse.library.achievements

import io.github.matiyaaa.fuse.library.InMemoryFileSystem
import io.github.matiyaaa.fuse.library.steam.BinaryVdf
import io.github.matiyaaa.fuse.library.steam.BinaryVdf.Block
import io.github.matiyaaa.fuse.library.steam.BinaryVdf.Int32
import io.github.matiyaaa.fuse.library.steam.BinaryVdf.Str
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Achievements read from Steam's stats cache and RPCS3's trophy folders, in their own formats. */
class LocalAchievementsTest {
    private fun block(vararg e: Pair<String, BinaryVdf.Value>) = Block(e.toMutableList())

    private fun schema(): ByteArray = BinaryVdf.write(
        block(
            "620" to block(
                "gamename" to Str("Portal 2"),
                "stats" to block(
                    "1" to block(
                        "type" to Str("4"),
                        "bits" to block(
                            "0" to block("name" to Str("ACH_SURVIVE"), "bit" to Int32(0), "display" to block(
                                "name" to block("english" to Str("Wake Up Call"), "token" to Str("T0")),
                                "desc" to block("english" to Str("Survive the manual override.")),
                                "hidden" to Str("0"), "icon" to Str("a.jpg"), "icon_gray" to Str("a_gray.jpg"),
                            )),
                            "1" to block("name" to Str("ACH_SECRET"), "bit" to Int32(1), "display" to block(
                                "name" to block("english" to Str("Secret")), "desc" to block("english" to Str("Find it.")), "hidden" to Str("1"),
                            )),
                            "2" to block("name" to Str("ACH_THIRD"), "bit" to Int32(2), "display" to block("name" to block("english" to Str("Third")))),
                        ),
                    ),
                ),
            ),
        ),
    )

    private fun stats(): ByteArray = BinaryVdf.write(
        block("cache" to block("crc" to Int32(1), "1" to block("data" to Int32(0b101), "AchievementTimes" to block("0" to Int32(1_700_000_000), "2" to Int32(1_700_000_500))))),
    )

    @Test
    fun steamAchievementsComeFromItsStatsCache() = runTest {
        val fs = InMemoryFileSystem()
            .bytes("/steam/appcache/stats/UserGameStatsSchema_620.bin", schema())
            .bytes("/steam/appcache/stats/UserGameStats_123_620.bin", stats())
        val set = assertNotNull(LocalAchievements(fs).steam(listOf("/steam"), 620))
        assertEquals("Portal 2", set.title)
        assertEquals(3, set.items.size)
        val wake = set.items.single { it.id == "ACH_SURVIVE" }
        assertEquals("Wake Up Call", wake.name)
        assertEquals(1_700_000_000_000L, wake.unlockedAt)
        assertEquals("https://cdn.akamai.steamstatic.com/steamcommunity/public/images/apps/620/a.jpg", wake.icon)
        assertNull(set.items.single { it.id == "ACH_SECRET" }.unlockedAt)
        assertTrue(set.items.single { it.id == "ACH_SECRET" }.hidden)
        assertNotNull(set.items.single { it.id == "ACH_THIRD" }.unlockedAt)
        assertNull(LocalAchievements(fs).steam(listOf("/steam"), 999), "a game without a schema has none")
    }

    private val conf = """<?xml version="1.0" encoding="UTF-8"?>
<trophyconf version="1.0" policy="large">
<npcommid>NPWR00508_00</npcommid>
<title-name>Uncharted: Drake&apos;s Fortune</title-name>
<trophy id="000" hidden="no" ttype="P" pid="-1"><name>Platinum</name><detail>All of them.</detail></trophy>
<trophy id="001" hidden="no" ttype="B" pid="000"><name>First Treasure</name><detail>Find one treasure.</detail></trophy>
<trophy id="002" hidden="yes" ttype="G" pid="000"><name>Secret</name><detail>Hidden.</detail></trophy>
</trophyconf>"""

    /** A TROPUSR.DAT with one type 6 table where trophy 1 is unlocked. */
    private fun tropusr(): ByteArray {
        val out = ByteArray(48 + 32 + 3 * 0x70)
        fun put32(at: Int, v: Long) { for (i in 0..3) out[at + i] = (v ushr (24 - 8 * i)).toByte() }
        fun put64(at: Int, v: Long) { put32(at, v ushr 32); put32(at + 4, v and 0xFFFFFFFFL) }
        put32(0, 0x818F54ADL); put32(8, 1)
        put32(48, 6); put32(52, 0x60); put32(56, 1); put32(60, 3); put64(64, 80)
        for (i in 0 until 3) {
            val e = 80 + i * 0x70
            put32(e, 6); put32(e + 4, 0x60); put32(e + 8, i.toLong()); put32(e + 16, i.toLong())
            if (i == 1) { put32(e + 20, 1); put64(e + 32, 62_135_596_800_000_000L + 1_700_000_000_000_000L) }
        }
        return out
    }

    @Test
    fun ps3TrophiesComeFromRpcs3() = runTest {
        val fs = InMemoryFileSystem()
            .file("/rpcs3/dev_hdd0/home/00000001/trophy/NPWR00508_00/TROPCONF.SFM", content = conf)
            .bytes("/rpcs3/dev_hdd0/home/00000001/trophy/NPWR00508_00/TROPUSR.DAT", tropusr())
            .dir("/games/ps3/Uncharted/PS3_GAME/TROPDIR/NPWR00508_00")
        val reader = LocalAchievements(fs)
        val set = assertNotNull(reader.ps3Trophies(listOf("/rpcs3/dev_hdd0"), listOf("/games/ps3/Uncharted"), "BCUS98103", "Uncharted"))
        assertEquals("Uncharted: Drake's Fortune", set.title)
        assertEquals(listOf("platinum", "bronze", "gold"), set.items.map { it.grade })
        assertEquals(1_700_000_000_000L, set.items[1].unlockedAt)
        assertNull(set.items[0].unlockedAt)
        assertTrue(set.items[2].hidden)
        // Without the game's own folders, the set's name finds it.
        assertNotNull(reader.ps3Trophies(listOf("/rpcs3/dev_hdd0"), emptyList(), null, "Uncharted: Drake's Fortune"))
        assertNull(reader.ps3Trophies(listOf("/rpcs3/dev_hdd0"), emptyList(), null, "Another Game"))
    }
}
