package io.github.matiyaaa.fuse.integrations

/**
 * The outcome of testing a key or credentials, for settings screens that check them. It separates
 * a key that needs fixing ([Rejected]) from a connection problem ([Unreachable]), which the same
 * key would pass once the device is online. Every [reason] is already redacted.
 */
sealed interface KeyCheck {

    /** The provider accepted the credentials. */
    data object Working : KeyCheck

    /** The provider rejected the credentials, or they are missing or malformed. */
    data class Rejected(val reason: String) : KeyCheck

    /** The provider could not be reached (offline, DNS, TLS, timeout). */
    data class Unreachable(val reason: String) : KeyCheck

    /** Anything else: a server error, a rate limit or an answer Fuse could not read. */
    data class Failed(val reason: String) : KeyCheck

    companion object {
        /** Classifies the result of a real call made with the credentials under test. */
        fun from(result: ApiResult<*>): KeyCheck = when (result) {
            is ApiResult.Success -> Working
            is ApiResult.AuthError -> Rejected(result.message)
            is ApiResult.NotConfigured -> Rejected(result.message)
            is ApiResult.NetworkError -> Unreachable(result.message)
            is ApiResult.HttpError -> if (result.code == 401 || result.code == 403) Rejected(result.message) else Failed(result.message)
            is ApiResult.RateLimited -> Failed(result.message)
            is ApiResult.InvalidResponse -> Failed(result.message)
        }
    }
}
