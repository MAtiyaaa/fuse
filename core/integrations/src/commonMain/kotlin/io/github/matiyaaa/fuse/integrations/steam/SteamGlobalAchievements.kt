package io.github.matiyaaa.fuse.integrations.steam

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * How rare each of a Steam game's achievements is: the share of its players who have it, as Steam
 * publishes it for every game (ISteamUserStats/GetGlobalAchievementPercentagesForApp). Public, with
 * no key, no account and nothing about the person sent.
 */
object SteamGlobalAchievements {
    fun url(appId: Long): String = "https://api.steampowered.com/ISteamUserStats/GetGlobalAchievementPercentagesForApp/v2/?gameid=$appId"

    /** Each achievement's api name to its percentage (0 to 100), or null when the answer isn't one. */
    fun parse(json: String): Map<String, Float>? {
        val root = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return null
        val list = (root["achievementpercentages"] as? JsonObject)?.get("achievements") as? JsonArray ?: return null
        return list.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val name = o["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            // A number in some answers, text ("12.3") in others.
            val percent = o["percent"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull()?.takeIf { it in 0f..100f } ?: return@mapNotNull null
            name to percent
        }.toMap()
    }
}
