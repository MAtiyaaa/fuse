package io.github.matiyaaa.fuse.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

class RefreshRatesTest {
    @Test fun automaticUsesMaximumAtEveryRefreshClass() {
        for (maximum in listOf(90f, 120f, 144f, 165f, 240f)) {
            assertEquals(1, RefreshRates.pick(listOf(61f, maximum)))
        }
    }
    @Test fun capFallsBackBelowItRatherThanChoosingNearestAbove() {
        assertEquals(0, RefreshRates.pick(listOf(60f, 120f), 90))
        assertEquals(1, RefreshRates.pick(listOf(60f, 90f, 120f), 100))
        assertEquals(0, RefreshRates.pick(listOf(120f, 144f), 60))
    }
    @Test fun fractionalFamiliesRemainPredictable() {
        assertEquals(1, RefreshRates.pick(listOf(59.94f, 61f, 119.88f, 120f), 60))
        assertEquals(3, RefreshRates.pick(listOf(59.94f, 61f, 119.88f, 120f), 120))
        assertEquals(listOf(60, 120), RefreshRates.options(listOf(59.94f, 61f, 119.88f, 120f)))
    }
    @Test fun eachDisplayUsesOnlyItsOwnModes() {
        assertEquals(1, RefreshRates.pick(listOf(60f, 120f)))
        assertEquals(0, RefreshRates.pick(listOf(60f)))
        assertEquals(1, RefreshRates.pick(listOf(120f, 240f)))
        assertEquals(0, RefreshRates.pick(listOf(60f, 120f), 90))
    }
    @Test fun invalidAndMissingRatesAreNotFabricated() {
        assertNull(RefreshRates.pick(emptyList()))
        assertNull(RefreshRates.pick(listOf(Float.NaN, -1f, 0f)))
        assertEquals(1, RefreshRates.pick(listOf(Float.NaN, 120f, Float.POSITIVE_INFINITY)))
    }
    @Test fun calmRateIsNearSixtyAndOnlyWellBelowTheFullOne() {
        assertEquals(0, RefreshRates.calm(listOf(60f, 120f), 120f))
        assertEquals(1, RefreshRates.calm(listOf(30f, 60f, 90f, 120f), 120f))
        assertEquals(0, RefreshRates.calm(listOf(59.94f, 90f), 90f))
        assertEquals(1, RefreshRates.calm(listOf(48f, 50f, 144f), 144f))
        // Nothing worth switching to: the screen tops out near 60, or has nothing slower.
        assertNull(RefreshRates.calm(listOf(60f), 60f))
        assertNull(RefreshRates.calm(listOf(60f, 72f), 72f))
        assertNull(RefreshRates.calm(listOf(120f), 120f))
        assertNull(RefreshRates.calm(listOf(24f, 30f, 120f), 120f))
    }
    @Test fun legacySettingsKeepDisplayMaximumAndCapPersists() {
        assertEquals(0, Json.decodeFromString<DisplayProfile>("{}").maxRefreshRate)
        val settings = DisplayProfile(maxRefreshRate = 90)
        assertEquals(settings, Json.decodeFromString<DisplayProfile>(Json.encodeToString(settings)))
    }
}
