package io.github.matiyaaa.fuse.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer

/** Levels a setting can be decided at. Lower levels override higher ones. */
@Serializable
enum class SettingScope { GLOBAL, PLATFORM, GAME }

/** Where an override is stored: the scope plus which platform or game. */
@Serializable
data class ScopeRef(val scope: SettingScope, val id: String? = null) {
    companion object {
        val Global = ScopeRef(SettingScope.GLOBAL)
        fun platform(id: PlatformId) = ScopeRef(SettingScope.PLATFORM, id.value)
        fun game(id: GameId) = ScopeRef(SettingScope.GAME, id.value.toString())
    }
}

/**
 * A typed setting that can be overridden per platform and per game. [scopes] lists where the UI
 * offers it; the global value always exists (the default when never set).
 */
class ScopedKey<T>(
    val id: String,
    val default: T,
    val serializer: KSerializer<T>,
    val scopes: Set<SettingScope> = setOf(SettingScope.GLOBAL, SettingScope.PLATFORM, SettingScope.GAME),
) {
    override fun toString() = id
}

/** A resolved value and the scope it came from, so the UI can say "Inherited from PlayStation 2". */
data class Resolved<T>(val value: T, val from: SettingScope, val isDefault: Boolean)

/** Settings that follow Global -> Platform -> Game inheritance. */
object ScopedSettings {
    val Layout = ScopedKey("layout", LibraryLayout.ICON, LibraryLayout.serializer(), setOf(SettingScope.GLOBAL, SettingScope.PLATFORM))
    val ShowHero = ScopedKey("media.hero", true, Boolean.serializer())
    val ShowLogo = ScopedKey("media.logo", true, Boolean.serializer())
    val PreferredCover = ScopedKey("media.cover", MediaKind.BOXART, MediaKind.serializer())
    val Border = ScopedKey("media.border", BorderStyle(), BorderStyle.serializer())
    val Emulator = ScopedKey("emulator", "", String.serializer(), setOf(SettingScope.PLATFORM, SettingScope.GAME))
    val RetroArchCore = ScopedKey("emulator.core", "", String.serializer(), setOf(SettingScope.PLATFORM, SettingScope.GAME))
    val FolderMode = ScopedKey("folder.policy", FolderPolicy.AUTO, FolderPolicy.serializer())
    val VideoPreview = ScopedKey("video.preview", true, Boolean.serializer())
    val VideoDelaySeconds = ScopedKey("video.delay", 10, Int.serializer(), setOf(SettingScope.GLOBAL, SettingScope.PLATFORM))
    val ScrapeEnabled = ScopedKey("scrape.enabled", true, Boolean.serializer())
    /** The name art and metadata searches use for a game, when the user set one; empty uses its title. */
    val SearchTitle = ScopedKey("scrape.title", "", String.serializer(), setOf(SettingScope.GAME))
    val Matching = ScopedKey("scrape.matching", MatchStrictness.NORMAL, MatchStrictness.serializer(), setOf(SettingScope.GLOBAL, SettingScope.PLATFORM))
    val LaunchScreen = ScopedKey("launch.display", LaunchDisplay.PRIMARY, LaunchDisplay.serializer())
    val GenerateM3u = ScopedKey("launch.m3u", true, Boolean.serializer(), setOf(SettingScope.GLOBAL, SettingScope.PLATFORM))

    val all: List<ScopedKey<*>> = listOf(
        Layout, ShowHero, ShowLogo, PreferredCover, Border, Emulator, RetroArchCore, FolderMode,
        VideoPreview, VideoDelaySeconds, ScrapeEnabled, SearchTitle, Matching, LaunchScreen, GenerateM3u,
    )
}

/** How strictly scraper results must match before Fuse accepts them without asking. */
@Serializable
enum class MatchStrictness { EXACT, NORMAL, AGGRESSIVE }

/** Which screen a game should open on, when the device and emulator allow choosing. */
@Serializable
enum class LaunchDisplay { PRIMARY, SECONDARY }
