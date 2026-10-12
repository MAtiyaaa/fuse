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

    @Test
    fun twoAccountsOnOneComputerNeverSeeEachOthersAchievements() = runTest {
        // Account 123's stats unlock two; account 456's (the newer file) unlock none.
        val none = BinaryVdf.write(block("cache" to block("crc" to Int32(1), "1" to block("data" to Int32(0)))))
        val fs = InMemoryFileSystem()
            .bytes("/steam/appcache/stats/UserGameStatsSchema_620.bin", schema())
            .bytes("/steam/appcache/stats/UserGameStats_123_620.bin", stats())
            .bytes("/steam/appcache/stats/UserGameStats_456_620.bin", none)
        val reader = LocalAchievements(fs)
        // SteamIDs are the account ids plus 76561197960265728.
        val mine = assertNotNull(reader.steam(listOf("/steam"), 620, steamId = "76561197960265851"))
        assertEquals(2, mine.items.count { it.unlockedAt != null })
        val theirs = assertNotNull(reader.steam(listOf("/steam"), 620, steamId = "76561197960266184"))
        assertEquals(0, theirs.items.count { it.unlockedAt != null })
        // An account with no stats here sees the set, with nothing unlocked: never someone else's.
        val stranger = assertNotNull(reader.steam(listOf("/steam"), 620, steamId = "76561197960265729"))
        assertEquals(0, stranger.items.count { it.unlockedAt != null })
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

    @Test
    fun ps4TrophiesComeFromShadPs4() = runTest {
        // As shadPS4 keeps them (np_trophy.cpp): the set's TROP.XML, and each user's unlocks.
        val trop = """<?xml version="1.0" encoding="UTF-8"?>
<trophyconf version="1.0" platform="ps4">
  <title-name>Bloodborne&#8482;</title-name>
  <trophy id="000" hidden="no" ttype="P" pid="-1"><name>Bloodborne</name><detail>All trophies.</detail></trophy>
  <trophy ttype="B" id="001" hidden="no"><name>Yharnam, Pthumerian Birthplace</name><detail>Discover &amp; enter.</detail></trophy>
  <trophy id="002" hidden="yes" ttype="G"><name>Secret</name><detail>Hidden.</detail></trophy>
</trophyconf>"""
        val state = """<trophyconf><trophy id="0" hidden="no" ttype="P" unlockstate="false"/>
<trophy id="1" unlockstate="true" timestamp="1700000000" ttype="B"/><trophy id="2" ttype="G" unlockstate="false" timestamp="0"/></trophyconf>"""
        val fs = InMemoryFileSystem()
            .file("/shadPS4/trophy/NPWR08661_00/Xml/TROP.XML", content = trop)
            .file("/shadPS4/home/1000/trophy/NPWR08661_00.xml", content = state)
            .file("/shadPS4/trophy/NPWR00001_00/Xml/TROP.XML", content = trop.replace("Bloodborne&#8482;", "Another Game"))
        val reader = LocalAchievements(fs)
        val npbind = "....NPWR08661_00\u0000\u0000....".encodeToByteArray()
        assertEquals(listOf("NPWR08661_00"), reader.npCommIds(npbind))
        val set = assertNotNull(reader.ps4Trophies(listOf("/shadPS4"), listOf("NPWR08661_00"), "Bloodborne"))
        assertEquals(LocalAchievementSource.PS4_TROPHIES, set.source)
        assertEquals(listOf("platinum", "bronze", "gold"), set.items.map { it.grade })
        assertEquals("Discover & enter.", set.items[1].description)
        assertEquals(1_700_000_000_000L, set.items[1].unlockedAt)
        assertNull(set.items[0].unlockedAt)
        assertTrue(set.items[2].hidden)
        assertEquals("/shadPS4/trophy/NPWR08661_00/Icons/TROP001.PNG", set.items[1].icon)
        // Without the game's npbind.dat, the set's title finds it; another set's never does.
        assertEquals("Another Game", reader.ps4Trophies(listOf("/shadPS4"), emptyList(), "Another Game")?.title)
        assertNull(reader.ps4Trophies(listOf("/shadPS4"), emptyList(), "Not There"))
    }

    /** Vita3K's TROPUSR.DAT (context.cpp save_trophy_progress_file): little-endian, 1648 bytes. */
    private fun vitaUsr(unlocked: Map<Int, Long>): ByteArray {
        val b = ByteArray(1648)
        fun put32(at: Int, v: Long) { for (i in 0 until 4) b[at + i] = (v ushr (8 * i)).toByte() }
        fun put64(at: Int, v: Long) { for (i in 0 until 8) b[at + i] = (v ushr (8 * i)).toByte() }
        put32(0, 0x12D5819AL)
        for ((id, seconds) in unlocked) {
            b[4 + id / 8] = (b[4 + id / 8].toInt() or (1 shl (id % 8))).toByte()
            put64(112 + id * 8, seconds)
        }
        return b
    }

    @Test
    fun vitaTrophiesComeFromVita3kByTheGamesOwnSet() = runTest {
        val sfm = """<?xml version="1.0" encoding="UTF-8"?>
<trophyconf version="1.1" policy="large">
  <npcommid>NPWR01234_00</npcommid>
  <title-name>Gravity Rush</title-name>
  <trophy id="000" hidden="no" ttype="P" pid="-1"><name>Queen of Gravity</name><detail>All trophies.</detail></trophy>
  <trophy id="001" hidden="no" ttype="B" pid="000"><name>First Fall</name><detail>Fall &amp; land.</detail></trophy>
  <trophy pid="000" ttype="S" id="002" hidden="yes"><name>Secret</name><detail>Hidden.</detail></trophy>
</trophyconf>"""
        val user = "/vita/ux0/user/00/trophy"
        val fs = InMemoryFileSystem()
            .file("/vita/ux0/app/PCSA00011/sce_sys/trophy/NPWR01234_00/TROPHY.TRP", content = "trp")
            .file("$user/conf/NPWR01234_00/TROP.SFM", content = sfm)
            .bytes("$user/data/NPWR01234_00/TROPUSR.DAT", vitaUsr(mapOf(1 to 1_700_000_000L, 2 to 0L)))
            .file("$user/conf/NPWR09999_00/TROP.SFM", content = sfm.replace("Gravity Rush", "Another Game"))
        val reader = LocalAchievements(fs)
        // By the title id of its installed copy: the set its sce_sys/trophy names.
        val set = assertNotNull(reader.vitaTrophies(listOf("/vita"), "PCSA00011", emptyList()))
        assertEquals(LocalAchievementSource.VITA_TROPHIES, set.source)
        assertEquals("Gravity Rush", set.title)
        assertEquals(listOf("platinum", "bronze", "silver"), set.items.map { it.grade })
        assertEquals("Fall & land.", set.items[1].description)
        assertEquals(1_700_000_000_000L, set.items[1].unlockedAt)
        // Unlocked at a time Vita3K didn't keep: unlocked all the same.
        assertEquals(0L, set.items[2].unlockedAt)
        assertNull(set.items[0].unlockedAt)
        assertTrue(set.items[2].hidden)
        assertEquals("$user/conf/NPWR01234_00/TROP001.PNG", set.items[1].icon)
        assertEquals("$user/conf/NPWR01234_00/ICON0.PNG", set.icon)
        // By the set the game's own folder ships, without an installed copy.
        assertEquals("Another Game", reader.vitaTrophies(listOf("/vita"), null, listOf("NPWR09999_00"))?.title)
        // Never by name: a game whose set isn't known has none, even with a set of the same title there.
        assertNull(reader.vitaTrophies(listOf("/vita"), "PCSE00000", emptyList()))
        assertNull(reader.vitaTrophies(listOf("/vita"), null, listOf("Gravity Rush")))
        // A file that isn't Vita3K's (wrong magic, cut short) unlocks nothing.
        assertTrue(reader.vitaTropusr(ByteArray(1648)).isEmpty())
        assertTrue(reader.vitaTropusr(vitaUsr(mapOf(1 to 5L)).copyOf(600)).isEmpty())
    }

    /**
     * A title's GPD as Xenia writes it (xdbf_io.h, gpd_info.h): the XDBF header, 18-byte entries, one
     * free slot, then the data: achievements (section 1), a picture (2) and the game's name (5).
     */
    private fun gpd(): ByteArray {
        val out = ArrayList<Byte>()
        fun u16(v: Int) { out += (v shr 8).toByte(); out += v.toByte() }
        fun u32(v: Long) { u16((v ushr 16).toInt() and 0xFFFF); u16((v and 0xFFFF).toInt()) }
        fun u64(v: Long) { u32(v ushr 32); u32(v and 0xFFFFFFFFL) }
        fun utf16(s: String): List<Byte> = (s + "\u0000").flatMap { listOf((it.code shr 8).toByte(), it.code.toByte()) }
        fun achievement(id: Long, image: Long, score: Long, flags: Long, fileTime: Long, vararg texts: String): List<Byte> {
            val b = ArrayList<Byte>()
            fun b32(v: Long) { for (i in 3 downTo 0) b += (v ushr (8 * i)).toByte() }
            b32(0x1C); b32(id); b32(image); b32(score); b32(flags); b32(fileTime ushr 32); b32(fileTime and 0xFFFFFFFFL)
            texts.forEach { b += utf16(it) }
            return b
        }
        val earnedAt = (1_700_000_000_000L + 11_644_473_600_000L) * 10_000
        val png = listOf(0x89, 'P'.code, 'N'.code, 'G'.code, 13, 10, 26, 10, 0, 0).map { it.toByte() }
        val blocks = listOf(
            Triple(1, 1L, achievement(1, 101, 10, 0x8 or 0x20000 or 3, earnedAt, "Finish the Fight", "You finished it.", "Finish the fight.")),
            Triple(1, 2L, achievement(2, 101, 50, 3, 0, "Secret", "Found it.", "Keep playing.")),
            Triple(1, 3L, achievement(3, 999, 25, 0x8 or 3, 0, "Locked", "", "Do the thing.")),
            Triple(2, 101L, png),
            Triple(5, 0x8000L, utf16("Halo 3")),
        )
        out += "XDBF".encodeToByteArray().toList(); u32(0x10000); u32(blocks.size.toLong()); u32(blocks.size.toLong()); u32(1); u32(1)
        var offset = 0L
        for ((section, id, data) in blocks) { u16(section); u64(id); u32(offset); u32(data.size.toLong()); offset += data.size }
        u32(offset); u32(0)
        blocks.forEach { out += it.third }
        return out.toByteArray()
    }

    @Test
    fun xbox360AchievementsComeFromXeniasProfileByTitleId() = runTest {
        val profile = "/xenia/content/E030000012345678/FFFE07D1/00010000/E030000012345678"
        val other = "/xenia/content/E0300000AAAAAAAA/FFFE07D1/00010000/E0300000AAAAAAAA"
        val fs = InMemoryFileSystem()
            .bytes("$other/4D5307E6.gpd", gpd().also { it[it.size - 4] = 'X'.code.toByte() })
            // Played last on this profile: its file is the newer one.
            .bytes("$profile/4D5307E6.gpd", gpd())
            .bytes("/xenia/content/0000000000000000/FFFE07D1/00010000/0000000000000000/4D5307E6.gpd", gpd())
        val reader = LocalAchievements(fs)
        val set = assertNotNull(reader.xbox360Achievements(listOf("/xenia/content"), "4d5307e6"))
        assertEquals(LocalAchievementSource.XBOX360, set.source)
        assertEquals("Halo 3", set.title)
        assertEquals(listOf("1", "2", "3"), set.items.map { it.id })
        val first = set.items[0]
        assertEquals("Finish the Fight", first.name)
        assertEquals("You finished it.", first.description)
        assertEquals(1_700_000_000_000L, first.unlockedAt)
        assertEquals(10, first.points)
        assertTrue(first.icon!!.startsWith("data:image/png;base64,"))
        // Not shown before it is earned: hidden, and what to do is the description while locked.
        assertTrue(set.items[1].hidden)
        assertEquals("Keep playing.", set.items[1].description)
        assertNull(set.items[1].unlockedAt)
        // A picture the file doesn't have is none, rather than a broken one.
        assertNull(set.items[2].icon)
        // Only by title id: another id, or a name, finds nothing.
        assertNull(reader.xbox360Achievements(listOf("/xenia/content"), "41560817"))
        assertNull(reader.xbox360Achievements(listOf("/xenia/content"), "Halo 3"))
        // Not a GPD: nothing.
        assertNull(reader.gpd("not an xdbf file at all".encodeToByteArray()))
    }
}
