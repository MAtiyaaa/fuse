package io.github.matiyaaa.fuse.ui.shell.settings

import io.github.matiyaaa.fuse.data.TitleText
import io.github.matiyaaa.fuse.data.search.SearchRank

/**
 * A setting search can find: its section, the row's label as Settings shows it (null for the
 * section itself), other words people use for it, and the folded group it sits in, if any, so
 * opening it can unfold that group first.
 */
class SettingTopic(
    val section: String,
    val row: String?,
    val words: String = "",
    val group: String? = null,
    /** Only where Cartridge runs (Android). */
    val cartridge: Boolean = false,
    /** Only on a device with a second screen. */
    val secondScreen: Boolean = false,
)

/** A setting found by search, with where it is ("Settings, Appearance"). */
class SettingHit(val topic: SettingTopic, val title: String, val path: String, val section: SettingsSection, val score: Int)

/**
 * The settings search knows: every section, and the rows people look for, each with the words
 * they might type instead ("font" for text size, "controller" for controls). Rows keep the labels
 * Settings shows, so opening one lands on it.
 */
object SettingsIndex {
    val rows = listOf(
        SettingTopic("appearance", "Theme", "colours colors look dark light skin"),
        SettingTopic("appearance", "Game art", "box art covers tiles posters"),
        SettingTopic("appearance", "Background art", "wallpaper hero"),
        SettingTopic("appearance", "Background dimming", "darker dim brightness"),
        // Tuning only shows while its effect is on, so these land on the switch with the tuning unfolded under it.
        SettingTopic("appearance", "Glass panels", "blur transparency frosted opacity glass tuning", group = "appearance.glass"),
        SettingTopic("appearance", "CRT effect", "retro tv scanlines curvature bloom vignette crt tuning", group = "appearance.crt"),
        SettingTopic("accessibility", "Text size", "font bigger larger smaller read"),
        SettingTopic("accessibility", "Screen edges", "overscan tv safe area margin cut off borders"),
        SettingTopic("accessibility", "Motion", "animations reduce motion"),
        SettingTopic("accessibility", "High contrast focus", "visibility outline"),
        SettingTopic("home", "Home style", "flow channels widgets rows layout"),
        SettingTopic("home", "Add a widget", "board widgets"),
        SettingTopic("home", "Row order", "rows reorder"),
        SettingTopic("home", "Section order", "tabs top bar reorder"),
        SettingTopic("home", "24-hour clock", "time format"),
        SettingTopic("home", "Wi-Fi in the top bar", "status area wifi icon"),
        SettingTopic("library", "Add a games folder", "roms folder path directory source"),
        SettingTopic("library", "Scan for changes", "refresh rescan update library"),
        SettingTopic("library", "Full rescan", "rebuild scan"),
        SettingTopic("library", "Check BIOS again", "firmware bios"),
        SettingTopic("library", "Default view", "grid list capsules layout"),
        SettingTopic("library", "Clean display names", "titles tags names usa", group = "library.names"),
        SettingTopic("library", "Collections", "playlists groups"),
        SettingTopic("library", "Automatic series", "franchise series"),
        SettingTopic("store", null, "store addons obtainium emulators install apps download"),
        SettingTopic("store", "Edition", "standard obtainium pack variant"),
        SettingTopic("store", "Catalogue", "refresh obtainium emulation pack apps"),
        SettingTopic("store", "Check installed apps for updates", "emulator updates automatic"),
        SettingTopic("store", "GitHub token", "rate limit api github"),
        SettingTopic("systems", null, "emulator per system bios folders consoles"),
        SettingTopic("systems", "Look for emulators again", "detect find retroarch", group = "systems.finding"),
        SettingTopic("systems", "Locate an emulator", "path executable app", group = "systems.finding"),
        SettingTopic("systems", "Emulator folders", "search folders", group = "systems.finding"),
        SettingTopic("systems", "Reset system order", "order systems sort"),
        SettingTopic("systems", "Download system art", "art book logos", group = "systems.art"),
        SettingTopic("media", "Fill missing art", "scrape download art covers"),
        SettingTopic("media", "Video previews", "trailers videos"),
        SettingTopic("media", "Preferred region", "region", group = "media.matching"),
        SettingTopic("media", "Preferred language", "language", group = "media.matching"),
        SettingTopic("media", "Strictness", "matching match aggressive exact", group = "media.matching"),
        SettingTopic("media", "SteamGridDB API key", "key art sgdb", group = "media.sources"),
        SettingTopic("media", "IGDB Client ID", "igdb twitch metadata key", group = "media.sources"),
        SettingTopic("media", "ScreenScraper username", "screenscraper account", group = "media.sources"),
        SettingTopic("media", "Source order", "scraper providers order", group = "media.sources"),
        SettingTopic("inputs", "Detect my buttons", "controller gamepad buttons mapping"),
        SettingTopic("inputs", "Button layout", "xbox nintendo playstation abxy"),
        SettingTopic("inputs", "Swap confirm and back", "a b swap accept cancel"),
        SettingTopic("inputs", "Button mapping and test", "test controller remap"),
        SettingTopic("inputs", "Stick deadzone", "analog drift joystick", group = "inputs.feel"),
        SettingTopic("inputs", "Repeat delay", "scroll speed held direction", group = "inputs.feel"),
        SettingTopic("inputs", "Vibration", "rumble haptics", group = "inputs.feel"),
        SettingTopic("inputs", "Screenshots and recordings", "capture screenshot record video"),
        SettingTopic("displays", "Menu music", "music background songs"),
        SettingTopic("displays", "Interface sounds", "clicks sound effects sfx"),
        SettingTopic("displays", "Window", "fullscreen full screen borderless"),
        SettingTopic("displays", "Second screen", "dual screen thor ayn bottom screen", group = "displays.second", secondScreen = true),
        SettingTopic("displays", "Games open on", "which screen launch display"),
        SettingTopic("displays", "Performance profile", "speed battery fps"),
        SettingTopic("displays", "Low Power Mode", "battery saver"),
        SettingTopic("displays", "Performance overlay", "fps cpu temperature"),
        SettingTopic("displays", "About this device", "processor memory ram cpu displays specs"),
        SettingTopic("accounts", "Connect RetroAchievements", "retroachievements ra cheevos login account"),
        SettingTopic("accounts", "Cartridge support", "romm downloads cartridge", cartridge = true),
        SettingTopic("accounts", "Pair a phone", "phone link companion upload qr"),
        SettingTopic("health", "Diagnostics report", "bug report logs problems diagnostics support"),
        SettingTopic("storage", "Back up now", "backup export save settings"),
        SettingTopic("storage", "Restore a backup", "restore import move device"),
        SettingTopic("storage", "File access", "permission storage access sd card"),
        SettingTopic("storage", "Games and space", "disk space drives size usage"),
        SettingTopic("about", "Check for updates", "update new version"),
        SettingTopic("about", "Wi-Fi settings", "wifi internet network"),
        SettingTopic("about", "What Fuse connects to", "network internet privacy online"),
        SettingTopic("about", "No telemetry", "privacy tracking analytics data"),
        SettingTopic("about", "Delete all saved keys", "remove keys passwords forget"),
        SettingTopic("about", "Open-source licences", "licenses"),
        SettingTopic("about", "Run setup again", "onboarding setup wizard"),
        SettingTopic("about", "Made with", "credits thanks", group = "about.credits"),
    )

    fun search(query: String, sections: List<SettingsSection>, limit: Int = 8, cartridge: Boolean = true, secondScreen: Boolean = true): List<SettingHit> {
        val needle = TitleText.normalize(query)
        if (needle.length < 2) return emptyList()
        val byId = sections.associateBy { it.id }
        val topics = sections.map { SettingTopic(it.id, null, it.summary) } + rows.filter { (cartridge || !it.cartridge) && (secondScreen || !it.secondScreen) }
        return topics.mapNotNull { t ->
            val section = byId[t.section] ?: return@mapNotNull null
            val title = t.row ?: section.label
            val name = SearchRank.score(TitleText.normalize(title), needle)
            // Other words find it too, a little behind its own name.
            val words = SearchRank.score(TitleText.normalize(t.words), needle)?.let { it - 150 }
            val score = listOfNotNull(name, words).maxOrNull() ?: return@mapNotNull null
            SettingHit(t, title, if (t.row == null) "Settings" else "Settings, ${section.label}", section, score)
        }.sortedByDescending { it.score }.distinctBy { it.title }.take(limit)
    }
}
