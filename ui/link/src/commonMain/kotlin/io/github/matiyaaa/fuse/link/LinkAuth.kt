package io.github.matiyaaa.fuse.link

import io.github.matiyaaa.fuse.data.settings.SecretStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What a sign-in attempt got. */
internal sealed interface LoginResult {
    data class Ok(val token: String) : LoginResult
    data object Wrong : LoginResult
    data class Locked(val retryAfterSeconds: Int) : LoginResult
    data object NoAccount : LoginResult
}

/**
 * Phone Link's one account and its sessions, kept in Fuse's secret store (encrypted on Android).
 *
 * The password is stored as a salted PBKDF2-HMAC-SHA256 hash, never as text. Five wrong tries lock
 * sign-in for a minute (for every phone, so guessing from several at once doesn't help). A session
 * is a random token in the phone's cookie; only its SHA-256 is stored, and a password change or
 * "Sign out all phones" ends every session.
 */
internal class LinkAuth(
    private val secrets: SecretStore,
    private val clock: () -> Long,
) {
    private val lock = Mutex()
    private var sessions: MutableSet<String>? = null
    private var failures = 0
    private var lockedUntil = 0L

    suspend fun username(): String? = secrets.get(USER)?.takeIf { it.isNotBlank() }

    suspend fun hasAccount(): Boolean = username() != null && secrets.get(PASSWORD) != null

    /** Sets the account; every phone signed in with the old one is signed out. */
    suspend fun setAccount(username: String, password: String) = lock.withLock {
        require(username.isNotBlank()) { "A username is needed" }
        require(password.length >= MIN_PASSWORD) { "The password needs at least $MIN_PASSWORD characters" }
        val salt = secureRandom(16)
        val hash = pbkdf2(password, salt, ITERATIONS, 32)
        secrets.put(USER, username.trim())
        secrets.put(PASSWORD, "pbkdf2-sha256\$$ITERATIONS\$${base64(salt)}\$${base64(hash)}")
        saveSessions(mutableSetOf())
        failures = 0
        lockedUntil = 0
    }

    suspend fun login(username: String, password: String): LoginResult = lock.withLock {
        val now = clock()
        if (now < lockedUntil) return LoginResult.Locked(((lockedUntil - now + 999) / 1000).toInt())
        val user = secrets.get(USER) ?: return LoginResult.NoAccount
        val stored = secrets.get(PASSWORD) ?: return LoginResult.NoAccount
        val parts = stored.split('$')
        val ok = parts.size == 4 && parts[0] == "pbkdf2-sha256" && run {
            val iterations = parts[1].toIntOrNull() ?: return@run false
            val salt = runCatching { unbase64(parts[2]) }.getOrNull() ?: return@run false
            val expected = runCatching { unbase64(parts[3]) }.getOrNull() ?: return@run false
            // The hash is worked out even for a wrong name, so timing doesn't tell names apart.
            val actual = pbkdf2(password, salt, iterations, expected.size)
            constantTimeEquals(actual, expected) && username.trim().equals(user, ignoreCase = true)
        }
        if (!ok) {
            failures++
            if (failures >= MAX_FAILURES) {
                failures = 0
                lockedUntil = now + LOCK_MS
                return LoginResult.Locked((LOCK_MS / 1000).toInt())
            }
            return LoginResult.Wrong
        }
        failures = 0
        val token = base64(secureRandom(32)).replace('+', '-').replace('/', '_').trimEnd('=')
        val all = loadSessions()
        all += digest(token)
        // A bounded list: the oldest sessions give way first.
        while (all.size > MAX_SESSIONS) all.remove(all.first())
        saveSessions(all)
        LoginResult.Ok(token)
    }

    suspend fun isSignedIn(token: String?): Boolean {
        if (token.isNullOrBlank() || token.length > 200) return false
        return lock.withLock { digest(token) in loadSessions() }
    }

    suspend fun logout(token: String?) = lock.withLock {
        if (token.isNullOrBlank()) return@withLock
        val all = loadSessions()
        if (all.remove(digest(token))) saveSessions(all)
    }

    suspend fun signOutAll() = lock.withLock { saveSessions(mutableSetOf()) }

    suspend fun sessionCount(): Int = lock.withLock { loadSessions().size }

    /** Removes the account and every session. */
    suspend fun clear() = lock.withLock {
        secrets.remove(USER)
        secrets.remove(PASSWORD)
        saveSessions(mutableSetOf())
    }

    private suspend fun loadSessions(): MutableSet<String> {
        sessions?.let { return it }
        val loaded = secrets.get(SESSIONS).orEmpty().split(',').filter { it.isNotBlank() }.toCollection(LinkedHashSet())
        sessions = loaded
        return loaded
    }

    private suspend fun saveSessions(all: MutableSet<String>) {
        sessions = all
        if (all.isEmpty()) secrets.remove(SESSIONS) else secrets.put(SESSIONS, all.joinToString(","))
    }

    private fun digest(token: String): String = base64(sha256(token.encodeToByteArray()))

    companion object {
        const val USER = "link.username"
        const val PASSWORD = "link.password"
        const val SESSIONS = "link.sessions"
        const val MIN_PASSWORD = 6
        const val MAX_FAILURES = 5
        const val LOCK_MS = 60_000L
        const val ITERATIONS = 120_000
        const val MAX_SESSIONS = 20
    }
}
