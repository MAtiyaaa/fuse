package io.github.matiyaaa.fuse.sync

import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class IdentityAliasesTest {
    @Test fun punctuationOnlySerialsDoNotCreateOneSharedStrongIdentity() {
        assertEquals(GameKey.of("ps4", null, null, "First"), GameKey.of("ps4", "---", null, "First"))
        assertNotEquals(GameKey.of("ps4", "---", null, "First"), GameKey.of("ps4", "---", null, "Second"))
    }

    @Test fun strongClaimsCannotAbsorbAnEarlierTitleOnlyCopyInEitherArrivalOrder() = temporary { dir ->
        val title = GameKey.of("ps4", null, null, "Game").id
        val serial = GameKey.of("ps4", "CUSA00001", null, "Game").id
        for ((index, lists) in listOf(listOf(listOf(title), listOf(serial, title)), listOf(listOf(serial, title), listOf(title))).withIndex()) {
            val host = HostStore(java.io.File(dir, index.toString()))
            val ids = host.resolveGames(lists)
            assertNotEquals(ids[0], ids[1])
            assertEquals(title, host.resolveGames(listOf(listOf(title))).single())
            assertEquals(serial, host.resolveGames(listOf(listOf(serial, title))).single())
        }
    }

    @Test fun hashesAndExplicitProviderOrRommIdentitiesReuseProvenAliasesWithoutTitleSearch() = temporary { dir ->
        val host = HostStore(dir)
        val serial = "switch:s.0100000000000000"
        val hash = "switch:h.1234abcd"
        val provider = "switch:p.igdb.123"
        val romm = "switch:r.household-42"
        val canonical = host.resolveGames(listOf(listOf(serial, hash, provider, romm))).single()
        for (identity in listOf(serial, hash, provider, romm)) {
            assertEquals(canonical, host.resolveGames(listOf(listOf(identity))).single())
        }
        val conflict = "switch:s.0100000000000001"
        assertNotEquals(canonical, host.resolveGames(listOf(listOf(conflict, provider))).single())
        assertEquals(canonical, HostStore(dir).resolveGames(listOf(listOf(hash))).single())
    }

    @Test fun newlyLearnedStrongEvidenceJoinsPreviouslyIndependentCanonicalGroups() = temporary { dir ->
        val host = HostStore(dir)
        val serial = "ps4:s.cusa00001"
        val hash = "ps4:h.abcd"
        assertEquals(listOf(serial, hash), host.resolveGames(listOf(listOf(serial), listOf(hash))))
        assertEquals(serial, host.resolveGames(listOf(listOf(serial, hash))).single())
        assertEquals(serial, host.resolveGames(listOf(listOf(hash))).single())
        assertEquals(serial, HostStore(dir).resolveGames(listOf(listOf(hash))).single())
    }

    @Test fun indirectProviderBridgeCannotMergeGroupsWithConflictingSerials() = temporary { dir ->
        val host = HostStore(dir)
        val first = "ps4:s.cusa00001"
        val second = "ps4:s.cusa00002"
        val hash = "ps4:h.abcd"
        val provider = "ps4:p.igdb.123"
        host.resolveGames(listOf(listOf(first, hash), listOf(second, provider)))
        host.resolveGames(listOf(listOf(hash, provider)))
        assertEquals(first, host.resolveGames(listOf(listOf(hash))).single())
        assertEquals(second, host.resolveGames(listOf(listOf(provider))).single())
    }

    @Test fun legacyConflictingSerialAliasesSplitWithoutGuessingHistoricalOwnership() = temporary { dir ->
        val first = "ps4:s.cusa00001"
        val second = "ps4:s.cusa00002"
        val title = "ps4:t.game"
        java.io.File(dir, "game-aliases.json").writeText(Json.encodeToString(GameAliasesSerializer,
            mapOf(first to first, second to first, title to first)))
        val host = HostStore(dir)
        assertEquals(listOf(first, second, title), host.resolveGames(listOf(listOf(first), listOf(second), listOf(title))))
        assertEquals(listOf(first, second), HostStore(dir).resolveGames(listOf(listOf(first), listOf(second))))
    }

    @Test fun legacyUnambiguousTitleCanonicalCanMoveToItsStrongIdentityOnce() = temporary { dir ->
        val serial = "ps4:s.cusa00001"
        val title = "ps4:t.game"
        java.io.File(dir, "game-aliases.json").writeText(Json.encodeToString(GameAliasesSerializer,
            mapOf(serial to title, title to title)))
        val host = HostStore(dir)
        assertEquals(serial, host.resolveGames(listOf(listOf(serial, title))).single())
        assertEquals(title, host.resolveGames(listOf(listOf(title))).single())
        assertEquals(serial, HostStore(dir).resolveGames(listOf(listOf(serial))).single())
    }

    @Test fun unambiguousLegacyMigrationPreservesProfileChoicesAndCollectionMembership() = temporary { dir ->
        val original = HostStore(dir)
        val person = original.createProfile(NewProfile("Person", "fox"))
        val title = GameKey("ps4", "t.game")
        val serial = GameKey("ps4", "s.cusa00001")
        val favorite = Lww(true, Hlc(10, 0, "pc"))
        val record = GameRecord(title, favorite = favorite, title = Lww("My game", favorite.at))
        original.mergeMeta(person.id, "pc", ProfileMeta(games = mapOf(title.id to record),
            collections = mapOf("c1" to CollectionRecord("c1", Lww("Mine", favorite.at), members = mapOf(title.id to favorite)))))
        java.io.File(dir, "game-aliases.json").writeText(Json.encodeToString(GameAliasesSerializer,
            mapOf(serial.id to title.id, title.id to title.id)))
        val restarted = HostStore(dir)
        val migrated = restarted.meta(person.id).meta
        assertEquals("My game", migrated.games.getValue(serial.id).title?.value)
        assertEquals(favorite, migrated.games.getValue(serial.id).favorite)
        assertEquals(setOf(serial.id), migrated.collections.getValue("c1").members.keys)
    }

    @Test fun cachedWeakBridgesAndConflictingStrongAliasesAreNotUsedOffline() {
        val serial = "ps4:s.cusa00001"
        val other = "ps4:s.cusa00002"
        val title = "ps4:t.game"
        val hash = "ps4:h.abcd"
        val safe = safeCachedGameAliases(mapOf(serial to serial, other to serial, title to serial, hash to serial))
        assertEquals(mapOf(serial to serial), safe)
        assertTrue(safeCachedGameAliases(mapOf(serial to title, title to title)).containsKey(title))
        assertTrue(!safeCachedGameAliases(mapOf(serial to title, title to title)).containsKey(serial))
    }

    @Test fun aliasMigrationRemapsCollectionsEvenWithoutAGameRecord() {
        val from = "ps4:t.game"
        val to = "ps4:s.cusa00001"
        val member = Lww(true, Hlc(1, 0, "deck"))
        val meta = ProfileMeta(collections = mapOf("c1" to CollectionRecord("c1", Lww("Mine", member.at), members = mapOf(from to member))))
        assertEquals(mapOf(to to member), meta.byIds(mapOf(from to to)).collections.getValue("c1").members)
    }

    private fun temporary(block: (java.io.File) -> Unit) {
        val dir = Files.createTempDirectory("fuse-identity-aliases").toFile()
        try { block(dir) } finally { dir.deleteRecursively() }
    }
}
