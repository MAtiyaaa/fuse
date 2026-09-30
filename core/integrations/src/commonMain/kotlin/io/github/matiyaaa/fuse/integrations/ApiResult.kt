package io.github.matiyaaa.fuse.integrations

/**
 * Outcome of one provider call. Clients never throw network or HTTP problems into the UI; they
 * return a [Failure] whose [Failure.message] is already redacted (no keys, tokens or passwords).
 */
sealed interface ApiResult<out T> {

    /** The call worked and produced [value]. */
    data class Success<out T>(val value: T) : ApiResult<T>

    /** Every non-success outcome. [message] is safe to show and to log. */
    sealed interface Failure : ApiResult<Nothing> {
        val message: String
    }

    /** The server answered with an unexpected HTTP status. */
    data class HttpError(val code: Int, override val message: String = "HTTP $code") : Failure

    /** The request never got a usable answer (offline, DNS, TLS, timeout). */
    data class NetworkError(override val message: String) : Failure

    /** The provider rejected the credentials (or the call needs credentials it did not get). */
    data class AuthError(override val message: String = "Credentials were rejected") : Failure

    /** The provider asked us to slow down. [retryAfterSeconds] comes from Retry-After when sent. */
    data class RateLimited(
        val retryAfterSeconds: Long? = null,
        override val message: String = "Rate limited by the provider",
    ) : Failure

    /** The server answered 2xx but the body could not be understood. */
    data class InvalidResponse(override val message: String) : Failure

    /** The provider cannot be used until the user (or the build) supplies something, see [message]. */
    data class NotConfigured(override val message: String) : Failure
}

/** The value, or null for any failure. */
fun <T> ApiResult<T>.getOrNull(): T? = (this as? ApiResult.Success<T>)?.value

/** The failure, or null on success. */
fun ApiResult<*>.failureOrNull(): ApiResult.Failure? = this as? ApiResult.Failure

val ApiResult<*>.isSuccess: Boolean get() = this is ApiResult.Success

/** Transforms a successful value, passing failures through unchanged. */
inline fun <T, R> ApiResult<T>.map(transform: (T) -> R): ApiResult<R> = when (this) {
    is ApiResult.Success -> ApiResult.Success(transform(value))
    is ApiResult.Failure -> this
}

/** Chains another call after a success. */
inline fun <T, R> ApiResult<T>.flatMap(transform: (T) -> ApiResult<R>): ApiResult<R> = when (this) {
    is ApiResult.Success -> transform(value)
    is ApiResult.Failure -> this
}

/** Runs [action] on success and returns this result. */
inline fun <T> ApiResult<T>.onSuccess(action: (T) -> Unit): ApiResult<T> {
    if (this is ApiResult.Success) action(value)
    return this
}

/** Runs [action] on failure and returns this result. */
inline fun <T> ApiResult<T>.onFailure(action: (ApiResult.Failure) -> Unit): ApiResult<T> {
    if (this is ApiResult.Failure) action(this)
    return this
}
