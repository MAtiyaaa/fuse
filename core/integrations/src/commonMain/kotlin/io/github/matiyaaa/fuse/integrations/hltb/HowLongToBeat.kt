package io.github.matiyaaa.fuse.integrations.hltb

import io.github.matiyaaa.fuse.integrations.match.TitleNormalizer
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** One game on HowLongToBeat, with its times in seconds (0 when nobody has submitted one). */
@Serializable
data class HltbGame(
    val id: Long,
    val name: String,
    /** Other names it is known by, comma separated, as HowLongToBeat lists them. */
    val alias: String = "",
    val mainSeconds: Long = 0,
    val plusSeconds: Long = 0,
    val completionistSeconds: Long = 0,
    val year: Int? = null,
    /** The systems it lists the game on ("Game Boy Advance, Nintendo Switch"). */
    val platforms: String = "",
    val image: String? = null,
) {
    val url: String get() = "https://howlongtobeat.com/game/$id"

    /** Whether it has any time to show. */
    val hasTimes: Boolean get() = mainSeconds > 0 || plusSeconds > 0 || completionistSeconds > 0
}

/** What a search came back with. */
sealed interface HltbResult {
    data class Found(val games: List<HltbGame>) : HltbResult

    /** HowLongToBeat couldn't be asked now (offline, changed, or asked too often); try again later. */
    data class Unavailable(val reason: String) : HltbResult
}

/**
 * HowLongToBeat's own search, as its website asks it: a short-lived token from
 * `/api/search/site/init` (tied to the address and browser it was given to, so one browser name is
 * used throughout), then the search with that token. A refused token is renewed once, as the site
 * does; when the search moves, its new address is read from the site's own scripts (the way
 * howlongtobeatpy finds it). Nothing about the person is sent, only a game's name.
 */
class HowLongToBeat(
    private val http: HttpClient,
    private val now: () -> Long,
) {
    private val lock = Mutex()
    private var token: String? = null
    private var tokenAt = 0L
    private var searchPath = DEFAULT_SEARCH
    private var pausedUntil = 0L

    /** Games named like [title], most popular first. */
    suspend fun search(title: String): HltbResult = lock.withLock {
        val terms = title.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (terms.isEmpty()) return@withLock HltbResult.Found(emptyList())
        if (now() < pausedUntil) return@withLock HltbResult.Unavailable("HowLongToBeat asked Fuse to wait a moment")
        try {
            var attempt = 0
            var rediscovered = false
            while (true) {
                val t = token?.takeIf { now() - tokenAt < TOKEN_MS } ?: freshToken() ?: return@withLock HltbResult.Unavailable("HowLongToBeat didn't answer")
                val response = http.post(BASE + searchPath) {
                    headers()
                    header("x-auth-token", t)
                    header("Origin", BASE)
                    contentType(ContentType.Application.Json)
                    setBody(body(terms))
                }
                when (response.status.value) {
                    in 200..299 -> return@withLock HltbResult.Found(parse(response.bodyAsText()))
                    403 -> {
                        token = null
                        if (attempt++ >= 1) return@withLock HltbResult.Unavailable("HowLongToBeat refused the search")
                    }
                    429 -> {
                        pausedUntil = now() + PAUSE_MS
                        return@withLock HltbResult.Unavailable("HowLongToBeat asked Fuse to wait a moment")
                    }
                    404, 405 -> {
                        if (rediscovered) return@withLock HltbResult.Unavailable("HowLongToBeat's search moved")
                        rediscovered = true
                        searchPath = discoverSearchPath() ?: return@withLock HltbResult.Unavailable("HowLongToBeat's search moved")
                        token = null
                    }
                    else -> return@withLock HltbResult.Unavailable("HowLongToBeat answered ${response.status.value}")
                }
            }
            @Suppress("UNREACHABLE_CODE")
            HltbResult.Unavailable("")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            HltbResult.Unavailable("HowLongToBeat couldn't be reached")
        }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.headers() {
        header("User-Agent", USER_AGENT)
        header("Referer", "$BASE/")
        header("Accept", "*/*")
    }

    private suspend fun freshToken(): String? {
        val response = http.get("$BASE$searchPath/init?t=${now()}") { headers() }
        if (response.status.value !in 200..299) return null
        val found = runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject["token"]?.jsonPrimitive?.contentOrNull }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: return null
        token = found
        tokenAt = now()
        return found
    }

    /** The search's address as the site's scripts name it: the `/api/...` path fetched with POST. */
    private suspend fun discoverSearchPath(): String? {
        val home = http.get("$BASE/") { headers() }.takeIf { it.status.value in 200..299 }?.bodyAsText() ?: return null
        val scripts = SCRIPT.findAll(home).map { it.groupValues[1] }.distinct().take(MAX_SCRIPTS).toList()
        for (script in scripts) {
            val text = runCatching { http.get(BASE + script) { headers() }.takeIf { it.status.value in 200..299 }?.bodyAsText() }.getOrNull() ?: continue
            searchPathIn(text)?.let { return it }
        }
        return null
    }

    companion object {
        const val BASE = "https://howlongtobeat.com"
        private const val DEFAULT_SEARCH = "/api/search/site"

        /** One fixed browser name: the token is only good for the one it was given to. */
        private const val USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"
        private const val TOKEN_MS = 20 * 60_000L
        private const val PAUSE_MS = 5 * 60_000L
        private const val MAX_SCRIPTS = 24
        private val SCRIPT = Regex("""src="(/_next/static/chunks/[^"]+\.js)"""")
        private val SEARCH = Regex("""fetch\(\s*["'](/api/[A-Za-z0-9_/]+)["']\s*,\s*\{[^}]*method:\s*["']POST["']""")

        /** The search address in one of the site's scripts, or null; error reports and the rest aren't it. */
        fun searchPathIn(script: String): String? =
            SEARCH.findAll(script).map { it.groupValues[1] }.firstOrNull { "search" in it || "find" in it }

        /** The search as the site sends it. */
        fun body(terms: List<String>): String = buildJsonObject {
            put("searchType", "games")
            put("searchTerms", buildJsonArray { terms.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
            put("searchPage", 1)
            put("size", 20)
            putJsonObject("searchOptions") {
                putJsonObject("games") {
                    put("userId", 0)
                    put("platform", "")
                    put("sortCategory", "popular")
                    put("rangeCategory", "main")
                    putJsonObject("rangeTime") { put("min", 0); put("max", 0) }
                    putJsonObject("gameplay") { put("perspective", ""); put("flow", ""); put("genre", "") }
                    put("year", "")
                    put("modifier", "")
                }
                putJsonObject("users") { put("sortCategory", "postcount") }
                putJsonObject("lists") { put("sortCategory", "follows") }
                put("filter", "")
                put("sort", 0)
                put("randomizer", 0)
            }
            put("useCache", true)
        }.toString()

        /** The games in a search's answer. */
        fun parse(json: String): List<HltbGame> {
            val root = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return emptyList()
            val data = root["data"] as? JsonArray ?: return emptyList()
            return data.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                fun text(key: String) = o[key]?.jsonPrimitive?.contentOrNull
                fun long(key: String) = o[key]?.jsonPrimitive?.longOrNull ?: text(key)?.toLongOrNull()
                val id = long("game_id")?.takeIf { it > 0 } ?: return@mapNotNull null
                val name = text("game_name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                HltbGame(
                    id = id,
                    name = name,
                    alias = text("game_alias").orEmpty(),
                    mainSeconds = long("comp_main") ?: 0,
                    plusSeconds = long("comp_plus") ?: 0,
                    completionistSeconds = long("comp_100") ?: 0,
                    year = long("release_world")?.toInt()?.takeIf { it in 1950..2100 },
                    platforms = text("profile_platform").orEmpty(),
                    image = text("game_image")?.takeIf { it.isNotBlank() }?.let { "$BASE/games/$it" },
                )
            }
        }

        /**
         * The game in [games] that is [title] on [platform]: the same name (or one of its other
         * names) once case, punctuation, region tags and articles are set aside, on that system when
         * HowLongToBeat says, from the same year when both say. When more than one fits and nothing
         * tells them apart, none is picked: a guess would show another game's times.
         */
        fun pick(games: List<HltbGame>, titles: List<String>, platformNames: List<String>, year: Int?): HltbGame? {
            val wanted = titles.map(TitleNormalizer::normalize).filter { it.isNotEmpty() }.toSet()
            if (wanted.isEmpty()) return null
            val named = games.filter { g ->
                (listOf(g.name) + g.alias.split(',')).map { TitleNormalizer.normalize(it) }.any { it in wanted }
            }
            if (named.isEmpty()) return null
            if (named.size == 1) return named.single()
            val systems = platformNames.map { it.lowercase() }.filter { it.isNotBlank() }
            val onSystem = named.filter { g -> systems.any { s -> g.platforms.lowercase().split(',').map(String::trim).any { it == s } } }
            val narrowed = onSystem.ifEmpty { named }
            if (narrowed.size == 1) return narrowed.single()
            val sameYear = if (year != null) narrowed.filter { it.year == null || kotlin.math.abs(it.year - year) <= 1 } else narrowed
            return sameYear.singleOrNull()
        }
    }
}
