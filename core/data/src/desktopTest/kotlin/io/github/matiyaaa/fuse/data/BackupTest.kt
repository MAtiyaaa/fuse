package io.github.matiyaaa.fuse.data

import io.github.matiyaaa.fuse.data.backup.BackupContent
import io.github.matiyaaa.fuse.data.backup.BackupPart
import io.github.matiyaaa.fuse.data.backup.BackupRepository
import io.github.matiyaaa.fuse.model.EmulatorId
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.LibrarySourceKind
import io.github.matiyaaa.fuse.model.MediaKind
import io.github.matiyaaa.fuse.model.MediaOwner
import io.github.matiyaaa.fuse.model.MediaSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackupTest {
    private val files = listOf("psx/Crash.chd", "psx/Spyro.chd", "gba/Golden Sun.gba")

    /** A library at [root] holding [files], each game on the system its folder names. */
    private suspend fun TestDb.library(root: String): Map<String, GameId> {
        val source = data.sources.add(root, "ROMs", LibrarySourceKind.ROMS_ROOT)
        val folders = files.groupBy { it.substringBefore('/') }.map { (platform, names) ->
            folder("$root/$platform", names.map { scanned("$root/$it", platform = platform, source = source.value) }, platform = platform)
        }
        data.indexer.apply(report(*folders.toTypedArray()), now, testCleaner)
        return data.games.observeAll().first().associate { data.games.get(it.id)!!.location.path.removePrefix("$root/") to it.id }
    }

    /** Through JSON and back, as the archive carries it. */
    private fun BackupContent.carried(): BackupContent =
        BackupRepository.Codec.decodeFromString(BackupContent.serializer(), BackupRepository.Codec.encodeToString(BackupContent.serializer(), this))

    @Test
    fun aLibraryOnAnotherCardGetsEverythingBack() = runBlocking {
        val backup = TestDb().use { t ->
            val ids = t.library("/run/media/me/OLD/ROMs")
            val crash = ids.getValue("psx/Crash.chd")
            t.data.games.rename(crash, "Crash Bandicoot")
            t.data.games.setFavorite(crash, true)
            t.data.games.setEmulatorOverride(crash, EmulatorId("duckstation"))
            t.data.games.setHidden(ids.getValue("gba/Golden Sun.gba"), true)
            val session = t.data.playSessions.start(crash, EmulatorId("duckstation"), t.now)
            t.data.playSessions.end(session, t.now + 3_600_000)
            val favs = t.data.collections.create("Couch games")
            t.data.collections.addGames(favs, listOf(crash, ids.getValue("psx/Spyro.chd")))
            t.data.media.setCustom(MediaOwner.OfGame(crash), MediaKind.BOXART, localPath = null, remoteUrl = "https://example.com/crash.png")
            t.db.settingQueries.put("GAME", crash.value.toString(), "launch", """{"fast":true}""", t.now)
            t.data.settings.update { it.copy(appearance = it.appearance.copy(themeId = "dusk")) }
            t.data.backup.export().carried()
        }

        TestDb().use { t ->
            val ids = t.library("/storage/SD/Games")
            val report = t.data.backup.restore(backup, BackupPart.entries.toSet())

            val crash = t.data.games.get(ids.getValue("psx/Crash.chd"))!!
            assertEquals("Crash Bandicoot", crash.displayTitle)
            assertTrue(crash.favorite)
            assertEquals(EmulatorId("duckstation"), crash.emulatorOverride)
            assertEquals(3_600L, crash.play.trackedSeconds)
            assertEquals(1, crash.play.sessions)
            assertTrue(t.data.games.get(ids.getValue("gba/Golden Sun.gba"))!!.hidden)
            assertFalse(t.data.games.get(ids.getValue("psx/Spyro.chd"))!!.favorite)

            val couch = t.data.collections.observeManual().first().single()
            assertEquals("Couch games", couch.name)
            assertEquals(2, couch.gameCount)
            val art = t.data.media.get(MediaOwner.OfGame(crash.id)).first(MediaKind.BOXART)!!
            assertEquals(MediaSource.USER, art.source)
            assertEquals("https://example.com/crash.png", art.remoteUrl)
            assertEquals("""{"fast":true}""", t.db.settingQueries.get("GAME", crash.id.value.toString(), "launch").executeAsOneOrNull())
            assertEquals("dusk", t.data.settings.current().appearance.themeId)

            assertEquals(1, report.sessions)
            assertEquals(1, report.collections)
            assertEquals(0, report.gamesNotFound)
        }
    }

    @Test
    fun restoringNeverErasesWhatThisLibraryHas() = runBlocking {
        val backup = TestDb().use { t ->
            val ids = t.library("/a")
            t.data.games.setFavorite(ids.getValue("psx/Spyro.chd"), true)
            t.data.media.setCustom(MediaOwner.OfGame(ids.getValue("psx/Spyro.chd")), MediaKind.BOXART, null, "https://example.com/old.png")
            t.data.backup.export().carried()
        }
        TestDb().use { t ->
            val ids = t.library("/a")
            val spyro = ids.getValue("psx/Spyro.chd")
            t.data.games.rename(spyro, "Spyro the Dragon")
            t.data.media.setCustom(MediaOwner.OfGame(spyro), MediaKind.BOXART, null, "https://example.com/new.png")

            t.data.backup.restore(backup, BackupPart.entries.toSet())

            val game = t.data.games.get(spyro)!!
            assertEquals("Spyro the Dragon", game.displayTitle)
            assertTrue(game.favorite)
            assertEquals("https://example.com/new.png", t.data.media.get(MediaOwner.OfGame(spyro)).first(MediaKind.BOXART)!!.remoteUrl)
        }
    }

    @Test
    fun restoringTwiceCountsPlayTimeOnce() = runBlocking {
        val backup = TestDb().use { t ->
            val crash = t.library("/a").getValue("psx/Crash.chd")
            repeat(2) { i ->
                val s = t.data.playSessions.start(crash, null, t.now + i * 10_000_000L)
                t.data.playSessions.end(s, t.now + i * 10_000_000L + 600_000)
            }
            t.data.backup.export().carried()
        }
        TestDb().use { t ->
            val crash = t.library("/a").getValue("psx/Crash.chd")
            assertEquals(2, t.data.backup.restore(backup, setOf(BackupPart.PLAYTIME)).sessions)
            assertEquals(0, t.data.backup.restore(backup, setOf(BackupPart.PLAYTIME)).sessions)
            val play = t.data.games.get(crash)!!.play
            assertEquals(1_200L, play.trackedSeconds)
            assertEquals(2, play.sessions)
            assertEquals(2, t.data.playSessions.sessions(crash).first().size)
        }
    }

    @Test
    fun partsStayApart() = runBlocking {
        val backup = TestDb().use { t ->
            val crash = t.library("/a").getValue("psx/Crash.chd")
            t.data.games.setFavorite(crash, true)
            t.data.settings.update {
                it.copy(
                    appearance = it.appearance.copy(themeId = "dusk"),
                    sound = it.sound.copy(enabled = false),
                    performance = it.performance.copy(lowPowerMode = true),
                    home = it.home.copy(continueDismissed = mapOf("99" to 1L)),
                )
            }
            t.data.backup.export().carried()
        }
        TestDb().use { t ->
            val crash = t.library("/a").getValue("psx/Crash.chd")
            t.data.settings.update { it.copy(home = it.home.copy(continueDismissed = mapOf("1" to 5L))) }

            t.data.backup.restore(backup, setOf(BackupPart.SETTINGS))
            var now = t.data.settings.current()
            assertFalse(now.sound.enabled)
            assertEquals("fuse", now.appearance.themeId)
            // The performance profile belongs to the device it was set on.
            assertFalse(now.performance.lowPowerMode)
            assertFalse(t.data.games.get(crash)!!.favorite)

            t.data.backup.restore(backup, setOf(BackupPart.APPEARANCE))
            now = t.data.settings.current()
            assertEquals("dusk", now.appearance.themeId)
            // Which games were taken off Continue playing names this library's games, so it stays.
            assertEquals(mapOf("1" to 5L), now.home.continueDismissed)
        }
    }

    @Test
    fun twoGamesOfTheSameNameAreNeverGuessedBetween() = runBlocking {
        val backup = TestDb().use { t ->
            val ids = t.library("/a")
            t.data.games.setFavorite(ids.getValue("psx/Crash.chd"), true)
            t.data.backup.export().carried()
        }
        TestDb().use { t ->
            // The same name twice on the same system, in folders the backup didn't have.
            val source = t.data.sources.add("/b", "ROMs", LibrarySourceKind.ROMS_ROOT)
            t.data.indexer.apply(
                report(folder("/b/psx", listOf(
                    scanned("/b/psx/us/Crash.chd", platform = "psx", source = source.value),
                    scanned("/b/psx/eu/Crash.chd", platform = "psx", source = source.value),
                ), platform = "psx")),
                t.now, testCleaner,
            )
            val report = t.data.backup.restore(backup, BackupPart.entries.toSet())
            assertEquals(1, report.gamesNotFound)
            assertTrue(t.data.games.observeAll().first().none { it.favorite })
        }
    }

    @Test
    fun anEmptyBackupChangesNothing() = runBlocking {
        TestDb().use { t ->
            val crash = t.library("/a").getValue("psx/Crash.chd")
            t.data.games.rename(crash, "Crash")
            val report = t.data.backup.restore(BackupContent(), BackupPart.entries.toSet())
            assertEquals(0, report.games)
            assertFalse(report.settings)
            assertEquals("Crash", t.data.games.get(crash)!!.displayTitle)
            assertNull(t.data.games.get(crash)!!.emulatorOverride)
        }
    }
}
