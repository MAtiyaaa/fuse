package io.github.matiyaaa.fuse.integrations

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/** Network settings shared by every provider client. */
data class FuseHttpConfig(
    /** Fuse's version for the User-Agent, for example "0.1.0". */
    val appVersion: String = "0.0.0",
    val connectTimeoutMillis: Long = 10_000,
    val requestTimeoutMillis: Long = 30_000,
    val socketTimeoutMillis: Long = 30_000,
)

/**
 * Builds the [HttpClient] every integration uses. The app supplies the engine so this module stays
 * platform-neutral: `OkHttp.create()` on Android (ktor-client-okhttp) and `CIO.create()` or
 * `Java.create()` on desktop (ktor-client-cio / ktor-client-java). No logging plugin is installed on
 * purpose: request URLs carry API keys for some providers.
 */
object FuseHttp {

    /** Project page linked from the User-Agent so providers can reach the maintainers. */
    const val PROJECT_URL = "https://github.com/MAtiyaaa/fuse"

    /**
     * JSON settings tolerant of provider quirks: unknown keys are ignored, quoted numbers and
     * unquoted strings are accepted, and nulls for non-null fields fall back to defaults.
     */
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
        encodeDefaults = true
    }

    /** "Fuse/<version> (+https://github.com/MAtiyaaa/fuse)". */
    fun userAgent(appVersion: String): String = "Fuse/$appVersion (+$PROJECT_URL)"

    /**
     * Creates a client on [engine]. The client does not own the engine: close both when the app
     * shuts down. Status codes are never thrown (`expectSuccess = false`); clients map them to
     * [ApiResult] instead.
     */
    fun client(engine: HttpClientEngine, config: FuseHttpConfig = FuseHttpConfig()): HttpClient =
        HttpClient(engine) {
            expectSuccess = false
            followRedirects = true
            install(UserAgent) { agent = userAgent(config.appVersion) }
            install(HttpTimeout) {
                connectTimeoutMillis = config.connectTimeoutMillis
                requestTimeoutMillis = config.requestTimeoutMillis
                socketTimeoutMillis = config.socketTimeoutMillis
            }
            install(ContentNegotiation) { json(json) }
        }
}
