package io.github.matiyaaa.fuse.ui.shell.app

import io.github.matiyaaa.fuse.model.ConnectionState
import io.github.matiyaaa.fuse.model.PerformanceMetric
import io.github.matiyaaa.fuse.model.SystemStatus
import io.github.matiyaaa.fuse.ui.shell.home.bytesText
import io.github.matiyaaa.fuse.ui.shell.store.StorageSummary
import kotlin.math.roundToInt

/** What a status ring shows. */
internal enum class GaugeKind { PROCESSOR, MEMORY, STORAGE, WIFI }

/**
 * One ring on the status page: how full it is ([fraction]), a short value inside it ([centre], else
 * the kind's icon), and its label, value and detail beside it. [warn] colours it as a warning.
 */
internal data class Gauge(
    val kind: GaugeKind,
    val label: String,
    val value: String,
    val detail: String?,
    val fraction: Float?,
    val centre: String?,
    val warn: Boolean = false,
)

/**
 * The status page's rings, from what the platform really measures. Android reports `cpu_temp`,
 * `thermal` and `memory` ("3.1 of 8.0 GB free"); Linux, Windows and macOS report `cpu`, `cpu-temp`
 * and `memory` ("5.2 / 16.0 GB"). Rings read the metrics' fractions, never their text, and anything
 * not reported is left out. Wi-Fi is always there.
 */
internal fun statusGauges(metrics: List<PerformanceMetric>, status: SystemStatus, storage: StorageSummary?): List<Gauge> = buildList {
    fun metric(vararg keys: String): PerformanceMetric? = keys.firstNotNullOfOrNull { k -> metrics.firstOrNull { it.key == k } }
    fun percent(f: Float) = (f.coerceIn(0f, 1f) * 100).roundToInt()

    val load = metric("cpu")?.takeIf { it.fraction != null }
    val temp = metric("cpu_temp", "cpu-temp")
    val thermal = metric("thermal")
    if (load != null || temp != null || thermal != null) {
        val celsius = temp?.value?.let { Regex("""(\d+(?:\.\d+)?)""").find(it)?.value?.toFloatOrNull() }
        val value = when {
            load != null -> "${percent(load.fraction!!)}% busy"
            temp != null -> temp.value
            else -> thermal!!.value
        }
        val detail = listOfNotNull(
            temp?.value?.takeIf { load != null },
            thermal?.value?.takeIf { load != null || temp != null }?.let { "Running ${it.lowercase()}" },
        ).joinToString("  ·  ").ifEmpty { null }
        add(
            Gauge(
                GaugeKind.PROCESSOR, "Processor", value, detail,
                fraction = load?.fraction ?: thermal?.fraction ?: celsius?.let { it / 100f },
                centre = load?.fraction?.let { "${percent(it)}%" } ?: celsius?.let { "${it.roundToInt()}°" },
                warn = (thermal?.fraction ?: 0f) >= 0.5f || (celsius ?: 0f) >= 85f,
            ),
        )
    }

    metric("memory")?.let { m ->
        val used = m.fraction
        add(
            Gauge(
                GaugeKind.MEMORY, "Memory",
                value = used?.let { "${percent(it)}% used" } ?: m.value,
                detail = if (used != null) m.value.replace(" / ", " of ") else null,
                fraction = used,
                centre = used?.let { "${percent(it)}%" },
                warn = (used ?: 0f) >= 0.9f,
            ),
        )
    }

    if (storage != null && storage.totalBytes > 0) {
        val used = 1f - storage.freeBytes.toFloat() / storage.totalBytes
        add(
            Gauge(
                GaugeKind.STORAGE, "Storage",
                value = "${bytesText(storage.freeBytes)} free",
                detail = "of ${bytesText(storage.totalBytes)}  ·  ${storage.label}",
                fraction = used,
                centre = "${percent(used)}%",
                warn = storage.freeBytes.toFloat() / storage.totalBytes < 0.05f,
            ),
        )
    }

    val strength = status.wifiStrength?.coerceIn(0, 4)
    add(
        Gauge(
            GaugeKind.WIFI, "Wi-Fi",
            value = when (status.wifi) {
                ConnectionState.CONNECTED -> "Connected"
                ConnectionState.ON -> "Not connected"
                ConnectionState.OFF -> "Off"
                ConnectionState.UNKNOWN -> "Unknown"
            },
            detail = strength?.let(::signalWords) ?: if (status.network == ConnectionState.CONNECTED) "Online" else "Offline",
            fraction = if (status.wifi == ConnectionState.CONNECTED) (strength ?: 3) / 4f else 0f,
            centre = null,
        ),
    )
}

internal fun signalWords(bars: Int): String = when {
    bars >= 4 -> "Excellent signal"
    bars == 3 -> "Good signal"
    bars == 2 -> "Fair signal"
    else -> "Weak signal"
}
