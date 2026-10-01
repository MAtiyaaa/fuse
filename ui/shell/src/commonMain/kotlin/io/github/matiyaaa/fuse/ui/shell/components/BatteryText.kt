package io.github.matiyaaa.fuse.ui.shell.components

import io.github.matiyaaa.fuse.model.SystemStatus

/**
 * A battery estimate as people say it: "45 min", "3 h 20 min" (rounded to 5 minutes, since an
 * estimate is never minute-exact), whole hours from 10 h.
 */
fun batteryDurationText(minutes: Int): String {
    val m = minutes.coerceAtLeast(1)
    if (m < 60) return "$m min"
    if (m >= 600) return "${(m + 30) / 60} h"
    val rounded = (m + 2) / 5 * 5
    val h = rounded / 60
    val rest = rounded % 60
    return if (rest == 0) "$h h" else "$h h $rest min"
}

/** "Charged", "Full in 1 h 5 min" or "3 h 20 min left"; null while there is nothing to say yet. */
fun batteryTimeText(s: SystemStatus): String? = when {
    s.batteryPercent == null -> null
    s.batteryFull -> "Charged"
    s.charging -> s.batteryMinutes?.let { "Full in ${batteryDurationText(it)}" }
    else -> s.batteryMinutes?.let { "${batteryDurationText(it)} left" }
}
