package io.github.matiyaaa.fuse.integrations

/**
 * Holds a credential so it cannot leak through string templates, logs or data class printing.
 * The raw value is only reachable through [reveal], which clients call while building a request.
 */
class Secret(private val value: String) {
    /** The raw secret. Only pass it to the HTTP request that needs it. */
    fun reveal(): String = value

    val isBlank: Boolean get() = value.isBlank()

    override fun toString(): String = REDACTED
    override fun equals(other: Any?): Boolean = other is Secret && other.value == value
    override fun hashCode(): Int = value.hashCode()
}

internal const val REDACTED = "***"

/**
 * [raw] without whitespace, control or invisible formatting characters (zero-width spaces, byte
 * order marks). Keys pasted from a web page or a password manager often carry a line break or a
 * zero-width space, which the provider rejects or which cannot even be sent as a header. No
 * provider Fuse talks to uses such characters inside a key.
 */
fun sanitizeKey(raw: String): String =
    raw.filterNot { it.isWhitespace() || it.isISOControl() || it.category == CharCategory.FORMAT }

private val secretQueryParam = Regex(
    "(?i)((?:^|[?&;\\s,(\\[])(?:y|apikey|api_key|key|client_secret|devid|devpassword|ssid|sspassword|password|access_token|token)=)" +
        "[^&\\s#\"',;\\]]*",
)
private val authorizationHeader = Regex("(?i)(authorization\\s*[:=]\\s*)(?:bearer\\s+|basic\\s+)?[^\\s,;\"'\\]]+")
private val bearerToken = Regex("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]+")
private val jsonSecretField = Regex("(?i)(\"(?:access_token|client_secret|apikey|api_key)\"\\s*:\\s*\")[^\"]*")

/**
 * Removes credentials from [text] before it is put into an error message or a log line:
 * `y=`, `apikey=`, `client_secret=`, ScreenScraper `devpassword=`/`sspassword=` (and ids) query
 * parameters, `Authorization` headers, bearer tokens, token fields in JSON, and every literal in
 * [secrets] (the caller's own keys, in case a server echoes them somewhere unexpected).
 */
fun redact(text: String?, secrets: Collection<Secret> = emptyList()): String {
    if (text == null) return ""
    var out: String = text
    for (secret in secrets) {
        val raw = secret.reveal()
        if (raw.length >= 4) out = out.replace(raw, REDACTED)
    }
    out = secretQueryParam.replace(out) { it.groupValues[1] + REDACTED }
    out = authorizationHeader.replace(out) { it.groupValues[1] + REDACTED }
    out = bearerToken.replace(out) { it.groupValues[1] + REDACTED }
    out = jsonSecretField.replace(out) { it.groupValues[1] + REDACTED }
    return out
}
