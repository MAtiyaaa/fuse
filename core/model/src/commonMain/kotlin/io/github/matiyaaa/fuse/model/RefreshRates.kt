package io.github.matiyaaa.fuse.model

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A cap chooses the highest native mode at or below it; Automatic chooses the maximum.
 * A small tolerance accepts OEM nominal families (59.94/60/61 and 119.88/120).
 * If every mode exceeds a cap, the slowest available mode is the only feasible choice.
 * Android can ignore an app's request, so requested and active rates remain distinct.
 * Source: https://developer.android.com/reference/android/view/WindowManager.LayoutParams
 */
object RefreshRates {
    fun pick(rates: List<Float>, maximum: Int = 0): Int? {
        val valid = rates.indices.filter { rates[it].isFinite() && rates[it] > 0f }
        if (maximum <= 0) return valid.maxByOrNull { rates[it] }
        return valid.filter { rates[it] <= maximum + NOMINAL_TOLERANCE_HZ }
            .maxByOrNull { rates[it] } ?: valid.minByOrNull { rates[it] }
    }

    /** Labels reflect the hardware's modes, with fractional OEM variants grouped together. */
    fun options(rates: List<Float>): List<Int> = rates.filter { it.isFinite() && it > 0f }
        .map { rate -> NOMINAL_RATES.firstOrNull { abs(rate - it) <= NOMINAL_TOLERANCE_HZ } ?: rate.roundToInt() }
        .distinct().sorted()

    private const val NOMINAL_TOLERANCE_HZ = 1.1f
    private val NOMINAL_RATES = listOf(30, 40, 50, 60, 72, 75, 90, 100, 120, 125, 144, 165, 240)
}
