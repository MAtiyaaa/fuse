package io.github.matiyaaa.fuse.ui.shell.settings

import io.github.matiyaaa.fuse.data.TitleText
import io.github.matiyaaa.fuse.data.search.SearchRank

/**
 * A setting search can find: its section, the row's label as Settings shows it (null for the
 * section itself), and other words people use for it.
 */
class SettingTopic(val section: String, val row: String?, val words: String = "")

/** A setting found by search, with where it is ("Settings, Appearance"). */
class SettingHit(val topic: SettingTopic, val title: String, val path: String, val section: SettingsSection, val score: Int)

/**
 * The settings search knows: every section, and the rows people look for, each with the words
 * they might type instead ("font" for text size, "controller" for inputs). Rows keep the labels
 * Settings shows, so opening one lands on it.
 */
object SettingsIndex {
    private val rows = listOf(
        SettingTopic("appearance", "Theme", "colours colors look dark light skin"),
        SettingTopic("appearance", "Game art", "box art covers tiles"),
        SettingTopic("appearance", "Background art", "wallpaper hero"),
        SettingTopic("appearance", "Glass panels", "blur transparency frosted"),
        SettingTopic("appearance", "CRT effect", "scanlines retro tv curvature"),
        SettingTopic("appearance", "Motion", "animations reduce motion"),
        SettingTopic("appearance", "High contrast focus", "accessibility visibility outline"),
        SettingTopic("home", "Home style", "flow widgets rows layout"),
        SettingTopic("home", "Add a widget", "board clock weather"),
        SettingTopic("home", "Row order", "rows reorder"),
        SettingTopic("home", "24-hour clock", "time format"),
        SettingTopic("library", "Add a games folder", "roms folder path directory source"),
        SettingTopic("library", "Scan for changes", "refresh rescan update library"),
        SettingTopic("library", "Full rescan", "rebuild scan"),
        SettingTopic("library", "Check BIOS again", "firmware bios"),
        SettingTopic("library", "Clean display names", "titles tags names"),
        SettingTopic("library", "Collections", "playlists groups"),
        SettingTopic("library", "Automatic series", "franchise series"),
        SettingTopic("systems", null, "emulator per system bios folders consoles"),
        SettingTopic("emulators", "Look for emulators again", "detect find retroarch"),
        SettingTopic("emulators", "Locate an emulator", "path executable app"),
        SettingTopic("media", "SteamGridDB API key", "key art sgdb"),
        SettingTopic("media", "IGDB Client ID", "igdb twitch metadata key"),
        SettingTopic("media", "ScreenScraper username", "screenscraper account"),
        SettingTopic("media", "Fill missing art", "scrape download art covers"),
        SettingTopic("media", "Video previews", "trailers videos"),
        SettingTopic("media", "Preferred region", "region language"),
        SettingTopic("achievements", "Connect RetroAchievements", "retroachievements ra cheevos login account"),
        SettingTopic("cartridge", null, "romm downloads cartridge"),
        SettingTopic("inputs", "Detect my buttons", "controller gamepad buttons mapping"),
        SettingTopic("inputs", "Button layout", "xbox nintendo playstation abxy"),
        SettingTopic("inputs", "Swap confirm and back", "a b swap accept cancel"),
        SettingTopic("inputs", "Stick deadzone", "analog drift joystick"),
        SettingTopic("inputs", "Vibration", "rumble haptics"),
        SettingTopic("inputs", "Button mapping and test", "test controller"),
        SettingTopic("sound", "Menu music", "music background songs"),
        SettingTopic("sound", "Interface sounds", "clicks sound effects sfx"),
        SettingTopic("displays", "Second screen", "dual screen thor ayn bottom screen"),
        SettingTopic("displays", "Games open on", "which screen launch display"),
        SettingTopic("performance", "Performance profile", "speed battery fps"),
        SettingTopic("performance", "Low Power Mode", "battery saver"),
        SettingTopic("performance", "Performance overlay", "fps cpu temperature"),
        SettingTopic("network", "Wi-Fi settings", "wifi internet network"),
        SettingTopic("phonelink", "Pair a phone", "phone link companion upload qr"),
        SettingTopic("health", "Diagnostics report", "bug report logs problems diagnostics support"),
        SettingTopic("backup", "Back up now", "backup export save settings"),
        SettingTopic("backup", "Restore a backup", "restore import move device"),
        SettingTopic("storage", "File access", "permission storage access sd card"),
        SettingTopic("storage", "Games and space", "disk space drives size usage"),
        SettingTopic("privacy", "Delete all saved keys", "remove keys passwords forget"),
        SettingTopic("updates", "Check for updates", "update new version"),
        SettingTopic("about", "Open-source licences", "licenses credits"),
        SettingTopic("about", "Run setup again", "onboarding setup wizard"),
    )

    fun search(query: String, sections: List<SettingsSection>, limit: Int = 8): List<SettingHit> {
        val needle = TitleText.normalize(query)
        if (needle.length < 2) return emptyList()
        val byId = sections.associateBy { it.id }
        val topics = sections.map { SettingTopic(it.id, null, it.summary) } + rows
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
