package io.github.matiyaaa.fuse.integrations

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.request
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.request
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.fromHttpToGmtDate
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json

/** A fully read response: status, headers, body text and the address it finally came from (after redirects). */
internal class RawResponse(val status: Int, val body: String, val headers: Headers, val url: String = "") {
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

    /**
     * Sends the request described by [block] and reads the whole body. A request that cannot be
     * built (a key with a line break makes an illegal header value) is a [ApiResult.NotConfigured]
     * failure, not a crash; its message leaves out the exception text, which may quote the key.
     */
    suspend fun execute(block: HttpRequestBuilder.() -> Unit): ApiResult<RawResponse> {
        val builder = try {
            HttpRequestBuilder().apply(block)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return ApiResult.NotConfigured(
                "$provider: the request could not be built (${e::class.simpleName}); check the key for stray characters",
            )
        }
        val host = builder.url.host
        val run: suspend () -> RawResponse = {
            val response = http.request(builder)
            RawResponse(response.status.value, response.bodyAsText(), response.headers, response.request.url.toString())
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

    /**
     * Decodes [raw]'s body, mapping every parse problem to [ApiResult.InvalidResponse], not only
     * SerializationException: a truncated or unexpected body can make a decoder throw anything.
     */
    fun <T> decode(raw: RawResponse, deserializer: DeserializationStrategy<T>): ApiResult<T> = try {
        ApiResult.Success(json.decodeFromString(deserializer, raw.body))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        ApiResult.InvalidResponse(safe("$provider sent an unreadable response: ${e.message?.take(240)}"))
    }

    /** [execute] + [failureFor] + [decode] for the common JSON GET/POST. */
    suspend fun <T> json(deserializer: DeserializationStrategy<T>, block: HttpRequestBuilder.() -> Unit): ApiResult<T> =
        execute(block).flatMap { raw -> failureFor(raw) ?: decode(raw, deserializer) }
}

/** Both Retry-After forms (delay seconds and HTTP-date) preserve the provider's cooldown. */
internal fun retryAfterSeconds(headers: Headers, nowMillis: Long = Clock.System.now().toEpochMilliseconds()): Long? {
    val value = headers[HttpHeaders.RetryAfter]?.trim() ?: return null
    value.toLongOrNull()?.let { return it.coerceAtLeast(0) }
    val date = runCatching { value.fromHttpToGmtDate().timestamp }.getOrNull() ?: return null
    val remaining = (date - nowMillis).coerceAtLeast(0)
    return remaining / 1000 + if (remaining % 1000 == 0L) 0 else 1
}
