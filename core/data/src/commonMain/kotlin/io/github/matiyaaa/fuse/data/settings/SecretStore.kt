package io.github.matiyaaa.fuse.data.settings

/**
 * Credentials storage, implemented by each app with the platform's secure store (Android Keystore
 * backed encryption, Linux Secret Service). Secrets never go into the database or [AppSettings].
 */
interface SecretStore {
    suspend fun get(key: String): String?

    suspend fun put(key: String, value: String)

    suspend fun remove(key: String)

    suspend fun has(key: String): Boolean = get(key) != null
}

/**
 * Well-known [SecretStore] keys. Usernames are not secret, but they live next to their keys so an
 * account is added and removed as one unit.
 */
object SecretKeys {
    const val RA_USERNAME = "ra.username"
    const val RA_API_KEY = "ra.apikey"
    const val SGDB_API_KEY = "sgdb.apikey"
    const val IGDB_CLIENT_ID = "igdb.clientId"
    const val IGDB_CLIENT_SECRET = "igdb.clientSecret"
    const val TGDB_API_KEY = "tgdb.apikey"
    const val SCREENSCRAPER_USER = "ss.user"
    const val SCREENSCRAPER_PASSWORD = "ss.password"

    val all: List<String> = listOf(
        RA_USERNAME, RA_API_KEY, SGDB_API_KEY, IGDB_CLIENT_ID, IGDB_CLIENT_SECRET, TGDB_API_KEY,
        SCREENSCRAPER_USER, SCREENSCRAPER_PASSWORD,
    )
}
