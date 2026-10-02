package io.github.matiyaaa.fuse.ui.shell.store.impl

/** Moments in words for messages ("today at 2:14 PM"), in local time from a UTC offset. */
internal object TimeWords {
    const val DAY_MS = 86_400_000L
    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    fun relative(at: Long, now: Long, utcOffsetMillis: Long): String {
        val local = at + utcOffsetMillis
        val day = local.floorDiv(DAY_MS)
        val today = (now + utcOffsetMillis).floorDiv(DAY_MS)
        val clock = clock(local)
        return when (today - day) {
            0L -> "today at $clock"
            1L -> "yesterday at $clock"
            else -> "on ${date(day)}"
        }
    }

    /** 12-hour time of a local epoch millis: "2:14 PM". */
    fun clock(localMillis: Long): String {
        val minutes = (localMillis.mod(DAY_MS)) / 60_000
        val h = (minutes / 60).toInt()
        val m = (minutes % 60).toInt()
        val h12 = if (h % 12 == 0) 12 else h % 12
        return "$h12:${m.toString().padStart(2, '0')} ${if (h < 12) "AM" else "PM"}"
    }

    /** "3 Oct 2026" for a day number since 1970-01-01. */
    fun date(epochDay: Long): String {
        val (y, m, d) = civil(epochDay)
        return "$d ${MONTHS[m - 1]} $y"
    }

    /** "2026-10-03" for a day number since 1970-01-01, for file names. */
    fun isoDate(epochDay: Long): String {
        val (y, m, d) = civil(epochDay)
        return "$y-${m.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}"
    }

    /** The day number of the first of [epochDay]'s month. */
    fun firstOfMonth(epochDay: Long): Long = epochDay - (civil(epochDay).third - 1)

    /** "October" for a day number since 1970-01-01. */
    fun monthName(epochDay: Long): String = FULL_MONTHS[civil(epochDay).second - 1]

    private val FULL_MONTHS = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")

    /** Year, month and day of a day number since 1970-01-01 (civil-from-days, proleptic Gregorian). */
    private fun civil(epochDay: Long): Triple<Long, Int, Int> {
        val z = epochDay + 719_468
        val era = (if (z >= 0) z else z - 146_096) / 146_097
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        val y = yoe + era * 400 + if (m <= 2) 1 else 0
        return Triple(y, m.toInt(), d.toInt())
    }
}
