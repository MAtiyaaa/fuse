package io.github.matiyaaa.fuse.integrations.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/** How a server is reached: at home, from outside, or whichever answers (recommended). */
enum class RouteMode { AUTO, LOCAL, REMOTE }

/** The way a call went. */
enum class NetRoute { LOCAL, REMOTE }

/**
 * Which address a server's calls go to, the way Fuse Sync learned to do it in 0.3.5:
 *
 * - At home, or not knowing yet: home first, then outside.
 * - Away (the last call that got through went outside): outside first, so no call waits on an
 *   address that can't answer from here. Home is looked at quietly on the side, at most every
 *   [lookEveryMs], and calls go home again the moment it answers.
 * - LOCAL and REMOTE modes use only their own address.
 *
 * It never decides a running transfer's fate: a transfer asks for an address each time it (re)starts,
 * and carries on from where it was by whichever route that is.
 */
class RoutePicker(
    local: String?,
    remote: String?,
    mode: RouteMode,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    /** Whether the server answers at [base] (quickly: a second or two at most). */
    private val probe: suspend (base: String) -> Boolean,
    private val lookEveryMs: Long = 60_000,
) {
    @kotlin.concurrent.Volatile private var local: String? = local?.let(::clean)?.ifEmpty { null }
    @kotlin.concurrent.Volatile private var remote: String? = remote?.let(::clean)?.ifEmpty { null }
    @kotlin.concurrent.Volatile private var mode: RouteMode = mode
    @kotlin.concurrent.Volatile private var lookedAt = Long.MIN_VALUE / 2
    private val looking = Mutex()

    private val _route = MutableStateFlow<NetRoute?>(null)
    /** The route the last successful call used; null before any, or after every address failed. */
    val route: StateFlow<NetRoute?> = _route.asStateFlow()

    fun configure(local: String?, remote: String?, mode: RouteMode) {
        val l = local?.let(::clean)?.ifEmpty { null }
        val r = remote?.let(::clean)?.ifEmpty { null }
        if (l == this.local && r == this.remote && mode == this.mode) return
        this.local = l
        this.remote = r
        this.mode = mode
        _route.value = null
    }

    /** Addresses to try, in order. */
    fun bases(): List<Pair<NetRoute, String>> {
        val home = local?.let { NetRoute.LOCAL to it }
        val away = remote?.let { NetRoute.REMOTE to it }
        return when (mode) {
            RouteMode.LOCAL -> listOfNotNull(home)
            RouteMode.REMOTE -> listOfNotNull(away)
            RouteMode.AUTO -> if (_route.value == NetRoute.REMOTE && home != null && away != null) {
                lookForHome(home.second)
                listOf(away, home)
            } else {
                listOfNotNull(home, away)
            }
        }
    }

    /** The address a new call (or a transfer starting again) should use first. */
    fun preferred(): Pair<NetRoute, String>? = bases().firstOrNull()

    /** A call got through on [route]. */
    fun answered(route: NetRoute) {
        _route.value = route
    }

    /** Every address failed. */
    fun lost() {
        _route.value = null
    }

    /** Looks for home now (a network change), without waiting for the next turn. */
    fun lookNow() {
        lookedAt = Long.MIN_VALUE / 2
        local?.let { if (mode == RouteMode.AUTO && _route.value == NetRoute.REMOTE) lookForHome(it) }
    }

    private fun lookForHome(base: String) {
        val now = clock()
        if (now - lookedAt < lookEveryMs) return
        lookedAt = now
        scope.launch {
            if (!looking.tryLock()) return@launch
            try {
                if (runCatching { probe(base) }.getOrDefault(false) && _route.value == NetRoute.REMOTE && local == base) _route.value = NetRoute.LOCAL
            } finally {
                looking.unlock()
            }
        }
    }

    companion object {
        /**
         * An address as typed, made usable: no trailing slash, and a scheme when it had none (https
         * for a name on the internet, http for an address at home).
         */
        fun clean(address: String): String {
            val a = address.trim().trimEnd('/')
            if (a.isEmpty() || a.contains("://")) return a
            val host = a.substringBefore('/').substringBefore(':')
            val home = host == "localhost" || host.endsWith(".local") || host.endsWith(".lan") || host.endsWith(".home") ||
                !host.contains('.') || host.all { it.isDigit() || it == '.' } && isPrivate(host)
            return (if (home) "http://" else "https://") + a
        }

        private fun isPrivate(ip: String): Boolean {
            val p = ip.split('.').mapNotNull { it.toIntOrNull() }
            if (p.size != 4) return false
            return p[0] == 10 || p[0] == 127 || (p[0] == 192 && p[1] == 168) || (p[0] == 172 && p[1] in 16..31) || (p[0] == 100 && p[1] in 64..127) || (p[0] == 169 && p[1] == 254)
        }
    }
}
