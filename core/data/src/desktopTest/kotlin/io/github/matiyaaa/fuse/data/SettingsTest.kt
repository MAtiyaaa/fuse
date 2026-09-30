package io.github.matiyaaa.fuse.data

import io.github.matiyaaa.fuse.data.settings.AppSettings
import io.github.matiyaaa.fuse.data.settings.AppearanceSettings
import io.github.matiyaaa.fuse.data.settings.OnboardingState
import io.github.matiyaaa.fuse.data.settings.SettingsStore
import io.github.matiyaaa.fuse.model.CrtSettings
import io.github.matiyaaa.fuse.model.Destination
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.HomeLayoutConfig
import io.github.matiyaaa.fuse.model.HomeMode
import io.github.matiyaaa.fuse.model.InputProfile
import io.github.matiyaaa.fuse.model.LibraryLayout
import io.github.matiyaaa.fuse.model.MatchStrictness
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.model.PadButton
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.model.Resolved
import io.github.matiyaaa.fuse.model.ScopeRef
import io.github.matiyaaa.fuse.model.ScopedSettings
import io.github.matiyaaa.fuse.model.ScrapeProviderId
import io.github.matiyaaa.fuse.model.SettingScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsTest {
    private val gba = PlatformId("gba")
    private val game = GameId(42)

    @Test
    fun scopedSettingsInheritGamePlatformGlobalDefault() = runBlocking {
        TestDb().use { t ->
            val scoped = t.data.scopedSettings
            val key = ScopedSettings.ShowHero
            assertEquals(Resolved(true, SettingScope.GLOBAL, isDefault = true), scoped.resolve(key, gba, game))

            scoped.set(key, ScopeRef.Global, false)
            assertEquals(Resolved(false, SettingScope.GLOBAL, false), scoped.resolve(key, gba, game))
            scoped.set(key, ScopeRef.platform(gba), true)
            assertEquals(Resolved(true, SettingScope.PLATFORM, false), scoped.resolve(key, gba, game))
            scoped.set(key, ScopeRef.game(game), false)
            assertEquals(Resolved(false, SettingScope.GAME, false), scoped.resolve(key, gba, game))
            assertEquals(Resolved(false, SettingScope.GLOBAL, false), scoped.resolve(key, PlatformId("snes"), GameId(7)))
            assertEquals(setOf(key.id), scoped.observeOverriddenKeys(ScopeRef.game(game)).first())

            scoped.clear(key, ScopeRef.game(game))
            assertEquals(SettingScope.PLATFORM, scoped.observeResolved(key, gba, game).first().from)
            scoped.clearAll(ScopeRef.platform(gba))
            assertEquals(SettingScope.GLOBAL, scoped.resolve(key, gba, game).from)

            val border = ScopedSettings.Border
            val custom = border.default.copy(gradient = false, accent = 0xFF00FF00)
            scoped.set(border, ScopeRef.game(game), custom)
            assertEquals(custom, scoped.resolve(border, gba, game).value)
        }
    }

    @Test
    fun scopesAKeyDoesNotAllowAreRejected() = runBlocking {
        TestDb().use { t ->
            val scoped = t.data.scopedSettings
            assertFailsWith<IllegalArgumentException> { scoped.set(ScopedSettings.Layout, ScopeRef.game(game), LibraryLayout.CAPSULE) }
            assertFailsWith<IllegalArgumentException> { scoped.set(ScopedSettings.Emulator, ScopeRef.Global, "duckstation") }
            assertFailsWith<IllegalArgumentException> { scoped.set(ScopedSettings.Emulator, ScopeRef(SettingScope.PLATFORM), "duckstation") }

            // A value stored at a disallowed scope (for example by an older version) is ignored.
            t.db.settingQueries.put("GAME", "42", ScopedSettings.Layout.id, "\"COVER_GRID\"", 0)
            scoped.set(ScopedSettings.Layout, ScopeRef.platform(gba), LibraryLayout.COMPACT_LIST)
            assertEquals(Resolved(LibraryLayout.COMPACT_LIST, SettingScope.PLATFORM, false), scoped.resolve(ScopedSettings.Layout, gba, game))

            // Unreadable values fall through to the next scope.
            t.db.settingQueries.put("GAME", "42", ScopedSettings.ShowLogo.id, "\"not a boolean\"", 0)
            assertEquals(Resolved(true, SettingScope.GLOBAL, true), scoped.resolve(ScopedSettings.ShowLogo, gba, game))

            scoped.set(ScopedSettings.Emulator, ScopeRef.platform(gba), "mgba")
            assertEquals(Resolved("mgba", SettingScope.PLATFORM, false), scoped.resolve(ScopedSettings.Emulator, gba, game))
        }
    }

    @Test
    fun globalLevelOfBoundKeysIsAppSettings() = runBlocking {
        TestDb().use { t ->
            val scoped = t.data.scopedSettings
            val store = t.data.settings
            scoped.set(ScopedSettings.VideoPreview, ScopeRef.Global, false)
            assertFalse(store.current().videoPreview.enabled)
            store.update { it.copy(videoPreview = it.videoPreview.copy(delaySeconds = 4)) }
            assertEquals(Resolved(4, SettingScope.GLOBAL, false), scoped.resolve(ScopedSettings.VideoDelaySeconds, gba, null))
            assertEquals(Resolved(false, SettingScope.GLOBAL, false), scoped.observeResolved(ScopedSettings.VideoPreview, gba, game).first())
            scoped.set(ScopedSettings.VideoPreview, ScopeRef.game(game), true)
            assertEquals(Resolved(true, SettingScope.GAME, false), scoped.resolve(ScopedSettings.VideoPreview, gba, game))

            store.update { it.copy(scraping = it.scraping.copy(matching = MatchStrictness.EXACT)) }
            assertEquals(MatchStrictness.EXACT, scoped.resolve(ScopedSettings.Matching, gba, null).value)
            scoped.clear(ScopedSettings.Matching, ScopeRef.Global)
            assertEquals(Resolved(MatchStrictness.NORMAL, SettingScope.GLOBAL, true), scoped.resolve(ScopedSettings.Matching, gba, null))
        }
    }

    @Test
    fun appSettingsRoundTrip() = runBlocking {
        TestDb().use { t ->
            val store = t.data.settings
            assertEquals(AppSettings(), store.settings.first())
            val saved = store.update {
                it.copy(
                    onboarding = OnboardingState(completed = true, step = 4),
                    home = it.home.copy(
                        layout = HomeLayoutConfig(mode = HomeMode.CHANNELS),
                        destinations = it.home.destinations.map { d -> if (d.destination == Destination.APPS) d.copy(visible = false) else d },
                    ),
                    appearance = AppearanceSettings(themeId = "crt", crt = CrtSettings(enabled = true, scanlines = 0.5f)),
                    input = InputProfile(nintendoLayout = true, remap = mapOf(PadButton.Y to NavAction.SEARCH)),
                    scraping = it.scraping.copy(providerOrder = listOf(ScrapeProviderId.IGDB, ScrapeProviderId.ROMM)),
                )
            }
            assertEquals(saved, store.current())
            assertEquals(saved, store.settings.first())
            assertEquals(saved, SettingsStore(t.db, t.dispatcher).current(), "a fresh store reads the same document")
            assertFalse(Destination.APPS in saved.home.visibleDestinations())
            assertEquals(ScrapeProviderId.IGDB, saved.scraping.effectiveOrder().first())

            // Removing a map entry is not undone by the unknown-field merge.
            store.update { it.copy(input = it.input.copy(remap = emptyMap())) }
            assertEquals(emptyMap(), store.current().input.remap)

            assertEquals(AppSettings(), store.reset())
        }
    }

    @Test
    fun concurrentUpdatesDoNotLoseWrites() = runBlocking {
        TestDb().use { t ->
            val store = t.data.settings
            (1..20).map { i ->
                async { store.update { it.copy(onboarding = it.onboarding.copy(step = it.onboarding.step + 1)) } }
            }.awaitAll()
            assertEquals(20, store.current().onboarding.step)
        }
    }

    @Test
    fun documentsFromOtherVersionsAreToleratedAndPreserved() = runBlocking {
        TestDb().use { t ->
            val newer = """
                {"version":3,"futureFlag":true,
                 "sound":{"enabled":false,"futureNested":3},
                 "scraping":{"providerOrder":["MOBYGAMES","IGDB"],"matching":"FUZZY_FUTURE","preferredLanguage":"fr"},
                 "home":{"destinations":[{"destination":"STORE"},{"destination":"LIBRARY","visible":false}]},
                 "input":{"repeatDelayMs":"not a number"},
                 "performance":{"lowPowerMode":true}}
            """.trimIndent()
            t.db.settingQueries.put("GLOBAL", "", "app", newer, 0)
            val store = t.data.settings
            val read = store.current()
            assertFalse(read.sound.enabled)
            assertEquals(listOf(ScrapeProviderId.IGDB), read.scraping.providerOrder)
            assertEquals(MatchStrictness.NORMAL, read.scraping.matching)
            assertEquals("fr", read.scraping.preferredLanguage)
            assertEquals(listOf(Destination.LIBRARY), read.home.destinations.map { it.destination })
            assertFalse(Destination.LIBRARY in read.home.visibleDestinations())
            assertTrue(Destination.HOME in read.home.visibleDestinations(), "destinations unknown to the document are shown")
            assertEquals(InputProfile(), read.input, "an unreadable section falls back to defaults")
            assertTrue(read.performance.lowPowerMode, "other sections survive a broken one")

            store.update { it.copy(sound = it.sound.copy(volume = 0.25f)) }
            val raw = t.db.settingQueries.get("GLOBAL", "", "app").executeAsOne()
            assertTrue("\"futureFlag\":true" in raw, raw)
            assertTrue("\"futureNested\":3" in raw, raw)
            assertEquals(0.25f, store.current().sound.volume)
        }
    }
}
