package io.github.matiyaaa.fuse.platform

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * How long the battery lasts, or how long it takes to fill, from what Android tells Fuse. Pure: the
 * time comes in as [elapsed realtime][android.os.SystemClock.elapsedRealtime] milliseconds and
 * nothing here touches Android, so it is tested without a device.
 *
 * On battery, in order:
 * 1. Android's own prediction ([android.os.PowerManager.getBatteryDischargePrediction], Android 12).
 * 2. The rate the level has been dropping. It is measured only while the screen is on, so a night
 *    asleep doesn't read as two days left. The first reading after a change of state isn't a real
 *    change of level, so the clock starts at the first drop Fuse sees.
 * 3. Until there is a rate, the charge left divided by the current drawn. Units and signs differ
 *    between makers (microamps or milliamps, either sign), so both are recognised by their size and
 *    anything implausible is ignored. As drops accumulate, the rate takes over.
 *
 * Charging uses [android.os.BatteryManager.computeChargeTimeRemaining], then the rate the level
 * rises (slower above 80%, as batteries charge), then the current.
 */
class BatteryEstimator {
    private data class Point(val clock: Long, val level: Int)

    private var plugged: Boolean? = null
    private var level: Int? = null
    private val points = ArrayDeque<Point>()

    private var screenOn = true
    private var activeBefore = 0L
    private var activeSince: Long? = null

    private var chargeCounter: Int? = null
    private var currentAverage: Int? = null
    private val currents = ArrayDeque<Int>()

    /** Whether the screen is on, which decides whether the battery clock runs. */
    fun onScreen(on: Boolean, atMs: Long) {
        if (on == screenOn && (activeSince != null || !on)) return
        if (screenOn) activeSince?.let { activeBefore += (atMs - it).coerceAtLeast(0) }
        screenOn = on
        activeSince = if (on) atMs else null
    }

    /** Each battery broadcast: the level in percent and whether a charger is connected. */
    fun onLevel(atMs: Long, percent: Int, plugged: Boolean) {
        if (screenOn && activeSince == null) activeSince = atMs
        val before = level
        if (this.plugged != plugged) {
            this.plugged = plugged
            reset()
        } else if (before != null && percent != before) {
            val rose = percent > before
            // A jump, or the level going the wrong way, means the old rate no longer holds.
            if (abs(percent - before) > MAX_STEP || rose != plugged) {
                reset()
            } else {
                points.addLast(Point(clock(atMs), percent))
                while (points.size > MAX_POINTS) points.removeFirst()
            }
        }
        level = percent
    }

    /** BatteryManager's charge counter and currents, as raw integers (null when not reported). */
    fun onReadings(chargeCounter: Int?, currentNow: Int?, currentAverage: Int?) {
        this.chargeCounter = chargeCounter?.takeIf { it > 0 && it != Int.MIN_VALUE && it != Int.MAX_VALUE }
        this.currentAverage = currentAverage?.takeIf { it != 0 && it != Int.MIN_VALUE && it != Int.MAX_VALUE }
        currentNow?.takeIf { it != 0 && it != Int.MIN_VALUE && it != Int.MAX_VALUE }?.let {
            currents.addLast(it)
            while (currents.size > CURRENT_SAMPLES) currents.removeFirst()
        }
    }

    /**
     * Minutes to empty (on battery) or to full (charging), or null when there's no sound estimate.
     * [systemDischargeMinutes] and [systemChargeMinutes] are Android's own, when it has them.
     */
    fun estimate(atMs: Long, systemDischargeMinutes: Int? = null, systemChargeMinutes: Int? = null): Int? {
        val l = level ?: return null
        val charging = plugged ?: return null
        if (charging) {
            if (l >= 100) return null
            systemChargeMinutes?.takeIf { it in 1..MAX_CHARGE_MINUTES }?.let { return it }
            val observed = observedRate(atMs, MIN_CHARGE_SPAN_MS)?.let { r -> chargeMinutes(l, r) }
            val fromCurrent = currentMinutes(l, charging = true)
            return blend(observed, fromCurrent)?.takeIf { it in 1..MAX_CHARGE_MINUTES }
        }
        systemDischargeMinutes?.takeIf { it in 1..MAX_DISCHARGE_MINUTES }?.let { return it }
        val observed = observedRate(atMs, MIN_DISCHARGE_SPAN_MS)?.let { r -> (l / r / MINUTE_MS).roundToInt() }
        val fromCurrent = currentMinutes(l, charging = false)
        return blend(observed, fromCurrent)?.takeIf { it in 1..MAX_DISCHARGE_MINUTES }
    }

    private fun reset() {
        points.clear()
        currents.clear()
    }

    /** The battery clock: wall time while charging, screen-on time on battery. */
    private fun clock(atMs: Long): Long =
        if (plugged == true) atMs else activeBefore + (activeSince?.let { (atMs - it).coerceAtLeast(0) } ?: 0)

    /**
     * Percent per millisecond over the last half hour of drops (at least the last eight), once two
     * points apart over [minSpan]. When the next change is overdue, the rate can't be faster than
     * it happening now, which slows the estimate as soon as the battery does.
     */
    private fun observedRate(atMs: Long, minSpan: Long): Double? {
        if (points.size < 2) return null
        val last = points.last()
        val start = points.indexOfFirst { it.clock >= last.clock - WINDOW_MS }.coerceAtMost(points.size - MIN_WINDOW_POINTS).coerceAtLeast(0)
        val first = points[start]
        val diff = abs(last.level - first.level)
        val span = last.clock - first.clock
        if (diff < MIN_LEVELS || span < minSpan) return null
        val rate = diff.toDouble() / span
        val overdue = (clock(atMs) - last.clock).coerceAtLeast(0)
        return minOf(rate, (diff + 1).toDouble() / (span + overdue))
    }

    /** Full from [level] at [rate] percent per millisecond, at half speed above 80%. */
    private fun chargeMinutes(level: Int, rate: Double): Int {
        val ms = if (level < TAPER_FROM) (TAPER_FROM - level) / rate + (100 - TAPER_FROM) / (rate * TAPER_SPEED) else (100 - level) / rate
        return (ms / MINUTE_MS).roundToInt()
    }

    /** The rate takes over from the current as drops accumulate: a quarter at two, all of it at five. */
    private fun blend(observed: Int?, fromCurrent: Int?): Int? {
        if (observed == null) return fromCurrent
        if (fromCurrent == null) return observed
        val w = ((points.size - 1) / 4.0).coerceIn(0.0, 1.0)
        return (w * observed + (1 - w) * fromCurrent).roundToInt()
    }

    private fun currentMinutes(level: Int, charging: Boolean): Int? {
        if (level <= 0) return null
        val charge = chargeMicroAmpHours(level) ?: return null
        val current = currentMicroAmps() ?: return null
        val capacity = charge * 100.0 / level
        val hours = if (charging) (capacity - charge).coerceAtLeast(0.0) / current * CHARGE_OVERHEAD else charge / current
        return ceil(hours * 60).toInt()
    }

    /** The charge counter in microamp hours, recognised by the battery size it implies (0.5 to 30 Ah). */
    private fun chargeMicroAmpHours(level: Int): Double? {
        val counter = chargeCounter?.toDouble() ?: return null
        val capacity = counter * 100 / level
        return when {
            capacity in 500_000.0..30_000_000.0 -> counter
            capacity in 500.0..30_000.0 -> counter * 1000
            else -> null
        }
    }

    /** The current in microamps, either sign: the average when reported, else the median of the last readings. */
    private fun currentMicroAmps(): Double? {
        val raw = currentAverage ?: currents.sorted().getOrNull(currents.size / 2) ?: return null
        val a = abs(raw.toDouble())
        return when {
            a in 20_000.0..10_000_000.0 -> a
            a in 20.0..10_000.0 -> a * 1000
            else -> null
        }
    }

    private companion object {
        const val MINUTE_MS = 60_000.0
        const val WINDOW_MS = 30 * 60_000L
        const val MIN_WINDOW_POINTS = 8
        const val MAX_POINTS = 64
        const val MIN_LEVELS = 2
        const val MIN_DISCHARGE_SPAN_MS = 6 * 60_000L
        const val MIN_CHARGE_SPAN_MS = 4 * 60_000L
        const val MAX_STEP = 5
        const val CURRENT_SAMPLES = 5
        const val TAPER_FROM = 80
        const val TAPER_SPEED = 0.5
        const val CHARGE_OVERHEAD = 1.15
        const val MAX_DISCHARGE_MINUTES = 2880
        const val MAX_CHARGE_MINUTES = 1440
    }
}
