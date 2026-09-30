package io.github.matiyaaa.fuse.integrations

import io.github.matiyaaa.fuse.integrations.retroachievements.RaCredentials
import io.github.matiyaaa.fuse.integrations.retroachievements.RetroAchievementsClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FoundationTest {

    @Test
    fun redactRemovesKeysFromUrlsAndHeaders() {
        val text = "GET https://retroachievements.org/API/API_GetUserProfile.php?u=alice&y=SECRETKEY123 failed; " +
            "https://api.thegamesdb.net/v1/Games/Images?apikey=abcdef&games_id=1 " +
            "Authorization: Bearer tok_987654 client_secret=shh123 devpassword=pw sspassword=pw2 " +
            "{\"access_token\":\"zzz999\"}"
        val out = redact(text)
        assertFalse("SECRETKEY123" in out)
        assertFalse("abcdef" in out)
        assertFalse("tok_987654" in out)
        assertFalse("shh123" in out)
        assertFalse("zzz999" in out)
        assertTrue("u=alice" in out)
        assertTrue("games_id=1" in out)
        assertTrue("y=***" in out)
    }

    @Test
    fun redactRemovesLiteralSecretsAnywhere() {
        val out = redact("the server echoed MYKEY-42 back", listOf(Secret("MYKEY-42")))
        assertEquals("the server echoed *** back", out)
    }

    @Test
    fun secretAndCredentialsNeverPrintTheKey() {
        assertEquals("***", Secret("hunter2").toString())
        val creds = RaCredentials("alice", "RA_KEY_0123456789")
        assertFalse("RA_KEY_0123456789" in creds.toString())
        assertFalse("RA_KEY_0123456789" in "$creds")
        assertTrue("alice" in creds.toString())
    }

    @Test
    fun networkErrorsAreRedacted() = runTest {
        val key = "LEAKY_KEY_987"
        val client = FuseHttp.client(MockEngine { request -> error("connection reset while fetching ${request.url}") })
        val ra = RetroAchievementsClient(client, RaCredentials("alice", key), RateLimiter.unlimited())
        val result = ra.userProfile()
        assertIs<ApiResult.NetworkError>(result)
        assertFalse(key in result.message, result.message)
        assertTrue("RetroAchievements" in result.message)
    }

    @Test
    fun userAgentIsSent() = runTest {
        val http = TestHttp { json("{}") }
        RetroAchievementsClient(http.client, RaCredentials("a", "k"), RateLimiter.unlimited()).userProfile()
        assertEquals("Fuse/1.2.3 (+https://github.com/MAtiyaaa/fuse)", http.last.headers[HttpHeaders.UserAgent])
    }

    @Test
    fun statusCodesMapToTypedResults() = runTest {
        var status = HttpStatusCode.Unauthorized
        val http = TestHttp { json("{\"message\":\"Unauthenticated.\"}", status, "Retry-After" to "30") }
        val ra = RetroAchievementsClient(http.client, RaCredentials("a", "KEY_SHOULD_NOT_LEAK"), RateLimiter.unlimited())
        val auth = ra.userProfile()
        assertIs<ApiResult.AuthError>(auth)
        assertFalse("KEY_SHOULD_NOT_LEAK" in auth.message)
        status = HttpStatusCode.TooManyRequests
        val limited = ra.userProfile()
        assertIs<ApiResult.RateLimited>(limited)
        assertEquals(30, limited.retryAfterSeconds)
        status = HttpStatusCode.InternalServerError
        val server = ra.userProfile()
        assertIs<ApiResult.HttpError>(server)
        assertEquals(500, server.code)
    }

    @Test
    fun rateLimiterSpacesCallsPerHost() = runTest {
        val limiter = RateLimiter(minIntervalMillis = 400, maxConcurrency = 1, nowMillis = { testScheduler.currentTime })
        val starts = mutableListOf<Long>()
        repeat(3) { limiter.withPermit("a.example") { starts += testScheduler.currentTime } }
        assertEquals(listOf(0L, 400L, 800L), starts)
        // A different host has its own gate.
        limiter.withPermit("b.example") { starts += testScheduler.currentTime }
        assertEquals(800L, starts.last())
    }

    @Test
    fun rateLimiterBoundsConcurrency() = runTest {
        val limiter = RateLimiter(minIntervalMillis = 0, maxConcurrency = 2, nowMillis = { testScheduler.currentTime })
        var running = 0
        var peak = 0
        (1..6).map {
            async {
                limiter.withPermit("host") {
                    running++
                    peak = maxOf(peak, running)
                    delay(100)
                    running--
                }
            }
        }.awaitAll()
        assertEquals(2, peak)
        assertEquals(300L, testScheduler.currentTime)
    }

    @Test
    fun urlCodingRoundTrips() {
        val raw = "Pokémon: Red & Blue (100%) + extras/\"quoted\""
        val encoded = UrlCoding.encode(raw)
        assertFalse(' ' in encoded)
        assertTrue("%20" in encoded)
        assertEquals(raw, UrlCoding.decode(encoded))
        assertEquals("a b", UrlCoding.decode("a+b", plusAsSpace = true))
        assertEquals("a+b", UrlCoding.decode("a+b"))
    }

    @Test
    fun datesParseRaFormats() {
        assertEquals(1_705_343_025_000L, Dates.parseEpochMillis("2024-01-15 18:23:45"))
        assertEquals(1_705_343_025_000L, Dates.parseEpochMillis("2024-01-15T18:23:45.000000Z"))
        assertEquals(1_705_343_025_000L, Dates.parseEpochMillis("2024-01-15T20:23:45+02:00"))
        assertEquals(null, Dates.parseEpochMillis("not a date"))
        assertEquals(1994, Dates.yearOfEpochSeconds(764_553_600))
        assertEquals(1969, Dates.yearOfEpochSeconds(-1))
        assertEquals(1994, Dates.yearIn("1994-03-19"))
    }

    @Test
    fun apiResultHelpers() {
        val ok: ApiResult<Int> = ApiResult.Success(2)
        assertEquals(4, ok.map { it * 2 }.getOrNull())
        val failed: ApiResult<Int> = ApiResult.NetworkError("offline")
        assertEquals(null, failed.map { it * 2 }.getOrNull())
        assertIs<ApiResult.NetworkError>(failed.flatMap { ApiResult.Success(it) })
    }
}
