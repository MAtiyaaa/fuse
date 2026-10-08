package io.github.matiyaaa.fuse.data.settings

import io.github.matiyaaa.fuse.data.TestDb
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProfileCausalityTest {
    private val theme = "appearance.themeId"

    @Test fun inheritedPreferencesSurviveSwitchWithoutAnyLocalEdit() = runBlocking {
        TestDb().use { t ->
            val s = t.data.settings
            s.update { it.copy(appearance = it.appearance.copy(themeId = "fusi")) }
            s.update { it.copy(sync = it.sync.copy(activeProfile = "a")) }
            val inherited = s.profileValues()
            s.update { it.copy(sync = it.sync.copy(activeProfile = "b")) }
            s.mergeProfileValues("b", mapOf(theme to ProfileSettingValue(JsonPrimitive("bo"), ProfileSettingRevision(5000, 0, "pc"))))
            val restarted = SettingsStore(t.db, t.dispatcher) { t.now }
            restarted.update { it.copy(sync = it.sync.copy(activeProfile = "a")) }
            assertEquals("fusi", restarted.current().appearance.themeId)
            assertEquals(inherited.getValue(theme), restarted.profileValues().getValue(theme))
        }
    }

    @Test fun staleReplyCannotUndoOfflineThemeAfterRestart() = runBlocking {
        TestDb().use { t ->
            val store = SettingsStore(t.db, t.dispatcher) { t.now }
            store.update { it.copy(sync = it.sync.copy(activeProfile = "a", deviceId = "thor")) }
            val stale = ProfileSettingValue(JsonPrimitive("fuse"), ProfileSettingRevision(t.now, 0, "deck"))
            store.mergeProfileValues("a", mapOf(theme to stale))
            t.now -= 100_000 // A wall-clock correction must not reverse logical time.
            store.update { it.copy(appearance = it.appearance.copy(themeId = "fusi")) }
            val revision = store.profileValues().getValue(theme).revision
            assertTrue(revision > stale.revision)
            val restarted = SettingsStore(t.db, t.dispatcher) { t.now }
            restarted.mergeProfileValues("a", mapOf(theme to stale))
            assertEquals("fusi", restarted.current().appearance.themeId)
            assertEquals(revision, restarted.profileValues().getValue(theme).revision)
            val newer = stale.copy(value = JsonPrimitive("bo"), revision = revision.copy(counter = revision.counter + 1, origin = "pc"))
            restarted.mergeProfileValues("a", mapOf(theme to newer))
            assertEquals("bo", restarted.current().appearance.themeId)
            assertEquals(newer.revision, restarted.profileValues().getValue(theme).revision)
        }
    }

    @Test fun differentPreferencesMergeIndependentlyInAnyReconnectOrder() = runBlocking {
        val devices = List(3) { TestDb() }
        try {
            val stores = devices.mapIndexed { i, t -> SettingsStore(t.db, t.dispatcher) { 1000L + i } }
            for ((i, s) in stores.withIndex()) s.update { it.copy(sync = it.sync.copy(activeProfile = "a", deviceId = "d$i")) }
            stores[0].update { it.copy(appearance = it.appearance.copy(themeId = "fusi")) }
            stores[1].update { it.copy(sound = it.sound.copy(enabled = false)) }
            stores[2].update { it.copy(appearance = it.appearance.copy(themeId = "bo")) }
            val edits = stores.map { it.profileValues().filterValues { v -> v.revision.millis > 0 } }
            for (index in stores.indices) {
                val order = if (index == 0) edits else edits.reversed()
                for (edit in order) stores[index].mergeProfileValues("a", edit)
                assertEquals("bo", stores[index].current().appearance.themeId)
                assertEquals(false, stores[index].current().sound.enabled)
            }
            // A replay and a second restart cannot manufacture a fresh winning revision.
            val s = SettingsStore(devices[0].db, devices[0].dispatcher) { 0L }
            val settled = s.profileValues()
            for (edit in edits.reversed()) s.mergeProfileValues("a", edit)
            assertEquals(settled, s.profileValues())
        } finally { devices.forEach { it.close() } }
    }

    @Test fun profileSwitchDoesNotCopyThePreviousPersonsPreferencesOrApplyLateReply() = runBlocking {
        TestDb().use { t ->
            val s = t.data.settings
            s.update { it.copy(sync = it.sync.copy(activeProfile = "a")) }
            s.update { it.copy(appearance = it.appearance.copy(themeId = "fusi")) }
            val a = s.profileValues()
            s.update { it.copy(sync = it.sync.copy(activeProfile = "b")) }
            s.update { it.copy(appearance = it.appearance.copy(themeId = "bo")) }
            val b = s.profileValues()
            s.mergeProfileValues("a", a)
            assertEquals("bo", s.current().appearance.themeId)
            s.update { it.copy(sync = it.sync.copy(activeProfile = "a")) }
            assertEquals("fusi", s.current().appearance.themeId)
            assertEquals(a.getValue(theme).revision, s.profileValues().getValue(theme).revision)
            s.update { it.copy(sync = it.sync.copy(activeProfile = "b")) }
            assertEquals("bo", s.current().appearance.themeId)
            assertEquals(b.getValue(theme).revision, s.profileValues().getValue(theme).revision)
        }
    }

    @Test fun settingsRevisionDoesNotChangeDuringRemoteHydrationOrDeviceOnlyEdits() = runBlocking {
        TestDb().use { t ->
            val s = t.data.settings
            s.update { it.copy(sync = it.sync.copy(activeProfile = "a")) }
            val remote = ProfileSettingValue(JsonPrimitive("fusi"), ProfileSettingRevision(t.now + 1000, 4, "pc"))
            s.mergeProfileValues("a", mapOf(theme to remote))
            s.update { it.copy(sync = it.sync.copy(deviceName = "Renamed Thor")) }
            s.mergeProfileValues("a", mapOf(theme to remote))
            assertEquals(remote, s.profileValues().getValue(theme))
            s.update { it.copy(sound = it.sound.copy(enabled = false)) }
            assertTrue(s.profileValues().getValue("sound.enabled").revision > remote.revision)
        }
    }
}
