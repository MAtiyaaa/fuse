package io.github.matiyaaa.fuse.integrations

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.request
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json

/** A fully read response: status, headers and body text. */
internal class RawResponse(val status: Int, val body: String, val headers: Headers) {
    val isSuccess: Boolean get() = status in 200..299
}

/**
 * Shared request plumbing for one provider: pacing through [limiter], exception capture into
 * [ApiResult.NetworkError], default status mapping and tolerant JSON decoding. Every message it
 * produces goes through [redact] with the provider's [secrets].
 */
internal class ProviderHttp(
    val http: HttpClient,
    val provider: String,
    var limiter: RateLimiter?,
    private val secrets: () -> List<Secret>,
    val json: Json = FuseHttp.json,
) {
    fun safe(text: String?): String = redact(text, secrets())

    /** Sends the request described by [block] and reads the whole body. */
    suspend fun execute(block: HttpRequestBuilder.() -> Unit): ApiResult<RawResponse> {
        val builder = HttpRequestBuilder().apply(block)
        val host = builder.url.host
        val run: suspend () -> RawResponse = {
            val response = http.request(builder)
            RawResponse(response.status.value, response.bodyAsText(), response.headers)
        }
        return try {
            val raw = limiter?.withPermit(host, run) ?: run()
            ApiResult.Success(raw)
        } catch (e: CancellationException) {
            // A timeout can surface as a cancellation; only a cancelled caller is rethrown.
            currentCoroutineContext().ensureActive()
            ApiResult.NetworkError(safe("$provider: request cancelled (${e::class.simpleName}: ${e.message})"))
        } catch (e: Throwable) {
            ApiResult.NetworkError(safe("$provider: ${e::class.simpleName}: ${e.message}"))
        }
    }

    /** The usual mapping of a non-2xx status, or null for 2xx. */
    fun failureFor(raw: RawResponse): ApiResult.Failure? = when {
        raw.isSuccess -> null
        raw.status == 401 || raw.status == 403 ->
            ApiResult.AuthError("$provider rejected the credentials (HTTP ${raw.status})")
        raw.status == 429 ->
            ApiResult.RateLimited(retryAfterSeconds(raw.headers), "$provider is rate limiting requests")
        else -> ApiResult.HttpError(raw.status, "$provider answered HTTP ${raw.status}")
    }

    /** Decodes [raw]'s body, mapping parse problems to [ApiResult.InvalidResponse]. */
    fun <T> decode(raw: RawResponse, deserializer: DeserializationStrategy<T>): ApiResult<T> = try {
        ApiResult.Success(json.decodeFromString(deserializer, raw.body))
    } catch (e: IllegalArgumentException) {
        // SerializationException is an IllegalArgumentException.
        ApiResult.InvalidResponse(safe("$provider sent an unreadable response: ${e.message?.take(240)}"))
    }

    /** [execute] + [failureFor] + [decode] for the common JSON GET/POST. */
    suspend fun <T> json(deserializer: DeserializationStrategy<T>, block: HttpRequestBuilder.() -> Unit): ApiResult<T> =
        execute(block).flatMap { raw -> failureFor(raw) ?: decode(raw, deserializer) }
}

/** Retry-After in seconds when the header carries a number (HTTP-date values are ignored). */
internal fun retryAfterSeconds(headers: Headers): Long? =
    headers[HttpHeaders.RetryAfter]?.trim()?.toLongOrNull()?.coerceAtLeast(0)
