package io.github.matiyaaa.fuse.sync

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConvergenceStateTest {
    @Test fun settingEditsKeepTheirOriginalDurableClockInsteadOfTimeOfReconnect() {
        val old = Lww<JsonElement>(JsonPrimitive("fuse"), Hlc(100, 0, "pc"))
        val edit = Lww<JsonElement>(JsonPrimitive("fusi"), Hlc(200, 0, "thor"))
        val diff = ProfileDiff.changes(ProfileMeta(settings = mapOf("theme" to old)),
            ProfileMeta(settings = mapOf("theme" to edit)), "thor", HlcClock("thor") { 9000 })
        assertEquals(edit, diff.settings["theme"])
        val newerRemote = old.copy(at = Hlc(300, 0, "deck"))
        assertTrue(ProfileDiff.changes(ProfileMeta(settings = mapOf("theme" to newerRemote)),
            ProfileMeta(settings = mapOf("theme" to edit)), "thor", HlcClock("thor") { 9000 }).settings.isEmpty())
    }

    @Test fun similarTitlesCannotUnifyDifferentSerialsOrPlatforms() {
        val dir = Files.createTempDirectory("fuse-strong-identities").toFile()
        try {
            val host = HostStore(dir)
            val title = GameKey.of("ps4", null, null, "The Game").id
            val one = GameKey.of("ps4", "CUSA00001", null, "The Game").id
            val two = GameKey.of("ps4", "CUSA00002", null, "The Game").id
            val first = host.resolveGames(listOf(listOf(one, title))).single()
            val second = host.resolveGames(listOf(listOf(two, title))).single()
            assertTrue(first != second)
            assertEquals(first, host.resolveGames(listOf(listOf(one, title))).single())
            assertEquals(second, host.resolveGames(listOf(listOf(two, title))).single())
            val ps5 = GameKey.of("ps5", "PPSA00001", null, "The Game").id
            assertTrue(host.resolveGames(listOf(listOf(ps5, title))).single() != first)
            val restarted = HostStore(dir)
            assertEquals(second, restarted.resolveGames(listOf(listOf(two, title))).single())
        } finally { dir.deleteRecursively() }
    }

    @Test fun queuedClockSurvivesRestartAndWallClockRollback() = runBlocking {
        val dir = Files.createTempDirectory("fuse-convergence-clock").toFile()
        try {
            val before = SyncDevice(dir, "thor", "Thor") { 5000 }
            before.changeMeta("person") { meta, clock -> meta.copy(settings = mapOf("theme" to Lww(JsonPrimitive("fusi"), clock.now()))) }
            val queued = before.meta("person").settings.getValue("theme")
            val after = SyncDevice(dir, "thor", "Thor") { 100 }
            assertEquals(queued, after.meta("person").settings.getValue("theme"))
            assertTrue(after.now() > queued.at)
        } finally { dir.deleteRecursively() }
    }

    @Test fun absorbedUpdateIntentCannotRenameOrHideTheBaseTitle() {
        val baseKey = GameKey.of("switch", "0100000000000000", null, "Base")
        val updateKey = GameKey.of("switch", "0100000000000800", null, "Base Update")
        val at = Hlc(5000, 0, "thor")
        val base = GameRecord(baseKey, title = Lww("My Base", Hlc(1, 0, "pc")), hidden = Lww(false, Hlc(1, 0, "pc")))
        val child = GameRecord(updateKey, title = Lww("Update Package", at), hidden = Lww(true, at),
            favorite = Lww(true, at), pinned = Lww(true, at), emulator = Lww("wrong", at),
            playSeconds = mapOf("thor" to 42))
        val collection = CollectionRecord("c1", Lww("My collection", at), members = mapOf(updateKey.id to Lww(true, at)))
        val original = ProfileMeta(games = mapOf(baseKey.id to base, updateKey.id to child), collections = mapOf("c1" to collection))
        val ownership = mapOf(updateKey.id to baseKey)
        val folded = original.foldAbsorbed(ownership)
        assertEquals(setOf(baseKey.id), folded.games.keys)
        assertEquals("My Base", folded.games.getValue(baseKey.id).title?.value)
        assertEquals(false, folded.games.getValue(baseKey.id).hidden?.value)
        assertEquals(null, folded.games.getValue(baseKey.id).emulator)
        assertEquals(true, folded.games.getValue(baseKey.id).favorite?.value)
        assertEquals(42L, folded.games.getValue(baseKey.id).totalSeconds)
        assertEquals(listOf(baseKey.id), folded.collections.getValue("c1").games)
        assertEquals(folded, folded.foldAbsorbed(ownership))
        assertEquals("Update Package", original.games.getValue(updateKey.id).title?.value)
    }

    @Test fun topologyReconcilesArrivalOrderRememberedOfflineDevicesAndRevocation() {
        val self = DeviceInfo("thor", "Thor", "ANDROID")
        val pc = DeviceLibrary("pc", "Gaming PC", "LINUX", accepts = false)
        val fromLibraryFirst = HouseholdTopology.reconcile(listOf(self), listOf(pc))
        assertEquals(listOf("pc"), HouseholdTopology.others("thor", fromLibraryFirst).map { it.id })
        // A registry may omit self; its list's size says nothing about another device existing.
        val registry = listOf(DeviceInfo("pc", "Renamed PC", "LINUX", lastSeen = 20))
        val fromRegistryFirst = HouseholdTopology.reconcile(registry, emptyList())
        assertEquals(1, HouseholdTopology.others("thor", fromRegistryFirst).size)
        assertEquals(30L, HouseholdTopology.reconcile(registry, listOf(pc), mapOf("pc" to 30)).single().lastSeen)
        val revoked = registry.map { it.copy(revoked = true) }
        assertTrue(HouseholdTopology.others("thor", HouseholdTopology.reconcile(revoked, listOf(pc))).isEmpty())
        assertEquals("Renamed PC", HouseholdTopology.reconcile(registry, listOf(pc)).single().name)
    }
}
