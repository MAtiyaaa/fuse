package io.github.matiyaaa.fuse.sync

import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelTest {
    private val game = GameKey.of("snes", null, null, "Chrono Trigger")

    private fun session(id: String, device: String, minutes: Long, start: Long = 1_000_000) =
        SessionEntry(id, device, start, start + minutes * 60_000)

    @Test
    fun playTimeAddsUpAcrossDevicesPlayedOffline() {
        // 30 minutes on the Deck and 20 on the PC, both offline: 50 once they meet, either way round.
        val deck = ProfileMeta().withGame(GameRecord(game).played(session("a", "deck", 30)))
        val pc = ProfileMeta().withGame(GameRecord(game).played(session("b", "pc", 20, start = 5_000_000)))
        val one = deck.merge(pc)
        val other = pc.merge(deck)
        assertEquals(one, other)
        assertEquals(50 * 60L, one.game(game).totalSeconds)
        // Merging again changes nothing (a retried sync never counts twice).
        assertEquals(one, one.merge(deck).merge(pc))
        assertEquals(5_000_000 + 20 * 60_000L, one.game(game).lastPlayed)
    }

    @Test
    fun theSameSessionIsCountedOnce() {
        val r = GameRecord(game).played(session("a", "deck", 10)).played(session("a", "deck", 10))
        assertEquals(600L, r.totalSeconds)
        assertEquals(1, r.sessions.size)
    }

    @Test
    fun theLaterSettingWinsWhateverOrderItArrives() {
        val earlier = Lww(true, Hlc(100, 0, "deck"))
        val later = Lww(false, Hlc(200, 0, "pc"))
        val a = GameRecord(game, favorite = earlier).merge(GameRecord(game, favorite = later))
        val b = GameRecord(game, favorite = later).merge(GameRecord(game, favorite = earlier))
        assertEquals(false, a.favorite?.value)
        assertEquals(a, b)
        // Same millisecond: the counter, then the device, decide, the same way everywhere.
        assertTrue(Hlc(100, 1, "a") > Hlc(100, 0, "z"))
        assertTrue(Hlc(100, 0, "b") > Hlc(100, 0, "a"))
    }

    @Test
    fun aSlowClockStillMovesForward() {
        var wall = 1_000L
        val clock = HlcClock("deck") { wall }
        val first = clock.now()
        clock.seen(Hlc(9_000, 3, "pc"))
        wall = 1_001
        val next = clock.now()
        assertTrue(next > Hlc(9_000, 3, "pc"))
        assertTrue(next > first)
    }

    @Test
    fun collectionsKeepBothDevicesChanges() {
        val base = CollectionRecord("c1", Lww("RPGs", Hlc(1, 0, "a")))
        val added = base.copy(members = mapOf("snes:t.a" to Lww(true, Hlc(5, 0, "a"))))
        val other = base.copy(members = mapOf("snes:t.b" to Lww(true, Hlc(6, 0, "b"))))
        val removedOnB = added.copy(members = added.members + ("snes:t.a" to Lww(false, Hlc(9, 0, "b"))))
        assertEquals(listOf("snes:t.a", "snes:t.b"), added.merge(other).games)
        assertEquals(listOf("snes:t.b"), added.merge(other).merge(removedOnB).games)
    }

    @Test
    fun settingsMergeByKey() {
        val a = ProfileMeta(settings = mapOf("theme" to Lww(JsonPrimitive("midnight"), Hlc(10, 0, "a"))))
        val b = ProfileMeta(settings = mapOf("theme" to Lww(JsonPrimitive("dawn"), Hlc(20, 0, "b")), "clock24" to Lww(JsonPrimitive(true), Hlc(1, 0, "b"))))
        val m = a.merge(b)
        assertEquals(JsonPrimitive("dawn"), m.settings["theme"]?.value)
        assertEquals(2, m.settings.size)
    }

    @Test
    fun continuePlayingFollowsTheProfile() {
        val b = GameKey.of("gba", "AGB-BPEE", null, "Emerald")
        val meta = ProfileMeta()
            .withGame(GameRecord(game).played(session("a", "deck", 10, start = 100)))
            .withGame(GameRecord(b).played(session("b", "pc", 10, start = 900_000)))
        assertEquals(listOf(b, game), meta.continuePlaying().map { it.key })
        val dismissed = meta.withGame(meta.game(b).copy(continueDismissed = Lww(2_000_000L, Hlc(1, 0, "pc"))))
        assertEquals(listOf(game), dismissed.continuePlaying().map { it.key })
    }

    @Test
    fun gameKeysIgnoreWherTheFileIs() {
        assertEquals(GameKey.of("psx", "SLUS-00067", null, "Castlevania (USA)"), GameKey.of("PSX", "slus00067", "abc", "Castlevania"))
        assertEquals("snes:t.chrono-trigger", game.id)
        assertEquals(game, GameKey.parse(game.id))
        assertNull(GameKey.parse("nocolon"))
    }

    @Test
    fun decisionsNeverTrustClocks() {
        assertEquals(SyncDecision.UpToDate, SyncRules.decide("r1", localChanged = false, hostHead = "r1"))
        assertEquals(SyncDecision.Upload, SyncRules.decide("r1", localChanged = true, hostHead = "r1"))
        assertEquals(SyncDecision.Download, SyncRules.decide("r1", localChanged = false, hostHead = "r2"))
        assertEquals(SyncDecision.Conflict, SyncRules.decide("r1", localChanged = true, hostHead = "r2"))
        // Both arrived at the same files: nothing to settle.
        assertEquals(SyncDecision.UpToDate, SyncRules.decide("r1", localChanged = true, hostHead = "r2", sameContent = true))
        // A first save on a new device with one on the host: the host's comes down.
        assertEquals(SyncDecision.Download, SyncRules.decide(null, localChanged = false, hostHead = "r9"))
    }

    @Test
    fun retentionKeepsRecentDailyWeeklyAndEveryMilestone() {
        val day = 86_400_000L
        val now = 100 * day
        val revs = (0 until 100).map { i ->
            SaveRevision("r$i", "p", game.id, SaveKind.SAVE, if (i == 0) null else "r${i - 1}", "d", "Deck", Hlc(now - i * day / 2, 0, "d"), SaveManifest("f", emptyList()),
                reason = if (i == 90) RevisionReason.MILESTONE else if (i == 95) RevisionReason.CONFLICT_COPY else RevisionReason.PLAYED)
        }
        val keep = Retention(recent = 5, days = 7, weeks = 4).keep(revs, now)
        assertTrue("r0" in keep)
        assertTrue((0 until 5).all { "r$it" in keep })
        assertTrue("r90" in keep && "r95" in keep)
        assertTrue(keep.size < revs.size)
        // An old ordinary save is let go.
        assertFalse("r80" in keep)
    }

    @Test
    fun savePathsCantLeaveTheSave() {
        listOf("../x", "a/../../x", "/etc/passwd", "C:\\x", "a\\b", "", "a//b", "./a", "a/./b", "con:x").forEach { assertFalse(SavePath.isSafe(it), it) }
        listOf("Chrono Trigger.srm", "SAVEDATA/ULUS10041/DATA.BIN", "card 1.mcd").forEach { assertTrue(SavePath.isSafe(it), it) }
        assertTrue(SavePath.isHash("a".repeat(64)))
        assertFalse(SavePath.isHash("A".repeat(64)))
        assertFalse(SavePath.isHash("../" + "a".repeat(61)))
    }

    @Test
    fun pinsAreStretchedAndCheckedNeverStored() {
        val stored = SyncCrypto.hashSecret("2468", iterations = 1_000)
        assertFalse("2468" in stored)
        assertTrue(SyncCrypto.verifySecret("2468", stored))
        assertFalse(SyncCrypto.verifySecret("2469", stored))
        assertFalse(SyncCrypto.verifySecret("2468", "garbage"))
    }

    @Test
    fun aSealOpensOnlyWithItsCode() {
        val salt = SyncCrypto.token(16)
        val sealed = SyncCrypto.seal("secret".toByteArray(), "ABCD2345", salt)
        assertEquals("secret", SyncCrypto.open(sealed, "abcd2345", salt)?.decodeToString())
        assertNull(SyncCrypto.open(sealed, "ABCD2346", salt))
        // A changed byte doesn't open.
        val bytes = SyncCrypto.decode(sealed).also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertNull(SyncCrypto.open(SyncCrypto.encode(bytes), "ABCD2345", salt))
    }

    @Test
    fun theContentStoreRefusesDamagedFiles() {
        val dir = Files.createTempDirectory("cas").toFile()
        try {
            val store = ContentStore(dir)
            val h = store.put("hello".toByteArray())
            assertEquals(SyncCrypto.sha256("hello".toByteArray()), h)
            assertTrue(store.verify(h))
            val wrong = SyncCrypto.sha256("other".toByteArray())
            assertFailsWith<IntegrityException> { store.put("hello!".toByteArray().inputStream(), expected = wrong) }
            assertFalse(store.has(wrong))
            // Nothing half-written is left behind.
            assertTrue(File(dir, "tmp").listFiles().orEmpty().isEmpty())
            // Rot on disk is noticed.
            store.fileOf(h).writeText("hellp")
            assertFalse(store.verify(h))
            assertFailsWith<IllegalArgumentException> { store.fileOf("../../etc/passwd") }
            assertEquals(5L, store.collect(emptySet()))
        } finally {
            dir.deleteRecursively()
        }
    }
}
