package io.github.matiyaaa.fuse.integrations.gamebanana

import io.github.matiyaaa.fuse.integrations.flexBoolean
import io.github.matiyaaa.fuse.integrations.flexLong
import io.github.matiyaaa.fuse.integrations.flexString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** A game on GameBanana, as its search lists it. */
@Serializable
data class GbGame(val id: Long, val name: String, val url: String, val icon: String? = null)

/** A mod in a game's list on GameBanana. */
@Serializable
data class GbMod(
    val id: Long,
    val name: String,
    val url: String,
    /** Its category on GameBanana ("Textures", "Cheats", "Patches"), as its makers filed it. */
    val category: String? = null,
    val version: String? = null,
    val submitter: String? = null,
    /** When it last changed, in seconds since 1970. */
    val updated: Long = 0,
    val likes: Long = 0,
    /** Its makers marked it out of date. */
    val obsolete: Boolean = false,
    val thumbnail: String? = null,
)

/** One downloadable file of a mod, with what GameBanana's own checks said about it. */
@Serializable
data class GbFile(
    val id: Long,
    val name: String,
    val size: Long,
    val md5: String?,
    val version: String?,
    val downloadUrl: String,
    val added: Long = 0,
    /** GameBanana's file analysis passed ("ok"). */
    val analysisOk: Boolean,
    /** GameBanana's virus scan found it clean. */
    val avClean: Boolean,
    /** What the analysis said, in its words. */
    val analysis: String? = null,
    /** GameBanana archived it (kept, but no longer offered). */
    val archived: Boolean = false,
) {
    /**
     * Fuse fetches and opens only files that GameBanana's checks passed, that are `.zip` archives
     * (which Fuse reads entry by entry, never running anything in them) and not too big to be a
     * game's cheats or textures; the rest are left to the mod's own page.
     */
    val fetchable: Boolean
        get() = analysisOk && avClean && !archived && name.endsWith(".zip", ignoreCase = true) && size in 1..MAX_BYTES

    /** Why [fetchable] is false, in plain words; null when it is fetchable. */
    val notFetchable: String?
        get() = when {
            !avClean -> "GameBanana's virus scan hasn't passed it"
            !analysisOk -> "GameBanana's file check hasn't passed it"
            archived -> "GameBanana archived it"
            !name.endsWith(".zip", ignoreCase = true) -> "it is a .${name.substringAfterLast('.', "?").lowercase()} archive, which Fuse doesn't open; only .zip"
            size > MAX_BYTES -> "it is larger than Fuse fetches (4 GB)"
            else -> null
        }

    companion object {
        const val MAX_BYTES = 4L * 1024 * 1024 * 1024
    }
}

/** A mod's page: the mod, its files, and its description as plain text. */
data class GbModPage(val mod: GbMod, val files: List<GbFile>, val text: String, val license: String?)

/** A page of a game's mods, with how many there are in all. */
data class GbModList(val mods: List<GbMod>, val total: Long, val complete: Boolean)

/**
 * GameBanana's public API (apiv11), read only: no account is needed to search games, list a game's
 * mods and read a mod's files. Every field is read tolerantly, as GameBanana sends numbers as text
 * at times and leaves out what a record doesn't have.
 */
object GameBanana {
    const val BASE = "https://gamebanana.com/apiv11"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun searchGamesUrl(name: String): String = "$BASE/Util/Search/Results?_sModelName=Game&_nPage=1&_sSearchString=${encode(name.trim())}"

    fun modsUrl(gameId: Long, page: Int, perPage: Int = PER_PAGE): String =
        "$BASE/Mod/Index?_nPage=$page&_nPerpage=$perPage&_sSort=Generic_MostLiked&_aFilters%5BGeneric_Game%5D=$gameId"

    fun modUrl(modId: Long): String = "$BASE/Mod/$modId/ProfilePage"

    fun parseGames(body: String): List<GbGame>? = root(body)?.let { r ->
        records(r).mapNotNull { o ->
            val id = o["_idRow"].flexLong() ?: return@mapNotNull null
            val name = o["_sName"].flexString()?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            if (o["_sModelName"].flexString().let { it != null && it != "Game" }) return@mapNotNull null
            GbGame(id, name, o["_sProfileUrl"].flexString() ?: "https://gamebanana.com/games/$id", image(o, "icon"))
        }
    }

    fun parseMods(body: String): GbModList? = root(body)?.let { r ->
        val meta = r["_aMetadata"] as? JsonObject
        val mods = records(r).mapNotNull(::mod)
        GbModList(mods, meta?.get("_nRecordCount").flexLong() ?: mods.size.toLong(), meta?.get("_bIsComplete").flexBoolean() ?: true)
    }

    fun parseMod(body: String): GbModPage? {
        val o = root(body) ?: return null
        if (o["_sErrorCode"] != null) return null
        // A mod that is private, withheld or trashed isn't offered.
        if (o["_bIsPrivate"].flexBoolean() == true || o["_bIsWithheld"].flexBoolean() == true || o["_bIsTrashed"].flexBoolean() == true) return null
        val m = mod(o) ?: return null
        val files = (o["_aFiles"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::file) }
        return GbModPage(m, files, plain(o["_sText"].flexString().orEmpty()).take(MAX_TEXT), o["_sLicense"].flexString()?.let(::plain)?.take(MAX_TEXT)?.ifBlank { null })
    }

    private fun mod(o: JsonObject): GbMod? {
        val id = o["_idRow"].flexLong() ?: return null
        val name = o["_sName"].flexString()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val category = ((o["_aRootCategory"] ?: o["_aCategory"]) as? JsonObject)?.get("_sName").flexString()
        return GbMod(
            id = id,
            name = name,
            url = o["_sProfileUrl"].flexString() ?: "https://gamebanana.com/mods/$id",
            category = category,
            version = o["_sVersion"].flexString()?.takeIf { it.isNotBlank() },
            submitter = (o["_aSubmitter"] as? JsonObject)?.get("_sName").flexString(),
            updated = o["_tsDateModified"].flexLong() ?: o["_tsDateAdded"].flexLong() ?: 0,
            likes = o["_nLikeCount"].flexLong() ?: 0,
            obsolete = o["_bIsObsolete"].flexBoolean() ?: false,
            thumbnail = screenshot(o),
        )
    }

    private fun file(o: JsonObject): GbFile? {
        val id = o["_idRow"].flexLong() ?: return null
        val name = o["_sFile"].flexString() ?: return null
        val url = o["_sDownloadUrl"].flexString()?.takeIf { it.startsWith("https://") } ?: return null
        return GbFile(
            id = id,
            name = name,
            size = o["_nFilesize"].flexLong() ?: 0,
            md5 = o["_sMd5Checksum"].flexString()?.lowercase()?.takeIf { MD5.matches(it) },
            version = o["_sVersion"].flexString()?.takeIf { it.isNotBlank() },
            downloadUrl = url,
            added = o["_tsDateAdded"].flexLong() ?: 0,
            analysisOk = o["_sAnalysisResult"].flexString() == "ok",
            avClean = o["_sAvResult"].flexString() == "clean",
            analysis = o["_sAnalysisResultVerbose"].flexString(),
            archived = o["_bIsArchived"].flexBoolean() ?: false,
        )
    }

    private fun root(body: String): JsonObject? = try {
        json.parseToJsonElement(body).jsonObject
    } catch (e: Exception) {
        null
    }

    private fun records(r: JsonObject): List<JsonObject> = (r["_aRecords"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    private fun images(o: JsonObject): List<JsonObject> =
        ((o["_aPreviewMedia"] as? JsonObject)?.get("_aImages") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    /** A game's [type] image ("icon", "banner"), given by its whole address. */
    private fun image(o: JsonObject, type: String): String? =
        images(o).firstOrNull { it["_sType"].flexString() == type }?.get("_sUrl").flexString()?.takeIf { it.startsWith("https://") }

    /** A mod's first screenshot, as its small (220px) copy: base address and file name. */
    private fun screenshot(o: JsonObject): String? {
        val shot = images(o).firstOrNull { it["_sType"].flexString() == "screenshot" } ?: return null
        val base = shot["_sBaseUrl"].flexString()?.takeIf { it.startsWith("https://") } ?: return null
        val file = shot["_sFile220"].flexString() ?: shot["_sFile"].flexString() ?: return null
        return "$base/$file"
    }

    /** GameBanana's HTML as plain text: line breaks kept, tags and entities taken out. */
    fun plain(html: String): String = html
        .replace(Regex("(?i)<br\\s*/?>|</p>|</h[1-6]>|</li>|</div>"), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        .lines().joinToString("\n") { it.trim() }
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

    private fun encode(s: String): String = buildString {
        for (b in s.encodeToByteArray()) {
            val c = b.toInt() and 0xFF
            if (c.toChar().isLetterOrDigit() && c < 128 || c.toChar() in "-_.~") append(c.toChar()) else append('%').append(HEX[c shr 4]).append(HEX[c and 15])
        }
    }

    const val PER_PAGE = 20
    private const val MAX_TEXT = 4000
    private const val HEX = "0123456789ABCDEF"
    private val MD5 = Regex("[0-9a-f]{32}")
}
