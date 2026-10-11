package io.github.matiyaaa.fuse.ui.shell.store.impl

import kotlin.test.Test
import kotlin.test.assertEquals

/** Which of the household's keys a device takes, from what it has and what both last had alike. */
class HouseholdKeysTest {
    @Test
    fun aDeviceWithoutAKeyTakesTheHouseholds() {
        assertEquals(mapOf("sgdb.apikey" to "a"), HouseholdKeys.incoming(mapOf("sgdb.apikey" to "a"), emptyMap(), emptyMap(), emptySet()))
    }

    @Test
    fun aKeyChangedElsewhereIsTakenOnlyWhileThisDeviceKeptWhatBothHad() {
        val household = mapOf("sgdb.apikey" to "new")
        // Unchanged here since both had "old": the change from elsewhere comes in.
        assertEquals(household, HouseholdKeys.incoming(household, mapOf("sgdb.apikey" to "old"), mapOf("sgdb.apikey" to "old"), emptySet()))
        // Changed here too: this device's goes up instead, nothing comes in.
        assertEquals(emptyMap(), HouseholdKeys.incoming(household, mapOf("sgdb.apikey" to "mine"), mapOf("sgdb.apikey" to "old"), emptySet()))
        // Never agreed (the first look after updating): this device's own is kept.
        assertEquals(emptyMap(), HouseholdKeys.incoming(household, mapOf("sgdb.apikey" to "mine"), emptyMap(), emptySet()))
    }

    @Test
    fun aKeyRemovedHereNeverComesBackByItself() {
        assertEquals(emptyMap(), HouseholdKeys.incoming(mapOf("ra.apikey" to "k"), emptyMap(), mapOf("ra.apikey" to "k"), setOf("ra.apikey")))
    }

    @Test
    fun onlyKeysAreReadFromTheHouseholdsSignIns() {
        val signIns = mapOf("romm" to "{}", "jellyfin:p1" to "{}", "key:tgdb.apikey" to "t", "key:" to "")
        assertEquals(mapOf("tgdb.apikey" to "t", "" to ""), HouseholdKeys.fromSignIns(signIns))
        // A blank value is never taken.
        assertEquals(mapOf("tgdb.apikey" to "t"), HouseholdKeys.incoming(HouseholdKeys.fromSignIns(signIns), emptyMap(), emptyMap(), emptySet()))
    }
}
