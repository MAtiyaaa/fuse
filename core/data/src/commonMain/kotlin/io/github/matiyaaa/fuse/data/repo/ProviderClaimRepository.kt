package io.github.matiyaaa.fuse.data.repo

import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.ProviderClaim
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer

/** Durable identities survive restart and path movement; no provider-specific database columns. */
class ProviderClaimRepository(private val cache: CacheRepository) {
    private val lock = Mutex()
    private val serializer = ListSerializer(ProviderClaim.serializer())

    suspend fun get(game: GameId): List<ProviderClaim> =
        cache.get(NAMESPACE, game.value.toString(), serializer, 0) ?: emptyList()

    /** Preserve conflicting evidence instead of silently replacing another device's identity. */
    suspend fun merge(game: GameId, claims: List<ProviderClaim>, now: Long) = lock.withLock {
        val next = (get(game) + claims).filter { it.provider.isNotBlank() && it.gameId.isNotBlank() }
            .map { it.copy(provider = it.canonicalProvider) }
            .groupBy { Triple(it.provider, it.gameId, it.origin) }
            .map { (_, copies) -> copies.maxBy { it.verifiedAtMillis } }
        cache.put(NAMESPACE, game.value.toString(), next, serializer, now, null)
    }

    /** A conscious Identify Game selection resolves this device's previous evidence for that provider. */
    suspend fun confirm(game: GameId, claim: ProviderClaim, now: Long) = lock.withLock {
        require(claim.origin == io.github.matiyaaa.fuse.model.ProviderClaimOrigin.USER_CONFIRMED)
        val next = get(game).filterNot { it.canonicalProvider == claim.canonicalProvider } +
            claim.copy(provider = claim.canonicalProvider)
        cache.put(NAMESPACE, game.value.toString(), next, serializer, now, null)
    }

    companion object { const val NAMESPACE = "provider.claims" }
}
