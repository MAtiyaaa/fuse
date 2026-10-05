package io.github.matiyaaa.fuse.data.settings

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Which settings are a person's and which are the device's, for Fuse Sync's profiles. A person's
 * follow them to every device they use (their theme, their Home, their quick menu, how their
 * library looks and sounds); a device's stay with it (its controller, its screens, its
 * performance, its drives, its add-ons' accounts and the paths of its files). Settings are named by
 * their path in [AppSettings]' JSON, so a field added later is the device's until listed here.
 */
object ProfileSettings {
    val paths: List<String> = listOf(
        // How it looks.
        "appearance.themeId", "appearance.customThemes", "appearance.glass", "appearance.crt", "appearance.motion",
        "appearance.heroDim", "appearance.highContrastFocus", "appearance.textScale", "appearance.startupAnimation",
        "appearance.recentColors", "appearance.rememberPlace", "appearance.standbyMinutes",
        // Home, the tabs and the quick menu.
        "home.layout", "home.destinations", "home.addonsOrder", "home.quickMenu",
        // The library as they like it.
        "library.cleanDisplayNames", "library.systemOrder", "library.sort", "library.systemArtStyle",
        "library.selectOpensGamePage", "library.collectionsEnabled", "library.autoSeries", "library.hiddenSeries",
        "library.systemColors", "library.appsFilter", "library.gameArt",
        "videoPreview.enabled", "videoPreview.delaySeconds",
        // Sound and music (not a song's path, which is this device's file).
        "sound.enabled", "sound.volume", "sound.profile",
        "music.enabled", "music.volume", "music.track", "music.shuffle",
        // The status line.
        "statusArea.showClock", "statusArea.use24HourClock", "statusArea.showBattery", "statusArea.showWifi", "statusArea.showBluetooth",
        // Fuse Player: what they like to hear and read.
        "jellyfin.audioLanguage", "jellyfin.subtitleLanguage", "jellyfin.subtitleMode", "jellyfin.subtitleScale",
        "jellyfin.subtitleLift", "jellyfin.subtitleBackground", "jellyfin.autoplayNext", "jellyfin.seekSeconds",
        "jellyfin.controlsTimeoutSeconds", "jellyfin.rememberSpeed", "jellyfin.speed",
    )

    /** The person's settings in [settings], by path. */
    fun extract(settings: AppSettings): Map<String, JsonElement> {
        val root = AppSettingsCodec.json.encodeToJsonElement(AppSettings.serializer(), settings).jsonObject
        return paths.mapNotNull { p -> get(root, p)?.let { p to it } }.toMap()
    }

    /** [settings] with the person's settings from [values] (paths not listed or not given stay). */
    fun apply(settings: AppSettings, values: Map<String, JsonElement>): AppSettings {
        var root = AppSettingsCodec.json.encodeToJsonElement(AppSettings.serializer(), settings).jsonObject
        for ((p, v) in values) if (p in paths) root = set(root, p.split('.'), v)
        return runCatching { AppSettingsCodec.json.decodeFromJsonElement(AppSettings.serializer(), root) }.getOrDefault(settings)
    }

    private fun get(root: JsonObject, path: String): JsonElement? {
        var cur: JsonElement = root
        for (part in path.split('.')) cur = (cur as? JsonObject)?.get(part) ?: return null
        return cur
    }

    private fun set(obj: JsonObject, parts: List<String>, value: JsonElement): JsonObject {
        val head = parts.first()
        if (parts.size == 1) return JsonObject(obj + (head to value))
        val child = obj[head] as? JsonObject ?: JsonObject(emptyMap())
        return JsonObject(obj + (head to set(child, parts.drop(1), value)))
    }
}
