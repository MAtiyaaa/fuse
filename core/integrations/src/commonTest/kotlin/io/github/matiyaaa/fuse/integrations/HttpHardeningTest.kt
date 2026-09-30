package io.github.matiyaaa.fuse.integrations

import io.github.matiyaaa.fuse.integrations.igdb.IgdbClient
import io.github.matiyaaa.fuse.integrations.igdb.IgdbCredentials
import io.github.matiyaaa.fuse.integrations.steamgriddb.SteamGridDbClient
import io.github.matiyaaa.fuse.integrations.thegamesdb.TheGamesDbClient
import io.ktor.client.request.header
import io.ktor.client.request.url
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HttpHardeningTest {

    private val offline = TestHttp { throw IllegalStateException("Unable to resolve host") }

    @Test
    fun sanitizeKeyDropsWhitespaceControlAndInvisibleCharacters() {
        assertEquals("abc123DEF", sanitizeKey(" abc\t123\r\nDEF ​﻿\u0007"))
        assertEquals("a1b2-c3_d4.e5", sanitizeKey("a1b2-c3_d4.e5"))
        assertEquals("", sanitizeKey(" \n "))
    }

    @Test
    fun aRequestThatCannotBeBuiltIsAFailureWithoutTheValue() = runTest {
        val http = TestHttp { json("{}") }
        val api = ProviderHttp(http.client, "Test", null, { emptyList() })
        val result = api.execute {
            url("https://provider.test/api")
            header("X-Api-Key", "BADKEY\nINJECTED")
        }
        assertIs<ApiResult.Failure>(result)
        assertFalse("BADKEY" in result.message, result.message)
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun decodeTurnsAnyDecoderExceptionIntoInvalidResponse() {
        val api = ProviderHttp(TestHttp { json("{}") }.client, "Test", null, { emptyList() })
        val throwing = object : DeserializationStrategy<String> {
            override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Throwing", PrimitiveKind.STRING)
            override fun deserialize(decoder: Decoder): String = throw IllegalStateException("decoder bug")
        }
        val raw = RawResponse(200, "\"x\"", Headers.Empty)
        assertIs<ApiResult.InvalidResponse>(api.decode(raw, throwing))
        assertIs<ApiResult.InvalidResponse>(api.decode(RawResponse(200, "{\"truncated\":", Headers.Empty), throwing))
    }

    @Test
    fun keyCheckClassifiesEveryFailure() {
        assertEquals(KeyCheck.Working, KeyCheck.from(ApiResult.Success(Unit)))
        assertEquals(KeyCheck.Rejected("no"), KeyCheck.from(ApiResult.AuthError("no")))
        assertEquals(KeyCheck.Rejected("needs key"), KeyCheck.from(ApiResult.NotConfigured("needs key")))
        assertEquals(KeyCheck.Rejected("HTTP 403"), KeyCheck.from(ApiResult.HttpError(403)))
        assertEquals(KeyCheck.Unreachable("offline"), KeyCheck.from(ApiResult.NetworkError("offline")))
        assertEquals(KeyCheck.Failed("HTTP 500"), KeyCheck.from(ApiResult.HttpError(500)))
        assertEquals(KeyCheck.Failed("slow down"), KeyCheck.from(ApiResult.RateLimited(5, "slow down")))
        assertEquals(KeyCheck.Failed("garbled"), KeyCheck.from(ApiResult.InvalidResponse("garbled")))
    }

    @Test
    fun steamGridDbVerifyKey() = runTest {
        var status = HttpStatusCode.OK
        val http = TestHttp { json("""{"success":true,"data":[]}""", status) }
        val client = SteamGridDbClient(http.client, " SGDB_KEY_1\n", RateLimiter.unlimited())
        assertEquals(KeyCheck.Working, client.verifyKey())
        assertEquals("Bearer SGDB_KEY_1", http.last.headers[HttpHeaders.Authorization], "the pasted key is sanitised")
        status = HttpStatusCode.Unauthorized
        val rejected = client.verifyKey()
        assertIs<KeyCheck.Rejected>(rejected)
        assertFalse("SGDB_KEY_1" in rejected.reason)
        status = HttpStatusCode.BadGateway
        assertIs<KeyCheck.Failed>(client.verifyKey())
        assertIs<KeyCheck.Unreachable>(SteamGridDbClient(offline.client, "SGDB_KEY_1", RateLimiter.unlimited()).verifyKey())
        assertIs<KeyCheck.Rejected>(SteamGridDbClient(http.client, " \n", RateLimiter.unlimited()).verifyKey())
    }

    @Test
    fun igdbVerifyCredentials() = runTest {
        var status = HttpStatusCode.OK
        val http = TestHttp { json("""{"access_token":"tok","expires_in":3600,"token_type":"bearer"}""", status) }
        val client = IgdbClient(http.client, IgdbCredentials("client-1\n", "SECRET_ONE "), RateLimiter.unlimited())
        assertEquals(KeyCheck.Working, client.verifyCredentials())
        assertEquals("client-1", http.last.url.parameters["client_id"])
        assertEquals("SECRET_ONE", http.last.url.parameters["client_secret"])
        status = HttpStatusCode.Forbidden
        assertIs<KeyCheck.Rejected>(client.verifyCredentials())
        status = HttpStatusCode.InternalServerError
        assertIs<KeyCheck.Failed>(client.verifyCredentials())
        assertIs<KeyCheck.Unreachable>(IgdbClient(offline.client, IgdbCredentials("id", "secret"), RateLimiter.unlimited()).verifyCredentials())
        assertIs<KeyCheck.Rejected>(IgdbClient(http.client, IgdbCredentials("", ""), RateLimiter.unlimited()).verifyCredentials())
    }

    @Test
    fun theGamesDbVerifyKey() = runTest {
        var status = HttpStatusCode.OK
        var body = """{"code":200,"status":"Success","data":{"count":0,"games":[]},"remaining_monthly_allowance":10}"""
        val http = TestHttp { json(body, status) }
        val client = TheGamesDbClient(http.client, "​TGDB_KEY_1 ", RateLimiter.unlimited())
        assertEquals(KeyCheck.Working, client.verifyKey())
        assertEquals("TGDB_KEY_1", http.last.url.parameters["apikey"])
        status = HttpStatusCode.Unauthorized
        body = """{"code":401,"status":"Invalid API key"}"""
        assertIs<KeyCheck.Rejected>(client.verifyKey())
        status = HttpStatusCode.Forbidden
        body = """{"code":403,"status":"This API Key has reached its allowance limit."}"""
        assertIs<KeyCheck.Failed>(client.verifyKey(), "a used-up allowance does not mean the key is wrong")
        assertIs<KeyCheck.Unreachable>(TheGamesDbClient(offline.client, "TGDB_KEY_1", RateLimiter.unlimited()).verifyKey())
    }
}
