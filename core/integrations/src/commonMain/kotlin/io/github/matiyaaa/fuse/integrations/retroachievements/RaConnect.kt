package io.github.matiyaaa.fuse.integrations.retroachievements

import io.github.matiyaaa.fuse.integrations.ApiResult
import io.github.matiyaaa.fuse.integrations.ProviderHttp
import io.github.matiyaaa.fuse.integrations.Secret
import io.github.matiyaaa.fuse.integrations.flatMap
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.Parameters
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A RetroAchievements account as its emulators sign in with it: the user name as RetroAchievements has it, and its login token. */
class RaLogin(val user: String, val token: Secret) {
    override fun toString(): String = "RaLogin(user=$user)"
}

/**
 * Signing in to RetroAchievements the way its emulators do, for the login token they keep instead of
 * a password: a form posted to `https://retroachievements.org/dorequest.php` with `r=login2`, the
 * user name `u` and the password `p`, as rcheevos builds it (`src/rapi/rc_api_user.c`,
 * `rc_api_init_login_request_hosted`, v12.1.0, `RETROACHIEVEMENTS_HOST` in `rc_api_common.c`),
 * answered with `Success`, `User` and `Token` (`rc_api_process_login_server_response`), or with
 * `Success: false` and an `Error`. The password goes out in this one request, over HTTPS, and is
 * kept nowhere; neither it nor the token is ever in a message (both go through `redact`).
 */
class RaConnect(http: HttpClient, private val userAgent: String, private val endpoint: String = URL) {
    private val password = arrayOfNulls<Secret>(1)
    private val token = arrayOfNulls<Secret>(1)
    private val api = ProviderHttp(http, "RetroAchievements", limiter = null, secrets = { listOfNotNull(password[0], token[0]) })

    @Serializable
    private class Answer(
        @SerialName("Success") val success: Boolean = false,
        @SerialName("User") val user: String? = null,
        @SerialName("Token") val token: String? = null,
        @SerialName("Error") val error: String? = null,
    )

    /** Signs [user] in with [secret] (the password) and returns the account with its login token. */
    suspend fun login(user: String, secret: String): ApiResult<RaLogin> {
        password[0] = Secret(secret)
        try {
            return api.execute {
                method = HttpMethod.Post
                url(endpoint)
                header(HttpHeaders.UserAgent, userAgent)
                setBody(FormDataContent(Parameters.build {
                    append("r", "login2")
                    append("u", user.trim())
                    append("p", secret)
                }))
            }.flatMap { raw ->
                val answer = runCatching { api.json.decodeFromString(Answer.serializer(), raw.body) }.getOrNull()
                token[0] = answer?.token?.let(::Secret)
                when {
                    answer == null -> api.failureFor(raw) ?: ApiResult.InvalidResponse("RetroAchievements answered in a way Fuse doesn't read")
                    !answer.success || answer.token.isNullOrBlank() || answer.user.isNullOrBlank() -> {
                        val why = api.safe(answer.error).ifBlank { "RetroAchievements didn't accept that user name and password." }
                        if (raw.status == 401 || raw.status == 403 || answer.error != null) ApiResult.AuthError(why) else ApiResult.HttpError(raw.status, why)
                    }
                    else -> ApiResult.Success(RaLogin(answer.user, Secret(answer.token)))
                }
            }
        } finally {
            password[0] = null
        }
    }

    companion object {
        const val URL = "https://retroachievements.org/dorequest.php"
    }
}
