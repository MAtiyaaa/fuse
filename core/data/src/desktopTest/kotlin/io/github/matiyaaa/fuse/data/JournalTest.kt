package io.github.matiyaaa.fuse.data

import io.github.matiyaaa.fuse.data.backup.BackupContent
import io.github.matiyaaa.fuse.data.backup.BackupPart
import io.github.matiyaaa.fuse.data.backup.BackupRepository
import io.github.matiyaaa.fuse.data.search.LibrarySearch
import io.github.matiyaaa.fuse.data.search.SearchFilter
import io.github.matiyaaa.fuse.data.search.SearchSyntax
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.GameJournal
import io.github.matiyaaa.fuse.model.LibrarySourceKind
import io.github.matiyaaa.fuse.model.PlayStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Journal (0.4.6): each game's status, rating and note, kept, listed, searched and backed up. */
class JournalTest {
    private val files = listOf("psx/Crash.chd", "psx/Spyro.chd", "gba/Golden Sun.gba")

    private suspend fun TestDb.library(root: String): Map<String, GameId> {
        val source = data.sources.add(root, "ROMs", LibrarySourceKind.ROMS_ROOT)
        val folders = files.groupBy { it.substringBefore('/') }.map { (platform, names) ->
            folder("$root/$platform", names.map { scanned("$root/$it", platform = platform, source = source.value) }, platform = platform)
        }
        data.indexer.apply(report(*folders.toTypedArray()), now, testCleaner)
        return data.games.observeAll().first().associate { data.games.get(it.id)!!.location.path.removePrefix("$root/") to it.id }
    }

    @Test
    fun aJournalIsKeptAsWrittenAndListedByStatus() = runBlocking {
        TestDb().use { t ->
            val ids = t.library("/roms")
            val crash = ids.getValue("psx/Crash.chd")
            val spyro = ids.getValue("psx/Spyro.chd")
            val sun = ids.getValue("gba/Golden Sun.gba")
            t.data.games.setJournal(crash, GameJournal(PlayStatus.BEATEN, statusAt = 5_000, rating = 9, note = "  Warped through it.  "))
            t.data.games.setJournal(spyro, GameJournal(PlayStatus.WANT, statusAt = 7_000))
            t.data.games.setJournal(sun, GameJournal(PlayStatus.WANT, statusAt = 9_000, rating = 14))

            val kept = t.data.games.get(crash)!!.journal
            assertEquals(GameJournal(PlayStatus.BEATEN, 5_000, 9, "Warped through it."), kept)
            // Out of ten at most.
            assertEquals(10, t.data.games.get(sun)!!.journal.rating)
            // Up Next, the latest added first; the whole Journal by any status.
            assertEquals(listOf(sun, spyro), t.data.games.observeJournal(listOf(PlayStatus.WANT)).first().map { it.id })
            assertEquals(setOf(crash, spyro, sun), t.data.games.observeJournal().first().map { it.id }.toSet())
            // Lists carry the status and rating for tiles.
            val summary = t.data.games.summary(crash)!!
            assertEquals(PlayStatus.BEATEN, summary.status)
            assertEquals(9, summary.rating)

            // A status set without a time takes the time it is written; clearing it clears the time.
            t.data.games.setJournal(spyro, GameJournal(PlayStatus.PLAYING))
            assertEquals(t.now, t.data.games.get(spyro)!!.journal.statusAt)
            t.data.games.setJournal(spyro, GameJournal())
            assertEquals(GameJournal(), t.data.games.get(spyro)!!.journal)
        }
    }

    @Test
    fun searchReadsStatusesAndStars() = runBlocking {
        fun filter(text: String) = SearchSyntax.parse("$text ").filters.single()
        assertEquals(SearchFilter.Status(setOf(PlayStatus.WANT), "status:backlog"), filter("status:backlog"))
        assertEquals(SearchFilter.Status(setOf(PlayStatus.PAUSED), "journal:on-hold"), filter("journal:on-hold"))
        assertEquals(SearchFilter.Status(emptySet(), "status:yes", any = true), filter("status:yes"))
        assertEquals(SearchFilter.Status(emptySet(), "status:no", none = true), filter("status:no"))
        assertTrue(SearchSyntax.parse("status:someday ").invalid.isNotEmpty())
        // Stars as typed, kept out of ten: "4" is four stars up to four and a half.
        assertEquals(8 to 9, SearchSyntax.rating("4"))
        assertEquals(9 to 9, SearchSyntax.rating("4.5"))
        assertEquals(7 to 10, SearchSyntax.rating(">=3.5"))
        assertEquals(7 to 10, SearchSyntax.rating(">3"))
        assertEquals(6 to 10, SearchSyntax.rating("3-5"))
        assertEquals(0 to 0, SearchSyntax.rating("no"))
        assertNull(SearchSyntax.rating("6"))

        TestDb().use { t ->
            val ids = t.library("/roms")
            t.data.games.setJournal(ids.getValue("psx/Crash.chd"), GameJournal(PlayStatus.BEATEN, rating = 9))
            t.data.games.setJournal(ids.getValue("gba/Golden Sun.gba"), GameJournal(PlayStatus.WANT))
            val entries = t.data.search.readEntries()
            fun found(text: String) = LibrarySearch.run(entries, SearchSyntax.parse(text), t.now, 0).map { it.entry.summary.displayTitle }.toSet()
            assertEquals(setOf("Crash"), found("status:beaten"))
            assertEquals(setOf("Golden Sun"), found("status:next"))
            assertEquals(setOf("Crash", "Golden Sun"), found("status:yes"))
            assertEquals(setOf("Spyro"), found("status:no"))
            assertEquals(setOf("Crash"), found("rating:>=4"))
            assertEquals(setOf("Spyro", "Golden Sun"), found("rating:no"))
        }
    }

    @Test
    fun aBackupCarriesTheJournalAndNeverErasesOne() = runBlocking {
        val backup = TestDb().use { t ->
            val ids = t.library("/old")
            t.data.games.setJournal(ids.getValue("psx/Crash.chd"), GameJournal(PlayStatus.COMPLETED, statusAt = 3_000, rating = 10, note = "Every gem."))
            t.data.backup.export().let {
                BackupRepository.Codec.decodeFromString(BackupContent.serializer(), BackupRepository.Codec.encodeToString(BackupContent.serializer(), it))
            }
        }
        TestDb().use { t ->
            val ids = t.library("/new")
            // Here already: a note on Spyro the backup knows nothing of.
            t.data.games.setJournal(ids.getValue("psx/Spyro.chd"), GameJournal(note = "Glide more."))
            t.data.backup.restore(backup, BackupPart.entries.toSet())
            assertEquals(GameJournal(PlayStatus.COMPLETED, 3_000, 10, "Every gem."), t.data.games.get(ids.getValue("psx/Crash.chd"))!!.journal)
            assertEquals("Glide more.", t.data.games.get(ids.getValue("psx/Spyro.chd"))!!.journal.note)
        }
    }
}
