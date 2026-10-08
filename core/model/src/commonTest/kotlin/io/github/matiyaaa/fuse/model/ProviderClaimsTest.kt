package io.github.matiyaaa.fuse.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class ProviderClaimsTest {
    @Test fun legacyProviderSpellingCannotHideConflictingHouseholdEvidence() {
        val old = ProviderClaim("steamgriddb", "1")
        val current = ProviderClaim("STEAMGRIDDB", "2")
        assertTrue(trustedProviderIds(listOf(old, current)).isEmpty())
        assertEquals(mapOf("STEAMGRIDDB" to "1"), trustedProviderIds(listOf(old)))
    }

    @Test fun conflictingAutomaticClaimsNeverChooseByArrival() {
        val claims = listOf(ProviderClaim("STEAMGRIDDB", "1"), ProviderClaim("STEAMGRIDDB", "2"))
        assertTrue(trustedProviderIds(claims).isEmpty())
        assertTrue(trustedProviderIds(claims.reversed()).isEmpty())
    }

    @Test fun userConfirmationWinsButConflictingUserChoicesRequireReview() {
        val confirmed = ProviderClaim("STEAMGRIDDB", "2", ProviderClaimOrigin.USER_CONFIRMED)
        assertEquals(mapOf("STEAMGRIDDB" to "2"), trustedProviderIds(listOf(ProviderClaim("STEAMGRIDDB", "1"), confirmed)))
        assertTrue(trustedProviderIds(listOf(confirmed, confirmed.copy(gameId = "3"))).isEmpty())
    }

    @Test fun weakMatchesAreNotKnowledgeAndUnknownProvidersRoundTrip() {
        assertTrue(trustedProviderIds(listOf(ProviderClaim("IGDB", "1", confidence = 0.5f))).isEmpty())
        val claim = ProviderClaim("future-provider", "abc", originDeviceId = "thor")
        assertEquals(claim, Json.decodeFromString<ProviderClaim>(Json.encodeToString(claim)))
    }
}
