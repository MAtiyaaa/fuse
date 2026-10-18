package io.github.matiyaaa.fuse.integrations.nexus

import io.github.matiyaaa.fuse.integrations.ApiResult
import io.github.matiyaaa.fuse.integrations.ProviderHttp
import io.github.matiyaaa.fuse.integrations.Secret
import io.github.matiyaaa.fuse.integrations.flatMap
import io.github.matiyaaa.fuse.integrations.map
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.url
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** The person Nexus Mods knows by their key, and whether their account downloads straight from the API (Premium). */
@Serializable
data class NexusUser(val name: String, @SerialName("is_premium") val premium: Boolean = false, @SerialName("is_supporter") val supporter: Boolean = false)

/** A game on Nexus Mods: its [domain] (the name in its addresses, "skyrimspecialedition") and [name]. */
@Serializable
data class NexusGame(val id: Long, @SerialName("domain_name") val domain: String, val name: String, val mods: Long = 0)

/** A mod, as Nexus Mods describes it. */
@Serializable
data class NexusMod(
    @SerialName("mod_id") val id: Long,
    @SerialName("domain_name") val game: String,
    val name: String? = null,
    val summary: String? = null,
    val version: String? = null,
    val author: String? = null,
    @SerialName("picture_url") val picture: String? = null,
    @SerialName("updated_timestamp") val updated: Long = 0,
    @SerialName("endorsement_count") val endorsements: Long = 0,
    @SerialName("contains_adult_content") val adult: Boolean = false,
    /** False for a mod that is hidden, under moderation or removed. */
    val available: Boolean = true,
    val status: String? = null,
) {
    /** Its page on the website. */
    val url: String get() = "https://www.nexusmods.com/$game/mods/$id"
}

/** One file of a mod: [sizeKb] in kilobytes, as Nexus Mods gives it. */
@Serializable
data class NexusFile(
    @SerialName("file_id") val id: Long,
    val name: String,
    @SerialName("file_name") val fileName: String,
    val version: String? = null,
    @SerialName("size_kb") val sizeKb: Long = 0,
    /** MAIN, PATCH, OPTION, OLD_VERSION, DELETED or ARCHIVED. */
    @SerialName("category_name") val category: String? = null,
    @SerialName("uploaded_timestamp") val uploaded: Long = 0,
    @SerialName("is_primary") val primary: Boolean = false,
    val description: String? = null,
) {
    /** Offered for adding: a current file (not old, deleted or archived) that is a `.zip`, which Fuse reads entry by entry. */
    val fetchable: Boolean
        get() = category?.uppercase() !in setOf("OLD_VERSION", "DELETED", "ARCHIVED") && fileName.endsWith(".zip", ignoreCase = true) &&
            sizeKb in 1..MAX_KB

    /** Why it isn't [fetchable], or null. */
    val notFetchable: String?
        get() = when {
            category?.uppercase() in setOf("OLD_VERSION", "DELETED", "ARCHIVED") -> "it is an old file, kept on the mod's page only"
            !fileName.endsWith(".zip", ignoreCase = true) -> "it isn't a .zip, so Fuse doesn't open it (follow the mod's own instructions)"
            sizeKb !in 1..MAX_KB -> "it is larger than the mods Fuse adds"
            else -> null
        }

    companion object {
        /** The largest file Fuse fetches: 4 GB, for texture packs. */
        const val MAX_KB = 4L * 1024 * 1024
    }
}

@Serializable
private data class NexusFiles(val files: List<NexusFile> = emptyList())

/** A download address Nexus Mods gave for a file, on its [server]. */
@Serializable
data class NexusLink(@SerialName("URI") val uri: String, @SerialName("name") val server: String? = null)

/**
 * What a "Mod Manager Download" link from the website carries (`nxm://game/mods/1/files/2?key=…&expires=…`):
 * the game, mod and file, and the one-time [key] and [expires] the API asks of accounts that aren't
 * Premium. The key is a [Secret], never printed.
 */
data class NxmLink(val game: String, val mod: Long, val file: Long, val key: Secret, val expires: Long)

/** The three lists Nexus Mods keeps for a game (ten mods each). */
enum class NexusList(val path: String, val title: String) {
    TRENDING("trending", "Trending"),
    LATEST_ADDED("latest_added", "Latest Added"),
    LATEST_UPDATED("latest_updated", "Latest Updated"),
}

/**
 * Nexus Mods' public API (v1), as its own documentation lays it out
 * (`api.swaggerhub.com/apis/NexusMods/nexus-mods_public_api_params_in_form_data/1.0`) and its own
 * client types the answers (`Nexus-Mods/node-nexus-api`, `src/types.ts`). Every request carries the
 * person's own key in the `apikey` header (never one of Fuse's: Nexus Mods asks that no key be
 * built into software) and names Fuse in `Application-Name` and `Application-Version`, as its client
 * does. Download addresses come straight from the API only for Premium accounts; for others Nexus
 * Mods asks for the key and expiry of the website's own link ([NxmLink]), so the person has visited
 * the mod's page. Every message is redacted of the key.
 */
class NexusMods(http: HttpClient, private val key: Secret, private val appVersion: String) {
    private val api = ProviderHttp(http, "Nexus Mods", null, { listOf(key) })

    private fun io.ktor.client.request.HttpRequestBuilder.common(path: String) {
        url("$API$path")
        header("apikey", key.reveal())
        header("Application-Name", APP)
        header("Application-Version", appVersion)
        header("Accept", "application/json")
    }

    /** Whose key it is (this route doesn't count towards Nexus Mods' limits). */
    suspend fun validate(): ApiResult<NexusUser> = api.json(NexusUser.serializer()) { common("/v1/users/validate.json") }

    /** Every game Nexus Mods has mods for. */
    suspend fun games(): ApiResult<List<NexusGame>> = api.json(ListSerializer(NexusGame.serializer())) { common("/v1/games.json") }

    suspend fun mod(game: String, id: Long): ApiResult<NexusMod> = api.json(NexusMod.serializer()) { common("/v1/games/${seg(game)}/mods/$id.json") }

    suspend fun list(game: String, list: NexusList): ApiResult<List<NexusMod>> =
        api.json(ListSerializer(NexusMod.serializer())) { common("/v1/games/${seg(game)}/mods/${list.path}.json") }

    /** A mod's files, newest first. */
    suspend fun files(game: String, mod: Long): ApiResult<List<NexusFile>> =
        api.json(NexusFiles.serializer()) { common("/v1/games/${seg(game)}/mods/$mod/files.json") }.map { f -> f.files.sortedByDescending { it.uploaded } }

    /**
     * Where to download [file] from: straight away for a Premium account, else with the [nxm] link's
     * key and expiry. A refusal says which it needs.
     */
    suspend fun downloadLinks(game: String, mod: Long, file: Long, nxm: NxmLink? = null): ApiResult<List<NexusLink>> =
        api.execute {
            common("/v1/games/${seg(game)}/mods/$mod/files/$file/download_link.json")
            if (nxm != null) {
                parameter("key", nxm.key.reveal())
                parameter("expires", nxm.expires)
            }
        }.flatMap { raw ->
            when (raw.status) {
                403 -> ApiResult.AuthError("Nexus Mods gives download links straight away to Premium accounts only. Use the mod page's Mod Manager Download link instead.")
                410 -> ApiResult.HttpError(410, "That download link has run out. Open the mod's page again for a new one.")
                400 -> ApiResult.HttpError(400, "That download link isn't for this file, or for this account.")
                else -> api.failureFor(raw) ?: api.decode(raw, ListSerializer(NexusLink.serializer()))
            }
        }.flatMap { links ->
            // Only secure addresses are followed.
            val safe = links.filter { it.uri.startsWith("https://") }
            if (safe.isEmpty()) ApiResult.InvalidResponse("Nexus Mods gave no secure download address.") else ApiResult.Success(safe)
        }

    companion object {
        const val API = "https://api.nexusmods.com"
        private const val APP = "Fuse"

        /** Where the person makes their own key: their account's API Access page. */
        const val KEY_PAGE = "https://www.nexusmods.com/users/myaccount?tab=api%20access"

        /** A game domain or other path part as it may appear in an address. */
        private fun seg(s: String): String = s.filter { it.isLetterOrDigit() || it == '-' || it == '_' }

        /** The game and mod a mod page's address names (`https://www.nexusmods.com/{game}/mods/{id}`), or null. */
        fun modOf(url: String): Pair<String, Long>? {
            val m = Regex("^(?:https://)?(?:www\\.)?nexusmods\\.com/([A-Za-z0-9_-]+)/mods/(\\d+)").find(url.trim()) ?: return null
            return m.groupValues[1].lowercase() to (m.groupValues[2].toLongOrNull() ?: return null)
        }

        /** A website "Mod Manager Download" link read, or null when it isn't one. */
        fun nxmOf(text: String): NxmLink? {
            val t = text.trim()
            if (!t.startsWith("nxm://", ignoreCase = true)) return null
            val m = Regex("^nxm://([A-Za-z0-9_-]+)/mods/(\\d+)/files/(\\d+)\\?(.*)$", RegexOption.IGNORE_CASE).find(t) ?: return null
            val query = m.groupValues[4].split('&').mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
            val key = query["key"]?.takeIf { it.isNotBlank() && it.all { c -> c.isLetterOrDigit() || c == '-' || c == '_' } } ?: return null
            val expires = query["expires"]?.toLongOrNull() ?: return null
            return NxmLink(m.groupValues[1].lowercase(), m.groupValues[2].toLong(), m.groupValues[3].toLong(), Secret(key), expires)
        }

        /** [games] that might be [title], the closest first: the same name, then names that start or contain it. */
        fun matching(games: List<NexusGame>, title: String): List<NexusGame> {
            fun key(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
            val t = key(title)
            if (t.isEmpty()) return emptyList()
            return games.filter { g -> key(g.name).let { it == t || it.startsWith(t) || t.startsWith(it) && it.length >= 4 || t in it } }
                .sortedWith(compareBy<NexusGame>({ if (key(it.name) == t) 0 else 1 }, { -it.mods }))
        }
    }
}
