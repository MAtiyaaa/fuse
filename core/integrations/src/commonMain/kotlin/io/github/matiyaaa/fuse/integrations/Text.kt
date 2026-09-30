package io.github.matiyaaa.fuse.integrations

import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** RFC 3986 percent-encoding helpers shared by the libretro URL builder and the Cartridge links. */
object UrlCoding {
    private const val HEX = "0123456789ABCDEF"

    private fun isUnreserved(b: Int): Boolean =
        b in 'A'.code..'Z'.code || b in 'a'.code..'z'.code || b in '0'.code..'9'.code ||
            b == '-'.code || b == '.'.code || b == '_'.code || b == '~'.code

    /** Encodes every byte of the UTF-8 form except unreserved characters; a space becomes %20. */
    fun encode(value: String): String {
        val sb = StringBuilder(value.length + 8)
        for (byte in value.encodeToByteArray()) {
            val b = byte.toInt() and 0xFF
            if (isUnreserved(b)) {
                sb.append(b.toChar())
            } else {
                sb.append('%').append(HEX[b shr 4]).append(HEX[b and 0x0F])
            }
        }
        return sb.toString()
    }

    /**
     * Decodes %XX sequences (UTF-8). With [plusAsSpace] a '+' becomes a space, as in form-encoded
     * query strings. Malformed escapes are kept literally.
     */
    fun decode(value: String, plusAsSpace: Boolean = false): String {
        if ('%' !in value && (!plusAsSpace || '+' !in value)) return value
        val out = ArrayList<Byte>(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%' && i + 2 < value.length && hexValue(value[i + 1]) >= 0 && hexValue(value[i + 2]) >= 0) {
                out.add(((hexValue(value[i + 1]) shl 4) or hexValue(value[i + 2])).toByte())
                i += 3
            } else if (c == '+' && plusAsSpace) {
                out.add(' '.code.toByte())
                i++
            } else {
                // Copy a run of literal characters at once so surrogate pairs stay intact.
                var end = i + 1
                while (end < value.length && value[end] != '%' && !(plusAsSpace && value[end] == '+')) end++
                value.substring(i, end).encodeToByteArray().forEach { out.add(it) }
                i = end
            }
        }
        return out.toByteArray().decodeToString()
    }

    private fun hexValue(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }
}

/** Date helpers that avoid a kotlinx-datetime dependency. All values are UTC. */
internal object Dates {
    private val dateTime = Regex(
        "^(\\d{4})-(\\d{2})-(\\d{2})(?:[ T](\\d{2}):(\\d{2})(?::(\\d{2})(?:\\.\\d+)?)?)?\\s*(Z|[+-]\\d{2}:?\\d{2})?$",
    )

    /** Days since 1970-01-01 for a proleptic Gregorian date (Howard Hinnant's algorithm). */
    fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = (if (month <= 2) year - 1 else year).toLong()
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val mp = (month + 9) % 12
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097 + doe - 719468
    }

    /** Year of [epochSeconds]. */
    fun yearOfEpochSeconds(epochSeconds: Long): Int {
        val days = floorDiv(epochSeconds, 86_400)
        val z = days + 719468
        val era = (if (z >= 0) z else z - 146096) / 146097
        val doe = z - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val month = if (mp < 10) mp + 3 else mp - 9
        val year = yoe + era * 400
        return (if (month <= 2) year + 1 else year).toInt()
    }

    private fun floorDiv(a: Long, b: Long): Long {
        val q = a / b
        return if ((a % b != 0L) && ((a < 0) != (b < 0))) q - 1 else q
    }

    /**
     * Parses "2024-01-15 18:23:45", "2024-01-15T18:23:45.000000Z", "2024-01-15T18:23:45+00:00" or
     * "2024-01-15" to epoch millis. Values without an offset are taken as UTC. Null when unparseable.
     */
    fun parseEpochMillis(text: String?): Long? {
        val m = dateTime.find(text?.trim() ?: return null) ?: return null
        val g = m.groupValues
        val year = g[1].toInt()
        val month = g[2].toInt()
        val day = g[3].toInt()
        if (month !in 1..12 || day !in 1..31) return null
        val hour = g[4].toIntOrNull() ?: 0
        val minute = g[5].toIntOrNull() ?: 0
        val second = g[6].toIntOrNull() ?: 0
        var seconds = daysFromCivil(year, month, day) * 86_400 + hour * 3600L + minute * 60L + second
        val offset = g[7]
        if (offset.isNotEmpty() && offset != "Z") {
            val sign = if (offset[0] == '-') -1 else 1
            val digits = offset.substring(1).replace(":", "")
            val oh = digits.take(2).toInt()
            val om = digits.drop(2).toIntOrNull() ?: 0
            seconds -= sign * (oh * 3600L + om * 60L)
        }
        return seconds * 1000
    }

    /** The first four-digit year in [text] ("1994-03-19", "Mar 1994"), or null. */
    fun yearIn(text: String?): Int? =
        Regex("(?<!\\d)(1[89]\\d{2}|2\\d{3})(?!\\d)").find(text ?: return null)?.value?.toInt()
}

/** Wall-clock epoch millis; the default clock for `fetchedAt`/`checkedAt` stamps. */
@OptIn(ExperimentalTime::class)
fun systemEpochMillis(): Long = Clock.System.now().toEpochMilliseconds()
