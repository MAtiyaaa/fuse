package io.github.matiyaaa.fuse.integrations.rpcs3

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** RPCS3's own five ratings, best first, as its compatibility list defines them. */
enum class Rpcs3Status(val label: String, val meaning: String) {
    PLAYABLE("Playable", "Can be played from start to finish."),
    INGAME("Ingame", "Goes into gameplay, but has problems that keep it from being finished."),
    INTRO("Intro", "Shows its intro or menus, but doesn't get into gameplay."),
    LOADABLE("Loadable", "Starts, but only shows a black screen."),
    NOTHING("Nothing", "Doesn't start, or crashes right away."),
    ;

    companion object {
        fun of(text: String?): Rpcs3Status? = entries.firstOrNull { it.label.equals(text?.trim(), ignoreCase = true) }
    }
}

/** One game's entry in RPCS3's compatibility list. */
@Serializable
data class Rpcs3Compat(
    val titleId: String,
    val title: String,
    val status: Rpcs3Status,
    /** When it was last tested ("2020-05-04"). */
    val date: String? = null,
    /** The forum thread with the report, when there is one. */
    val thread: Long? = null,
) {
    val reportUrl: String? get() = thread?.let { "https://forums.rpcs3.net/thread-$it.html" }
}

/**
 * RPCS3's public compatibility list (rpcs3.net/compatibility, api=v1). Only an entry whose key is
 * exactly the asked title id is trusted: for an id it doesn't know, the list answers with a text
 * search whose results are other games.
 */
object Rpcs3Compatibility {
    private val TITLE_ID = Regex("^[A-Z]{4}[0-9]{5}$")

    fun isTitleId(id: String): Boolean = TITLE_ID.matches(id)

    fun url(titleId: String): String = "https://rpcs3.net/compatibility?api=v1&g=$titleId"

    fun parse(json: String, titleId: String): Rpcs3Compat? {
        val root = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return null
        if (root["return_code"]?.jsonPrimitive?.intOrNull != 0) return null
        val entry = (root["results"] as? JsonObject)?.get(titleId) as? JsonObject ?: return null
        val status = Rpcs3Status.of(entry["status"]?.jsonPrimitive?.contentOrNull) ?: return null
        return Rpcs3Compat(
            titleId = titleId,
            title = entry["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            status = status,
            date = entry["date"]?.jsonPrimitive?.contentOrNull,
            thread = entry["thread"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 },
        )
    }
}
