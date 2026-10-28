package io.github.matiyaaa.fuse.romm

import io.github.matiyaaa.fuse.model.PlayStatus
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The Journal in RomM's words (RomM's `RomUserStatus` and the backlog and now-playing flags beside
 * it, backend/models/rom.py; `rom_user` on each game, backend/endpoints/responses/rom.py).
 */
class RommJournalTest {
    @Test
    fun everyStatusComesBackAsItWent() {
        for (s in PlayStatus.entries + listOf(null)) {
            assertEquals(s, RommJournal.statusOf(RommJournal.marks(s, 6)), "$s")
        }
        assertEquals(RommMarks(RommJournal.INCOMPLETE, nowPlaying = true, rating = 7), RommJournal.marks(PlayStatus.PLAYING, 7))
        assertEquals(RommMarks(backlogged = true), RommJournal.marks(PlayStatus.WANT, 0))
        assertEquals(RommMarks(RommJournal.RETIRED), RommJournal.marks(PlayStatus.DROPPED, 0))
    }

    @Test
    fun romMsOwnMarksReadAsTheNearestStatus() {
        // An ending says more than the flags left on beside it.
        assertEquals(PlayStatus.BEATEN, RommJournal.statusOf(RommMarks(RommJournal.FINISHED, backlogged = true, nowPlaying = true)))
        assertEquals(PlayStatus.DROPPED, RommJournal.statusOf(RommMarks(RommJournal.NEVER)))
        assertEquals(PlayStatus.PLAYING, RommJournal.statusOf(RommMarks(nowPlaying = true)))
        assertEquals(PlayStatus.WANT, RommJournal.statusOf(RommMarks(backlogged = true)))
        assertNull(RommJournal.statusOf(RommMarks()))
    }

    @Test
    fun aGamesMarksAreReadFromItsRomUser() {
        val rom = RommParse.rom(
            Json.parseToJsonElement(
                """{"id":7,"platform_id":1,"platform_slug":"psx","name":"Crash","fs_name":"Crash.chd",
                   "rom_user":{"id":3,"status":"finished","backlogged":false,"now_playing":false,"rating":9,"updated_at":"2026-10-01T00:00:00Z"}}""",
            ),
        )!!
        assertEquals(RommMarks(RommJournal.FINISHED, rating = 9, updatedAt = RommParse.millis("2026-10-01T00:00:00Z")), rom.marks)
        // RomM's stand-in for a game the person never marked says nothing.
        val unmarked = RommParse.rom(Json.parseToJsonElement("""{"id":8,"name":"Spyro","fs_name":"Spyro.chd","rom_user":{"id":-1,"rating":0}}"""))!!
        assertNull(unmarked.marks)
    }

    @Test
    fun onlyTheLastJournalOfAGameWaitsToGo() {
        var queue = RommWrites.add(emptyList(), RommWrite.Journal(7, "PLAYING", 0))
        queue = RommWrites.add(queue, RommWrite.Favourite(7, true))
        queue = RommWrites.add(queue, RommWrite.Journal(7, "BEATEN", 8))
        queue = RommWrites.add(queue, RommWrite.Journal(9, "WANT", 0))
        assertEquals(listOf(RommWrite.Favourite(7, true), RommWrite.Journal(7, "BEATEN", 8), RommWrite.Journal(9, "WANT", 0)), queue)
    }
}
