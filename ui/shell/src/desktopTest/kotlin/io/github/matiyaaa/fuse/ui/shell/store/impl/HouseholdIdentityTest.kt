package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.github.matiyaaa.fuse.sync.GameKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HouseholdIdentityTest {
    private val title = GameKey.of("psx", null, null, "Same Name").id
    private val first = GameKey.of("psx", "SLUS-00001", null, "").id
    private val second = GameKey.of("psx", "SLUS-00002", null, "").id

    @Test fun sharedTitleCannotJoinDifferentSerialsOrHideAnotherLocalGame() {
        assertFalse(householdIdentitiesMatch(listOf(first, title), listOf(second, title)))
        assertFalse(householdIdentitiesMatch(listOf(first, title), listOf(title)))
        assertTrue(householdIdentitiesMatch(listOf(first, title), listOf(first)))
    }

    @Test fun publishedSerialOverridesAnUnsafeOldHostAlias() {
        assertEquals(listOf(second), householdIdentityKeys(listOf(first, title), "psx", "SLUS00002"))
    }

    @Test fun conflictingSerialsRemainSeparateEvenWhenAnAliasAlsoSharesAHash() {
        val hash = GameKey.of("psx", null, "abc", "").id
        assertFalse(householdIdentitiesMatch(listOf(first, hash), listOf(second, hash)))
    }

    @Test fun gamesWithoutStrongIdsRetainExistingTitleIdentity() {
        assertTrue(householdIdentitiesMatch(listOf(title), listOf(title)))
        val hash = GameKey.of("psx", null, "abc", "").id
        assertFalse(householdIdentitiesMatch(listOf(hash, title), listOf(title)))
        assertTrue(householdIdentitiesMatch(listOf(hash, title), listOf(hash)))
    }
}
